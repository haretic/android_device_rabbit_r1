// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.location.LocationManager;
import android.os.BatteryManager;
import android.os.BatterySaverPolicyConfig;
import android.os.PowerManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

/**
 * Extreme battery saver: everything turned down at once, and back as it was after. The
 * screen at 60 Hz, dimmer and dark, no animations, off sooner; the little cores only, at
 * their lowest clocks, and the slowest memory (the EXTREME_SAVER hint, held by Controller);
 * Android's battery saver with all of its restrictions (apps in standby, no background
 * network, no sync, location off, no hotword); Bluetooth off. Wi-Fi stays, the r1 needs it.
 * Android keeps battery saver off on a charger, so that part comes and goes with the plug;
 * the rest stays until the switch goes off, or battery saver is switched off on battery.
 */
final class ExtremeSaver {

    private static final String TAG = "R1Performance";
    static final String KEY = "extreme_saver";
    private static final String SAVED = "extreme_saved_";

    /** The screen's rate while on; the panel isn't known to run slower. */
    private static final String REFRESH_HZ = "60";
    private static final int SCREEN_OFF_MS = 15000;

    private ExtremeSaver() {
    }

    static boolean isOn(Context context) {
        return Profiles.prefs(context).getBoolean(KEY, false);
    }

    static void set(Context context, boolean on) {
        if (on == isOn(context)) {
            return;
        }
        if (on) {
            enable(context);
        } else {
            disable(context);
        }
        Controller.get(context).update();
    }

