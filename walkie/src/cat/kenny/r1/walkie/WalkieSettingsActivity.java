// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * The walkie-talkie's settings, each on or off. The wheel moves between them and the side
 * button switches the one it is on; a finger taps one. A hold goes back.
 */
public final class WalkieSettingsActivity extends Activity {

    private SharedPreferences mPrefs;
    private boolean mHeld;
    private boolean mLeaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        setContentView(R.layout.settings);
        mPrefs = WalkieService.prefs(this);
        final ViewGroup rows = findViewById(R.id.rows);
        final View first = row(rows, R.string.set_talk_sounds, R.string.set_talk_sounds_sub,
                WalkieService.TALK_SOUNDS, WalkieService.TALK_SOUNDS_DEFAULT);
        row(rows, R.string.set_chime, R.string.set_chime_sub,
                WalkieService.CHIME, WalkieService.CHIME_DEFAULT);
        row(rows, R.string.set_aloud, R.string.set_aloud_sub,
                WalkieService.ALOUD_CLOSED, WalkieService.ALOUD_CLOSED_DEFAULT);
        row(rows, R.string.set_stay_on, R.string.set_stay_on_sub,
                WalkieService.STAY_ON, WalkieService.STAY_ON_DEFAULT);
        ((TextView) findViewById(R.id.hint)).setText(R.string.settings_hint);
        if (state == null) {
            first.requestFocus();
        }
    }

    private View row(ViewGroup rows, int title, int sub, String key, boolean otherwise) {
        final View row = getLayoutInflater().inflate(R.layout.setting_row, rows, false);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        ((TextView) row.findViewById(R.id.sub)).setText(sub);
        show(row, mPrefs.getBoolean(key, otherwise));
        row.setOnClickListener(v -> {
            final boolean on = !mPrefs.getBoolean(key, otherwise);
            mPrefs.edit().putBoolean(key, on).apply();
            v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
            show(v, on);
        });
        rows.addView(row);
        return row;
    }

    private void show(View row, boolean on) {
        final TextView state = row.findViewById(R.id.state);
        state.setText(on ? R.string.on : R.string.off);
        if (on) {
            final TypedValue accent = new TypedValue();
            getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
            state.setTextColor(accent.data);
        } else {
            state.setTextColor(getColor(R.color.text_dim));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLeaving = false;
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        mLeaving = true;
    }

    @Override
    protected void onStop() {
        if (mLeaving && !isChangingConfigurations()
                && !mPrefs.getBoolean(WalkieService.STAY_ON, WalkieService.STAY_ON_DEFAULT)) {
            WalkieService.stop(this);
        }
        super.onStop();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        // The side button comes as the select key, which a keyboard's Enter is too. The wheel
        // is D-pad up and down, and moves the focus as it does everywhere
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
            // The system says which it was: a hold comes flagged
            final boolean held = (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            final boolean touch = event.getSource() == InputDevice.SOURCE_TOUCHSCREEN;
            if (down) {
                if (event.getRepeatCount() == 0) {
                    mHeld = false;
                } else if (held && !mHeld) {
                    mHeld = true;
                    if (!touch) {
                        finish();
                    }
                }
            } else if (!mHeld && !event.isCanceled()) {
                if (held) {
                    mHeld = true;
                    if (!touch) {
                        finish();
                    }
                } else {
                    final View focused = getCurrentFocus();
                    if (focused != null) {
                        focused.performClick();
                    } else {
                        getWindow().getDecorView().requestFocusFromTouch();
                    }
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
