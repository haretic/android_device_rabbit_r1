// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.graphics.drawable.Icon;
import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings: shows the profile for all apps, a tap moves on to the next one. */
public class ProfileTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        Profiles.setProfile(this, Profiles.next(Profiles.getProfile(this)));
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final String profile = Profiles.getProfile(this);
        tile.setIcon(Icon.createWithResource(this, R.drawable.ic_performance));
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(Profiles.label(profile)));
        tile.setState(Profiles.BALANCED.equals(profile) ? Tile.STATE_INACTIVE : Tile.STATE_ACTIVE);
        tile.updateTile();
    }
}
