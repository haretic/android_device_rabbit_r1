// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.TypedArray;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.widget.ArrayAdapter;

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
 * comma separated; an app's default, from config_wheelAppModeDefaults or else taskbar, is
 * left out, as SystemUI's WheelTaskbar has it). The taskbar's Wheel mode button sets the same
 * for the app in front. Touch zones ("touch:X1:Y1:X2:Y2") are placed over the app
 * itself, in TouchZonesActivity.
 */
public class AppModesFragment extends SettingsBasePreferenceFragment {

    private static final String[] MODES = {"taskbar", "app", "scroll", "tab", "arrows", "page",
            "volume", "brightness", "media", "seek", "pinch", "zoom", "camera", "touch"};

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
        final Map<String, String> defaults = defaults(requireContext());
        final Map<String, Map<String, Integer>> tuning = readTuning();
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
            final String mode = modes.getOrDefault(pkg, defaults.getOrDefault(pkg, "taskbar"));
            preference.setSummary(tuning.containsKey(pkg)
                    ? getString(R.string.app_modes_tuned, label(mode)) : label(mode));
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
            labels[i] = choice(MODES[i]);
            if (MODES[i].equals(mode(current))) {
                checked = i;
            }
        }
        WheelSelect.attach(new AlertDialog.Builder(requireContext())
                .setTitle(app)
                .setSingleChoiceItems(new ArrayAdapter<>(requireContext(), R.layout.mode_choice,
                        android.R.id.text1, labels), checked, (dialog, which) -> {
                    dialog.dismiss();
                    if (MODES[which].equals("touch")) {
                        placeZones(requireContext(), pkg);
                        return;
                    }
                    final Map<String, String> modes = read(requireContext());
                    modes.remove(pkg);
                    if (!MODES[which].equals(
                            defaults(requireContext()).getOrDefault(pkg, "taskbar"))) {
                        modes.put(pkg, MODES[which]);
                    }
                    write(requireContext(), modes);
                    fill();
                })
                .setNeutralButton(R.string.app_tune_title, (dialog, which) ->
                        tune(pkg, app, "scroll".equals(mode(current))))
                .show());
    }

    /** The tunings an app may have of its own, as SystemUI's WheelTaskbarSettings names them. */
    private static final String[] TUNES = {"distance", "spin", "natural", "reverse", "divide",
            "guard"};
    private static final int[][] TUNE_VALUES = {{50, 75, 100, 150, 200}, {0, 1, 2}, {0, 1},
            {0, 1}, {1, 2, 3, 4}, {0, 100, 150, 250, 400}};
    private static final int[] TUNE_TITLES = {R.string.distance_title, R.string.spin_title,
            R.string.natural_title, R.string.reverse_title, R.string.divide_title,
            R.string.guard_title};
    private static final int[] TUNE_NAMES = {R.array.distance_entries, R.array.spin_entries,
            R.array.switch_entries, R.array.switch_entries, R.array.divide_entries,
            R.array.guard_entries};
    private static final String[] TUNE_KEYS = {LineageSettings.Secure.WHEEL_SCROLL_DISTANCE,
            LineageSettings.Secure.WHEEL_SPIN, LineageSettings.Secure.WHEEL_SCROLL_NATURAL,
            LineageSettings.Secure.WHEEL_REVERSE, null,
            LineageSettings.Secure.WHEEL_PRESS_GUARD};

    private int general(int tune) {
        return TUNE_KEYS[tune] == null ? 1 : LineageSettings.Secure.getIntForUser(
                requireContext().getContentResolver(), TUNE_KEYS[tune],
                WheelFragment.DEFAULTS.get(TUNE_KEYS[tune]), UserHandle.myUserId());
    }

    /**
     * The wheel's feel in one app, as the taskbar's Wheel mode list has it under This app's
     * wheel: a row steps its value on, kept for the app where it is not the general setting.
     * Scroll distance, spin and natural scrolling are Smooth scroll's, and only shown with it.
     */
    private void tune(String pkg, CharSequence app, boolean scrolls) {
        final Map<String, Map<String, Integer>> all = readTuning();
        final Map<String, Integer> own = all.getOrDefault(pkg, new LinkedHashMap<>());
        final List<Integer> rows = new java.util.ArrayList<>();
        final List<CharSequence> labels = new java.util.ArrayList<>();
        final List<Integer> next = new java.util.ArrayList<>();
        for (int i = 0; i < TUNES.length; i++) {
            if (!scrolls && i < 3) {
                continue;
            }
            final int value = own.getOrDefault(TUNES[i], general(i));
            int at = 0;
            for (int v = 0; v < TUNE_VALUES[i].length; v++) {
                if (Math.abs(TUNE_VALUES[i][v] - value) < Math.abs(TUNE_VALUES[i][at] - value)) {
                    at = v;
                }
            }
            final String said = getResources().getStringArray(TUNE_NAMES[i])[at];
            rows.add(i);
            next.add(TUNE_VALUES[i][(at + 1) % TUNE_VALUES[i].length]);
            labels.add(twoLines(getString(TUNE_TITLES[i]), own.containsKey(TUNES[i])
                    ? getString(R.string.app_tune_own, said) : said));
        }
        final AlertDialog.Builder builder = new AlertDialog.Builder(requireContext())
                .setTitle(getString(R.string.app_tune_of, app))
                .setItems(labels.toArray(new CharSequence[0]), (dialog, which) -> {
                    final int tune = rows.get(which);
                    if (next.get(which) == general(tune)) {
                        own.remove(TUNES[tune]);
                    } else {
                        own.put(TUNES[tune], next.get(which));
                    }
                    all.remove(pkg);
                    if (!own.isEmpty()) {
                        all.put(pkg, own);
                    }
                    writeTuning(all);
                    tune(pkg, app, scrolls);
                })
                .setPositiveButton(R.string.app_tune_done, (dialog, which) -> fill());
        if (!own.isEmpty()) {
            builder.setNeutralButton(R.string.app_tune_reset, (dialog, which) -> {
                all.remove(pkg);
                writeTuning(all);
                fill();
            });
        }
        WheelSelect.attach(builder.setOnCancelListener(dialog -> fill()).show());
    }

    private CharSequence twoLines(String title, String value) {
        final SpannableString lines = new SpannableString(title + "\n" + value);
        final TypedArray a = requireContext().obtainStyledAttributes(
                new int[] {android.R.attr.textColorSecondary});
        final int secondary = a.getColor(0, 0);
        a.recycle();
        lines.setSpan(new RelativeSizeSpan(0.8f), title.length() + 1, lines.length(), 0);
        lines.setSpan(new ForegroundColorSpan(secondary), title.length() + 1, lines.length(), 0);
        return lines;
    }

    /** LineageSettings.Secure.WHEEL_APP_TUNING: package=name:value;name:value, comma separated. */
    private Map<String, Map<String, Integer>> readTuning() {
        final Map<String, Map<String, Integer>> all = new LinkedHashMap<>();
        final String value = LineageSettings.Secure.getStringForUser(
                requireContext().getContentResolver(), LineageSettings.Secure.WHEEL_APP_TUNING,
                UserHandle.myUserId());
        if (TextUtils.isEmpty(value)) {
            return all;
        }
        for (String entry : value.split(",")) {
            final int equals = entry.indexOf('=');
            if (equals <= 0) {
                continue;
            }
            final Map<String, Integer> own = new LinkedHashMap<>();
            for (String tune : entry.substring(equals + 1).split(";")) {
                final int colon = tune.indexOf(':');
                try {
                    if (colon > 0) {
                        own.put(tune.substring(0, colon),
                                Integer.parseInt(tune.substring(colon + 1)));
                    }
                } catch (NumberFormatException e) {
                    // Not one of ours
                }
            }
            if (!own.isEmpty()) {
                all.put(entry.substring(0, equals), own);
            }
        }
        return all;
    }

    private void writeTuning(Map<String, Map<String, Integer>> all) {
        final StringBuilder value = new StringBuilder();
        for (Map.Entry<String, Map<String, Integer>> entry : all.entrySet()) {
            if (value.length() > 0) {
                value.append(',');
            }
            value.append(entry.getKey()).append('=');
            boolean first = true;
            for (Map.Entry<String, Integer> tune : entry.getValue().entrySet()) {
                value.append(first ? "" : ";").append(tune.getKey()).append(':')
                        .append(tune.getValue());
                first = false;
            }
        }
        LineageSettings.Secure.putStringForUser(requireContext().getContentResolver(),
                LineageSettings.Secure.WHEEL_APP_TUNING, value.toString(),
                UserHandle.myUserId());
    }

    /** Opens the app, then the zones over it, for the touch mode. */
    static void placeZones(Context context, String pkg) {
        final Intent app = context.getPackageManager().getLaunchIntentForPackage(pkg);
        if (app == null) {
            return;
        }
        context.startActivity(app.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        // Once the app is up, so it shows under them
        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() ->
                context.startActivity(new Intent(context, TouchZonesActivity.class)
                        .putExtra(TouchZonesActivity.EXTRA_PACKAGE, pkg)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                                | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                                | Intent.FLAG_ACTIVITY_NO_ANIMATION)), 700);
    }

    /** The mode's kind: touch zones, whatever their points, are "touch". */
    private static String mode(String mode) {
        return mode.startsWith("touch:") ? "touch" : mode;
    }

    private CharSequence label(String mode) {
        return getString(switch (mode(mode)) {
            case "app" -> R.string.mode_app;
            case "scroll" -> R.string.mode_scroll;
            case "tab" -> R.string.mode_tab;
            case "arrows" -> R.string.mode_arrows;
            case "page" -> R.string.mode_page;
            case "volume" -> R.string.mode_volume;
            case "brightness" -> R.string.mode_brightness;
            case "media" -> R.string.mode_media;
            case "seek" -> R.string.mode_seek;
            case "pinch" -> R.string.mode_pinch;
            case "zoom" -> R.string.mode_zoom;
            case "camera" -> R.string.mode_camera;
            case "touch" -> R.string.mode_touch;
            default -> R.string.mode_taskbar;
        });
    }

    /** The mode's name over a line on what the wheel and the side button do in it. */
    private CharSequence choice(String mode) {
        final String name = label(mode).toString();
        final SpannableString choice = new SpannableString(name + "\n" + getString(
                switch (mode(mode)) {
                    case "app" -> R.string.mode_app_desc;
                    case "scroll" -> R.string.mode_scroll_desc;
                    case "tab" -> R.string.mode_tab_desc;
                    case "arrows" -> R.string.mode_arrows_desc;
                    case "page" -> R.string.mode_page_desc;
                    case "volume" -> R.string.mode_volume_desc;
                    case "brightness" -> R.string.mode_brightness_desc;
                    case "media" -> R.string.mode_media_desc;
                    case "seek" -> R.string.mode_seek_desc;
                    case "pinch" -> R.string.mode_pinch_desc;
                    case "zoom" -> R.string.mode_zoom_desc;
                    case "camera" -> R.string.mode_camera_desc;
                    case "touch" -> R.string.mode_touch_desc;
                    default -> R.string.mode_taskbar_desc;
                }));
        final TypedArray a = requireContext().obtainStyledAttributes(
                new int[] {android.R.attr.textColorSecondary});
        final int secondary = a.getColor(0, 0);
        a.recycle();
        final int start = name.length() + 1;
        choice.setSpan(new RelativeSizeSpan(0.8f), start, choice.length(), 0);
        choice.setSpan(new ForegroundColorSpan(secondary), start, choice.length(), 0);
        return choice;
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

    /** The device's default modes, which apply to an app with none of the user's. */
    static Map<String, String> defaults(Context context) {
        final Map<String, String> modes = new LinkedHashMap<>();
        for (String entry : context.getResources().getStringArray(
                com.android.internal.R.array.config_wheelAppModeDefaults)) {
            final int equals = entry.indexOf('=');
            if (equals > 0) {
                modes.put(entry.substring(0, equals), entry.substring(equals + 1));
            }
        }
        return modes;
    }

    static void write(Context context, Map<String, String> modes) {
        final StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : modes.entrySet()) {
            if (value.length() > 0) {
                value.append(',');
            }
            value.append(entry.getKey()).append('=').append(entry.getValue());
        }
        LineageSettings.Secure.putStringForUser(context.getContentResolver(),
                LineageSettings.Secure.WHEEL_APP_MODES, value.toString(), UserHandle.myUserId());
    }

    static CharSequence summary(Context context) {
        final int count = read(context).size();
        return count == 0 ? context.getString(R.string.app_modes_none)
                : context.getResources().getQuantityString(R.plurals.app_modes_summary, count, count);
    }
}
