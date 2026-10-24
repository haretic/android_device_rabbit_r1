// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.UserHandle;

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
    private static final String KEY_PARTS = "parts";
    private static final String KEY_RUNNING = LineageSettings.Secure.WHEEL_TASKBAR_RUNNING;

    /** The settings' values when unset, as SystemUI's WheelTaskbarSettings has them. */
    static final Map<String, Integer> DEFAULTS =
            Map.ofEntries(
                    Map.entry(LineageSettings.Secure.WHEEL_BUTTON_COLUMNS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SHADE, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_LOCKSCREEN, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_DIALOGS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SETTINGS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_HOME, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SCROLL_DISTANCE, 100),
                    Map.entry(LineageSettings.Secure.WHEEL_SPIN, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_PRESS_GUARD, 150),
                    Map.entry(LineageSettings.Secure.WHEEL_REVERSE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_PRESS, 3),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_HOLD, 1),
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

    private MainSwitchPreference mWheelUi;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final String page = getArguments() == null ? null : getArguments().getString("page");
        setPreferencesFromResource(page == null ? R.xml.wheel_settings
                : getResources().getIdentifier("wheel_page_" + page, "xml",
                        requireContext().getPackageName()), rootKey);
        mWheelUi = findPreference(KEY_WHEEL_UI);
        if (mWheelUi != null) {
            mWheelUi.setOnPreferenceChangeListener((preference, value) -> {
                putInt(LineageSettings.Secure.WHEEL_UI, (Boolean) value ? 1 : 0);
                updateParts((Boolean) value);
                return true;
            });
        }
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
    }

    @Override
    public void onResume() {
        super.onResume();
        final boolean wheelUi = getInt(LineageSettings.Secure.WHEEL_UI, 1) != 0;
        if (mWheelUi != null) {
            mWheelUi.setChecked(wheelUi);
        }
        // The revised parts only apply with the wheel UI on
        updateParts(wheelUi);
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
        final Preference buttons = findPreference("buttons");
        if (buttons != null) {
            buttons.setSummary(ButtonsFragment.summary(requireContext()));
        }
        final Preference appModes = findPreference("app_modes");
        if (appModes != null) {
            appModes.setSummary(AppModesFragment.summary(requireContext()));
        }
    }

    private void updateParts(boolean wheelUi) {
        for (String key : new String[] {KEY_PARTS, "page_parts"}) {
            final Preference parts = findPreference(key);
            if (parts != null) {
                parts.setEnabled(wheelUi);
            }
        }
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
