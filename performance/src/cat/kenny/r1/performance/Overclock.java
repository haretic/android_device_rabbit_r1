// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.os.SystemClock;
import android.os.SystemProperties;
import android.text.TextUtils;

/**
 * The CPU overclock (Kernel Tweaks > CPU overclock): a top speed per cluster above stock, at
 * the stock top voltage, offered only as far as this r1 passed the stress test. Stored with
 * the other tweaks in persist.sys.r1.kt.*; r1-ktweaks.sh turns boost on for the cluster and
 * runs the test, the Controller has the power HAL hold the speed (OC_* hints).
 */
final class Overclock {

    static final String BIG = "big";
    static final String LITTLE = "little";

    /** The steps in MHz, stock first. */
    static final String[] BIG_STEPS = {"2200", "2300", "2400"};
    static final String[] LITTLE_STEPS = {"1600", "1700", "1800"};

    static final String ON = "oc";

    private Overclock() {
    }

    static String[] steps(String cluster) {
        return BIG.equals(cluster) ? BIG_STEPS : LITTLE_STEPS;
    }

    static boolean isOn() {
        return "1".equals(KernelTweaks.get(ON));
    }

    /** The picked step in MHz, null for stock. */
    static String picked(String cluster) {
        final String mhz = KernelTweaks.get("oc_" + cluster);
        return TextUtils.isEmpty(mhz) ? null : mhz;
    }

    static void pick(String cluster, String mhz) {
        KernelTweaks.set("oc_" + cluster, steps(cluster)[0].equals(mhz) ? "" : mhz);
    }

    /** The fastest step the stress test passed, the stock speed if none. */
    static String passed(String cluster) {
        final String mhz = KernelTweaks.get("oc_ok_" + cluster);
        return TextUtils.isEmpty(mhz) ? steps(cluster)[0] : mhz;
    }

    /** The step in use: on, the tweaks active, picked and passed. Null for stock. */
    static String step(String cluster) {
        if (!KernelTweaks.isActive() || !isOn()) {
            return null;
        }
        final String mhz = picked(cluster);
        if (mhz == null) {
            return null;
        }
        try {
            return Integer.parseInt(mhz) <= Integer.parseInt(passed(cluster)) ? mhz : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static void startTest() {
        SystemProperties.set("sys.r1.kt.octest", Long.toString(SystemClock.elapsedRealtime()));
    }

    /** running ..., done, stopped at ...: why; empty before the first test of this start. */
    static String testState() {
        return SystemProperties.get("sys.r1.kt.octest_state", "");
    }
}
