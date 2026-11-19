// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth.engine;

import java.util.concurrent.atomic.AtomicLongArray;

/** Seeded pad voices with bounded storage and an allocation-free audio path. */
public final class SynthEngine {
    public static final int PADS = 6;
    public static final int GRAIN = 0, CHIRP = 1, CRUSH = 2, BELL = 3, SUB = 4, HAT = 5;
    public static final int FILTER = 0, PITCH = 1, STUTTER = 2, ECHO = 3;
    /** The scales the pads play in, each of seven notes: major, minor, dorian, mixolydian,
     *  lydian, phrygian, harmonic minor. In the order of the app's names for them. */
    private static final int[][] SCALES = {
        {0, 2, 4, 5, 7, 9, 11},
        {0, 2, 3, 5, 7, 8, 10},
        {0, 2, 3, 5, 7, 9, 10},
        {0, 2, 4, 5, 7, 9, 10},
        {0, 2, 4, 6, 7, 9, 11},
        {0, 1, 3, 5, 7, 8, 10},
        {0, 2, 3, 5, 7, 8, 11},
    };
    public static final int SCALE_COUNT = SCALES.length;
    /** The echo comes back this long after the sound */
    private static final double ECHO_SECONDS = .240;

    private static final double TAU = 2.0 * Math.PI;
    private static final int[] GRAIN_NOTES = {7, 8, 9, 11, 13};
    private static final double[] LENGTH = {.180, .160, .220, .900, .500, .045};
    private static final double[] GAIN = {.43, .48, .43, .38, .40, 1.0};
    private static final int VOICES = 32;
    private final int rate;
    private final double dt;
    private final double smoothing;
    private final double meterDecay;
    private final AtomicLongArray input = new AtomicLongArray(PADS);
    private final Pad[] pads = new Pad[PADS];
    private final Voice[] voices = new Voice[VOICES];
    private final Biquad tone = new Biquad();
    private final Biquad[] guard = {new Biquad(), new Biquad(), new Biquad()};
    private final Biquad[] hatPreview = {
        new Biquad(), new Biquad(), new Biquad(), new Biquad()
    };
    private volatile float filterTarget = 1f;
    private volatile float pitchTarget = .5f;
    private volatile float stutterTarget = .35f;
    private volatile float echoTarget = 0f;
    private volatile int scaleTarget = 0;
    private volatile boolean audioSilent = true;
    private double filter = 1.0, pitch = .5, stutter = .35, echo = 0;
    /** The echo's line, allocated once; what is in it is heard again ECHO_SECONDS later */
    private final double[] echoLine;
    private int echoAt;
    private double echoPeak;
    private final double echoDecay;
    private double period;
    private double hatShift = 1.0;
    private final double[] frequencies = new double[43];
    private long random;
    private long clock;
    private int coefficientClock;

    public SynthEngine(int sampleRate, long seed) {
        if (sampleRate < 22050 || sampleRate > 192000) {
            throw new IllegalArgumentException("sampleRate must be 22050..192000");
        }
        rate = sampleRate;
        dt = 1.0 / rate;
        smoothing = 1.0 - Math.exp(-1.0 / (.020 * rate));
        meterDecay = Math.exp(-1.0 / (.045 * rate));
        echoLine = new double[Math.max(1, (int) Math.round(ECHO_SECONDS * rate))];
        // Slower than the gap between repeats, so a fading echo is not taken for silence
        echoDecay = Math.exp(-1.0 / (.5 * rate));
        random = seed ^ 0x9e3779b97f4a7c15L;
        if (random == 0) random = 1;
        for (int i = 0; i < PADS; i++) pads[i] = new Pad();
        for (int i = 0; i < VOICES; i++) voices[i] = new Voice();
        for (Biquad stage : guard) stage.lowpass(7000, .70710678, rate);
        hatPreview[0].lowpass(Math.min(19000, rate * .44), .70710678, rate);
        for (int i = 1; i < hatPreview.length; i++) {
            hatPreview[i].lowpass(7000, .70710678, rate);
        }
        updateCoefficients();
    }

