// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Kernel Tweaks choices, kept in persist.sys.r1.kt.* where ktweaks/r1-ktweaks.sh picks
 * them up as root. Empty means the default. "Big cores off while the screen is off" is the
 * exception: the power HAL does it, held by the Controller.
 */
final class KernelTweaks {

    private static final String PREFIX = "persist.sys.r1.kt.";

    static final String ENABLED = "enabled";
    /** Set by the script after two starts in a row failed. */
    static final String PAUSED = "paused";
    static final String MEM_SLEEP = "mem_sleep";
    static final String PARK = "park_screen_off";
    static final String CORES = "cores";
    static final String TRIP = "trip";
    static final String ZRAM = "zram";
    static final String GOVERNOR = "governor";
    static final String GPU_MAX = "gpu_max";
    static final String SWAPPINESS = "swappiness";
    static final String IO_SCHED = "io_sched";
    static final String READ_AHEAD = "read_ahead";
    static final String SPEAKER = "speaker";
    static final String MIC = "mic";
    static final String MOTOR_STEP = "motor_step";
    /** Not a Kernel Tweak: the camera HAL reads it, whether the tweaks are on or not. */
    static final String CAMERA_PARK = "persist.sys.r1.camera.park";
    /** The tweaks, as counted by changedCount(). */
    static final String[] TWEAKS = {MEM_SLEEP, PARK, CORES, Overclock.ON, TRIP, GOVERNOR, GPU_MAX,
            ZRAM, SWAPPINESS, IO_SCHED, READ_AHEAD, SPEAKER, MIC, MOTOR_STEP};

    private KernelTweaks() {
    }

    static String get(String key) {
        return SystemProperties.get(PREFIX + key, "");
    }

    static void set(String key, String value) {
        SystemProperties.set(PREFIX + key, value == null ? "" : value);
    }

    static boolean isEnabled() {
        return "1".equals(get(ENABLED));
    }

    static boolean isPaused() {
        return "1".equals(get(PAUSED));
    }

    /** Switched on and not paused: the choices are in use. */
    static boolean isActive() {
        return isEnabled() && !isPaused();
    }

    static void setEnabled(boolean enabled) {
        if (enabled) {
            // Switching on again is the way out of a pause
            set(PAUSED, "");
            set("tries", "");
        }
        set(ENABLED, enabled ? "1" : "");
    }

    static int changedCount() {
        int count = 0;
        for (String key : TWEAKS) {
            if (!TextUtils.isEmpty(get(key))) {
                count++;
            }
        }
        return count;
    }

    static void reset() {
        for (String key : TWEAKS) {
            set(key, "");
        }
        // The steps go back to stock too; what the stress test passed stays known
        set("oc_big", "");
        set("oc_little", "");
    }

    /** Has the script apply the choices now. */
    static void apply() {
        SystemProperties.set("sys.r1.kt.apply", Long.toString(SystemClock.elapsedRealtime()));
    }

    /** "ok", or the tweaks the script couldn't set, comma separated. */
    static String result() {
        return SystemProperties.get("sys.r1.kt.result", "");
    }

    /** What the device starts with for {@code key}, as the script noted it. */
    static String startValue(String key) {
        return SystemProperties.get("sys.r1.kt.default_" + key, "");
    }

    /** The GPU's speeds in MHz, slowest first, as the script found them. */
    static int[] gpuSteps() {
        final String[] hz = SystemProperties.get("sys.r1.kt.gpu_freqs", "").trim().split("\\s+");
        final List<Integer> mhz = new ArrayList<>();
        for (String step : hz) {
            try {
                mhz.add((int) (Long.parseLong(step) / 1000000));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(mhz);
        return mhz.stream().mapToInt(Integer::intValue).toArray();
    }

    /** The eMMC's I/O schedulers, as the script found them. */
    static String[] ioSchedulers() {
        final String list = SystemProperties.get("sys.r1.kt.io_schedulers", "").trim();
        return list.isEmpty() ? new String[0] : list.split("\\s+");
    }

    /** The compressed swap size the device starts with, in MiB. */
    static String defaultZram() {
        return SystemProperties.get("sys.r1.kt.default_zram", "");
    }
}
