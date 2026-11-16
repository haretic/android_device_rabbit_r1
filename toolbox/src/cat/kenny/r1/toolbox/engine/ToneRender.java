// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

/** Keeps the oscillator phase continuous while controls and the gate ramp. */
public final class ToneRender {
    private final int sampleRate;
    private final int glideFrames;
    private final int fadeFrames;
    private volatile float targetFrequency = 440;
    private volatile float targetLevel = 0.6f;
    private volatile boolean playing;
    private volatile int fadePosition;
    private double phase;
    private double frequency = 440;
    private double level = 0.6;
    private float seenFrequency = 440;
    private float seenLevel = 0.6f;
    private double frequencyStep;
    private double levelStep;
    private int frequencyLeft;
    private int levelLeft;

    public ToneRender(int sampleRate) {
        if (sampleRate < 8000) {
            throw new IllegalArgumentException("sampleRate");
        }
        this.sampleRate = sampleRate;
        glideFrames = Math.max(1, Math.round(sampleRate * 0.030f));
        fadeFrames = Math.max(1, Math.round(sampleRate * 0.015f));
    }

    public void setFrequency(float hz) {
        if (!Float.isFinite(hz)) {
            throw new IllegalArgumentException("hz");
        }
        targetFrequency = Math.max(20, Math.min(20000, hz));
    }

    public void setLevel(float fraction) {
        if (!Float.isFinite(fraction)) {
            throw new IllegalArgumentException("fraction");
        }
        targetLevel = Math.max(0, Math.min(1, fraction));
    }

    public void start() {
        playing = true;
    }

    public void stop() {
        playing = false;
    }

    public boolean silent() {
        return !playing && fadePosition == 0;
    }

    public void render(short[] out, int frames) {
        if (frames < 0 || frames > out.length) {
            throw new IllegalArgumentException("frames");
        }
        for (int i = 0; i < frames; i++) {
            float requestedFrequency = targetFrequency;
            float requestedLevel = targetLevel;
            if (requestedFrequency != seenFrequency) {
                seenFrequency = requestedFrequency;
                frequencyLeft = glideFrames;
                frequencyStep = (requestedFrequency - frequency) / glideFrames;
            }
            if (requestedLevel != seenLevel) {
                seenLevel = requestedLevel;
                levelLeft = glideFrames;
                levelStep = (requestedLevel - level) / glideFrames;
            }
            if (frequencyLeft > 0) {
                frequency += frequencyStep;
                if (--frequencyLeft == 0) {
                    frequency = seenFrequency;
                }
            }
            if (levelLeft > 0) {
                level += levelStep;
                if (--levelLeft == 0) {
                    level = seenLevel;
                }
            }
            double envelope = 0.5 - 0.5 * Math.cos(Math.PI * fadePosition / fadeFrames);
            out[i] = (short) Math.round(Math.sin(phase) * level * envelope * 32767);
            if (playing) {
                fadePosition = Math.min(fadeFrames, fadePosition + 1);
            } else {
                fadePosition = Math.max(0, fadePosition - 1);
            }
            phase += 2 * Math.PI * frequency / sampleRate;
            if (phase >= 2 * Math.PI) {
                phase %= 2 * Math.PI;
            }
        }
    }
}