    public void padDown(int pad) {
        checkPad(pad);
        long state = input.get(pad);
        if ((state & 1) == 0) input.set(pad, ((state + 2) & ~1L) | 1L);
    }

    public void padUp(int pad) {
        checkPad(pad);
        input.set(pad, input.get(pad) & ~1L);
    }

    public void setKnob(int which, float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("finite knob required");
        value = Math.max(0f, Math.min(1f, value));
        switch (which) {
            case FILTER: filterTarget = value; break;
            case PITCH: pitchTarget = value; break;
            case STUTTER: stutterTarget = value; break;
            case ECHO: echoTarget = value; break;
            default: throw new IllegalArgumentException("unknown knob");
        }
    }

    /** The scale the pads and the pitch knob move in, 0 to SCALE_COUNT - 1. */
    public void setScale(int scale) {
        scaleTarget = Math.max(0, Math.min(SCALE_COUNT - 1, scale));
    }

    public int scale() {
        return scaleTarget;
    }

    public float knob(int which) {
        switch (which) {
            case FILTER: return filterTarget;
            case PITCH: return pitchTarget;
            case STUTTER: return stutterTarget;
            case ECHO: return echoTarget;
            default: throw new IllegalArgumentException("unknown knob");
        }
    }

    public float level(int pad) {
        checkPad(pad);
        return pads[pad].level;
    }

    public int slice(int pad) {
        checkPad(pad);
        return pads[pad].slices;
    }

    public boolean silent() {
        if (!audioSilent) return false;
        for (int i = 0; i < PADS; i++) {
            long state = input.get(i);
            if ((state & 1) != 0 || (state >>> 1) != pads[i].consumed) return false;
        }
        return true;
    }

    /** One audio thread owns all oscillators; the UI publishes only controls and hit counters. */
    public void render(short[] out, int frames) {
        if (frames < 0 || frames > out.length) throw new IllegalArgumentException("frames");
        if (frames == 0) return;
        receivePads();
        boolean quiet = false;
        for (int frame = 0; frame < frames; frame++) {
            filter += smoothing * (filterTarget - filter);
            pitch += smoothing * (pitchTarget - pitch);
            stutter += smoothing * (stutterTarget - stutter);
            echo += smoothing * (echoTarget - echo);
            if (coefficientClock-- <= 0) {
                updateCoefficients();
                coefficientClock = 31;
            }
            for (int p = 0; p < PADS; p++) {
                Pad pad = pads[p];
                pad.peak *= meterDecay;
                if (pad.down && --pad.until <= 0) {
                    pad.until += period;
                    pad.slices++;
                    hit(p, true, true);
                }
            }
            double mix = 0;
            boolean active = false;
            for (Voice voice : voices) {
                if (!voice.active) continue;
                double sample = voice.sample();
                mix += sample;
                Pad pad = pads[voice.pad];
                pad.peak = Math.max(pad.peak, Math.abs(sample) * 2.0);
                active |= voice.active;
            }
            double output = tone.tick(mix);
            for (Biquad stage : guard) output = stage.tick(output);
            // The echo: what went in ECHO_SECONDS ago, fed back into itself to fade away.
            // Turned off, nothing goes into the line and it empties within one round.
            double delayed = echoLine[echoAt];
            double fed = echo > .001 ? output + delayed * .55 * echo : 0;
            // Flush a long tail before it reaches denormal arithmetic, as the filters do
            echoLine[echoAt] = Math.abs(fed) < 1e-20 ? 0 : fed;
            if (++echoAt >= echoLine.length) echoAt = 0;
            output += delayed * .6 * echo;
            echoPeak = Math.max(echoPeak * echoDecay, Math.abs(delayed * echo));
            // A memoryless soft knee recovers immediately after a dense chord.
            output = .98 * Math.tanh(output / .98);
            out[frame] = (short) Math.round(32767.0 * output);
            quiet = !active && Math.abs(output) < 1e-8 && echoPeak < 1e-5;
            clock++;
        }
        boolean held = false;
        for (Pad pad : pads) {
            held |= pad.down;
        }
        for (Pad pad : pads) {
            if (quiet && !held) pad.peak = 0;
            pad.level = (float) Math.min(1, pad.peak);
        }
        if (quiet && !held && !audioSilent) {
            // What is left of an echo is below hearing: start the next sound on a clean line
            java.util.Arrays.fill(echoLine, 0);
            echoPeak = 0;
        }
        audioSilent = quiet && !held;
    }

