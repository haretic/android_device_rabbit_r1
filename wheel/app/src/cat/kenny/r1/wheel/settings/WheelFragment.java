// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import lineageos.providers.LineageSettings;

import java.util.Map;

/**
 * The taskbar's settings. Every list and switch is the LineageSettings.Secure setting of its
 * key; SystemUI follows them as they change, so each shows at once.
 */
public class WheelFragment extends SettingsBasePreferenceFragment {

    private static final String KEY_WHEEL_UI = "wheel_ui";
    private static final String KEY_DOUBLE_PRESS = "double_press";
    private static final String KEY_RUNNING = LineageSettings.Secure.WHEEL_TASKBAR_RUNNING;

    /** The settings' values when unset, as SystemUI's WheelTaskbarSettings has them. */
    static final Map<String, Integer> DEFAULTS =
            Map.ofEntries(
                    Map.entry(LineageSettings.Secure.WHEEL_BUTTON_COLUMNS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_RUNNING, 6),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_EDGE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_PUSH, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_LIST_END, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_TIMEOUT, 3),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_LABELS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_HAPTICS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_SOUNDS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_ANIMATIONS, 2),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_SIZE, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_COLOR, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_RING, 0));

    /** PhoneWindowManager's MULTI_PRESS_POWER_WHEEL_TASKBAR and MULTI_PRESS_POWER_NOTHING. */
    private static final int DOUBLE_PRESS_TASKBAR = 4;
    private static final int DOUBLE_PRESS_NOTHING = 0;

    private MainSwitchPreference mWheelUi;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.wheel_settings, rootKey);
        mWheelUi = findPreference(KEY_WHEEL_UI);
        mWheelUi.setOnPreferenceChangeListener((preference, value) -> {
            putInt(LineageSettings.Secure.WHEEL_UI, (Boolean) value ? 1 : 0);
            return true;
        });
        for (String key : DEFAULTS.keySet()) {
            final Preference preference = findPreference(key);
            if (preference == null) {
                continue;
            }
            preference.setOnPreferenceChangeListener((p, value) -> {
                putInt(key, value instanceof Boolean on ? (on ? 1 : 0)
                        : Integer.parseInt((String) value));
                if (p instanceof ListPreference list) {
                    list.setValue((String) value);
                    summarise(list);
                }
                return true;
            });
        }
        findPreference(KEY_DOUBLE_PRESS).setOnPreferenceChangeListener((p, value) -> {
            Settings.Global.putInt(resolver(), Settings.Global.POWER_BUTTON_DOUBLE_PRESS,
                    (Boolean) value ? DOUBLE_PRESS_TASKBAR : DOUBLE_PRESS_NOTHING);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        mWheelUi.setChecked(getInt(LineageSettings.Secure.WHEEL_UI, 1) != 0);
        for (Map.Entry<String, Integer> entry : DEFAULTS.entrySet()) {
            final Preference preference = findPreference(entry.getKey());
            final int value = getInt(entry.getKey(), entry.getValue());
            if (preference instanceof ListPreference list) {
                list.setValue(Integer.toString(value));
                summarise(list);
            } else if (preference instanceof SwitchPreferenceCompat toggle) {
                toggle.setChecked(value != 0);
            }
        }
        ((SwitchPreferenceCompat) findPreference(KEY_DOUBLE_PRESS)).setChecked(
                Settings.Global.getInt(resolver(), Settings.Global.POWER_BUTTON_DOUBLE_PRESS,
                        DOUBLE_PRESS_TASKBAR) == DOUBLE_PRESS_TASKBAR);
        findPreference("buttons").setSummary(ButtonsFragment.summary(requireContext()));
        findPreference("app_modes").setSummary(AppModesFragment.summary(requireContext()));
    }

    private void summarise(ListPreference list) {
        final CharSequence entry = list.getEntry();
        if (KEY_RUNNING.equals(list.getKey()) && !"0".equals(list.getValue())) {
            list.setSummary(getString(R.string.running_summary, entry));
        } else {
            list.setSummary(entry);
        }
    }

    private int getInt(String key, int def) {
        return LineageSettings.Secure.getIntForUser(resolver(), key, def, UserHandle.myUserId());
    }

    private void putInt(String key, int value) {
        LineageSettings.Secure.putIntForUser(resolver(), key, value, UserHandle.myUserId());
    }

    private ContentResolver resolver() {
        return requireContext().getContentResolver();
    }
}
