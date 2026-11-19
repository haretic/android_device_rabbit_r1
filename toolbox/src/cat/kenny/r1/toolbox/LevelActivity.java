// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.widget.TextView;

import cat.kenny.r1.toolbox.engine.LevelMath;

/** Level: the wheel switches between flat, on edge and plumb, the button zeroes it. */
public final class LevelActivity extends ToolActivity {

    private static final int[] MODE_VIEWS = {R.id.mode_flat, R.id.mode_edge, R.id.mode_plumb};
    /** Under this the level calls itself dead level: the sensor's own noise is about that. */
    private static final float DEAD = 0.05f;
    /** Off by more than this, it buzzes again once it comes back to dead level */
    private static final float AWAY = 0.3f;

    private LevelSensor mSensor;
    private BubbleView mBubble;
    private TextView mAngle;
    private TextView mDetail;
    /** Buzz as it comes to dead level, from the settings page */
    private boolean mBuzz;
    /** It buzzed and has not been away since */
    private boolean mBuzzed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.level);
        mBubble = findViewById(R.id.bubble);
        mAngle = findViewById(R.id.angle);
        mDetail = findViewById(R.id.detail);
        mSensor = new LevelSensor(this, this::show);
        mSensor.math.setMode(Words.levelMode(this));
        // "This is flat" from an earlier visit, where the settings keep it
        Words.restoreZeros(this, mSensor.math);
        // By finger a mode is tapped, where the wheel steps to it
        for (int i = 0; i < MODE_VIEWS.length; i++) {
            final int mode = LevelMath.FLAT + i;
            findViewById(MODE_VIEWS[i]).setOnClickListener(v -> setMode(mode));
        }
        showMode();
        if (!mSensor.available()) {
            mDetail.setText(R.string.level_none);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mBuzz = Words.prefs(this).getInt(Words.LEVEL_BUZZ, 0) == 1;
        mBuzzed = false;
        mSensor.start();
    }

    @Override
    protected void onPause() {
        mSensor.stop();
        super.onPause();
    }

    @Override
    protected boolean usesWheel() {
        return true;
    }

    @Override
    protected void onTurn(int clicks) {
        setMode(Math.max(LevelMath.FLAT,
                Math.min(LevelMath.PLUMB, mSensor.math.mode() + clicks)));
    }

    private void setMode(int mode) {
        if (mode == mSensor.math.mode()) {
            return;
        }
        mSensor.math.setMode(mode);
        Words.prefs(this).edit().putInt("level_mode", mode).apply();
        tick();
        showMode();
        show();
    }

    @Override
    protected boolean onPress() {
        if (mSensor.math.zeroed()) {
            mSensor.math.clearZero();
        } else {
            mSensor.math.zero();
        }
        Words.saveZero(this, mSensor.math);
        confirm();
        showMode();
        show();
        return true;
    }

    private void showMode() {
        final int mode = mSensor.math.mode();
        for (int i = 0; i < MODE_VIEWS.length; i++) {
            final TextView view = findViewById(MODE_VIEWS[i]);
            view.setTextColor(getColor(i == mode ? R.color.text : R.color.text_faint));
            view.setTypeface(android.graphics.Typeface.create(
                    i == mode ? "haretic-text-bold" : "haretic-text",
                    android.graphics.Typeface.NORMAL));
        }
        hint(mSensor.math.zeroed() ? R.string.level_hint_unzero : R.string.level_hint_zero);
    }

    /** Once as it comes to dead level, again only after it was well away from it. */
    private void buzz(boolean dead, boolean away) {
        if (!mBuzz) {
            return;
        }
        if (dead && !mBuzzed) {
            mBuzzed = true;
            final Vibrator vibrator = getSystemService(Vibrator.class);
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(VibrationEffect.createOneShot(60,
                        VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } else if (away) {
            mBuzzed = false;
        }
    }

    private void show() {
        final LevelMath math = mSensor.math;
        final int mode = math.mode();
        mBubble.set(mode, math.tiltX(), math.tiltY(), math.angle());
        mAngle.setText(Words.degrees(this, math.angle()));
        // Held the way the mode is for, how far off level it is
        final boolean held = (mode == LevelMath.FLAT) == mSensor.lying;
        final float off = mode == LevelMath.FLAT
                ? Math.max(Math.abs(math.tiltX()), Math.abs(math.tiltY()))
                : Math.abs(math.angle());
        buzz(held && off < DEAD, !held || off > AWAY);
        if (mode == LevelMath.FLAT) {
            if (!mSensor.lying) {
                mDetail.setText(R.string.level_lay_flat);
                return;
            }
            final float x = math.tiltX();
            final float y = math.tiltY();
            final String side = Math.abs(x) < DEAD ? null : getString(
                    x > 0 ? R.string.level_right : R.string.level_left, Words.tenths(x));
            final String front = Math.abs(y) < DEAD ? null : getString(
                    y > 0 ? R.string.level_away : R.string.level_toward, Words.tenths(y));
            mDetail.setText(side == null && front == null ? getString(R.string.level_dead)
                    : side == null ? front : front == null ? side
                    : getString(R.string.level_detail, side, front));
        } else if (mSensor.lying) {
            mDetail.setText(R.string.level_stand_up);
        } else if (mode == LevelMath.ON_EDGE) {
            final float a = math.angle();
            mDetail.setText(Math.abs(a) < DEAD ? R.string.level_edge_dead
                    : a > 0 ? R.string.level_edge_right : R.string.level_edge_left);
        } else {
            mDetail.setText(Math.abs(math.angle()) < DEAD ? R.string.level_plumb_dead
                    : R.string.level_plumb_off);
        }
    }
}
