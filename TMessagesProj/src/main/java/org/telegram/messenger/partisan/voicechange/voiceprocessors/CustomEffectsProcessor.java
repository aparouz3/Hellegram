package org.telegram.messenger.partisan.voicechange.voiceprocessors;

import be.tarsos.dsp.AudioEvent;

/**
 * Custom time-domain effects used by voice presets (Darth Vader, Robot, ...):
 *  - ring modulation: multiplies the signal by a low-frequency sine carrier,
 *    producing the metallic/robotic buzz (Darth Vader's respirator).
 *  - low-pass filter: 2nd-order Butterworth biquad, muffles high frequencies
 *    ("inside a helmet" tone).
 *  - echo: simple delay line with feedback decay (chamber resonance).
 *
 * Mirrors the reference Python pipeline (darth_vader_voice.py).
 */
public class CustomEffectsProcessor extends ChainedAudioProcessor {

    private final double ringModFreq;
    private final double ringModMix;
    private final double lowPassCutoff;
    private final int echoDelayMs;
    private final double echoDecay;
    private final int sampleRate;

    // Ring modulation phase accumulator
    private long ringPhase = 0;

    // Low-pass biquad state
    private double lpX1, lpX2, lpY1, lpY2;
    private final double lpB0, lpB1, lpB2, lpA1, lpA2;

    // Echo delay line
    private final float[] echoBuffer;
    private int echoPos = 0;

    public CustomEffectsProcessor(double ringModFreq, double ringModMix,
                                  double lowPassCutoff,
                                  int echoDelayMs, double echoDecay,
                                  int sampleRate) {
        this.ringModFreq = ringModFreq;
        this.ringModMix = ringModMix;
        this.lowPassCutoff = lowPassCutoff;
        this.echoDelayMs = echoDelayMs;
        this.echoDecay = echoDecay;
        this.sampleRate = sampleRate;

        // 2nd-order Butterworth low-pass coefficients (normalized by a0)
        if (lowPassCutoff > 0 && lowPassCutoff < sampleRate / 2.0) {
            double w0 = 2.0 * Math.PI * lowPassCutoff / sampleRate;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double q = 0.7071; // Butterworth Q
            double alpha = sinW0 / (2.0 * q);
            double a0 = 1.0 + alpha;
            lpB0 = (1.0 - cosW0) / 2.0 / a0;
            lpB1 = (1.0 - cosW0) / a0;
            lpB2 = (1.0 - cosW0) / 2.0 / a0;
            lpA1 = (-2.0 * cosW0) / a0;
            lpA2 = (1.0 - alpha) / a0;
        } else {
            lpB0 = 1.0; lpB1 = 0.0; lpB2 = 0.0;
            lpA1 = 0.0; lpA2 = 0.0;
        }

        if (echoDelayMs > 0) {
            echoBuffer = new float[Math.max(1, sampleRate * echoDelayMs / 1000)];
        } else {
            echoBuffer = null;
        }
    }

    @Override
    public boolean processInternal(AudioEvent audioEvent) {
        float[] buffer = audioEvent.getFloatBuffer();
        for (int i = 0; i < buffer.length; i++) {
            double x = buffer[i];

            // 1) Low-pass filter (muffle high end)
            if (lowPassCutoff > 0) {
                double y = lpB0 * x + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2;
                lpX2 = lpX1;
                lpX1 = x;
                lpY2 = lpY1;
                lpY1 = y;
                x = y;
            }

            // 2) Ring modulation (metallic buzz)
            if (ringModFreq > 0) {
                double carrier = Math.sin(2.0 * Math.PI * ringModFreq * ringPhase / sampleRate);
                ringPhase++;
                x = x * (1.0 - ringModMix) + x * carrier * ringModMix;
            }

            // 3) Echo (delay line with feedback)
            if (echoBuffer != null) {
                double delayed = echoBuffer[echoPos];
                x = x + delayed * echoDecay;
                echoBuffer[echoPos] = (float) x;
                echoPos = (echoPos + 1) % echoBuffer.length;
            }

            buffer[i] = (float) x;
        }
        return true;
    }
}
