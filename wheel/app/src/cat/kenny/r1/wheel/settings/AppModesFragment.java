// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import lineageos.providers.LineageSettings;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the wheel does in each app, LineageSettings.Secure.WHEEL_APP_MODES (package=mode,
 * comma separated; taskbar, the default, is left out). The taskbar's Wheel mode button sets
 * the same for the app in front.
 */
public class AppModesFragment extends SettingsBasePreferenceFragment {

    private static final String[] MODES = {"taskbar", "app", "volume"};

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        getPreferenceScreen().setTitle(R.string.app_modes_title);
    }

    @Override
    public void onResume() {
        super.onResume();
        fill();
    }

    private void fill() {
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final PackageManager pm = requireContext().getPackageManager();
        final List<ResolveInfo> apps = pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0);
        apps.sort((a, b) -> a.loadLabel(pm).toString()
                .compareToIgnoreCase(b.loadLabel(pm).toString()));
        final Map<String, String> modes = read(requireContext());
        final List<String> done = new java.util.ArrayList<>();
        for (ResolveInfo app : apps) {
            final String pkg = app.activityInfo.packageName;
            if (done.contains(pkg)) {
                continue;
            }
            done.add(pkg);
            final Preference preference = new Preference(requireContext());
            preference.setKey(pkg);
            preference.setTitle(app.loadLabel(pm));
            preference.setIcon(app.loadIcon(pm));
            final String mode = modes.getOrDefault(pkg, "taskbar");
            preference.setSummary(label(mode));
            preference.setOnPreferenceClickListener(p -> {
                pick(pkg, app.loadLabel(pm), mode);
                return true;
            });
            screen.addPreference(preference);
        }
    }

    private void pick(String pkg, CharSequence app, String current) {
        final CharSequence[] labels = new CharSequence[MODES.length];
        int checked = 0;
        for (int i = 0; i < MODES.length; i++) {
            labels[i] = label(MODES[i]);
            if (MODES[i].equals(current)) {
                checked = i;
            }
        }
        WheelSelect.attach(new AlertDialog.Builder(requireContext())
                .setTitle(app)
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    final Map<String, String> modes = read(requireContext());
                    modes.remove(pkg);
                    if (which != 0) {
                        modes.put(pkg, MODES[which]);
                    }
                    final StringBuilder value = new StringBuilder();
                    for (Map.Entry<String, String> entry : modes.entrySet()) {
                        if (value.length() > 0) {
                            value.append(',');
                        }
                        value.append(entry.getKey()).append('=').append(entry.getValue());
                    }
                    LineageSettings.Secure.putStringForUser(
                            requireContext().getContentResolver(),
                            LineageSettings.Secure.WHEEL_APP_MODES, value.toString(),
                            UserHandle.myUserId());
                    dialog.dismiss();
                    fill();
                })
                .show());
    }

    private CharSequence label(String mode) {
        return getString(switch (mode) {
            case "app" -> R.string.mode_app;
            case "volume" -> R.string.mode_volume;
            default -> R.string.mode_taskbar;
        });
    }

    static Map<String, String> read(Context context) {
        final Map<String, String> modes = new LinkedHashMap<>();
        final String value = LineageSettings.Secure.getStringForUser(
                context.getContentResolver(), LineageSettings.Secure.WHEEL_APP_MODES,
                UserHandle.myUserId());
        if (!TextUtils.isEmpty(value)) {
            for (String entry : value.split(",")) {
                final int equals = entry.indexOf('=');
                if (equals > 0) {
                    modes.put(entry.substring(0, equals), entry.substring(equals + 1));
                }
            }
        }
        return modes;
    }

    static CharSequence summary(Context context) {
        final int count = read(context).size();
        return count == 0 ? context.getString(R.string.app_modes_none)
                : context.getString(R.string.app_modes_summary, count);
    }
}
