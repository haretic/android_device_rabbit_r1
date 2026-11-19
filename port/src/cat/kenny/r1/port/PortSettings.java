// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * What the port does by itself, set once: whether the sheet comes up for a computer and for a
 * stick, what the port becomes once the r1 is unlocked, and ejecting a stick once copied. The
 * same sheet as the port's own: the wheel goes from row to row, a press or a tap changes one.
 */
public final class PortSettings extends Activity {
    private ViewGroup mRows;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.sheet);
        mRows = findViewById(R.id.rows);
        // A cut-short copy is told on its own stick's sheet only, not here
        findViewById(R.id.outside).setOnClickListener(v -> finish());
        ((TextView) findViewById(R.id.head)).setText(R.string.settings_head);
        ((TextView) findViewById(R.id.sum)).setText(R.string.settings_guide);
        findViewById(R.id.hint).setVisibility(View.GONE);
        toggle(R.drawable.ic_usb, R.string.popup_computer, Port.POPUP_COMPUTER, true,
                R.string.popup_computer_off);
        toggle(R.drawable.ic_album, R.string.popup_stick, Port.POPUP_STICK, true,
                R.string.popup_stick_off);
        unlockRow();
        toggle(R.drawable.ic_eject, R.string.eject_copied, Port.EJECT_COPIED, false,
                R.string.eject_copied_off);
        mRows.getChildAt(0).requestFocus();
    }

    private View row(int icon, int title) {
        final View row = getLayoutInflater().inflate(R.layout.row, mRows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        mRows.addView(row);
        return row;
    }

    private static void summary(View row, String text) {
        final TextView summary = row.findViewById(R.id.summary);
        summary.setText(text);
        summary.setVisibility(View.VISIBLE);
    }

    /** A row that is on or off: its ring filled while on, its summary saying which. */
    private void toggle(int icon, int title, String key, boolean otherwise, int offText) {
        final View row = row(icon, title);
        final View radio = row.findViewById(R.id.radio);
        radio.setVisibility(View.VISIBLE);
        final Runnable show = () -> {
            final boolean on = Port.on(this, key, otherwise);
            radio.setSelected(on);
            summary(row, getString(on ? R.string.setting_on : offText));
        };
        show.run();
        row.setOnClickListener(v -> {
            Port.setOn(this, key, !Port.on(this, key, otherwise));
            show.run();
        });
    }

    /** Goes through the roles the r1 can take by itself, one press or tap at a time. */
    private void unlockRow() {
        final View row = row(R.drawable.ic_lock, R.string.unlock_role);
        final Runnable show = () -> summary(row, getString(R.string.unlock_role_sum,
                getString(Port.roleName(Port.unlockRole(this)))));
        show.run();
        row.setOnClickListener(v -> {
            final int[] roles = Port.unlockRoles();
            final int now = Port.unlockRole(this);
            int next = roles[0];
            for (int i = 0; i < roles.length; i++) {
                if (roles[i] == now) {
                    next = roles[(i + 1) % roles.length];
                    break;
                }
            }
            Port.setUnlockRole(this, next);
            show.run();
        });
    }
}
