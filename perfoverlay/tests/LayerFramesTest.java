package cat.kenny.r1.perfoverlay;

import java.util.Arrays;
import java.util.List;

/** Host test of the app frame rate's dump parsing (plain Java, no android.*). */
public final class LayerFramesTest {

    private static int sFailed;
    private static int sRun;

    private static void check(String name, Object want, Object got) {
        sRun++;
        if (!want.equals(got)) {
            sFailed++;
            System.out.println("FAIL " + name + ": want " + want + ", got " + got);
        }
    }

    /** "dumpsys SurfaceFlinger --list" on Android 11 with a game in front. */
    private static final String LIST = String.join("\n",
            "Display 0 name=\"Built-in Screen\"#0",
            "WindowedMagnification:0:31#0",
            "ActivityRecord{6a1c2f u0 com.example.game/.MainActivity t12}#0",
            "com.example.game/com.example.game.MainActivity#0",
            "Background for -SurfaceView - com.example.game/com.example.game.MainActivity#0",
            "SurfaceView - com.example.game/com.example.game.MainActivity#0",
            "com.example.gamepad/com.example.gamepad.Main#0",
            "cat.kenny.r1.perfoverlay#0",
            "StatusBar#0",
            "");

    /** A "--latency" dump: the period, then desired, actual and ready times per frame. */
    private static String latency(long... presents) {
        final StringBuilder out = new StringBuilder("16666666\n");
        // Empty slots first, like FrameTracker's ring buffer before it fills up
        out.append("0\t0\t0\n0\t0\t0\n");
        for (long p : presents) {
            out.append(p - 5_000_000).append('\t').append(p).append('\t')
                    .append(p - 2_000_000).append('\n');
        }
        // The newest frame, latched but not on screen yet
        out.append("9223372036854775807\t9223372036854775807\t9223372036854775807\n");
        return out.append('\n').toString();
    }

    /** n frames at a steady rate, the first at start. */
    private static long[] steady(long start, int perSecond, int n) {
        final long[] t = new long[n];
        for (int i = 0; i < n; i++) {
            t[i] = start + i * 1_000_000_000L / perSecond;
        }
        return t;
    }

    public static void main(String[] args) {
        final List<String> layers = LayerFrames.appLayers(LIST, "com.example.game");
        check("layers: SurfaceView first, then the window, nothing else", Arrays.asList(
                "SurfaceView - com.example.game/com.example.game.MainActivity#0",
                "com.example.game/com.example.game.MainActivity#0"), layers);
        check("layers: a package that is a prefix of another doesn't take its layers",
                Arrays.asList("com.example.gamepad/com.example.gamepad.Main#0"),
                LayerFrames.appLayers(LIST, "com.example.gamepad"));
        check("layers: none for an app without windows", 0,
                LayerFrames.appLayers(LIST, "com.example.other").size());

        final long s = 1_000_000_000L;
        // 45 frames a second for a bit over two seconds
        final long[] at45 = steady(10 * s, 45, 100);
        final String dump = latency(at45);
        check("presents: pending and empty slots skipped", 100,
                LayerFrames.presentTimes(dump).length);
        check("records: a dump with frames has records", true, LayerFrames.hasRecords(dump));
        check("records: a layer that is gone has none", false,
                LayerFrames.hasRecords("16666666\n\n"));
        check("records: permission denial has none", false, LayerFrames.hasRecords(
                "Permission Denial: can't dump SurfaceFlinger from pid=1, uid=2\n"));
        final long[] p = LayerFrames.presentTimes(dump);
        check("window: 45 in the second second", 45, LayerFrames.countBetween(p, 11 * s, 12 * s));
        check("window: nothing after the last frame", 0,
                LayerFrames.countBetween(p, 13 * s, 14 * s));
        // Sampling once a second: every frame shown since the newest one counted
        final long newest = LayerFrames.newest(LayerFrames.presentTimes(
                latency(steady(10 * s, 45, 45))), 0);
        check("newest: last frame of the first second", 10 * s + 44 * s / 45, newest);
        final long[] twoSeconds = LayerFrames.presentTimes(latency(steady(10 * s, 45, 90)));
        check("fps: 45 in the next second", 45,
                LayerFrames.perSecond(LayerFrames.countAfter(twoSeconds, newest), s));
        check("fps: no time passed", -1, LayerFrames.perSecond(10, 0));
        check("newest: empty keeps the old one", 7L, LayerFrames.newest(new long[0], 7));
        // A frame shown just before a dump, whose time only shows up in the next dump, is
        // still counted once: 45 frames shown, the dump at 11 s only knew 44 of them
        final long[] firstDump = Arrays.copyOf(steady(10 * s, 45, 45), 44);
        final long seen = LayerFrames.newest(firstDump, 0);
        final long[] secondDump = steady(10 * s, 45, 90);
        check("fps: a late frame time is not lost", 46,
                LayerFrames.perSecond(LayerFrames.countAfter(secondDump, seen), s));
        // 60 a second fills the 127 records in about 2 s, a 1 s sample still counts right
        final long[] at60 = LayerFrames.presentTimes(latency(steady(20 * s, 60, 127)));
        check("fps: 60 with a full record buffer", 60, LayerFrames.perSecond(
                LayerFrames.countAfter(at60, 20 * s + 66 * s / 60), s));
        check("count: boundaries are (from, to]", 1,
                LayerFrames.countBetween(new long[] {5, 10}, 5, 10));
        check("presents: junk lines skipped", 1, LayerFrames.presentTimes(
                "16666666\nnot a frame line\n1\t2\t3\nx\ty\tz\n").length);

        System.out.println(sRun + " checks, " + sFailed + " failed");
        if (sFailed > 0) {
            System.exit(1);
        }
    }
}
