// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.radiosleep;

import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.preference.ListPreference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/** The wait before the radios go off, and whether they do on the charger too. */
public final class RadioSleepFragment extends SettingsBasePreferenceFragment {
    private static final int[] MINUTES = {0, 1, 5, 10, 15, 30, 60, 120};

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.radio_sleep, rootKey);
        final SharedPreferences prefs = RadioSleep.prefs(requireContext());

        final String[] labels = new String[MINUTES.length];
        final String[] values = new String[MINUTES.length];
        for (int i = 0; i < MINUTES.length; i++) {
            labels[i] = i == 0 ? getString(R.string.never) : getString(R.string.minutes, MINUTES[i]);
            values[i] = Integer.toString(MINUTES[i]);
        }
        final ListPreference after = findPreference(RadioSleep.MINUTES);
        after.setEntries(labels);
        after.setEntryValues(values);
        after.setValue(Integer.toString(prefs.getInt(RadioSleep.MINUTES,
                RadioSleep.DEFAULT_MINUTES)));
        after.setSummaryProvider(ListPreference.SimpleSummaryProvider.getInstance());
        after.setOnPreferenceChangeListener((preference, value) -> {
            prefs.edit().putInt(RadioSleep.MINUTES, Integer.parseInt((String) value)).apply();
            return true;
        });

        final SwitchPreferenceCompat charger = findPreference(RadioSleep.ON_CHARGER);
        charger.setChecked(prefs.getBoolean(RadioSleep.ON_CHARGER, false));
        charger.setOnPreferenceChangeListener((preference, value) -> {
            prefs.edit().putBoolean(RadioSleep.ON_CHARGER, (Boolean) value).apply();
            return true;
        });
    }
}
