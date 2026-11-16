// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

import java.util.ArrayList;

/** Accumulates monotonic running intervals and stores lap durations. */
public final class StopwatchModel {
    private final ArrayList<Long> laps = new ArrayList<>();
    private boolean running;
    private long accumulated;
    private long startedAt;
    private long lapAt;

    public void start(long nowMillis) {
        if (!running) {
            startedAt = nowMillis;
            running = true;
        }
    }

    public void stop(long nowMillis) {
        if (running) {
            accumulated = elapsed(nowMillis);
            running = false;
        }
    }

    public void reset() {
        running = false;
        accumulated = 0;
        startedAt = 0;
        lapAt = 0;
        laps.clear();
    }

    public boolean running() {
        return running;
    }

    public long elapsed(long nowMillis) {
        if (!running || nowMillis <= startedAt) {
            return accumulated;
        }
        long interval = nowMillis - startedAt;
        if (interval < 0 || interval > Long.MAX_VALUE - accumulated) {
            return Long.MAX_VALUE;
        }
        return accumulated + interval;
    }

    public void lap(long nowMillis) {
        if (running) {
            long current = elapsed(nowMillis);
            if (current >= lapAt) {
                laps.add(current - lapAt);
                lapAt = current;
            }
        }
    }

    public int laps() {
        return laps.size();
    }

    public long lap(int i) {
        if (i < 0 || i >= laps.size()) {
            throw new IndexOutOfBoundsException(i);
        }
        return laps.get(laps.size() - 1 - i);
    }

    public String save() {
        StringBuilder out = new StringBuilder("S1|");
        out.append(accumulated).append('|').append(running ? '1' : '0').append('|');
        out.append(startedAt).append('|').append(lapAt).append('|');
        for (int i = 0; i < laps.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(laps.get(i));
        }
        return out.toString();
    }

    public static StopwatchModel restore(String s) {
        StopwatchModel result = new StopwatchModel();
        if (s == null || s.length() > 1_000_000) {
            return result;
        }
        try {
            String[] fields = s.split("\\|", -1);
            if (fields.length != 6 || !fields[0].equals("S1")) {
                return result;
            }
            result.accumulated = Long.parseLong(fields[1]);
            if (!fields[2].equals("0") && !fields[2].equals("1")) {
                return new StopwatchModel();
            }
            result.running = fields[2].equals("1");
            result.startedAt = Long.parseLong(fields[3]);
            result.lapAt = Long.parseLong(fields[4]);
            if (result.accumulated < 0 || result.lapAt < 0
                    || (!result.running && result.lapAt > result.accumulated)) {
                return new StopwatchModel();
            }
            long total = 0;
            if (!fields[5].isEmpty()) {
                for (String entry : fields[5].split(",", -1)) {
                    long duration = Long.parseLong(entry);
                    if (duration < 0 || duration > Long.MAX_VALUE - total) {
                        return new StopwatchModel();
                    }
                    total += duration;
                    result.laps.add(duration);
                }
            }
            return total == result.lapAt ? result : new StopwatchModel();
        } catch (IllegalArgumentException ex) {
            return new StopwatchModel();
        }
    }

    public static String format(long millis) {
        millis = Math.max(0, millis);
        long seconds = millis / 1000;
        if (seconds < 3600) {
            return seconds / 60 + ":" + two(seconds % 60) + "." + millis / 100 % 10;
        }
        return seconds / 3600 + ":" + two(seconds / 60 % 60) + ":" + two(seconds % 60);
    }

    private static String two(long value) {
        return value < 10 ? "0" + value : Long.toString(value);
    }
}