    private void receivePads() {
        for (int p = 0; p < PADS; p++) {
            Pad pad = pads[p];
            long state = input.get(p);
            long hits = state >>> 1;
            boolean down = (state & 1) != 0;
            if (hits != pad.consumed) {
                // Counters preserve a tap even when both edges arrive between callbacks.
                long count = Math.min(VOICES, hits - pad.consumed);
                for (long i = 0; i < count; i++) hit(p, false, down);
                pad.until = period;
                pad.consumed = hits;
            }
            if (pad.down && !down) {
                for (Voice voice : voices) {
                    if (voice.active && voice.pad == p && voice.sliceEnd > 0) {
                        // Keep the gain reached by a slice's exit ramp when releasing it.
                        voice.strength *= Math.min(1, Math.max(0,
                                (voice.sliceEnd - voice.age) / .002));
                        voice.sliceEnd = 0;
                    }
                }
            }
            pad.down = down;
        }
    }

    private void updateCoefficients() {
        double cutoff = 600.0 * Math.pow(19000.0 / 600.0, filter);
        cutoff = Math.min(cutoff, rate * .44);
        double q = .707 + 1.293 * Math.sin(Math.PI * filter);
        tone.lowpass(cutoff, q, rate);
        period = rate / (4.0 * Math.pow(8, stutter));
        hatShift = Math.pow(2, (pitch - .5) * .35);
        int shift = (int) Math.round((pitch - .5) * 14);
        for (int degree = -21; degree <= 21; degree++) {
            frequencies[degree + 21] = noteFrequency(degree + shift);
        }
        for (Voice voice : voices) {
            if (voice.active && voice.pad == HAT) voice.hatCoefficients();
        }
    }

    private double noteFrequency(int degree) {
        int[] scale = SCALES[Math.max(0, Math.min(SCALE_COUNT - 1, scaleTarget))];
        int octave = Math.floorDiv(degree, 7);
        int semitone = 12 * octave + scale[Math.floorMod(degree, 7)];
        return 440 * Math.pow(2, semitone / 12.0);
    }

    private void hit(int pad, boolean slice, boolean held) {
        audioSilent = false;
        Voice free = null;
        Voice oldest = voices[0];
        for (Voice voice : voices) {
            if (!voice.active && free == null) free = voice;
            if (voice.born < oldest.born) oldest = voice;
            if (voice.active && voice.pad == pad) {
                voice.killEnd = Math.min(voice.killEnd, voice.age + .002);
            }
        }
        // The two-millisecond exit limits normal overlap to two voices per pad.
        if (free == null) free = oldest;
        free.start(pad, slice, held);
    }

    private long nextRandom() {
        random ^= random << 13;
        random ^= random >>> 7;
        random ^= random << 17;
        return random;
    }

    private double uniform() {
        return (nextRandom() >>> 11) * 0x1.0p-53;
    }

    private static void checkPad(int pad) {
        if (pad < 0 || pad >= PADS) throw new IllegalArgumentException("unknown pad");
    }

    /** The volatile fields are the complete audio-to-UI snapshot. */
    private static final class Pad {
        volatile float level;
        volatile int slices;
        volatile long consumed;
        boolean down;
        double until;
        double peak;
    }

