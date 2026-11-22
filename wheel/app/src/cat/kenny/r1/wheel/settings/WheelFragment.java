// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.content.ContentResolver;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.Toast;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;
import androidx.recyclerview.widget.RecyclerView;

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
    /**
     * The wheel's sensitivity: the degrees of turn a step takes, in the wheel's driver. Set by
     * r1-ktweaks (ktweaks/) from this property, on sys.r1.kt.apply and at every start.
     */
    private static final String KEY_SENSITIVITY = "wheel_sensitivity";
    private static final String PROP_STEP = "persist.sys.r1.kt.wheel_step";
    private static final String DEFAULT_STEP = "20";
    private static final String KEY_RUNNING = LineageSettings.Secure.WHEEL_TASKBAR_RUNNING;

    /** The settings' values when unset, as SystemUI's WheelTaskbarSettings has them. */
    static final Map<String, Integer> DEFAULTS =
            Map.ofEntries(
                    Map.entry(LineageSettings.Secure.WHEEL_BUTTON_COLUMNS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SHADE, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_LOCKSCREEN, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_UNLOCK, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_DIALOGS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SETTINGS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_HOME, 1),
                    // Its switch on the Parts page was neither read nor written without it
                    Map.entry(LineageSettings.Secure.WHEEL_STYLES, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_GUIDE_HINTS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_GUIDE_LEGEND, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SCROLL_DISTANCE, 100),
                    Map.entry(LineageSettings.Secure.WHEEL_SPIN, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_MOTION, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_PRESS_GUARD, 150),
                    Map.entry(LineageSettings.Secure.WHEEL_REVERSE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_TURN_WITH_SCREEN, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_PRESS, 3),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_HOLD, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_TRIPLE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_SIDE_BUTTON_WAKE_DOUBLE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_SCROLL_NATURAL, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_RUNNING, 6),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_EDGE, 0),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_PUSH, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_LIST_END, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_TIMEOUT, 3),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_LABELS, 1),
                    Map.entry(LineageSettings.Secure.WHEEL_TASKBAR_POWER_LINGER, 1),
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
        final ListPreference sensitivity = findPreference(KEY_SENSITIVITY);
        if (sensitivity != null) {
            sensitivity.setOnPreferenceChangeListener((p, value) -> {
                // Unset at the default, as every tweak of r1-ktweaks is
                SystemProperties.set(PROP_STEP,
                        DEFAULT_STEP.equals(value) ? "" : (String) value);
                SystemProperties.set("sys.r1.kt.apply",
                        Long.toString(SystemClock.elapsedRealtime()));
                sensitivity.setValue((String) value);
                summarise(sensitivity);
                return true;
            });
        }
        final Preference hintsAgain = findPreference("hints_again");
        if (hintsAgain != null) {
            hintsAgain.setOnPreferenceClickListener(p -> {
                if (LineageSettings.Secure.putStringForUser(resolver(),
                        LineageSettings.Secure.WHEEL_GUIDE_HINTS_SEEN, "", UserHandle.myUserId())) {
                    Toast.makeText(requireContext(), R.string.hints_again_done,
                            Toast.LENGTH_SHORT).show();
                }
                return true;
            });
        }
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Room under the last row, every page's: the wheel scrolls a row clear of the list's
        // padding, so the ring round the last one is whole, not cut by the screen's edge
        final RecyclerView rows = getListView();
        rows.setPadding(rows.getPaddingLeft(), rows.getPaddingTop(), rows.getPaddingRight(),
                rows.getPaddingBottom()
                        + Math.round(16 * getResources().getDisplayMetrics().density));
        rows.setClipToPadding(false);
        if (mWheelUi == null) {
            return;
        }
        // The wheel's first stop in the list is not the switch that turns the whole wheel UI
        // off, where one stray press would: the row below it, the wheel going up to it. Once,
        // the first time the focus comes into the list (from the toolbar's back arrow, the
        // window's first stop skipping it), so a step back up to the switch stays there. The
        // framework's rule for main switches (isWheelRiskyFirstStop) knows the MainSwitchBar
        // only, not this preference's row round it, which is the view that takes the focus
        final RecyclerView list = getListView();
        list.getViewTreeObserver().addOnGlobalFocusChangeListener(
                new ViewTreeObserver.OnGlobalFocusChangeListener() {
                    @Override
                    public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                        View row = newFocus;
                        while (row != null && row.getParent() != list) {
                            row = row.getParent() instanceof View parent ? parent : null;
                        }
                        if (row == null) {
                            return;
                        }
                        list.getViewTreeObserver().removeOnGlobalFocusChangeListener(this);
                        // The switch is the page's first row (wheel_settings.xml)
                        if (!row.isInTouchMode() && list.getChildAdapterPosition(row) == 0) {
                            final View next = row.focusSearch(View.FOCUS_DOWN);
                            if (next != null && next != row) {
                                next.requestFocus(View.FOCUS_DOWN);
                            }
                        }
                    }
                });
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
        final ListPreference sensitivity = findPreference(KEY_SENSITIVITY);
        if (sensitivity != null) {
            final String step = SystemProperties.get(PROP_STEP, DEFAULT_STEP);
            sensitivity.setValue(sensitivity.findIndexOfValue(step) < 0 ? DEFAULT_STEP : step);
            summarise(sensitivity);
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
        } else if (LineageSettings.Secure.WHEEL_GUIDE_LEGEND.equals(list.getKey())) {
            list.setSummary(getString(R.string.legend_summary, entry));
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
