// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

/** Uses YIN and several periods to reduce interpolation bias on rich waveforms. */
public final class PitchDetector {
    private final int sampleRate;
    private final double[] ring;
    private final double[] work;
    private final double[] difference;
    private final double[] normalized;
    private int next;
    private int filled;
    private float frequency;
    private float clarity;

    public PitchDetector(int sampleRate, int window) {
        if (sampleRate < 8000 || window < 4 || window / 2 < Math.ceil(sampleRate / 60.0)) {
            throw new IllegalArgumentException("window must hold two periods at 60 Hz");
        }
        this.sampleRate = sampleRate;
        ring = new double[window];
        work = new double[window];
        difference = new double[window / 2 + 1];
        normalized = new double[difference.length];
    }

    public void feed(short[] pcm, int count) {
        if (count < 0 || count > pcm.length) {
            throw new IllegalArgumentException("count");
        }
        for (int i = 0; i < count; i++) {
            ring[next] = pcm[i] / 32768.0;
            next = (next + 1) % ring.length;
        }
        filled = (int) Math.min(ring.length, (long) filled + count);
    }

    public boolean analyse() {
        frequency = 0;
        clarity = 0;
        if (filled < ring.length) {
            return false;
        }
        double sum = 0;
        double squares = 0;
        for (int i = 0; i < work.length; i++) {
            double value = ring[(next + i) % ring.length];
            work[i] = value;
            sum += value;
            squares += value * value;
        }
        double mean = sum / work.length;
        if (squares / work.length - mean * mean < 0.000004) {
            return false;
        }
        int half = work.length / 2;
        double cumulative = 0;
        normalized[0] = 1;
        for (int lag = 1; lag <= half; lag++) {
            double delta = 0;
            for (int j = 0; j < half; j++) {
                double d = work[j] - work[j + lag];
                delta += d * d;
            }
            difference[lag] = delta;
            cumulative += delta;
            normalized[lag] = cumulative == 0 ? 1 : delta * lag / cumulative;
        }
        int minimum = Math.max(2, (int) Math.floor(sampleRate / 2000.0) - 1);
        int maximum = Math.min(half - 1, (int) Math.ceil(sampleRate / 60.0) + 1);
        int candidate = -1;
        for (int lag = minimum; lag <= maximum; lag++) {
            if (normalized[lag] < 0.15) {
                while (lag < maximum && normalized[lag + 1] < normalized[lag]) {
                    lag++;
                }
                candidate = lag;
                break;
            }
        }
        if (candidate < 0) {
            return false;
        }
        double period = interpolate(candidate);
        double numerator = 0;
        double denominator = 0;
        for (int multiple = 1; multiple * period < half - 2; multiple++) {
            int center = (int) Math.round(multiple * period);
            int best = center;
            for (int lag = Math.max(1, center - 2); lag <= Math.min(half - 1, center + 2);
                    lag++) {
                if (difference[lag] < difference[best]) {
                    best = lag;
                }
            }
            numerator += multiple * interpolate(best);
            denominator += (double) multiple * multiple;
            // Refine each search center before its error can grow with the period count.
            period = numerator / denominator;
        }
        if (denominator > 0) {
            period = numerator / denominator;
        }
        double hz = sampleRate / period;
        if (hz < 59.9 || hz > 2001) {
            return false;
        }
        frequency = (float) hz;
        clarity = (float) Math.max(0, Math.min(1, 1 - normalized[candidate]));
        return true;
    }

    private double interpolate(int at) {
        double left = difference[at - 1];
        double middle = difference[at];
        double right = difference[at + 1];
        double curvature = left - 2 * middle + right;
        double shift = curvature <= 0 ? 0 : 0.5 * (left - right) / curvature;
        return at + Math.max(-0.5, Math.min(0.5, shift));
    }

    public float frequency() {
        return frequency;
    }

    public float clarity() {
        return clarity;
    }
}
