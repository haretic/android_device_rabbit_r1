// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.widget.TextView;

import cat.kenny.r1.toolbox.engine.LevelMath;

/** Level: the wheel switches between flat, on edge and plumb, the button zeroes it. */
public final class LevelActivity extends ToolActivity {

    private static final int[] MODE_VIEWS = {R.id.mode_flat, R.id.mode_edge, R.id.mode_plumb};
    /** Under this the level calls itself dead level: the sensor's own noise is about that. */
    private static final float DEAD = 0.05f;

    private LevelSensor mSensor;
    private BubbleView mBubble;
    private TextView mAngle;
    private TextView mDetail;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.level);
        mBubble = findViewById(R.id.bubble);
        mAngle = findViewById(R.id.angle);
        mDetail = findViewById(R.id.detail);
        mSensor = new LevelSensor(this, this::show);
        mSensor.math.setMode(Words.levelMode(this));
        showMode();
        if (!mSensor.available()) {
            mDetail.setText(R.string.level_none);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
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
        final int mode = Math.max(LevelMath.FLAT,
                Math.min(LevelMath.PLUMB, mSensor.math.mode() + clicks));
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

    private void show() {
        final LevelMath math = mSensor.math;
        final int mode = math.mode();
        mBubble.set(mode, math.tiltX(), math.tiltY(), math.angle());
        mAngle.setText(Words.degrees(this, math.angle()));
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
