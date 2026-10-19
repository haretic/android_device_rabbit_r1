package cat.kenny.r1.perfoverlay;

import java.util.ArrayList;
import java.util.List;

/**
 * Reads SurfaceFlinger's text dumps for the app frame rate (plain Java, so the host test
 * can run it). "--list" names every layer, one per line. "--latency <layer>" gives the
 * refresh period on the first line, then one line per frame the layer sent, the last 127:
 * when it wanted the frame on screen, when it really got there and when it was drawn, in
 * nanoseconds of the monotonic clock. A frame not on screen yet has INT64_MAX, an empty
 * slot 0.
 */
final class LayerFrames {

    private static final String SURFACE_VIEW = "SurfaceView - ";

    private LayerFrames() {
    }

    /**
     * The layers an app draws into, from "--list": its windows ("pkg/pkg.Activity#0") and
     * the SurfaceViews in them ("SurfaceView - pkg/pkg.Activity#0"), where games made with
     * GLSurfaceView, SDL and the like draw. SurfaceViews first.
     */
    static List<String> appLayers(String list, String pkg) {
        final List<String> views = new ArrayList<>();
        final List<String> windows = new ArrayList<>();
        final String marker = pkg + "/";
        for (String line : list.split("\n")) {
            final String name = line.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.startsWith(SURFACE_VIEW + marker)) {
                views.add(name);
            } else if (name.startsWith(marker)) {
                windows.add(name);
            }
        }
        views.addAll(windows);
        return views;
    }

    /** When each frame got on the screen, from "--latency"; empty if the layer is gone. */
    static long[] presentTimes(String latency) {
        final String[] lines = latency.split("\n");
        final long[] times = new long[lines.length];
        int n = 0;
        // The first line is the refresh period
        for (int i = 1; i < lines.length; i++) {
            final String[] columns = lines[i].trim().split("\\s+");
            if (columns.length < 3) {
                continue;
            }
            final long present;
            try {
                present = Long.parseLong(columns[1]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (present > 0 && present != Long.MAX_VALUE) {
                times[n++] = present;
            }
        }
        final long[] result = new long[n];
        System.arraycopy(times, 0, result, 0, n);
        return result;
    }

    /** Frames drawn but not on the screen yet (present time INT64_MAX), for the log. */
    static int pending(String latency) {
        int n = 0;
        for (String line : latency.split("\n")) {
            final String[] columns = line.trim().split("\\s+");
            if (columns.length >= 3 && String.valueOf(Long.MAX_VALUE).equals(columns[1])) {
                n++;
            }
        }
        return n;
    }

    /** Whether "--latency" had frame lines at all; it has none for a layer that is gone. */
    static boolean hasRecords(String latency) {
        final String[] lines = latency.split("\n");
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].trim().split("\\s+").length >= 3) {
                return true;
            }
        }
        return false;
    }

    /** Frames that got on the screen after {@code from} and up to {@code to}. */
    static int countBetween(long[] presents, long from, long to) {
        int n = 0;
        for (long t : presents) {
            if (t > from && t <= to) {
                n++;
            }
        }
        return n;
    }

    /**
     * Frames that got on the screen after {@code after}. A frame's time is only known once
     * the screen showed it, so a frame shown just before the last dump may only turn up in
     * this one, with a time before that dump: counting by time window would lose it.
     */
    static int countAfter(long[] presents, long after) {
        int n = 0;
        for (long t : presents) {
            if (t > after) {
                n++;
            }
        }
        return n;
    }

    /** The newest time in the list, {@code otherwise} if it is empty. */
    static long newest(long[] presents, long otherwise) {
        long newest = otherwise;
        for (long t : presents) {
            newest = Math.max(newest, t);
        }
        return newest;
    }

    /** {@code frames} in {@code nanos}, as frames per second, rounded; -1 without time. */
    static int perSecond(int frames, long nanos) {
        return nanos <= 0 ? -1 : Math.round(frames * 1e9f / nanos);
    }
}
