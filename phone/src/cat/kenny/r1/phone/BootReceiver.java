// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The link, if it was on, is on again after a restart. */
public final class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (PhoneService.wanted(context)) {
            context.startForegroundService(new Intent(context, PhoneService.class));
        }
    }
}
