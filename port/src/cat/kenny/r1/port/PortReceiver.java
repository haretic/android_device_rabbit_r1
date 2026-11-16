// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbManager;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;

import java.io.File;

/**
 * Something was plugged in or pulled out. A computer starts the service that keeps watch of
 * it; for a stick the sheet comes up, the r1 unlocked.
 */
public final class PortReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        if (UsbManager.ACTION_USB_STATE.equals(action)) {
            if (intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)) {
                // The service does the rest: the card while locked, the sheet once unlocked
                context.startForegroundService(new Intent(context, PortService.class));
            } else {
                // Unplugged, or the port changing what it is, which looks the same for a
                // moment: the time tells them apart when the computer is next there
                Port.setGone(context);
            }
        } else if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
            final VolumeInfo stick = stick(context, intent);
            if (stick != null && !Port.locked(context) && !Port.shown(context, stick.getId())) {
                Port.setShown(context, stick.getId());
                context.startActivity(new Intent(context, PortActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(PortActivity.EXTRA_VOLUME, stick.getId()));
            }
        } else if (intent.getData() != null) {
            // Unmounted, ejected or pulled out
            context.sendBroadcast(new Intent(PortActivity.ACTION_GONE)
                    .setPackage(context.getPackageName())
                    .putExtra("what", intent.getData().getPath()));
        }
    }

    /** The volume that was mounted, if it is one that was plugged in. */
    private static VolumeInfo stick(Context context, Intent intent) {
        if (intent.getData() == null || intent.getData().getPath() == null) {
            return null;
        }
        final File path = new File(intent.getData().getPath());
        for (VolumeInfo volume : context.getSystemService(StorageManager.class).getVolumes()) {
            if (volume.getType() == VolumeInfo.TYPE_PUBLIC && volume.getDisk() != null
                    && volume.getPath() != null && volume.getPath().equals(path)) {
                return volume;
            }
        }
        return null;
    }
}
