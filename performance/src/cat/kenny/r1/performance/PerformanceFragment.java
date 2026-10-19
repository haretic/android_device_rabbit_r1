// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SelectorWithWidgetPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Settings > Performance: the profile for all apps, per-app profiles, games, the tile. */
public class PerformanceFragment extends SettingsBasePreferenceFragment
        implements SelectorWithWidgetPreference.OnClickListener {

    private static final String TILE_SPEC =
            "custom(cat.kenny.r1.performance/.ProfileTileService)";

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.performance, rootKey);
        final Context context = requireContext();
        for (String profile : Profiles.ALL) {
            ((SelectorWithWidgetPreference) findPreference(profile)).setOnClickListener(this);
        }
        findPreference("app_profiles").setIntent(new Intent(context, AppProfilesActivity.class));

        final SwitchPreferenceCompat gameDram = findPreference(Profiles.KEY_GAME_DRAM);
        gameDram.setChecked(Profiles.isGameDramHeld(context));
        gameDram.setOnPreferenceChangeListener((p, value) -> {
            Profiles.prefs(context).edit()
                    .putBoolean(Profiles.KEY_GAME_DRAM, (Boolean) value).apply();
            return true;
        });

        final SwitchPreferenceCompat tile = findPreference("tile");
        tile.setOnPreferenceChangeListener((p, value) -> {
            setTileShown((Boolean) value);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    @Override
    public void onRadioButtonClicked(SelectorWithWidgetPreference preference) {
        Profiles.setProfile(requireContext(), preference.getKey());
        update();
    }

    private void update() {
        final Context context = requireContext();
        final String profile = Profiles.getProfile(context);
        for (String p : Profiles.ALL) {
            ((SelectorWithWidgetPreference) findPreference(p)).setChecked(p.equals(profile));
        }
        final Map<String, String> apps = Profiles.getAppProfiles(context);
        final Preference appProfiles = findPreference("app_profiles");
        if (apps.isEmpty()) {
            appProfiles.setSummary(R.string.app_profiles_none);
        } else {
            final Map.Entry<String, String> first =
                    AppProfilesFragment.sorted(context, apps).get(0);
            appProfiles.setSummary(getResources().getQuantityString(
                    R.plurals.app_profiles_summary, apps.size(), apps.size(),
                    getString(R.string.app_profile_entry,
                            AppProfilesFragment.label(context, first.getKey()),
                            getString(Profiles.label(first.getValue())))));
        }
        ((SwitchPreferenceCompat) findPreference("tile")).setChecked(tiles().contains(TILE_SPEC));
    }

    private List<String> tiles() {
        final String tiles = Settings.Secure.getString(requireContext().getContentResolver(),
                Settings.Secure.QS_TILES);
        return TextUtils.isEmpty(tiles) ? new ArrayList<>()
                : new ArrayList<>(Arrays.asList(tiles.split(",")));
    }

    /** Adds the tile to the end of Quick Settings or takes it out. */
    private void setTileShown(boolean shown) {
        final ContentResolver resolver = requireContext().getContentResolver();
        final List<String> tiles = tiles();
        tiles.remove(TILE_SPEC);
        if (shown) {
            tiles.add(TILE_SPEC);
        }
        Settings.Secure.putString(resolver, Settings.Secure.QS_TILES, String.join(",", tiles));
    }
}