    private static void enable(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        final SharedPreferences.Editor saved = Profiles.prefs(context).edit();
        saved.putBoolean(KEY, true);
        // What gets restored after, as it was before
        saveSystem(resolver, saved, Settings.System.PEAK_REFRESH_RATE);
        saveSystem(resolver, saved, Settings.System.MIN_REFRESH_RATE);
        saveSystem(resolver, saved, Settings.System.SCREEN_OFF_TIMEOUT);
        saved.putBoolean(SAVED + "sync", ContentResolver.getMasterSyncAutomatically());
        final LocationManager location = context.getSystemService(LocationManager.class);
        saved.putBoolean(SAVED + "location", location.isLocationEnabled());
        final BluetoothAdapter bluetooth = bluetooth(context);
        saved.putBoolean(SAVED + "bluetooth", bluetooth != null && bluetooth.isEnabled());
        final PowerManager power = context.getSystemService(PowerManager.class);
        saved.putBoolean(SAVED + "saver", power.isPowerSaveMode());
        saved.commit();

        Settings.System.putString(resolver, Settings.System.PEAK_REFRESH_RATE, REFRESH_HZ);
        Settings.System.putString(resolver, Settings.System.MIN_REFRESH_RATE, REFRESH_HZ);
        final int timeout = Settings.System.getInt(resolver,
                Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_OFF_MS);
        if (timeout > SCREEN_OFF_MS) {
            Settings.System.putInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT, SCREEN_OFF_MS);
        }
        ContentResolver.setMasterSyncAutomatically(false);
        location.setLocationEnabledForUser(false, UserHandle.SYSTEM);
        if (bluetooth != null && bluetooth.isEnabled()) {
            bluetooth.disable();
        }
        startSaver(power);
        Log.i(TAG, "Extreme battery saver on");
    }

    private static void disable(Context context) {
        final ContentResolver resolver = context.getContentResolver();
        final SharedPreferences prefs = Profiles.prefs(context);
        restoreSystem(resolver, prefs, Settings.System.PEAK_REFRESH_RATE);
        restoreSystem(resolver, prefs, Settings.System.MIN_REFRESH_RATE);
        restoreSystem(resolver, prefs, Settings.System.SCREEN_OFF_TIMEOUT);
        ContentResolver.setMasterSyncAutomatically(prefs.getBoolean(SAVED + "sync", true));
        context.getSystemService(LocationManager.class).setLocationEnabledForUser(
                prefs.getBoolean(SAVED + "location", true), UserHandle.SYSTEM);
        final BluetoothAdapter bluetooth = bluetooth(context);
        if (bluetooth != null && prefs.getBoolean(SAVED + "bluetooth", false)) {
            bluetooth.enable();
        }
        final PowerManager power = context.getSystemService(PowerManager.class);
        // Off and on again if it was on before: that clears the extreme policy
        power.setPowerSaveModeEnabled(false);
        if (prefs.getBoolean(SAVED + "saver", false)) {
            power.setPowerSaveModeEnabled(true);
        }
        final SharedPreferences.Editor editor = prefs.edit().putBoolean(KEY, false);
        for (String key : prefs.getAll().keySet()) {
            if (key.startsWith(SAVED)) {
                editor.remove(key);
            }
        }
        editor.commit();
        Log.i(TAG, "Extreme battery saver off");
    }

    /** Battery saver with the extreme policy; on a charger it comes on once unplugged. */
    private static void startSaver(PowerManager power) {
        if (!power.isPowerSaveMode() && !power.setPowerSaveModeEnabled(true)) {
            Log.i(TAG, "Battery saver waits for the charger to go");
            return;
        }
        // Set once saver is on: leaving it drops the override, back to the usual policy
        if (!power.setFullPowerSavePolicy(policy())) {
            Log.w(TAG, "Battery saver didn't take the extreme policy");
        }
    }

    /** Battery saver's restrictions, all of them, except the vibration the wheel ticks with. */
    private static BatterySaverPolicyConfig policy() {
        return new BatterySaverPolicyConfig.Builder()
                .setAdjustBrightnessFactor(0.5f)
                .setEnableAdjustBrightness(true)
                .setAdvertiseIsEnabled(true)
                .setDeferFullBackup(true)
                .setDeferKeyValueBackup(true)
                .setDisableAnimation(true)
                .setDisableAod(true)
                .setDisableLaunchBoost(true)
                .setDisableOptionalSensors(true)
                .setSoundTriggerMode(PowerManager.SOUND_TRIGGER_MODE_ALL_DISABLED)
                .setDisableVibration(false)
                .setEnableDataSaver(true)
                .setEnableFirewall(true)
                .setEnableNightMode(true)
                .setEnableQuickDoze(true)
                .setForceAllAppsStandby(true)
                .setForceBackgroundCheck(true)
                .setLocationMode(PowerManager.LOCATION_MODE_ALL_DISABLED_WHEN_SCREEN_OFF)
                .build();
    }

    /**
     * After a boot: battery saver stays on by itself but forgets the extreme policy, which
     * goes back on; if saver is off, the rest follows.
     */
    static void restore(Context context) {
        if (isOn(context)) {
            startSaver(context.getSystemService(PowerManager.class));
        }
    }

    /** Off the charger: battery saver, which Android kept off while charging, comes on. */
    static void onUnplugged(Context context) {
        restore(context);
    }

    /**
     * Battery saver came on again (after unplugging, it's sticky): it gets the extreme policy
     * back. Switched off on battery (its tile): the rest of the extreme saver goes with it;
     * off because of the charger, it stays.
     */
    static void onPowerSaveModeChanged(Context context) {
        if (!isOn(context)) {
            return;
        }
        final PowerManager power = context.getSystemService(PowerManager.class);
        if (power.isPowerSaveMode()) {
            power.setFullPowerSavePolicy(policy());
        } else if (!isPlugged(context)) {
            set(context, false);
        }
    }

    private static boolean isPlugged(Context context) {
        final Intent battery = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        return battery != null && battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
    }

    private static BluetoothAdapter bluetooth(Context context) {
        final BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager == null ? null : manager.getAdapter();
    }

    private static void saveSystem(ContentResolver resolver, SharedPreferences.Editor saved,
            String key) {
        final String value = Settings.System.getString(resolver, key);
        if (value != null) {
            saved.putString(SAVED + key, value);
        }
    }

    /** Back as before; one that wasn't set before is unset again. */
    private static void restoreSystem(ContentResolver resolver, SharedPreferences prefs,
            String key) {
        final String value = prefs.getString(SAVED + key, null);
        if (value != null) {
            Settings.System.putString(resolver, key, value);
        } else {
            resolver.call(Settings.System.CONTENT_URI, Settings.CALL_METHOD_DELETE_SYSTEM, key,
                    null);
        }
    }
}
