// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.PowerManager;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * One reading of the battery. The battery itself comes from BatteryManager, as the health
 * HAL reads it from the MT6357 gauge: apps may not read the gauge's sysfs nodes
 * (sysfs_batteryinfo is the HAL's alone). The USB voltage comes from the MT6370 ADC's hwmon
 * view and the charger's state from the charging policy (ChargeCurrent).
 */
final class Reading {

    static final int UNKNOWN = Integer.MIN_VALUE;

    /** iio-hwmon on the "power-monitor" node in mt6765-rabbit-r1.dts, the MT6370 ADC */
    private static final String POWER_MONITOR = "/sys/devices/platform/power-monitor/hwmon";
    /** mt6370-adc's label for the VBUS channel; its hwmon value is VBUS in mV */
    private static final String VBUS_LABEL = "vbusdiv5";
    /** Below this the "voltage" is the ADC's noise with nothing plugged in */
    private static final int VBUS_MIN_MV = 2000;

    long time;
    int percent = UNKNOWN;
    int status = BatteryManager.BATTERY_STATUS_UNKNOWN;
    /** BatteryManager.BATTERY_PLUGGED_*, 0 on battery */
    int plugged;
    int millivolts = UNKNOWN;
    /** Positive going into the battery */
    int microamps = UNKNOWN;
    int tenthsCelsius = UNKNOWN;
    /** Until full while charging, -1 when there's no estimate */
    long chargeTimeMs = -1;
    /** Until empty on battery, null when there's no estimate */
    Duration dischargeTime;
    int vbusMillivolts = UNKNOWN;
    /** Null when the charging policy can't be read */
    ChargeCurrent.State policy;

    boolean isCharging() {
        return status == BatteryManager.BATTERY_STATUS_CHARGING;
    }

    boolean isFull() {
        return status == BatteryManager.BATTERY_STATUS_FULL;
    }

    boolean isPlugged() {
        return plugged != 0;
    }

    /** The level and the charge state, all the graph needs. Cheap, any thread. */
    static Reading battery(Context context) {
        final Reading r = new Reading();
        r.time = System.currentTimeMillis();
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (battery != null) {
            final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0 && scale > 0) {
                r.percent = Math.round(level * 100f / scale);
            }
            r.status = battery.getIntExtra(BatteryManager.EXTRA_STATUS, r.status);
            r.plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            final int mv = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) {
                r.millivolts = mv;
            }
            final int temp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, UNKNOWN);
            if (temp != UNKNOWN) {
                r.tenthsCelsius = temp;
            }
        }
        final BatteryManager bm = context.getSystemService(BatteryManager.class);
        // Integer.MIN_VALUE when the HAL has none, the same as UNKNOWN
        r.microamps = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        return r;
    }

    /**
     * Everything the page shows. Reading the charging policy can wait for its pass, up to
     * 1.5 s, so not on the main thread.
     */
    static Reading all(Context context) {
        final Reading r = battery(context);
        final BatteryManager bm = context.getSystemService(BatteryManager.class);
        if (r.isCharging()) {
            r.chargeTimeMs = bm.computeChargeTimeRemaining();
        } else if (!r.isPlugged()) {
            r.dischargeTime = context.getSystemService(PowerManager.class)
                    .getBatteryDischargePrediction();
        }
        if (r.isPlugged()) {
            r.vbusMillivolts = readVbus();
        }
        r.policy = ChargeCurrent.state();
        return r;
    }

    private static int readVbus() {
        final File[] hwmons = new File(POWER_MONITOR).listFiles();
        if (hwmons == null) {
            return UNKNOWN;
        }
        for (File hwmon : hwmons) {
            // in1 in the device tree's order, but find it by its label anyway
            for (int i = 0; i <= 8; i++) {
                if (VBUS_LABEL.equals(read(hwmon + "/in" + i + "_label"))) {
                    final int mv = readInt(hwmon + "/in" + i + "_input");
                    return mv >= VBUS_MIN_MV ? mv : UNKNOWN;
                }
            }
        }
        return UNKNOWN;
    }

    static int readInt(String path) {
        final String text = read(path);
        if (text == null) {
            return UNKNOWN;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return UNKNOWN;
        }
    }

    static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8)
                    .trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
