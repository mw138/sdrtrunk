/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2015-2016 Dennis Sheirer
 * 
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package module;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

import message.IMessageListener;
import message.IMessageProvider;
import message.Message;
import module.decode.event.CallEvent;
import module.decode.event.CallEventModel;
import module.decode.event.ICallEventListener;
import module.decode.event.ICallEventProvider;
import module.decode.event.MessageActivityModel;
import module.decode.state.ChannelState;
import module.decode.state.DecoderState;
import module.decode.state.DecoderStateEvent;
import module.decode.state.DecoderStateEvent.Event;
import module.decode.state.IDecoderStateEventListener;
import module.decode.state.IDecoderStateEventProvider;
import module.decode.state.State;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import sample.Broadcaster;
import sample.Listener;
import sample.complex.ComplexBuffer;
import sample.complex.IComplexBufferListener;
import sample.real.IFilteredRealBufferListener;
import sample.real.IFilteredRealBufferProvider;
import sample.real.IUnFilteredRealBufferListener;
import sample.real.IUnFilteredRealBufferProvider;
import sample.real.RealBuffer;
import source.ComplexSource;
import source.RealSource;
import source.Source;
import source.SourceException;
import source.tuner.TunerChannelSource;
import source.tuner.frequency.FrequencyChangeEvent;
import source.tuner.frequency.IFrequencyChangeListener;
import source.tuner.frequency.IFrequencyChangeProvider;
import audio.AudioPacket;
import audio.IAudioPacketListener;
import audio.IAudioPacketProvider;
import audio.metadata.IMetadataListener;
import audio.metadata.IMetadataProvider;
import audio.metadata.Metadata;
import audio.squelch.ISquelchStateListener;
import audio.squelch.ISquelchStateProvider;
import audio.squelch.SquelchState;
import controller.NamingThreadFactory;
import controller.channel.Channel.ChannelType;
import controller.channel.ChannelEvent;
import controller.channel.IChannelEventListener;
import controller.channel.IChannelEventProvider;

/**
 * Processing chain provides a framework for connecting a complex or real sample
 * source to a set of one primary decoder and zero or more auxiliary decoders.  
 * All decoded messages and call events produced by the decoders and the decoder
 * call states are aggregated by the various broadcasters.  You can register
 * listeners to receive aggregated messages, call events, and audio packets.
 * 
 * Normal setup sequence:
 * 
 * 1) Add one or more modules
 * 2) Register listeners to receive messages, call events, audio, etc.
 * 3) Add a valid source
 * 4) Invoke the start() method to start processing.
 * 5) Invoke the stop() method to stop processing.
 * 
 * Optional: if you want to reuse the processing chain with a new sample source,
 * invoke the following method sequence:  stop(), setSource(), start()
 */
public class ProcessingChain implements IChannelEventListener
{
	private final static Logger mLog = LoggerFactory.getLogger( ProcessingChain.class );

	private Broadcaster<AudioPacket> mAudioPacketBroadcaster = new Broadcaster<>();
	private Broadcaster<Metadata> mMetadataBroadcaster = new Broadcaster<>();
	private Broadcaster<CallEvent> mCallEventBroadcaster = new Broadcaster<>();
	private Broadcaster<ChannelEvent> mChannelEventBroadcaster = new Broadcaster<>();
	private Broadcaster<ComplexBuffer> mComplexBufferBroadcaster = new Broadcaster<>();
	private Broadcaster<DecoderStateEvent> mDecoderStateEventBroadcaster = new Broadcaster<>();
	private Broadcaster<FrequencyChangeEvent> mFrequencyChangeEventBroadcaster = new Broadcaster<>();
	private Broadcaster<Message> mMessageBroadcaster = new Broadcaster<>();
	private Broadcaster<RealBuffer> mFilteredRealBufferBroadcaster = new Broadcaster<>();
	private Broadcaster<RealBuffer> mUnFilteredRealBufferBroadcaster = new Broadcaster<>();
	private Broadcaster<SquelchState> mSquelchStateBroadcaster = new Broadcaster<>();
	
	private ScheduledExecutorService mScheduledExecutorService;
	private String mName;
	private AtomicBoolean mRunning = new AtomicBoolean();
	
	protected Source mSource;
	private List<Module> mModules = new ArrayList<>();
	private CallEventModel mCallEventModel;
	private ChannelState mChannelState;
	private MessageActivityModel mMessageActivityModel;

	/**
	 * Creates a processing chain for managing a set of modules
	 * 
	 * @param name for thread pool naming each thread
	 * @param channelType 
	 */
	public ProcessingChain( String name, ChannelType channelType )
	{
		mName = name;
		
		mChannelState = new ChannelState( channelType );
		addModule( mChannelState );

		mCallEventModel = new CallEventModel();
		addCallEventListener( mCallEventModel );
	}
	