    /** Storage is allocated once, including each grain cloud and noise filter. */
    private final class Voice {
        final double[] grainStart = new double[14];
        final double[] grainLength = new double[14];
        final double[] grainPhase = new double[14];
        final int[] grainDegree = new int[14];
        final Biquad hp1 = new Biquad(), hp2 = new Biquad();
        final Biquad lp1 = new Biquad(), lp2 = new Biquad();
        boolean active;
        int pad, grains, hold;
        long born, noise;
        double age, offset, sliceEnd, killEnd, strength;
        double phase, secondPhase, frequency, secondFrequency, held;

        void start(int kind, boolean sliced, boolean heldDown) {
            pad = kind;
            age = 0;
            offset = sliced ? .006 : 0;
            if (sliced && (pad == GRAIN || pad == CRUSH || pad == HAT)) {
                offset = uniform() * (pad == GRAIN ? .008 : (pad == HAT ? .012 : .035));
            }
            sliceEnd = heldDown ? .6 * period / rate : 0;
            killEnd = Double.POSITIVE_INFINITY;
            strength = 1;
            phase = 0;
            secondPhase = 0;
            hold = 0;
            frequency = 0;
            secondFrequency = 0;
            held = 0;
            born = clock;
            noise = nextRandom();
            active = true;
            if (pad == GRAIN) {
                grains = 8 + (int) (uniform() * 7);
                for (int i = 0; i < grains; i++) {
                    grainLength[i] = .018 + uniform() * .027;
                    grainStart[i] = i == 0 ? 0 : uniform() * (.18 - grainLength[i]);
                    grainDegree[i] = GRAIN_NOTES[(int) (uniform() * GRAIN_NOTES.length)];
                    grainPhase[i] = uniform() * TAU;
                }
            } else if (pad == HAT) {
                hp1.clear(); hp2.clear(); lp1.clear(); lp2.clear();
                hatCoefficients();
                calibrateHat();
            }
        }

        void calibrateHat() {
            // A bounded preview keeps random noise hits at the same perceived strength.
            long savedNoise = noise;
            for (Biquad stage : hatPreview) stage.clear();
            double peak = 0;
            double envelope = 1;
            double decay = Math.exp(-dt / .012);
            int frames = (int) Math.ceil(LENGTH[HAT] * rate);
            for (int i = 0; i < frames + 128; i++) {
                double value = 0;
                if (i < frames) {
                    double t = i * dt;
                    double ramp = Math.min(1, t / .002)
                            * Math.min(1, (LENGTH[HAT] - t) / .002);
                    value = hatNoise() * envelope * ramp;
                    envelope *= decay;
                }
                for (Biquad stage : hatPreview) value = stage.tick(value);
                peak = Math.max(peak, Math.abs(value));
            }
            strength = .38 / Math.max(.001, peak);
            noise = savedNoise;
            hp1.clear(); hp2.clear(); lp1.clear(); lp2.clear();
        }

        double hatNoise() {
            noise ^= noise << 13;
            noise ^= noise >>> 7;
            noise ^= noise << 17;
            double value = (noise >>> 11) * 0x1.0p-52 - 1;
            return lp2.tick(lp1.tick(hp2.tick(hp1.tick(value))));
        }

        void hatCoefficients() {
            hp1.highpass(5000 * hatShift, .70710678, rate);
            hp2.highpass(5000 * hatShift, .70710678, rate);
            lp1.lowpass(6800 * hatShift, .70710678, rate);
            lp2.lowpass(6800 * hatShift, .70710678, rate);
        }

