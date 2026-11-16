// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import cat.kenny.r1.toolbox.engine.SoundMeter;
import cat.kenny.r1.toolbox.engine.Tally;

/** The list: every tool shows its reading before it is opened. */
public final class ToolboxActivity extends ToolActivity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Mic mMic = new Mic();
    private LevelSensor mLevel;
    private SoundMeter mMeter;
    private boolean mListening;
    private View mLevelRow;
    private View mSoundRow;
    private View mTallyRow;

    private final Runnable mShow = new Runnable() {
        @Override
        public void run() {
            showSound();
            mHandler.postDelayed(this, 250);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.toolbox);
        final ViewGroup rows = findViewById(R.id.rows);
        mLevelRow = row(rows, R.drawable.ic_level, R.string.level, LevelActivity.class);
        mSoundRow = row(rows, R.drawable.ic_sound, R.string.sound, SoundActivity.class);
        mTallyRow = row(rows, R.drawable.ic_tally, R.string.tally, TallyActivity.class);
        mLevel = new LevelSensor(this, this::showLevel);
        mMeter = SoundActivity.newMeter(this);
        if (state == null) {
            mLevelRow.requestFocus();
        }
    }

    private View row(ViewGroup rows, int icon, int title, Class<?> tool) {
        final View row = getLayoutInflater().inflate(R.layout.row, rows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        row.setOnClickListener(v -> startActivity(new Intent(this, tool)));
        rows.addView(row);
        return row;
    }

    private static void set(View row, CharSequence sub, CharSequence reading) {
        ((TextView) row.findViewById(R.id.sub)).setText(sub);
        ((TextView) row.findViewById(R.id.reading)).setText(reading);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mLevel.available()) {
            mLevel.math.setMode(cat.kenny.r1.toolbox.engine.LevelMath.FLAT);
            mLevel.start();
        } else {
            set(mLevelRow, getString(R.string.level_none), "");
        }
        final Tally tally = Words.tally(this);
        set(mTallyRow, getString(tally.count() == 0 && tally.laps() == 0
                ? R.string.tally_empty : R.string.tally_counting), String.valueOf(tally.count()));
        // Only once it was allowed on its own screen: the list does not ask
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            final SoundMeter meter = mMeter;
            mListening = mMic.start((pcm, count) -> {
                synchronized (meter) {
                    meter.feed(pcm, count, System.currentTimeMillis());
                }
            });
        }
        if (mListening) {
            mHandler.post(mShow);
        } else {
            set(mSoundRow, getString(R.string.sound_row_ask), "");
        }
    }

    @Override
    protected void onPause() {
        mLevel.stop();
        mHandler.removeCallbacks(mShow);
        mMic.stop();
        mListening = false;
        super.onPause();
    }

    private void showLevel() {
        set(mLevelRow, getString(Words.levelRow(mLevel)), mLevel.lying
                ? Words.degrees(this, (float) Math.hypot(mLevel.math.tiltX(), mLevel.math.tiltY()))
                : "");
    }

    private void showSound() {
        final float now;
        synchronized (mMeter) {
            now = mMeter.now();
        }
        set(mSoundRow, getString(Words.soundRow(now)),
                getString(R.string.sound_db, Math.max(0, Math.round(now))));
    }
}
