package org.telegram.messenger.partisan.voicechange;

/**
 * Ready-made voice presets for the Voice Changer.
 * Each preset combines WORLD formant/pitch parameters (f0Shift, low/mid/high ratios)
 * with custom time-domain effects (ring modulation, low-pass filter, echo).
 *
 * Values for DARTH_VADER mirror the reference Python pipeline:
 *   pitch -6 semitones (2^(-6/12) ≈ 0.707) + ring mod 28Hz/mix 0.3
 *   + low-pass 3200Hz + echo 80ms/decay 0.2.
 */
public enum VoicePreset {
    NONE("No effect",
            1.0, 1.0, 1.0, 1.0,
            0.0, 0.0,
            0.0,
            0, 0.0),

    DARTH_VADER("Darth Vader",
            0.71, 0.72, 0.78, 0.85,
            28.0, 0.30,
            3200.0,
            80, 0.20),

    CHIPMUNK("Chipmunk",
            1.8, 1.4, 1.4, 1.4,
            0.0, 0.0,
            0.0,
            0, 0.0),

    ROBOT("Robot",
            1.0, 1.0, 1.0, 1.0,
            45.0, 0.50,
            2400.0,
            0, 0.0),

    DEMON("Demon",
            0.55, 0.60, 0.65, 0.72,
            20.0, 0.20,
            2600.0,
            90, 0.30);

    private final String displayName;
    private final double f0Shift;
    private final double lowRatio;
    private final double midRatio;
    private final double highRatio;
    private final double ringModFreq;
    private final double ringModMix;
    private final double lowPassCutoff;
    private final int echoDelayMs;
    private final double echoDecay;

    VoicePreset(String displayName,
                double f0Shift, double lowRatio, double midRatio, double highRatio,
                double ringModFreq, double ringModMix,
                double lowPassCutoff,
                int echoDelayMs, double echoDecay) {
        this.displayName = displayName;
        this.f0Shift = f0Shift;
        this.lowRatio = lowRatio;
        this.midRatio = midRatio;
        this.highRatio = highRatio;
        this.ringModFreq = ringModFreq;
        this.ringModMix = ringModMix;
        this.lowPassCutoff = lowPassCutoff;
        this.echoDelayMs = echoDelayMs;
        this.echoDecay = echoDecay;
    }

    public String getDisplayName() {
        return displayName;
    }

    public double getF0Shift() {
        return f0Shift;
    }

    public double getLowRatio() {
        return lowRatio;
    }

    public double getMidRatio() {
        return midRatio;
    }

    public double getHighRatio() {
        return highRatio;
    }

    public double getRingModFrequency() {
        return ringModFreq;
    }

    public double getRingModMix() {
        return ringModMix;
    }

    public double getLowPassCutoff() {
        return lowPassCutoff;
    }

    public int getEchoDelayMs() {
        return echoDelayMs;
    }

    public double getEchoDecay() {
        return echoDecay;
    }

    /** True if this preset adds any custom time-domain effect. */
    public boolean hasCustomEffects() {
        return ringModFreq > 0 || lowPassCutoff > 0 || echoDelayMs > 0;
    }
}
