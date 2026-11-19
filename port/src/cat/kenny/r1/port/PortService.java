// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.app.Notification;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.hardware.usb.UsbManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;

/**
 * Runs while the r1 is plugged into a computer. Locking, unlocking and the screen going off are
 * only told to those listening at the time, and on them hangs what the port does: power only
 * once the r1 is locked, the lock screen's card saying so, the sheet once it is unlocked. Its
 * note is that card while locked, and what the port is to the computer otherwise.
 */
public final class PortService extends Service {
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    /** Unplugged for good, not the moment's absence a change of role brings. */
    private final Runnable mUnplugged = () -> {
        Port.set(this, Port.POWER);
        stopSelf();
    };
    private final Runnable mRefresh = this::refresh;
    /** Unlocked since the last refresh, for the role the user wants once unlocked. */
    private boolean mUnlocked;

    private final BroadcastReceiver mChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (UsbManager.ACTION_USB_STATE.equals(action)) {
                mHandler.removeCallbacks(mUnplugged);
                if (!intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)) {
                    mHandler.postDelayed(mUnplugged, Port.AWAY_MS);
                }
            } else if (Intent.ACTION_SCREEN_OFF.equals(action)
                    && Port.role(context) != Port.POWER) {
                Port.set(context, Port.POWER);
            } else if (Intent.ACTION_USER_PRESENT.equals(action)) {
                // Unlocked again: the role the user chose for then, if any
                mUnlocked = true;
            }
            // The role and the lock settle a moment after they are told of
            mHandler.removeCallbacks(mRefresh);
            mHandler.postDelayed(mRefresh, 400);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        final IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_STATE);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(mChanged, filter);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        refresh();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        mHandler.removeCallbacksAndMessages(null);
        unregisterReceiver(mChanged);
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void refresh() {
        final boolean locked = Port.locked(this);
        // A computer new to the sheet, unlocked: what the sheet comes up for
        final boolean fresh = !locked && !Port.shownComputer(this);
        if (!locked && (fresh || mUnlocked)) {
            mUnlocked = false;
            // The role chosen in the settings, never over one the user picked on the sheet
            final int unlock = Port.unlockRole(this);
            if (unlock != Port.POWER && Port.role(this) == Port.POWER && Port.computer(this)) {
                Port.set(this, unlock);
            }
        }
        final int role = Port.role(this);
        final Notification.Builder note;
        if (locked) {
            final String text = getString(Port.debugging(this) ? R.string.card_text_debug
                    : R.string.card_text);
            note = Port.note(this, Port.CHANNEL_LOCKED)
                    .setContentTitle(getString(R.string.card_title)).setContentText(text);
        } else {
            note = Port.note(this, Port.CHANNEL_ROLE)
                    .setContentTitle(getString(role == Port.POWER ? R.string.note_power
                            : R.string.role_on, getString(Port.roleName(role))))
                    .setContentText(getString(role == Port.POWER ? R.string.note_power_text
                            : R.string.role_on_text));
        }
        startForeground(Port.NOTE, note.setOngoing(true).build(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        if (fresh) {
            Port.setShown(this, "computer");
            // Left off in the settings: the note above still opens the sheet
            if (Port.on(this, Port.POPUP_COMPUTER, true)) {
                startActivity(new Intent(this, PortActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            }
        }
    }
}
