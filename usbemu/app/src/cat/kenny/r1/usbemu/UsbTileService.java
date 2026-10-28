// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.os.Looper;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** UsbMode tile behavior: off opens the hub; active stops; long click opens Settings. */
public final class UsbTileService extends TileService {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable poll = new Runnable() {
        @Override public void run() { update(); handler.postDelayed(this, 500); }
    };
    @Override public void onStartListening() { handler.post(poll); }
    @Override public void onStopListening() { handler.removeCallbacks(poll); }
    @Override public void onClick() {
        if (android.os.UserHandle.myUserId() != 0) return;
        if (Control.running()) {
            Control.request(this, "stop", null, true); update();
        } else {
            startActivityAndCollapse(PendingIntent.getActivity(this, 0,
                    new Intent(this, UsbActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
        }
    }
    private void update() {
        Tile tile = getQsTile(); if (tile == null) return;
        if (android.os.UserHandle.myUserId() != 0) {
            tile.setState(Tile.STATE_UNAVAILABLE); tile.updateTile(); return;
        }
        boolean running = Control.running();
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_usb));
        tile.setLabel(getString(R.string.app_name));
        tile.setSubtitle(running ? getString(R.string.tile_running, Images.session()[1]) : getString(R.string.off));
        tile.setContentDescription(getString(running ? R.string.tile_stop : R.string.tile_choose));
        tile.setState(running ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE); tile.updateTile();
    }
}
