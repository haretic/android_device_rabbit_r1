// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

/** Filters gravity so the level stays readable while the device settles. */
public final class LevelMath {
    public static final int FLAT = 0;
    public static final int ON_EDGE = 1;
    public static final int PLUMB = 2;

    private final float[] zeroX = new float[3];
    private final float[] zeroY = new float[3];
    private final boolean[] hasZero = new boolean[3];
    private int mode;
    private boolean initialized;
    private double x;
    private double y;
    private double z;
    private long lastNanos;
    private long[] historyTime = new long[128];
    private float[] historyX = new float[128];
    private float[] historyY = new float[128];
    private int historyHead;
    private int historySize;
    private boolean steady;

    public void setMode(int mode) {
        if (mode < FLAT || mode > PLUMB) {
            throw new IllegalArgumentException("mode");
        }
        if (this.mode != mode) {
            this.mode = mode;
            resetStability();
        }
    }

    public int mode() {
        return mode;
    }

    public void update(float ax, float ay, float az, long nanos) {
        if (!Float.isFinite(ax) || !Float.isFinite(ay) || !Float.isFinite(az)) {
            return;
        }
        if (ax == 0 && ay == 0 && az == 0) {
            return;
        }
        if (!initialized) {
            x = ax;
            y = ay;
            z = az;
            initialized = true;
        } else {
            if (nanos <= lastNanos) {
                return;
            }
            double alpha = -Math.expm1(-(nanos - lastNanos) / 150_000_000.0);
            x += alpha * (ax - x);
            y += alpha * (ay - y);
            z += alpha * (az - z);
        }
        lastNanos = nanos;
        float a = mode == FLAT ? tiltX() : angle();
        float b = mode == FLAT ? tiltY() : 0;
        trackStability(a, b, nanos);
    }

    private void resetStability() {
        historyHead = historySize = 0;
        steady = false;
    }

    private void trackStability(float a, float b, long nanos) {
        if (historySize == historyTime.length) {
            int capacity = historySize * 2;
            long[] times = new long[capacity];
            float[] xs = new float[capacity];
            float[] ys = new float[capacity];
            for (int i = 0; i < historySize; i++) {
                int at = (historyHead + i) % historyTime.length;
                times[i] = historyTime[at];
                xs[i] = historyX[at];
                ys[i] = historyY[at];
            }
            historyTime = times;
            historyX = xs;
            historyY = ys;
            historyHead = 0;
        }
        int end = (historyHead + historySize) % historyTime.length;
        historyTime[end] = nanos;
        historyX[end] = a;
        historyY[end] = b;
        historySize++;
        long cutoff = nanos - 500_000_000L;
        while (historySize > 1) {
            int next = (historyHead + 1) % historyTime.length;
            if (historyTime[next] > cutoff) {
                break;
            }
            historyHead = next;
            historySize--;
        }
        steady = false;
        if (historyTime[historyHead] > cutoff || historySize < 2) {
            return;
        }
        float startX = historyX[historyHead];
        float startY = historyY[historyHead];
        int next = (historyHead + 1) % historyTime.length;
        // Keep a boundary sample so sparse sensor updates cover the whole half second.
        float fraction = (float) ((double) (cutoff - historyTime[historyHead])
                / (historyTime[next] - historyTime[historyHead]));
        startX += fraction * (historyX[next] - startX);
        startY += fraction * (historyY[next] - startY);
        float minX = startX;
        float maxX = startX;
        float minY = startY;
        float maxY = startY;
        for (int i = 1; i < historySize; i++) {
            int at = (historyHead + i) % historyTime.length;
            minX = Math.min(minX, historyX[at]);
            maxX = Math.max(maxX, historyX[at]);
            minY = Math.min(minY, historyY[at]);
            maxY = Math.max(maxY, historyY[at]);
        }
        steady = Math.hypot(maxX - minX, maxY - minY) < 0.05;
    }

    private float rawX() {
        return (float) Math.toDegrees(Math.atan2(-x, Math.abs(z)));
    }

    private float rawY() {
        return (float) Math.toDegrees(Math.atan2(-y, Math.abs(z)));
    }

    private float rawAngle() {
        if (mode == ON_EDGE) {
            return (float) Math.toDegrees(Math.atan2(-y, Math.abs(x)));
        }
        return (float) Math.toDegrees(Math.atan2(Math.hypot(x, z), Math.abs(y)));
    }

    public float tiltX() {
        return initialized ? rawX() - zeroX[FLAT] : 0;
    }

    public float tiltY() {
        return initialized ? rawY() - zeroY[FLAT] : 0;
    }

    public float angle() {
        if (!initialized) {
            return 0;
        }
        return mode == FLAT ? (float) Math.hypot(tiltX(), tiltY())
                : rawAngle() - zeroX[mode];
    }

    public void zero() {
        if (!initialized) {
            return;
        }
        zeroX[mode] = mode == FLAT ? rawX() : rawAngle();
        zeroY[mode] = mode == FLAT ? rawY() : 0;
        hasZero[mode] = true;
        resetStability();
    }

    public void clearZero() {
        zeroX[mode] = zeroY[mode] = 0;
        hasZero[mode] = false;
        resetStability();
    }

    public boolean zeroed() {
        return hasZero[mode];
    }

    public boolean steady() {
        return steady;
    }
}
