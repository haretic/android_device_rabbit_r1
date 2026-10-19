package cat.kenny.r1.perfoverlay;

import android.service.quicksettings.Tile;
import android.service.quicksettings.TileService;

/** Quick Settings tile switching the overlay on and off. */
public class HudTileService extends TileService {

    @Override
    public void onStartListening() {
        updateTile();
    }

    @Override
    public void onClick() {
        Hud.setEnabled(this, !Hud.isEnabled(this));
        updateTile();
    }

    private void updateTile() {
        final Tile tile = getQsTile();
        if (tile == null) {
            return;
        }
        final boolean on = Hud.isEnabled(this);
        tile.setLabel(getString(R.string.tile_label));
        tile.setSubtitle(getString(on ? R.string.tile_on : R.string.tile_off));
        tile.setState(on ? Tile.STATE_ACTIVE : Tile.STATE_INACTIVE);
        tile.updateTile();
    }
}
