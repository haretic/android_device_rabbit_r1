// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Notes the level right after every boot and makes sure the sampler is pending. The
 * history is in device protected storage, so this needn't wait for the first unlock.
 */
public class BootReceiver extends BroadcastReceiver {

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED.equals(intent.getAction())) {
            SampleJob.schedule(context);
            History.record(context, Reading.battery(context));
        }
    }
}