        double sample() {
            double t = age + offset;
            double remaining = Math.min(LENGTH[pad] - t, killEnd - age);
            if (sliceEnd > 0) remaining = Math.min(remaining, sliceEnd - age);
            if (remaining <= 0) {
                active = false;
                return 0;
            }
            double ramp = Math.min(1, age / .002) * Math.min(1, remaining / .002);
            double value;
            switch (pad) {
                case GRAIN:
                    value = 0;
                    for (int i = 0; i < grains; i++) {
                        double u = (t - grainStart[i]) / grainLength[i];
                        if (u >= 0 && u < 1) {
                            double window = Math.sin(Math.PI * u);
                            grainPhase[i] += TAU * frequencies[grainDegree[i] + 21] * dt;
                            value += Math.sin(grainPhase[i]) * window * window;
                        }
                    }
                    value = Math.tanh(value * .70);
                    break;
                case CHIRP:
                    glide(frequencies[21], 0);
                    phase += TAU * frequency * Math.pow(2, (19.0 / 12.0) * t / .160) * dt;
                    value = Math.sin(phase) * Math.exp(-3.5 * t / .160);
                    break;
                case CRUSH:
                    glide(frequencies[21], frequencies[25]);
                    phase += TAU * frequency * dt;
                    secondPhase += TAU * secondFrequency * dt;
                    if (hold-- <= 0) {
                        double square = .72 * (Math.sin(phase) >= 0 ? 1 : -1)
                                + .28 * (Math.sin(secondPhase) >= 0 ? 1 : -1);
                        held = Math.rint(square * 5) / 5;
                        hold = Math.max(1, rate / 6500) - 1;
                    }
                    value = held * Math.exp(-3 * t / .220);
                    break;
                case BELL:
                    glide(frequencies[30], 0);
                    phase += TAU * frequency * dt;
                    value = Math.sin(phase + 1.6 * Math.exp(-t / .040)
                            * Math.sin(3.5 * phase)) * Math.exp(-t / .165);
                    break;
                case SUB:
                    glide(frequencies[7], 0);
                    phase += TAU * frequency * (1 + .17 * Math.exp(-t / .015)) * dt;
                    value = (.12 * Math.sin(phase) + .42 * Math.sin(2 * phase)
                            + .34 * Math.sin(3 * phase) + .16 * Math.sin(4 * phase)
                            + .16 * Math.sin(5 * phase) + .10 * Math.sin(6 * phase))
                            * Math.exp(-t / .115);
                    break;
                default:
                    value = hatNoise() * Math.exp(-t / .012);
                    break;
            }
            age += dt;
            return value * ramp * strength * GAIN[pad];
        }

        void glide(double target, double other) {
            // Scale targets stay discrete; the oscillator takes twenty milliseconds to settle.
            if (frequency == 0) {
                frequency = target;
                secondFrequency = other;
            } else {
                frequency += smoothing * (target - frequency);
                secondFrequency += smoothing * (other - secondFrequency);
            }
        }
    }

    /** Transposed direct form keeps coefficient changes and silence numerically well behaved. */
    private static final class Biquad {
        double b0, b1, b2, a1, a2, z1, z2;

        void lowpass(double hz, double q, int rate) {
            coefficients(hz, q, rate, false);
        }

        void highpass(double hz, double q, int rate) {
            coefficients(hz, q, rate, true);
        }

        void coefficients(double hz, double q, int rate, boolean high) {
            double w = TAU * Math.min(hz, rate * .44) / rate;
            double c = Math.cos(w), alpha = Math.sin(w) / (2 * q);
            double norm = 1 / (1 + alpha);
            b0 = (high ? (1 + c) : (1 - c)) * .5 * norm;
            b1 = (high ? -(1 + c) : (1 - c)) * norm;
            b2 = b0;
            a1 = -2 * c * norm;
            a2 = (1 - alpha) * norm;
        }

        double tick(double input) {
            double output = b0 * input + z1;
            z1 = b1 * input - a1 * output + z2;
            z2 = b2 * input - a2 * output;
            // Flush inaudible tails before they reach denormal arithmetic.
            if (Math.abs(z1) < 1e-20) z1 = 0;
            if (Math.abs(z2) < 1e-20) z2 = 0;
            return output;
        }

        void clear() {
            z1 = 0;
            z2 = 0;
        }
    }
}
