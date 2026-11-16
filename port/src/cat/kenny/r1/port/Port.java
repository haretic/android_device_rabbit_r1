// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.net.TetheringManager;
import android.os.BatteryManager;
import android.provider.Settings;

/** What the port is to a computer, and the two notes the r1 leaves about it. */
final class Port {
    /** The lock screen's card, and the quiet note of what the port is. */
    static final String CHANNEL_LOCKED = "locked", CHANNEL_ROLE = "role";
    static final int NOTE = 1;
    /** The roles the sheet's radio rows stand for. */
    static final int POWER = 0, FILES = 1, INTERNET = 2, WEBCAM = 3;
    private static final String PREFS = "port";

    private Port() {
    }

    /** Whether a computer has the r1 on its bus now. */
    static boolean computer(Context context) {
        final Intent state = context.registerReceiver(null,
                new IntentFilter(UsbManager.ACTION_USB_STATE));
        return state != null && state.getBooleanExtra(UsbManager.USB_CONNECTED, false);
    }

    static boolean locked(Context context) {
        return context.getSystemService(KeyguardManager.class).isKeyguardLocked();
    }

    /** USB debugging is the user's own switch: the port leaves it as it is. */
    static boolean debugging(Context context) {
        return Settings.Global.getInt(context.getContentResolver(),
                Settings.Global.ADB_ENABLED, 0) != 0;
    }

    static int role(Context context) {
        final long functions = context.getSystemService(UsbManager.class).getCurrentFunctions();
        if ((functions & UsbManager.FUNCTION_MTP) != 0) {
            return FILES;
        } else if ((functions & (UsbManager.FUNCTION_RNDIS | UsbManager.FUNCTION_NCM)) != 0) {
            return INTERNET;
        } else if ((functions & UsbManager.FUNCTION_UVC) != 0) {
            return WEBCAM;
        }
        return POWER;
    }

    /** Makes the port {@code role}; the service takes it back once the r1 is locked. */
    static void become(Context context, int role) {
        set(context, role);
        // Its note says what the port is now
        context.startForegroundService(new Intent(context, PortService.class));
    }

    /** The port's role alone, for the service itself. */
    static void set(Context context, int role) {
        final UsbManager usb = context.getSystemService(UsbManager.class);
        final TetheringManager tethering = context.getSystemService(TetheringManager.class);
        if (role != INTERNET && role(context) == INTERNET) {
            tethering.stopTethering(TetheringManager.TETHERING_USB);
        }
        if (role == INTERNET) {
            tethering.startTethering(new TetheringManager.TetheringRequest.Builder(
                    TetheringManager.TETHERING_USB).build(), context.getMainExecutor(),
                    new TetheringManager.StartTetheringCallback() { });
        } else {
            usb.setCurrentFunctions(role == FILES ? UsbManager.FUNCTION_MTP
                    : role == WEBCAM ? UsbManager.FUNCTION_UVC : UsbManager.FUNCTION_NONE);
        }
    }

    static int roleName(int role) {
        return role == FILES ? R.string.files : role == INTERNET ? R.string.internet
                : role == WEBCAM ? R.string.webcam : R.string.power_only;
    }

    /** "Charging at 0.9 A", or what there is to say without a current worth naming. */
    static String power(Context context) {
        final BatteryManager battery = context.getSystemService(BatteryManager.class);
        final Intent state = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (state == null || state.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) == 0) {
            return context.getString(R.string.not_charging);
        }
        if (state.getIntExtra(BatteryManager.EXTRA_STATUS, 0)
                != BatteryManager.BATTERY_STATUS_CHARGING) {
            // Full, or held below full by the charging limit
            return context.getString(R.string.on_power);
        }
        final double amps = Math.abs(battery.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) / 1e6;
        return amps < 0.05 || amps > 6 ? context.getString(R.string.charging)
                : context.getString(R.string.charging_at,
                        String.format(java.util.Locale.getDefault(), "%.1f", amps));
    }

    private static NotificationManager notes(Context context) {
        final NotificationManager notes = context.getSystemService(NotificationManager.class);
        // The lock screen leaves out what is of low importance, so the card is not: it is
        // without sound all the same
        final NotificationChannel locked = new NotificationChannel(CHANNEL_LOCKED,
                context.getString(R.string.channel_locked),
                NotificationManager.IMPORTANCE_DEFAULT);
        locked.setSound(null, null);
        locked.enableVibration(false);
        notes.createNotificationChannel(locked);
        notes.createNotificationChannel(new NotificationChannel(CHANNEL_ROLE,
                context.getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        return notes;
    }

    static Notification.Builder note(Context context, String channel) {
        notes(context);
        return new Notification.Builder(context, channel).setSmallIcon(R.drawable.ic_usb)
                .setVisibility(Notification.VISIBILITY_PUBLIC).setOnlyAlertOnce(true)
                .setContentIntent(PendingIntent.getActivity(context, 0,
                        new Intent(context, PortActivity.class), PendingIntent.FLAG_IMMUTABLE));
    }

    /** Whether the sheet has come up for what is plugged in now: once is enough. */
    static boolean shown(Context context, String what) {
        return what.equals(context.getSharedPreferences(PREFS, 0).getString("shown", ""));
    }

    static void setShown(Context context, String what) {
        context.getSharedPreferences(PREFS, 0).edit().putString("shown", what)
                .remove("gone").apply();
    }

    /** How long the computer may be away and still be the one the sheet came up for. */
    static final long AWAY_MS = 5000;

    static void setGone(Context context) {
        if (shown(context, "computer")) {
            context.getSharedPreferences(PREFS, 0).edit()
                    .putLong("gone", System.currentTimeMillis()).apply();
        }
    }

    /** Whether the sheet came up for this computer: one back within moments is the same. */
    static boolean shownComputer(Context context) {
        if (!shown(context, "computer")) {
            return false;
        }
        final long gone = context.getSharedPreferences(PREFS, 0).getLong("gone", 0);
        final long away = System.currentTimeMillis() - gone;
        return gone == 0 || (away >= 0 && away < AWAY_MS);
    }
}
