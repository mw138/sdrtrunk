package dsp.gain;

import sample.real.RealBuffer;

public class NonClippingGain
{
	private float mGain;
	private float mMaxValue;
	
	/**
	 * Applies a fixed gain value to the float samples.
	 * 
	 * @param gain value
	 * @param maxValue max absolute value to prevent clipping ( 0.0 to 1.0 )
	 */
	public NonClippingGain( float gain, float maxValue )
	{
		mGain = gain;
		mMaxValue = maxValue;
	}

	/**
	 * Applies gain to the sample
	 */
	public float apply( float sample )
	{
		float adjusted = sample * mGain;

		if( adjusted > mMaxValue )
		{
			adjusted = mMaxValue;
		}
		if( adjusted < -mMaxValue )
		{
			adjusted = -mMaxValue;
		}
		
		return adjusted;
	}

	/**
	 * Applies gain to the sample array
	 */
	public float[] apply( float[] samples )
	{
		for( int x = 0; x < samples.length; x++ )
		{
			samples[ x ] = apply( samples[ x ]);
		}
		
		return samples;
	}

	/**
	 * Applies gain to the real buffer
	 */
	public RealBuffer apply( RealBuffer buffer )
	{
		float[] samples = buffer.getSamples();
		
		apply( samples );
		
		return buffer;
	}
}