	public CallEventModel getCallEventModel()
	{
		return mCallEventModel;
	}
	
	public ChannelState getChannelState()
	{
		return mChannelState;
	}
	
	public MessageActivityModel getMessageActivityModel()
	{
		return mMessageActivityModel;
	}
	
	public void setMessageActivityModel( MessageActivityModel model )
	{
		mMessageActivityModel = model;
		
		addMessageListener( mMessageActivityModel );
	}
	
	public void dispose()
	{
		stop();
		
		for( Module module: mModules )
		{
			module.dispose();
		}
		
		mModules.clear();
		
		mScheduledExecutorService.shutdownNow();
		mScheduledExecutorService = null;
		
		mAudioPacketBroadcaster.dispose();
		mCallEventBroadcaster.dispose();
		mChannelEventBroadcaster.dispose();
		mComplexBufferBroadcaster.dispose();
		mMessageBroadcaster.dispose();
		mFilteredRealBufferBroadcaster.dispose();
		mSquelchStateBroadcaster.dispose();
		
		if( mScheduledExecutorService != null )
		{
			mScheduledExecutorService.shutdownNow();
		}
	}

	/**
	 * Broadcasts the metadata to any registered listeners
	 */
	public void broadcast( Metadata metadata )
	{
		if( mMetadataBroadcaster != null )
		{
			mMetadataBroadcaster.broadcast( metadata );
		}
	}
	
	/**
	 * Indicates if this processing chain is currently receiving samples from
	 * a source and sending those samples to the decoders.
	 */
	public boolean isProcessing()
	{
		return mRunning.get();
	}

	/**
	 * Indicates if this chain currently has a valid sample source.
	 */
	public boolean hasSource()
	{
		return mSource != null;
	}

	/**
	 * Applies a sample source to this processing chain.  Processing won't 
	 * start until the start() method is invoked.
	 * 
	 * @param source - real or complex sample source
	 * 
	 * @throws IllegalStateException if the processing chain is currently 
	 * processing with another source.  Invoke stop() before applying a new
	 * source.
	 */
	public void setSource( Source source ) throws IllegalStateException
	{
		if( isProcessing() )
		{
			throw new IllegalStateException( "Processing chain is currently "
				+ "processing.  Invoke stop() on the processing chain before "
				+ "applying a new sample source" );
		}
		
		mSource = source;
	}

	/**
	 * List of current modules for this processing chain
	 */
	public List<Module> getModules()
	{
		return mModules;
	}

	/**
	 * List of decoder states for this processing chain
	 */
	public List<DecoderState> getDecoderStates()
	{
		List<DecoderState> decoderStates = new ArrayList<>();
		
		for( Module module: mModules )
		{
			if( module instanceof DecoderState )
			{
				decoderStates.add( (DecoderState)module );
			}
		}
		
		return decoderStates;
	}
	
	/**
	 * Adds the list of modules to this processing chain
	 */
	public void addModules( List<Module> modules )
	{
		for( Module module: modules )
		{
			addModule( module );
		}
	}

	/**
	 * Adds a module to the processing chain.  Each module is tested for the
	 * interfaces that it supports and is registered or receives a listener
	 * to consume or produce the supported interface data type.  All elements
	 * and events that are produced by any module are automatically routed to 
	 * all other components that support the corresponding listener interface.
	 * 
	 * At least one module should consume complex samples and either produce
	 * decoded messages and/or audio, or produce decoded real sample buffers
	 * for all other modules to consume.
	 * 
	 * @param module - processing module, demodulator, decoder, source, state
	 * machine, etc.
	 */
	public void addModule( Module module )
	{
		mModules.add( module );
		
		registerListeners( module );
		registerProviders( module );
	}

