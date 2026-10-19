package cat.kenny.r1.perfoverlay;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * Switches the boot receiver off while nothing needs it at boot. Otherwise Android starts
 * this app at every boot (about 5 MB and some CPU while the home screen loads) only for it
 * to find nothing to do. The manifest leaves the receiver on, so a new install runs it once.
 */
final class BootStart {

    private BootStart() {
    }

    static void set(Context context, boolean needed) {
        final PackageManager pm = context.getPackageManager();
        final ComponentName receiver = new ComponentName(context, BootReceiver.class);
        final int state = needed ? PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        if (pm.getComponentEnabledSetting(receiver) != state) {
            pm.setComponentEnabledSetting(receiver, state, PackageManager.DONT_KILL_APP);
        }
    }
}
