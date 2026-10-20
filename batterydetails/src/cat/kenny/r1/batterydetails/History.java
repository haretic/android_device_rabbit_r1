// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The graph's data: one line per sample in files/history.csv, "time,percent,mV,mA,charging",
 * in device protected storage so the sampler works before the first unlock. A few kB,
 * trimmed to the last week.
 */
final class History {

    private static final String TAG = "R1BatteryDetails";
    private static final String FILE = "history.csv";
    private static final long KEEP_MS = 7 * 24 * 3600 * 1000L;
    /** Rewrite the file without the old lines once it has this many. */
    private static final int TRIM_AT = 1000;

    static final class Point {
        final long time;
        final int percent;

        Point(long time, int percent) {
            this.time = time;
            this.percent = percent;
        }
    }

    private History() {
    }

    static synchronized void record(Context context, Reading reading) {
        if (reading.percent == Reading.UNKNOWN) {
            return;
        }
        final File file = file(context);
        final String line = reading.time + "," + reading.percent + "," + reading.millivolts
                + "," + (reading.microamps == Reading.UNKNOWN ? Reading.UNKNOWN
                        : reading.microamps / 1000)
                + "," + (reading.isCharging() ? 1 : 0) + "\n";
        try (Writer out = new OutputStreamWriter(new FileOutputStream(file, true),
                StandardCharsets.UTF_8)) {
            out.write(line);
        } catch (IOException e) {
            Log.w(TAG, "Couldn't save a battery sample", e);
            return;
        }
        final List<String> lines = readLines(file);
        if (lines.size() >= TRIM_AT) {
            final long oldest = System.currentTimeMillis() - KEEP_MS;
            final StringBuilder kept = new StringBuilder();
            for (String l : lines) {
                if (parseTime(l) >= oldest) {
                    kept.append(l).append('\n');
                }
            }
            try (Writer out = new OutputStreamWriter(new FileOutputStream(file, false),
                    StandardCharsets.UTF_8)) {
                out.write(kept.toString());
            } catch (IOException e) {
                Log.w(TAG, "Couldn't trim the battery history", e);
            }
        }
    }

    /** The time of the newest sample, 0 if there's none. */
    static synchronized long lastTime(Context context) {
        final List<String> lines = readLines(file(context));
        return lines.isEmpty() ? 0 : Math.max(0, parseTime(lines.get(lines.size() - 1)));
    }

    /** Samples newer than {@code since}, oldest first. */
    static synchronized List<Point> load(Context context, long since) {
        final List<Point> points = new ArrayList<>();
        for (String line : readLines(file(context))) {
            final String[] f = line.split(",");
            if (f.length < 5) {
                continue;
            }
            try {
                final long time = Long.parseLong(f[0]);
                if (time >= since) {
                    points.add(new Point(time, Integer.parseInt(f[1])));
                }
            } catch (NumberFormatException e) {
                // A torn line from a crash while writing, skip it.
            }
        }
        // The clock may have jumped before network time was in, keep the order sane.
        points.sort((a, b) -> Long.compare(a.time, b.time));
        return points;
    }

    private static File file(Context context) {
        return new File(context.getFilesDir(), FILE);
    }

    private static long parseTime(String line) {
        final int comma = line.indexOf(',');
        try {
            return Long.parseLong(comma < 0 ? line : line.substring(0, comma));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<String> readLines(File file) {
        final List<String> lines = new ArrayList<>();
        if (!file.exists()) {
            return lines;
        }
        try (BufferedReader in = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                if (!line.isEmpty()) {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            Log.w(TAG, "Couldn't read the battery history", e);
        }
        return lines;
    }
}