	/**
	 * Registers the module as a listener to each of the broadcasters that 
	 * provide the data interface(s) supported by the module.
	 */
	private void registerListeners( Module module )
	{
		if( module instanceof IAudioPacketListener )
		{
			mAudioPacketBroadcaster.addListener( 
					((IAudioPacketListener)module).getAudioPacketListener() );
		}
		
		if( module instanceof ICallEventListener )
		{
			mCallEventBroadcaster.addListener( 
					((ICallEventListener)module).getCallEventListener() );
		}
		
		if( module instanceof IChannelEventListener )
		{
			mChannelEventBroadcaster.addListener( 
					((IChannelEventListener)module).getChannelEventListener() );
		}
		
		if( module instanceof IComplexBufferListener )
		{
			mComplexBufferBroadcaster.addListener( 
				((IComplexBufferListener)module).getComplexBufferListener() );
		}
		
		if( module instanceof IDecoderStateEventListener )
		{
			mDecoderStateEventBroadcaster.addListener( 
				((IDecoderStateEventListener)module).getDecoderStateListener() );
		}

		if( module instanceof IFrequencyChangeListener )
		{
			mFrequencyChangeEventBroadcaster.addListener( 
				((IFrequencyChangeListener)module).getFrequencyChangeListener());
		}
		
		if( module instanceof IMessageListener )
		{
			mMessageBroadcaster.addListener( 
					((IMessageListener)module).getMessageListener() );
		}
		
		if( module instanceof IMetadataListener )
		{
			mMetadataBroadcaster.addListener( 
				((IMetadataListener)module).getMetadataListener() );
		}
		
		if( module instanceof IFilteredRealBufferListener )
		{
			mFilteredRealBufferBroadcaster.addListener( 
				((IFilteredRealBufferListener)module).getFilteredRealBufferListener() );
		}

		if( module instanceof ISquelchStateListener )
		{
			mSquelchStateBroadcaster.addListener( 
				((ISquelchStateListener)module).getSquelchStateListener() );
		}

		if( module instanceof IUnFilteredRealBufferListener )
		{
			mUnFilteredRealBufferBroadcaster.addListener( 
				((IUnFilteredRealBufferListener)module).getUnFilteredRealBufferListener() );
		}
	}
	
	/**
	 * Registers the broadcaster(s) as listeners to the module for each 
	 * provider interface that is supported by the module.
	 */
	private void registerProviders( Module module )
	{
		if( module instanceof IAudioPacketProvider )
		{
			((IAudioPacketProvider)module).setAudioPacketListener( 
					mAudioPacketBroadcaster );
		}
		
		if( module instanceof ICallEventProvider )
		{
			((ICallEventProvider)module).addCallEventListener( mCallEventBroadcaster );
		}

		if( module instanceof IChannelEventProvider )
		{
			((IChannelEventProvider)module).setChannelEventListener( 
					mChannelEventBroadcaster );
		}
		
		if( module instanceof IDecoderStateEventProvider )
		{
			((IDecoderStateEventProvider)module).setDecoderStateListener( 
					mDecoderStateEventBroadcaster );
		}

		if( module instanceof IFrequencyChangeProvider )
		{
			((IFrequencyChangeProvider)module).setFrequencyChangeListener( 
					mFrequencyChangeEventBroadcaster );
		}
		
		if( module instanceof IMessageProvider )
		{
			((IMessageProvider)module).setMessageListener( mMessageBroadcaster );
		}
		
		if( module instanceof IMetadataProvider )
		{
			((IMetadataProvider)module).setMetadataListener( 
					mMetadataBroadcaster );
		}

		if( module instanceof IFilteredRealBufferProvider )
		{
			((IFilteredRealBufferProvider)module)
				.setFilteredRealBufferListener( mFilteredRealBufferBroadcaster );
		}
		
		if( module instanceof ISquelchStateProvider )
		{
			((ISquelchStateProvider)module).setSquelchStateListener( 
					mSquelchStateBroadcaster );
		}

		if( module instanceof IUnFilteredRealBufferProvider )
		{
			((IUnFilteredRealBufferProvider)module)
				.setUnFilteredRealBufferListener( mUnFilteredRealBufferBroadcaster );
		}
	}
	
	/**
	 * Starts processing if the chain has a valid source.  Invocations on an
	 * already started chain have no effect. 
	 */
	public void start()
	{
		if( mRunning.compareAndSet( false, true ) )
		{
			if( mSource != null )
			{
				/* Reset each of the modules */
				for( Module module: mModules )
				{
					module.reset();
				}
				
				/* Register with the source to receive sample data.  Setup a 
				 * timer task to process the buffer queues 50 times a second 
				 * (every 20 ms) */
				switch( mSource.getSampleType() )
				{
					case COMPLEX:
						((ComplexSource)mSource).setListener( mComplexBufferBroadcaster );
						break;
					case REAL:
						((RealSource)mSource).setListener( mFilteredRealBufferBroadcaster );
						break;
					default:
						throw new IllegalArgumentException( "Unrecognized source "
							+ "sample type - cannot start processing chain" );
				}
				
				/* If this is a tuner source, broadcast the frequency to all 
				 * of the decoder state's and start the samples flowing */
				if( mSource instanceof TunerChannelSource )
				{
					TunerChannelSource tcs = (TunerChannelSource)mSource;

					try
					{
						long frequency = tcs.getFrequency();

						mDecoderStateEventBroadcaster.broadcast( 
							new DecoderStateEvent( this, Event.SOURCE_FREQUENCY, 
									State.IDLE, frequency ) );
					}
					catch( SourceException e )
					{
						mLog.error( "Error getting frequency from tuner channel source", e );
					}
				}
				
				if( mScheduledExecutorService == null )
				{
					mScheduledExecutorService = Executors.newScheduledThreadPool( 
							1, new NamingThreadFactory( "channel " + mName ) );
				}
				
				/* Start each of the modules */
				for( Module module: mModules )
				{
					try
					{
						module.start( mScheduledExecutorService );
					}
					catch( Exception e )
					{
						mLog.error( "Error starting module", e );
					}
				}

				mSource.start( mScheduledExecutorService );
			}
			else
			{
				mLog.error( "Source is null on start()" );
			}
		}
	}

