// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings: the extreme battery saver, on and off. */
public class ExtremeSaverTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        ExtremeSaver.set(this, !ExtremeSaver.isOn(this));
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_extreme_saver));
        tile.setLabel(getString(R.string.extreme_tile_label));
        tile.setState(ExtremeSaver.isOn(this) ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
