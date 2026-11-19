// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * What the r1 shows of the phone outside its page: the phone's music as a player in the shade,
 * a call as a card over everything, and what was on the phone before the r1 came by. The wheel
 * goes from row to row, a press or a tap turns one over; the page itself always has it all.
 */
public final class PhoneSettings extends Activity {
    private ViewGroup mRows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.settings);
        mRows = findViewById(R.id.rows);
        toggle(R.drawable.ic_play, R.string.shade_music, R.string.shade_music_on,
                R.string.shade_music_off, PhoneService.SHADE_MUSIC);
        toggle(R.drawable.ic_call, R.string.shade_calls, R.string.shade_calls_on,
                R.string.shade_calls_off, PhoneService.SHADE_CALLS);
        toggle(R.drawable.ic_note, R.string.shade_old, R.string.shade_old_on,
                R.string.shade_old_off, PhoneService.SHADE_OLD);
        mRows.getChildAt(0).requestFocus();
    }

    private void toggle(int icon, int title, int onText, int offText, String what) {
        final View row = getLayoutInflater().inflate(R.layout.setting, mRows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        final Runnable show = () -> {
            final boolean on = PhoneService.shows(this, what);
            row.findViewById(R.id.check).setSelected(on);
            ((TextView) row.findViewById(R.id.text)).setText(on ? onText : offText);
        };
        show.run();
        row.setOnClickListener(v -> {
            PhoneService.setShows(this, what, !PhoneService.shows(this, what));
            show.run();
        });
        mRows.addView(row);
    }
}
