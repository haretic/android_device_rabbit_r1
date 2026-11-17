// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.app.Activity;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The wheel and the side button for a finger: on the view given, a slide up or down turns as
 * the wheel does, a click for every stretch of the way, a tap is the button's press and a
 * finger kept still its hold. The screen gets the same keys either way, so whatever the wheel
 * can do here a finger can.
 */
final class TouchWheel implements View.OnTouchListener {
    private final Activity mActivity;
    private final float mStep;
    private final int mSlop;
    private final boolean mHolds, mLive;
    private final int mPress;
    private float mFrom, mAt;
    private boolean mSliding, mHeld;

    /** A press: the finger's tap as this key, and kept still as the key held. */
    static final int TAP = 0;
    /** Live: the key goes down with the finger and up with it, for what lasts while held. */
    static final int LIVE = 1;
    /** Tap only: a finger kept still does nothing. */
    static final int TAP_ONLY = 2;

    TouchWheel(Activity activity, View surface, int pressKey, int mode) {
        mActivity = activity;
        mPress = pressKey;
        mHolds = mode == TAP;
        mLive = mode == LIVE;
        mStep = 22 * activity.getResources().getDisplayMetrics().density;
        mSlop = ViewConfiguration.get(activity).getScaledTouchSlop();
        surface.setOnTouchListener(this);
    }

    private void key(int code, int action, int repeat, int flags) {
        final long now = SystemClock.uptimeMillis();
        // From the touch screen: a screen can tell these from the wheel's and the button's
        mActivity.dispatchKeyEvent(new KeyEvent(now, now, action, code, repeat, 0, -1, 0, flags,
                InputDevice.SOURCE_TOUCHSCREEN));
    }

    private final Runnable mHold = this::hold;

    private void hold() {
        mHeld = true;
        key(mPress, KeyEvent.ACTION_DOWN, 0, 0);
        key(mPress, KeyEvent.ACTION_DOWN, 1, KeyEvent.FLAG_LONG_PRESS);
        key(mPress, KeyEvent.ACTION_UP, 0, KeyEvent.FLAG_LONG_PRESS);
    }

    @Override
    public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mFrom = mAt = event.getY();
                mSliding = mHeld = false;
                if (mHolds) {
                    view.postDelayed(mHold, ViewConfiguration.getLongPressTimeout());
                } else if (mLive) {
                    key(mPress, KeyEvent.ACTION_DOWN, 0, 0);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mHeld) {
                    return true;
                }
                if (!mSliding && Math.abs(event.getY() - mFrom) >= mSlop) {
                    mSliding = true;
                    view.removeCallbacks(mHold);
                    if (mLive) {
                        // Not a press after all
                        key(mPress, KeyEvent.ACTION_UP, 0, KeyEvent.FLAG_CANCELED);
                    }
                    view.getParent().requestDisallowInterceptTouchEvent(true);
                }
                while (mSliding && Math.abs(event.getY() - mAt) >= mStep) {
                    // A finger going up is the wheel turned up
                    final boolean up = event.getY() < mAt;
                    mAt += up ? -mStep : mStep;
                    final int code = up ? KeyEvent.KEYCODE_DPAD_UP : KeyEvent.KEYCODE_DPAD_DOWN;
                    key(code, KeyEvent.ACTION_DOWN, 0, 0);
                    key(code, KeyEvent.ACTION_UP, 0, 0);
                }
                return true;
            case MotionEvent.ACTION_UP:
                view.removeCallbacks(mHold);
                if (mLive) {
                    if (!mSliding) {
                        key(mPress, KeyEvent.ACTION_UP, 0, 0);
                    }
                } else if (!mSliding && !mHeld) {
                    key(mPress, KeyEvent.ACTION_DOWN, 0, 0);
                    key(mPress, KeyEvent.ACTION_UP, 0, 0);
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                view.removeCallbacks(mHold);
                if (mLive && !mSliding) {
                    key(mPress, KeyEvent.ACTION_UP, 0, KeyEvent.FLAG_CANCELED);
                }
                return true;
            default:
                return false;
        }
    }
}
