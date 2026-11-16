// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.app.Activity;
import android.media.AudioManager;
import android.os.Bundle;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.widget.TextView;

import cat.kenny.r1.synth.engine.SynthEngine;

/**
 * The synth: six pads under the fingers and the wheel as one knob. The side button moves the
 * knob on from the filter to the pitch to the stutter.
 */
public final class SynthActivity extends Activity implements Choreographer.FrameCallback {

    private static final int[] HINTS = {R.string.hint_filter, R.string.hint_pitch,
            R.string.hint_stutter};
    /** A wheel click moves the knob by this much of its travel */
    private static final float STEP = 1f / 24;

    private final SynthEngine mEngine = new SynthEngine(Speaker.RATE, System.nanoTime());
    private final Speaker mSpeaker = new Speaker();
    private PadsView mPads;
    private KnobView mKnob;
    private String[] mKnobs;
    private int mWhich = SynthEngine.FILTER;
    private boolean mShown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        setContentView(R.layout.synth);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mKnobs = getResources().getStringArray(R.array.knobs);
        mPads = findViewById(R.id.pads);
        mKnob = findViewById(R.id.knob);
        mPads.set(getResources().getStringArray(R.array.pads), (pad, down) -> {
            if (down) {
                mEngine.padDown(pad);
                play();
            } else {
                mEngine.padUp(pad);
            }
        });
        showKnob();
    }

    /** The speaker runs while anything sounds and stops with the last tail. */
    private void play() {
        if (!mSpeaker.running()) {
            mSpeaker.join();
            mSpeaker.start((out, frames) -> {
                mEngine.render(out, frames);
                return mShown && !mEngine.silent();
            });
        }
    }

    private void showKnob() {
        mKnob.set(mKnobs[mWhich], mWhich, mKnobs.length, mEngine.knob(mWhich));
        ((TextView) findViewById(R.id.hint)).setText(HINTS[mWhich]);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mShown = true;
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        mShown = false;
        Choreographer.getInstance().removeFrameCallback(this);
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            mEngine.padUp(pad);
        }
        mSpeaker.join();
        super.onPause();
    }

    @Override
    public void doFrame(long nanos) {
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            mPads.show(pad, mEngine.level(pad), mEngine.slice(pad));
        }
        if (mShown) {
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    private boolean mLongPress;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) {
                // Up is more
                final float value = Math.max(0, Math.min(1, mEngine.knob(mWhich)
                        + (code == KeyEvent.KEYCODE_DPAD_UP ? STEP : -STEP)));
                mEngine.setKnob(mWhich, value);
                showKnob();
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (down) {
                mLongPress = event.getRepeatCount() > 0
                        && (mLongPress || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0);
                return true;
            }
            // The system says which it was: a hold's repeat comes flagged, and the flag is
            // kept from there as the release may come without it. Not told by the times: a
            // press arrives only once a second press can no longer follow, a wait that alone
            // made every real press look like a hold
            final boolean held = mLongPress
                    || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            if (!down && !event.isCanceled()) {
                if (held) {
                    // A hold is the way back, as everywhere
                    finish();
                } else {
                    mWhich = (mWhich + 1) % mKnobs.length;
                    getWindow().getDecorView().performHapticFeedback(
                            HapticFeedbackConstants.CLOCK_TICK);
                    showKnob();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
