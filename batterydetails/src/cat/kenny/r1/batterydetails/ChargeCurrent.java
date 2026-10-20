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
 * policy takes a cap in charge_control_limit, 500 mA to 1 A in 100 mA steps. The choice is
 * kept in persist.sys.r1.charge_limit, the same property the Charging speed page uses, and
 * the r1-charging daemon (charging/) writes it to the policy at boot and keeps it there.
 */
final class ChargeCurrent {

    private static final String TAG = "R1BatteryDetails";

    private static final String PROPERTY = "persist.sys.r1.charge_limit";
    /** The board's most (R1_CHARGE_MAX_UA), the default when nothing is chosen */
    static final String AUTO = "auto";
    /** The values r1-charging takes (charging/native/choice.h), fastest first */
    static final String[] CHOICES = {AUTO, "900", "800", "700", "600", "500"};
    static final int MAX_MA = 1000;
    static final int MIN_MA = 500;
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

    /** The stored choice, one of CHOICES. */
    static String chosen() {
        final String value = SystemProperties.get(PROPERTY, AUTO);
        if (AUTO.equals(value) || Integer.toString(MAX_MA).equals(value)) {
            return AUTO;
        }
        for (String choice : CHOICES) {
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
        return AUTO.equals(choice) ? MAX_MA : Integer.parseInt(choice);
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
