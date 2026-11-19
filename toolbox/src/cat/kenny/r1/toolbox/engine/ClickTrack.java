// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

import java.util.Arrays;

/** Schedules beats against an absolute fractional frame clock to avoid tempo drift. */
public final class ClickTrack {
    /** The sounds a beat can make: a click, a wood block, a beep */
    public static final int CLICK = 0, WOOD = 1, BEEP = 2;
    public static final int SOUNDS = 3;
    private final int sampleRate;
    private short[] ordinary;
    private short[] accent;
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
        setSound(CLICK);
    }

    /** What a beat sounds like, CLICK, WOOD or BEEP; the first in a bar higher. */
    public void setSound(int sound) {
        switch (sound) {
            case WOOD:
                ordinary = burst(620, 0.55, 0.030, 7);
                accent = burst(900, 0.65, 0.030, 7);
                break;
            case BEEP:
                ordinary = burst(880, 0.40, 0.060, 1.5);
                accent = burst(1760, 0.45, 0.060, 1.5);
                break;
            default:
                ordinary = burst(1000, 0.45, 0.012, 4);
                accent = burst(1500, 0.60, 0.012, 4);
        }
        // A beat sounding now ends as it began
        click = null;
    }

    private short[] burst(double hz, double peak, double duration, double decay) {
        int length = Math.max(3, (int) Math.round(sampleRate * duration));
        double[] values = new double[length];
        double maximum = 0;
        for (int i = 0; i < length; i++) {
            double seconds = (double) i / sampleRate;
            double attack = Math.min(1, seconds / 0.0005);
            double release = Math.min(1, (length - 1.0 - i) / (sampleRate * 0.001));
            double envelope = 0.5 * (1 - Math.cos(Math.PI * attack));
            envelope *= 0.5 * (1 - Math.cos(Math.PI * release));
            values[i] = Math.sin(2 * Math.PI * hz * seconds)
                    * Math.exp(-decay * i / (length - 1)) * envelope;
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
