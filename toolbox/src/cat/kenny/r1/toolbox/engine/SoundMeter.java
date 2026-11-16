// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

/** Tracks A-weighted energy without allocating on the audio thread. */
public final class SoundMeter {
    private final int sampleRate;
    private final float offsetDb;
    private final long barPeriod;
    private final Biquad[] filters;
    private final double gain;
    private final double[] recent;
    private final double[] energies;
    private final long[] samples;
    private final long[] times;
    private int recentIndex;
    private int recentCount;
    private double recentSum;
    private int nextBar;
    private int kept;
    private double barEnergy;
    private long barSamples;
    private long barPhase;
    private long barStart;
    private volatile boolean held;

    public SoundMeter(int sampleRate, float offsetDb, int barMillis, int bars) {
        if (sampleRate < 8000 || !Float.isFinite(offsetDb) || barMillis < 1 || bars < 1) {
            throw new IllegalArgumentException("meter configuration");
        }
        this.sampleRate = sampleRate;
        this.offsetDb = offsetDb;
        barPeriod = (long) sampleRate * barMillis;
        recent = new double[Math.max(1, Math.round(sampleRate * 0.125f))];
        energies = new double[bars];
        samples = new long[bars];
        times = new long[bars];
        double p1 = pole(20.598997);
        double p2 = pole(107.65265);
        double p3 = pole(737.86223);
        double p4 = pole(12194.217);
        double k = 2.0 * sampleRate;
        filters = new Biquad[] {
            new Biquad(k * k, -2 * k * k, k * k, p1, p1, k),
            new Biquad(k * k, -2 * k * k, k * k, p2, p3, k),
            new Biquad(p4 * p4, 2 * p4 * p4, p4 * p4, p4, p4, k)
        };
        double magnitude = 1;
        for (Biquad filter : filters) {
            magnitude *= filter.magnitude(2 * Math.PI * 1000 / sampleRate);
        }
        gain = 1 / magnitude;
    }

    private double pole(double hz) {
        // Prewarping the upper pole keeps the 10 kHz response near the analog curve.
        return hz < sampleRate * 0.49
                ? 2 * sampleRate * Math.tan(Math.PI * hz / sampleRate) : 2 * Math.PI * hz;
    }

    public void feed(short[] pcm, int count, long wallClockMillis) {
        if (held) {
            return;
        }
        if (count < 0 || count > pcm.length) {
            throw new IllegalArgumentException("count");
        }
        for (int i = 0; i < count; i++) {
            double value = pcm[i] * (gain / 32768.0);
            for (Biquad filter : filters) {
                value = filter.process(value);
            }
            double energy = value * value;
            recentSum += energy - recent[recentIndex];
            recent[recentIndex] = energy;
            recentIndex = (recentIndex + 1) % recent.length;
            recentCount = Math.min(recentCount + 1, recent.length);
            if (barSamples == 0) {
                // The supplied wall time belongs to the last sample in this block.
                barStart = wallClockMillis - (count - 1L - i) * 1000 / sampleRate;
            }
            barEnergy += energy;
            barSamples++;
            barPhase += 1000;
            if (barPhase >= barPeriod) {
                energies[nextBar] = barEnergy;
                samples[nextBar] = barSamples;
                times[nextBar] = barStart;
                nextBar = (nextBar + 1) % energies.length;
                kept = Math.min(kept + 1, energies.length);
                barEnergy = 0;
                barSamples = 0;
                barPhase -= barPeriod;
            }
        }
    }

    private float db(double energy) {
        return (float) (10 * Math.log10(Math.max(1e-12, energy)) + offsetDb);
    }

    public float now() {
        return db(recentCount == 0 ? 0 : recentSum / recentCount);
    }

    public int barCount() {
        return kept;
    }

    private int index(int i) {
        if (i < 0 || i >= kept) {
            throw new IndexOutOfBoundsException(i);
        }
        return (nextBar - kept + i + energies.length) % energies.length;
    }

    public float bar(int i) {
        int at = index(i);
        return db(energies[at] / samples[at]);
    }

    public long barTime(int i) {
        return times[index(i)];
    }

    private int loudestIndex() {
        int best = -1;
        double peak = -1;
        for (int i = 0; i < kept; i++) {
            int at = index(i);
            double energy = energies[at] / samples[at];
            if (energy > peak) {
                best = at;
                peak = energy;
            }
        }
        return best;
    }

    public float loudest() {
        int at = loudestIndex();
        return db(at < 0 ? 0 : energies[at] / samples[at]);
    }

    public long loudestTime() {
        int at = loudestIndex();
        return at < 0 ? 0 : times[at];
    }

    public float average() {
        double energy = 0;
        long count = 0;
        for (int i = 0; i < kept; i++) {
            int at = index(i);
            energy += energies[at];
            count += samples[at];
        }
        return db(count == 0 ? 0 : energy / count);
    }

    public void hold(boolean hold) {
        held = hold;
    }

    public boolean held() {
        return held;
    }

    /** Uses double state to avoid losing precision at the low frequency poles. */
    private static final class Biquad {
        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;
        private double s1;
        private double s2;

        Biquad(double n0, double n1, double n2, double p, double q, double k) {
            double denominator = (k + p) * (k + q);
            b0 = n0 / denominator;
            b1 = n1 / denominator;
            b2 = n2 / denominator;
            a1 = 2 * (p * q - k * k) / denominator;
            a2 = (p - k) * (q - k) / denominator;
        }

        double process(double x) {
            double y = b0 * x + s1;
            s1 = b1 * x - a1 * y + s2;
            s2 = b2 * x - a2 * y;
            return y;
        }

        double magnitude(double w) {
            double c = Math.cos(w);
            double s = Math.sin(w);
            double c2 = Math.cos(2 * w);
            double s2w = Math.sin(2 * w);
            return Math.hypot(b0 + b1 * c + b2 * c2, b1 * s + b2 * s2w)
                    / Math.hypot(1 + a1 * c + a2 * c2, a1 * s + a2 * s2w);
        }
    }
}
