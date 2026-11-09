// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.os.SystemProperties;
import android.util.Log;

import java.io.File;
import java.text.NumberFormat;
import java.util.HashMap;
import java.util.Map;

/**
 * How fast the battery charges. The kernel's charging policy (rabbit-r1-charging.c) owns
 * the MT6370 charger and programs it every 2 seconds, so the charger's own
 * constant_charge_current can't be set from here: the policy would write over it. The
 * policy takes a cap in charge_control_limit, 500 mA to 3 A in 100 mA steps. Above the stock
 * pack's 1 A the choices are behind a confirmation, and above 2 A also behind Show unsafe
 * values: they are for larger cells fitted by hand, with a heat sink on the PMIC. The policy
 * drops all of them to 1 A when warm: from 38 C, the unsafe ones from 42 C. The choice is
 * kept in persist.sys.r1.charge_limit, the same property the Charging speed page uses, and
 * the r1-charging daemon (charging/) writes it to the policy at boot and keeps it there.
 */
final class ChargeCurrent {

    private static final String TAG = "R1BatteryDetails";

    private static final String PROPERTY = "persist.sys.r1.charge_limit";
    /** The stock pack's 1 C (R1_CHARGE_STD_UA), the default when nothing is chosen */
    static final String AUTO = "auto";
    /** The values r1-charging takes (charging/native/choice.h), fastest first */
    static final String[] UNSAFE = {"3000", "2500"};
    static final String[] FAST = {"2000", "1500", "1200"};
    static final String[] STOCK = {AUTO, "900", "800", "700", "600", "500"};
    /** The stock pack's rating, the most without a confirmation */
    static final int STD_MA = 1000;
    /** The most for the stock pack at all; above it only with Show unsafe values */
    static final int FAST_MAX_MA = 2000;
    static final int MIN_MA = 500;
    /** Whether the unsafe choices are listed, kept in the app's own preferences */
    static final String KEY_SHOW_UNSAFE = "show_unsafe_current";
    /** About what the 4.4 V pack sits at while charging, for the watts shown */
    private static final float BATTERY_VOLTS = 3.9f;

    private static final String STATUS = "/sys/devices/platform/charging-policy/status";

    /** The policy's status file: what it asked for, what it applied and why. */
    static final class State {
        final int requestedUa;
        final int appliedUa;
        /** What the charger may draw from USB, from the port's type (BC1.2) */
        final int inputUa;
        /** ready, usb-budget, thermal-limit, level-limit, voltage, ... */
        final String reason;

        private State(Map<String, String> fields) {
            requestedUa = parse(fields.get("requested_ua"));
            appliedUa = parse(fields.get("applied_ua"));
            inputUa = parse(fields.get("input_ua"));
            reason = fields.getOrDefault("reason", "");
        }

        private static int parse(String value) {
            try {
                return value == null ? Reading.UNKNOWN : Integer.parseInt(value);
            } catch (NumberFormatException e) {
                return Reading.UNKNOWN;
            }
        }
    }

    private ChargeCurrent() {
    }

    static boolean isSupported() {
        return new File(STATUS).exists();
    }

    /** The choices to list, fastest first: the unsafe ones only when asked for. */
    static String[] choices(boolean showUnsafe) {
        final java.util.List<String> list = new java.util.ArrayList<>();
        if (showUnsafe) {
            list.addAll(java.util.Arrays.asList(UNSAFE));
        }
        list.addAll(java.util.Arrays.asList(FAST));
        list.addAll(java.util.Arrays.asList(STOCK));
        return list.toArray(new String[0]);
    }

    /** Faster than the stock pack is rated for */
    static boolean isFast(String choice) {
        return milliamps(choice) > STD_MA;
    }

    /** Unsafe for the stock pack whatever its temperature */
    static boolean isUnsafe(String choice) {
        return milliamps(choice) > FAST_MAX_MA;
    }

    /** The stored choice, one of choices(true). */
    static String chosen() {
        final String value = SystemProperties.get(PROPERTY, AUTO);
        if (AUTO.equals(value) || Integer.toString(STD_MA).equals(value)) {
            return AUTO;
        }
        for (String choice : choices(true)) {
            if (choice.equals(value)) {
                return choice;
            }
        }
        // What r1-charging does with anything else
        return Integer.toString(MIN_MA);
    }

    static boolean choose(String choice) {
        try {
            SystemProperties.set(PROPERTY, choice);
            return true;
        } catch (RuntimeException e) {
            Log.w(TAG, "Cannot store the charge current", e);
            return false;
        }
    }

    static int milliamps(String choice) {
        return AUTO.equals(choice) ? STD_MA : Integer.parseInt(choice);
    }

    /** e.g. "0.8" */
    static String amps(int milliamps) {
        return format(milliamps / 1000f, 1);
    }

    /** About how many watts go into the battery at this current, e.g. "3.1" */
    static String batteryWatts(int milliamps) {
        return format(milliamps * BATTERY_VOLTS / 1000f, 1);
    }

    /** The policy's state, null when it can't be read. Can wait up to 1.5 s for its pass. */
    static State state() {
        final String text = Reading.read(STATUS);
        if (text == null) {
            return null;
        }
        final Map<String, String> fields = new HashMap<>();
        for (String line : text.split("\n")) {
            final int equals = line.indexOf('=');
            if (equals > 0) {
                fields.put(line.substring(0, equals), line.substring(equals + 1));
            }
        }
        return new State(fields);
    }

    private static String format(float value, int digits) {
        final NumberFormat format = NumberFormat.getInstance();
        format.setMaximumFractionDigits(digits);
        return format.format(value);
    }
}