	/**
	 * Stops processing if the chain is currently processing.  Invocations on 
	 * an already stopped chain have no effect.
	 */
	public void stop()
	{
		if( mRunning.compareAndSet( true, false ) )
		{
			if( mSource != null )
			{
				mSource.stop();
				
				switch( mSource.getSampleType() )
				{
					case COMPLEX:
						((ComplexSource)mSource).removeListener( mComplexBufferBroadcaster );
						break;
					case REAL:
						((RealSource)mSource).removeListener( mFilteredRealBufferBroadcaster );
						break;
					default:
						throw new IllegalArgumentException( "Unrecognized source "
							+ "sample type - cannot start processing chain" );
				}

				/* Release the source */
				mSource.dispose();
				mSource = null;
			}
			
			/* Stop each of the modules */
			for( Module module: mModules )
			{
				module.stop();
			}
		}
	}
	
	/**
	 * Adds the listener to receive audio packets from all modules.
	 */
	public void addAudioPacketListener( Listener<AudioPacket> listener )
	{
		mAudioPacketBroadcaster.addListener( listener );
	}
	
	public void removeAudioPacketListener( Listener<AudioPacket> listener )
	{
		mAudioPacketBroadcaster.removeListener( listener );
	}
	
	/**
	 * Adds the listener to receive call events from all modules.
	 */
	public void addCallEventListener( Listener<CallEvent> listener )
	{
		mCallEventBroadcaster.addListener( listener );
	}
	
	public void removeCallEventListener( Listener<CallEvent> listener )
	{
		mCallEventBroadcaster.removeListener( listener );
	}
	
	/**
	 * Adds the listener to receive call events from all modules.
	 */
	public void addChannelEventListener( Listener<ChannelEvent> listener )
	{
		mChannelEventBroadcaster.addListener( listener );
	}
	
	public void removeChannelEventListener( Listener<ChannelEvent> listener )
	{
		mChannelEventBroadcaster.removeListener( listener );
	}

	/**
	 * Adds the listener to receive decoder state events from decoder modules
	 */
	public void addDecoderStateEventListener( Listener<DecoderStateEvent> listener )
	{
		mDecoderStateEventBroadcaster.addListener( listener );
	}
	
	public void removeDecoderStateEventListener( Listener<DecoderStateEvent> listener)
	{
		mDecoderStateEventBroadcaster.removeListener( listener );
	}
	
	public Listener<DecoderStateEvent> getDecoderStateEventListener()
	{
		return mDecoderStateEventBroadcaster;
	}
	
	/**
	 * Adds the listener to receive decoded messages from all decoders.
	 */
	public void addMessageListener( Listener<Message> listener )
	{
		mMessageBroadcaster.addListener( listener );
	}
	
	/**
	 * Adds the list of listeners to receive decoded messages from all decoders.
	 */
	public void addMessageListeners( List<Listener<Message>> listeners )
	{
		for( Listener<Message> listener: listeners )
		{
			mMessageBroadcaster.addListener( listener );
		}
	}
	
	public void removeMessageListener( Listener<Message> listener )
	{
		mMessageBroadcaster.removeListener( listener );
	}
	
	/**
	 * Adds the listener to receive call events from all modules.
	 */
	public void addSquelchStateListener( Listener<SquelchState> listener )
	{
		mSquelchStateBroadcaster.addListener( listener );
	}
	
	public void removeSquelchStateListener( Listener<SquelchState> listener )
	{
		mSquelchStateBroadcaster.removeListener( listener );
	}
	
	public void addRealBufferListener( Listener<RealBuffer> listener )
	{
		mFilteredRealBufferBroadcaster.addListener( listener );
	}
	
	public void removeRealBufferListener( Listener<RealBuffer> listener )
	{
		mFilteredRealBufferBroadcaster.removeListener( listener );
	}
	
	@Override
	public Listener<ChannelEvent> getChannelEventListener()
	{
		return mChannelEventBroadcaster;
	}
}
