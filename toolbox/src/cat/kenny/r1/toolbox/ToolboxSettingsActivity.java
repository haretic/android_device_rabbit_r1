// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * The tools' settings, a row each with its choices. The wheel moves between the rows and the
 * side button, or a tap, moves the row on to its next choice. A hold goes back.
 */
public final class ToolboxSettingsActivity extends ToolActivity {

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.settings);
        final ViewGroup rows = findViewById(R.id.rows);
        final View first = row(rows, R.string.set_screen, R.array.set_screen_choices,
                Words.SCREEN_ON, 0);
        row(rows, R.string.set_click, R.array.set_click_choices, Words.CLICK_SOUND, 0);
        row(rows, R.string.set_tone_level, R.array.set_tone_level_choices, Words.TONE_LEVEL, 1);
        row(rows, R.string.set_tone_wave, R.array.set_tone_wave_choices, Words.TONE_WAVE, 0);
        row(rows, R.string.set_level_buzz, R.array.set_level_buzz_choices, Words.LEVEL_BUZZ, 0);
        row(rows, R.string.set_level_zero, R.array.set_level_zero_choices,
                Words.LEVEL_KEEP_ZERO, 0);
        row(rows, R.string.set_tuner_needle, R.array.set_tuner_needle_choices,
                Words.TUNER_NEEDLE, 0);
        hint(R.string.settings_hint);
        if (state == null) {
            first.requestFocus();
        }
    }

    @Override
    protected boolean isTool() {
        return false;
    }

    private View row(ViewGroup rows, int title, int choices, String key, int otherwise) {
        final String[] names = getResources().getStringArray(choices);
        final View row = getLayoutInflater().inflate(R.layout.row, rows, false);
        row.findViewById(R.id.icon).setVisibility(View.GONE);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        show(row, names, choice(key, otherwise, names.length));
        row.setOnClickListener(v -> {
            final int next = (choice(key, otherwise, names.length) + 1) % names.length;
            Words.prefs(this).edit().putInt(key, next).apply();
            show(v, names, next);
        });
        rows.addView(row);
        return row;
    }

    private int choice(String key, int otherwise, int count) {
        final int value = Words.prefs(this).getInt(key, otherwise);
        return value < 0 || value >= count ? otherwise : value;
    }

    private static void show(View row, String[] names, int choice) {
        ((TextView) row.findViewById(R.id.sub)).setText(names[choice]);
    }
}
