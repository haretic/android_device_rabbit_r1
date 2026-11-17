// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.app.Activity;
import android.os.Bundle;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * A screen worked with the wheel and the side button. The wheel arrives as D-pad up and down,
 * one key per click. The side button arrives as the select key once the window asks for it:
 * a press acts, a hold goes back unless the screen has a use for it.
 */
abstract class ToolActivity extends Activity {

    private boolean mHeld;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
    }

    @Override
    protected void onPostCreate(Bundle state) {
        super.onPostCreate(state);
        if (usesWheel()) {
            // By finger too: slide to turn, tap to press, keep it still for what a hold does
            new TouchWheel(this, findViewById(android.R.id.content), KeyEvent.KEYCODE_POWER,
                    TouchWheel.TAP);
        }
    }

    /** Whether the wheel turns something here, instead of moving the focus. */
    protected boolean usesWheel() {
        return false;
    }

    /** A wheel click, down is +1, where usesWheel(). */
    protected void onTurn(int clicks) {
    }

    /** A press of the side button. Return false to let it click what has the focus. */
    protected boolean onPress() {
        return false;
    }

    /** A hold of the side button. Return false to go back. */
    protected boolean onHold() {
        return false;
    }

    protected final void tick() {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    protected final void confirm() {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
    }

    protected final void hint(int text) {
        ((TextView) findViewById(R.id.hint)).setText(text);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (!usesWheel()) {
                return super.dispatchKeyEvent(event);
            }
            if (down) {
                onTurn(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return true;
        }
        // The side button comes as the select key, which a keyboard's Enter is too
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            // The system says which it was: a hold comes flagged. Not told by the times, as a
            // press arrives only once a second press can no longer follow, a wait that alone
            // made every real press look like a hold
            final boolean held = (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            final boolean touch =
                    event.getSource() == android.view.InputDevice.SOURCE_TOUCHSCREEN;
            if (down) {
                if (event.getRepeatCount() == 0) {
                    mHeld = false;
                } else if (held && !mHeld) {
                    hold(touch);
                }
            } else if (!mHeld && !event.isCanceled()) {
                if (held) {
                    hold(touch);
                } else {
                    press();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private void press() {
        if (onPress()) {
            return;
        }
        final android.view.View focused = getCurrentFocus();
        if (focused != null) {
            confirm();
            focused.performClick();
        } else {
            getWindow().getDecorView().requestFocusFromTouch();
        }
    }

    private void hold(boolean touch) {
        mHeld = true;
        // The button's hold goes back where the screen has no use for it; a resting finger
        // does not, the way back by touch is the system's
        if (!onHold() && !touch) {
            finish();
        }
    }
}
