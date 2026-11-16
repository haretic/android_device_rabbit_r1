// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

import java.util.Arrays;

/** Schedules beats against an absolute fractional frame clock to avoid tempo drift. */
public final class ClickTrack {
    private final int sampleRate;
    private final short[] ordinary;
    private final short[] accent;
    private volatile float tempo = 120;
    private volatile int beatsPerBar = 4;
    private volatile boolean running;
    private long frame;
    private double nextBeat;
    private long beatCount;
    private int beatInBar;
    private short[] click;
    private int clickFrame;

    public ClickTrack(int sampleRate) {
        if (sampleRate < 8000) {
            throw new IllegalArgumentException("sampleRate");
        }
        this.sampleRate = sampleRate;
        ordinary = burst(1000, 0.45);
        accent = burst(1500, 0.60);
    }

    private short[] burst(double hz, double peak) {
        int length = Math.max(3, (int) Math.round(sampleRate * 0.012));
        double[] values = new double[length];
        double maximum = 0;
        for (int i = 0; i < length; i++) {
            double seconds = (double) i / sampleRate;
            double attack = Math.min(1, seconds / 0.0005);
            double release = Math.min(1, (length - 1.0 - i) / (sampleRate * 0.001));
            double envelope = 0.5 * (1 - Math.cos(Math.PI * attack));
            envelope *= 0.5 * (1 - Math.cos(Math.PI * release));
            values[i] = Math.sin(2 * Math.PI * hz * seconds)
                    * Math.exp(-4.0 * i / (length - 1)) * envelope;
            maximum = Math.max(maximum, Math.abs(values[i]));
        }
        short[] result = new short[length];
        for (int i = 0; i < length; i++) {
            result[i] = (short) Math.round(values[i] * peak * 32767 / maximum);
        }
        return result;
    }

    public void setTempo(float bpm) {
        if (!Float.isFinite(bpm)) {
            throw new IllegalArgumentException("bpm");
        }
        tempo = Math.max(30, Math.min(300, bpm));
    }

    public float tempo() {
        return tempo;
    }

    public void setBeatsPerBar(int beats) {
        beatsPerBar = Math.max(1, Math.min(12, beats));
    }

    public void start() {
        if (!running) {
            frame = 0;
            nextBeat = 0;
            beatCount = 0;
            beatInBar = 0;
            click = null;
            running = true;
        }
    }

    public void stop() {
        running = false;
    }

    public boolean running() {
        return running;
    }

    public void render(short[] out, int frames) {
        if (frames < 0 || frames > out.length) {
            throw new IllegalArgumentException("frames");
        }
        if (!running) {
            Arrays.fill(out, 0, frames, (short) 0);
            return;
        }
        for (int i = 0; i < frames; i++, frame++) {
            if (frame >= Math.round(nextBeat)) {
                beatInBar = (int) (beatCount % beatsPerBar);
                beatCount++;
                click = beatInBar == 0 ? accent : ordinary;
                clickFrame = 0;
                nextBeat += sampleRate * 60.0 / tempo;
            }
            out[i] = click != null && clickFrame < click.length ? click[clickFrame++] : 0;
        }
    }

    public long beatCount() {
        return beatCount;
    }

    public int beatInBar() {
        return beatInBar;
    }

    public long framesToNextBeat() {
        return running ? Math.max(0, Math.round(nextBeat) - frame) : 0;
    }
}
