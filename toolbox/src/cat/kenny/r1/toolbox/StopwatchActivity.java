// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;

import cat.kenny.r1.toolbox.engine.StopwatchModel;

/** Stopwatch: the button starts and stops, a wheel click down marks a lap, a hold resets. */
public final class StopwatchActivity extends OneReading {

    private static final int LAPS_SHOWN = 4;

    private StopwatchModel mWatch;
    private long mLapAt;

    static StopwatchModel load(android.content.Context context) {
        return StopwatchModel.restore(Words.prefs(context).getString("stopwatch", ""));
    }

    /** The wall clock: a stopwatch left running is still right after a restart. */
    static long now() {
        return System.currentTimeMillis();
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.stopwatch, "", "⚑");
        findViewById(R.id.stage).setVisibility(android.view.View.GONE);
        // Six figures wide where the other tools have three
        mNumber.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,
                getResources().getDimension(R.dimen.tool_number) * 0.72f);
    }

    @Override
    protected void onResume() {
        mWatch = load(this);
        super.onResume();
    }

    private void save() {
        Words.prefs(this).edit().putString("stopwatch", mWatch.save()).apply();
    }

    @Override
    protected void onTurn(int clicks) {
        // One flick of the wheel is several clicks and one lap
        final long uptime = android.os.SystemClock.uptimeMillis();
        if (clicks > 0 && mWatch.running() && uptime - mLapAt > 400) {
            mLapAt = uptime;
            mWatch.lap(now());
            save();
            tick();
            show();
        }
    }

    @Override
    protected boolean onPress() {
        if (mWatch.running()) {
            mWatch.stop(now());
        } else {
            mWatch.start(now());
        }
        save();
        confirm();
        show();
        return true;
    }

    @Override
    protected boolean onHold() {
        if (mWatch.running() || mWatch.elapsed(now()) == 0) {
            // Running it is kept, and with nothing on it a hold is the way back
            return false;
        }
        mWatch.reset();
        save();
        confirm();
        show();
        return true;
    }

    @Override
    protected void show() {
        final long now = now();
        final long elapsed = mWatch.elapsed(now);
        final int laps = mWatch.laps();
        mNumber.setText(StopwatchModel.format(elapsed));
        long lapped = 0;
        long fastest = Long.MAX_VALUE;
        long slowest = -1;
        for (int i = 0; i < laps; i++) {
            lapped += mWatch.lap(i);
            fastest = Math.min(fastest, mWatch.lap(i));
            slowest = Math.max(slowest, mWatch.lap(i));
        }
        if (mWatch.running()) {
            mLine.setText(laps > 0 ? getString(R.string.stopwatch_running,
                    StopwatchModel.format(elapsed - lapped))
                    : getString(R.string.stopwatch_running_plain));
            hint(R.string.stopwatch_hint_running);
        } else if (elapsed > 0) {
            mLine.setText(R.string.stopwatch_stopped);
            hint(R.string.stopwatch_hint_stopped);
        } else {
            mLine.setText(R.string.stopwatch_start);
            hint(R.string.stopwatch_hint);
        }
        final SpannableStringBuilder text = new SpannableStringBuilder();
        for (int i = 0; i < Math.min(laps, LAPS_SHOWN); i++) {
            final long lap = mWatch.lap(i);
            if (i > 0) {
                text.append('\n');
            }
            text.append(getString(R.string.tally_lap, laps - i)).append("    ");
            final int from = text.length();
            text.append(StopwatchModel.format(lap));
            text.setSpan(new ForegroundColorSpan(getColor(R.color.text)), from, text.length(), 0);
            if (laps > 1 && fastest != slowest && (lap == fastest || lap == slowest)) {
                final int mark = text.length();
                text.append("  ").append(getString(lap == fastest ? R.string.stopwatch_fastest
                        : R.string.stopwatch_slowest));
                if (lap == fastest) {
                    final android.util.TypedValue accent = new android.util.TypedValue();
                    getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
                    text.setSpan(new ForegroundColorSpan(accent.data), mark, text.length(), 0);
                }
            }
        }
        mMore.setText(text);
    }
}
