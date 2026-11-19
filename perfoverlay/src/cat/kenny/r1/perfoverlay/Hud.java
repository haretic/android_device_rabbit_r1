package cat.kenny.r1.perfoverlay;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.provider.Settings;
import android.service.quicksettings.TileService;
import android.text.TextUtils;

import androidx.preference.PreferenceManager;

import java.util.Arrays;

/** The overlay's settings, and switching it on and off. */
final class Hud {

    static final String KEY_ENABLED = "enabled";
    static final String KEY_STYLE = "style";
    static final String KEY_POSITION = "position";
    /** The look: text size, how dark the backing is, how often the numbers change */
    static final String KEY_SIZE = "size";
    static final String KEY_BACKGROUND = "background";
    static final String KEY_INTERVAL = "interval";

    static final String SIZE_SMALL = "small";
    static final String SIZE_MEDIUM = "medium";
    static final String SIZE_LARGE = "large";
    static final String BACKGROUND_DARK = "dark";
    static final String BACKGROUND_LIGHT = "light";
    static final String BACKGROUND_NONE = "none";

    static final String STYLE_FPS = "fps";
    static final String STYLE_COMPACT = "compact";
    static final String STYLE_FULL = "full";

    private Hud() {
    }

    static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static String getStyle(Context context) {
        return prefs(context).getString(KEY_STYLE, STYLE_COMPACT);
    }

    static String getPosition(Context context) {
        return prefs(context).getString(KEY_POSITION, "status_bar");
    }

    static String getSize(Context context) {
        return prefs(context).getString(KEY_SIZE, SIZE_SMALL);
    }

    /** The text size in sp: small is the size the overlay always had. */
    static float getTextSp(Context context) {
        switch (getSize(context)) {
            case SIZE_MEDIUM: return 14;
            case SIZE_LARGE: return 17;
            default: return 11;
        }
    }

    static String getBackground(Context context) {
        return prefs(context).getString(KEY_BACKGROUND, BACKGROUND_DARK);
    }

    /** Milliseconds between updates, one second unless chosen otherwise. */
    static long getIntervalMs(Context context) {
        try {
            final int seconds = Integer.parseInt(prefs(context).getString(KEY_INTERVAL, "1"));
            return seconds >= 1 && seconds <= 10 ? seconds * 1000L : 1000L;
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }

    /** Remembers the choice and starts or stops the overlay to match. */
    static void setEnabled(Context context, boolean on) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply();
        apply(context, on);
    }

    /** Starts or stops the overlay service without touching the saved choice. */
    static void apply(Context context, boolean on) {
        // Only needed at boot to put the overlay back
        BootStart.set(context, on);
        final Intent service = new Intent(context, HudService.class);
        if (on) {
            context.startForegroundServiceAsUser(service, UserHandle.SYSTEM);
            showTile(context);
        } else {
            context.stopService(service);
        }
        // The tile is an active tile: SystemUI only asks it for its state when told to.
        TileService.requestListeningState(context,
                new ComponentName(context, HudTileService.class));
    }

    /** The first time the overlay is used, its tile appears in Quick Settings. */
    private static void showTile(Context context) {
        final PackageManager pm = context.getPackageManager();
        final ComponentName tile = new ComponentName(context, HudTileService.class);
        if (pm.getComponentEnabledSetting(tile)
                == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
            return;
        }
        pm.setComponentEnabledSetting(tile, PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                PackageManager.DONT_KILL_APP);

        final ContentResolver resolver = context.getContentResolver();
        final String spec = "custom(" + tile.flattenToShortString() + ")";
        String tiles = Settings.Secure.getString(resolver, Settings.Secure.QS_TILES);
        if (TextUtils.isEmpty(tiles)) {
            // Unset means the default tiles, which SystemUI expands "default" to.
            tiles = "default";
        }
        if (!Arrays.asList(tiles.split(",")).contains(spec)) {
            Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, tiles + "," + spec);
        }
    }
}
