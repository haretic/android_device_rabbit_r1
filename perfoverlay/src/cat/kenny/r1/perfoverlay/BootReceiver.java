package cat.kenny.r1.perfoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Puts the overlay back after a restart if it was on. Switched off while the overlay is off
 * (see BootStart), so the app doesn't start at every boot for nothing.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            if (Hud.isEnabled(context)) {
                Hud.apply(context, true);
            } else {
                BootStart.set(context, false);
            }
        }
    }
}
