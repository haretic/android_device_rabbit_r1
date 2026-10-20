// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import lineageos.providers.LineageSettings;

import java.util.ArrayList;
import java.util.List;

/**
 * The taskbar's buttons and their order, LineageSettings.Secure.WHEEL_TASKBAR_BUTTONS: every
 * button in order, a hidden one with a leading '!'. A press of the side button on one offers
 * show or hide and moving it; the wheel picks.
 */
public class ButtonsFragment extends SettingsBasePreferenceFragment {

    static final String[] DEFAULT = {"home", "drawer", "apps", "notifications", "mode"};

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        getPreferenceScreen().setTitle(R.string.buttons_title);
        fill();
    }

    private void fill() {
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final List<String> buttons = read(requireContext());
        int position = 0;
        for (int i = 0; i < buttons.size(); i++) {
            final String button = buttons.get(i);
            final boolean hidden = button.startsWith("!");
            final String name = hidden ? button.substring(1) : button;
            final Preference preference = new Preference(requireContext());
            preference.setKey(name);
            preference.setTitle(label(name));
            preference.setSummary(hidden ? getString(R.string.button_hidden)
                    : getString(R.string.button_shown, ++position));
            final int index = i;
            preference.setOnPreferenceClickListener(p -> {
                edit(index);
                return true;
            });
            screen.addPreference(preference);
        }
        final Preference reset = new Preference(requireContext());
        reset.setTitle(R.string.buttons_reset);
        reset.setOnPreferenceClickListener(p -> {
            write(List.of(DEFAULT));
            fill();
            return true;
        });
        screen.addPreference(reset);
        final Preference footer = new Preference(requireContext());
        footer.setSummary(R.string.buttons_footer);
        footer.setSelectable(false);
        screen.addPreference(footer);
    }

    private void edit(int index) {
        final List<String> buttons = new ArrayList<>(read(requireContext()));
        final String button = buttons.get(index);
        final boolean hidden = button.startsWith("!");
        final CharSequence[] actions = {
            getString(hidden ? R.string.button_show : R.string.button_hide),
            getString(R.string.button_up),
            getString(R.string.button_down),
        };
        WheelSelect.attach(new AlertDialog.Builder(requireContext())
                .setTitle(label(hidden ? button.substring(1) : button))
                .setItems(actions, (dialog, which) -> {
                    if (which == 0) {
                        buttons.set(index, hidden ? button.substring(1) : "!" + button);
                    } else {
                        final int to = which == 1 ? index - 1 : index + 1;
                        if (to < 0 || to >= buttons.size()) {
                            return;
                        }
                        buttons.set(index, buttons.get(to));
                        buttons.set(to, button);
                    }
                    write(buttons);
                    fill();
                })
                .show());
    }

    private void write(List<String> buttons) {
        LineageSettings.Secure.putStringForUser(requireContext().getContentResolver(),
                LineageSettings.Secure.WHEEL_TASKBAR_BUTTONS, String.join(",", buttons),
                UserHandle.myUserId());
    }

    /** Every button once, in order; unknown names dropped, missing ones added hidden. */
    static List<String> read(Context context) {
        final String value = LineageSettings.Secure.getStringForUser(
                context.getContentResolver(), LineageSettings.Secure.WHEEL_TASKBAR_BUTTONS,
                UserHandle.myUserId());
        final List<String> buttons = new ArrayList<>();
        final List<String> seen = new ArrayList<>();
        if (!TextUtils.isEmpty(value)) {
            for (String button : value.split(",")) {
                final String name = button.startsWith("!") ? button.substring(1) : button;
                if (List.of(DEFAULT).contains(name) && !seen.contains(name)) {
                    buttons.add(button);
                    seen.add(name);
                }
            }
        }
        for (String name : DEFAULT) {
            if (!seen.contains(name)) {
                buttons.add(TextUtils.isEmpty(value) ? name : "!" + name);
            }
        }
        return buttons;
    }

    static CharSequence summary(Context context) {
        final List<CharSequence> shown = new ArrayList<>();
        for (String button : read(context)) {
            if (!button.startsWith("!")) {
                shown.add(label(context, button));
            }
        }
        return TextUtils.join(", ", shown);
    }

    private CharSequence label(String name) {
        return label(requireContext(), name);
    }

    private static CharSequence label(Context context, String name) {
        return context.getString(switch (name) {
            case "home" -> R.string.button_home;
            case "drawer" -> R.string.button_drawer;
            case "apps" -> R.string.button_apps;
            case "notifications" -> R.string.button_notifications;
            default -> R.string.button_mode;
        });
    }
}
