// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Keeps net counts, lap totals, and a rolling minute of count changes. */
public final class Tally {
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private final ArrayList<Integer> laps = new ArrayList<>();
    private int count;
    private long since;
    private boolean started;
    private int lapBase;

    public void add(int n, long wallClockMillis) {
        if (!started) {
            since = wallClockMillis;
            started = true;
        }
        int updated = (int) Math.max(0, Math.min(Integer.MAX_VALUE, count + (long) n));
        int delta = updated - count;
        count = updated;
        prune(wallClockMillis);
        events.addLast(new Event(wallClockMillis, delta));
    }

    public int count() {
        return count;
    }

    public long since() {
        return since;
    }

    private void prune(long now) {
        while (!events.isEmpty() && (double) now - events.peekFirst().time >= 60000) {
            events.removeFirst();
        }
    }

    public float perMinute(long nowMillis) {
        if (!started) {
            return 0;
        }
        prune(nowMillis);
        long total = 0;
        for (Event event : events) {
            if (event.time <= nowMillis && (double) nowMillis - event.time < 60000) {
                total += event.delta;
            }
        }
        double duration = Math.min(60000, Math.max(0, (double) nowMillis - since));
        return duration == 0 ? 0 : (float) (total * 60000.0 / duration);
    }

    public void lap() {
        laps.add(count - lapBase);
        lapBase = count;
    }

    public int laps() {
        return laps.size();
    }

    public int lap(int i) {
        if (i < 0 || i >= laps.size()) {
            throw new IndexOutOfBoundsException(i);
        }
        return laps.get(laps.size() - 1 - i);
    }

    public void clear() {
        count = 0;
        since = 0;
        started = false;
        lapBase = 0;
        events.clear();
        laps.clear();
    }

    public String save() {
        StringBuilder out = new StringBuilder("T1|");
        out.append(count).append('|').append(since).append('|');
        out.append(started ? '1' : '0').append('|').append(lapBase).append('|');
        for (int i = 0; i < laps.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(laps.get(i));
        }
        out.append('|');
        boolean first = true;
        for (Event event : events) {
            if (!first) {
                out.append(',');
            }
            first = false;
            out.append(event.time).append(':').append(event.delta);
        }
        return out.toString();
    }

    public static Tally restore(String s) {
        Tally result = new Tally();
        if (s == null || s.length() > 1_000_000) {
            return result;
        }
        try {
            String[] fields = s.split("\\|", -1);
            if (fields.length != 7 || !fields[0].equals("T1")) {
                return result;
            }
            result.count = Integer.parseInt(fields[1]);
            result.since = Long.parseLong(fields[2]);
            if (!fields[3].equals("0") && !fields[3].equals("1")) {
                return new Tally();
            }
            result.started = fields[3].equals("1");
            result.lapBase = Integer.parseInt(fields[4]);
            if (result.count < 0 || result.lapBase < 0) {
                return new Tally();
            }
            long base = 0;
            if (!fields[5].isEmpty()) {
                for (String lap : fields[5].split(",", -1)) {
                    int delta = Integer.parseInt(lap);
                    base += delta;
                    if (base < 0 || base > Integer.MAX_VALUE) {
                        return new Tally();
                    }
                    result.laps.add(delta);
                }
            }
            if (base != result.lapBase) {
                return new Tally();
            }
            if (!fields[6].isEmpty()) {
                for (String entry : fields[6].split(",", -1)) {
                    String[] parts = entry.split(":", -1);
                    if (parts.length != 2) {
                        return new Tally();
                    }
                    result.events.add(new Event(Long.parseLong(parts[0]),
                            Integer.parseInt(parts[1])));
                }
            }
            if (!result.started && (result.count != 0 || result.since != 0
                    || !result.events.isEmpty() || result.lapBase != 0)) {
                return new Tally();
            }
            return result;
        } catch (IllegalArgumentException ex) {
            return new Tally();
        }
    }

    /** Stores the applied delta so subtracting below zero cannot skew the rate. */
    private static final class Event {
        private final long time;
        private final int delta;

        Event(long time, int delta) {
            this.time = time;
            this.delta = delta;
        }
    }
}
