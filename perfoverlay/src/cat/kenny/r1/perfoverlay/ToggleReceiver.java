package cat.kenny.r1.perfoverlay;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** cat.kenny.r1.perfoverlay.action.TOGGLE, for key shortcuts in system_server. */
public class ToggleReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        Hud.setEnabled(context, !Hud.isEnabled(context));
    }
}
