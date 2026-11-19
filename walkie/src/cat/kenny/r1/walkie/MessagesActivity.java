// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * The last few messages heard, newest first, each to hear again. The wheel moves between them
 * and the side button plays the one it is on; a finger taps one. A hold goes back.
 */
public final class MessagesActivity extends Activity {

    private boolean mHeld;
    private boolean mLeaving;
    /** What the rows were made for, so a change that is only "playing" keeps the focus */
    private final List<Object> mShownKeys = new ArrayList<>();

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        setContentView(R.layout.messages);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mLeaving = false;
        WalkieService.listen(this::show);
        show();
    }

    @Override
    protected void onPause() {
        WalkieService.listen(null);
        super.onPause();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        mLeaving = true;
    }

    @Override
    protected void onStop() {
        // As from the settings: left for home or another app is leaving the walkie-talkie
        if (mLeaving && !isChangingConfigurations() && !WalkieService.prefs(this)
                .getBoolean(WalkieService.STAY_ON, WalkieService.STAY_ON_DEFAULT)) {
            WalkieService.stop(this);
        }
        super.onStop();
    }

    private void show() {
        final WalkieService walkie = WalkieService.running();
        final List<WalkieService.Message> messages = walkie == null ? new ArrayList<>()
                : walkie.recent();
        final ViewGroup rows = findViewById(R.id.rows);
        final List<Object> keys = new ArrayList<>();
        for (WalkieService.Message message : messages) {
            keys.add(message.key);
        }
        if (!keys.equals(mShownKeys)) {
            // A new message came in: the rows again, the focus kept on the same message
            final View focused = getCurrentFocus();
            final Object focusedKey = focused == null ? null : focused.getTag();
            mShownKeys.clear();
            mShownKeys.addAll(keys);
            rows.removeAllViews();
            for (WalkieService.Message message : messages) {
                final View row = row(rows, message);
                if (message.key == focusedKey) {
                    row.requestFocus();
                }
            }
        }
        final boolean busy = walkie != null && (walkie.speaking() != null || walkie.talking());
        final String[] channels = getResources().getStringArray(R.array.channels);
        for (int i = 0; i < rows.getChildCount() && i < messages.size(); i++) {
            final WalkieService.Message message = messages.get(i);
            final View row = rows.getChildAt(i);
            ((TextView) row.findViewById(R.id.title)).setText(message.from);
            ((TextView) row.findViewById(R.id.sub)).setText(getString(R.string.message_sub,
                    channels[Math.max(1, Math.min(channels.length, message.channel)) - 1],
                    DateFormat.getTimeFormat(this).format(new Date(message.when)),
                    Math.max(1, Math.round(message.millis / 1000f))));
            final TextView state = row.findViewById(R.id.state);
            state.setText(message.playing ? R.string.message_playing
                    : busy || walkie != null && walkie.playingAgain() ? R.string.message_busy
                    : R.string.message_play);
            if (message.playing) {
                state.setTextColor(accent());
            } else {
                state.setTextColor(getColor(R.color.text_dim));
            }
        }
        findViewById(R.id.none).setVisibility(messages.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.hint)).setText(messages.isEmpty()
                ? R.string.messages_none_hint : R.string.messages_hint);
    }

    private View row(ViewGroup rows, WalkieService.Message message) {
        final View row = getLayoutInflater().inflate(R.layout.setting_row, rows, false);
        row.setTag(message.key);
        row.setOnClickListener(v -> {
            final WalkieService walkie = WalkieService.running();
            if (walkie != null) {
                v.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                walkie.again(message.key);
            }
        });
        rows.addView(row);
        return row;
    }

    private int accent() {
        final TypedValue accent = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        return accent.data;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        // As on the settings page: the wheel moves the focus, the side button comes as the
        // select key, a hold flagged
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
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
            if (!down) {
                // The gesture is over: a next press must not count as part of this hold
                mHeld = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
