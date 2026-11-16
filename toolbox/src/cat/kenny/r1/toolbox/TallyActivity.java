// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import cat.kenny.r1.toolbox.engine.Tally;

import java.util.Date;

/** Tally: a wheel click down counts one, the button starts a lap, a hold clears. */
public final class TallyActivity extends ToolActivity {

    private static final int LAPS_SHOWN = 4;

    private Tally mTally;
    private TextView mCount;
    private TextView mSince;
    private LinearLayout mLaps;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.tally);
        mCount = findViewById(R.id.count);
        mSince = findViewById(R.id.since);
        mLaps = findViewById(R.id.laps);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mTally = Words.tally(this);
        show();
    }

    @Override
    protected boolean usesWheel() {
        return true;
    }

    @Override
    protected void onTurn(int clicks) {
        if (clicks < 0 && mTally.count() == 0) {
            return;
        }
        mTally.add(clicks, System.currentTimeMillis());
        Words.save(this, mTally);
        tick();
        show();
    }

    @Override
    protected boolean onPress() {
        if (mTally.count() == 0) {
            return true;
        }
        mTally.lap();
        Words.save(this, mTally);
        confirm();
        show();
        return true;
    }

    @Override
    protected boolean onHold() {
        if (mTally.count() == 0 && mTally.laps() == 0) {
            // Nothing to clear: a hold is the way back, as everywhere
            return false;
        }
        mTally.clear();
        Words.save(this, mTally);
        confirm();
        show();
        mSince.setText(R.string.tally_cleared);
        return true;
    }

    private void show() {
        final int count = mTally.count();
        mCount.setText(String.valueOf(count));
        final boolean empty = count == 0 && mTally.laps() == 0;
        hint(empty ? R.string.tally_hint_empty : R.string.tally_hint);
        if (empty) {
            mSince.setText(R.string.tally_start);
        } else {
            final String clock = DateFormat.getTimeFormat(this).format(new Date(mTally.since()));
            // A rate needs a minute behind it: three clicks in two seconds are not "90 a minute"
            final long now = System.currentTimeMillis();
            final int rate = now - mTally.since() < 60_000 ? 0 : Math.round(mTally.perMinute(now));
            mSince.setText(rate > 0 ? getString(R.string.tally_since, clock, rate)
                    : getString(R.string.tally_since_only, clock));
        }
        mLaps.removeAllViews();
        final int laps = mTally.laps();
        for (int i = 0; i < Math.min(laps, LAPS_SHOWN); i++) {
            final LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            final TextView name = new TextView(this, null, 0, R.style.Dim);
            name.setText(getString(R.string.tally_lap, laps - i));
            name.setMinWidth(Math.round(96 * getResources().getDisplayMetrics().density));
            final TextView value = new TextView(this, null, 0, R.style.Body);
            value.setText(String.valueOf(mTally.lap(i)));
            row.addView(name);
            row.addView(value);
            row.setPadding(0, 0, 0, Math.round(6 * getResources().getDisplayMetrics().density));
            mLaps.addView(row);
        }
    }
}
