// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.app.Activity;
import android.content.SharedPreferences;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import cat.kenny.r1.synth.engine.SynthEngine;

/**
 * The synth: six pads under the fingers and the wheel as one knob. The side button moves the
 * knob on from the filter to the pitch, the stutter, the echo, the scale and the presets, and
 * last to playing the pads by wheel, and past the pads the loop. What it was left at is there
 * the next time.
 */
public final class SynthActivity extends Activity implements Choreographer.FrameCallback {

    /** What the wheel works on, in the order of the "knobs" names: the first four are knobs */
    static final int FILTER = SynthEngine.FILTER, PITCH = SynthEngine.PITCH,
            STUTTER = SynthEngine.STUTTER, ECHO = SynthEngine.ECHO, SCALE = 4, PRESET = 5,
            PLAY = 6;
    private static final int[] HINTS = {R.string.hint_filter, R.string.hint_pitch,
            R.string.hint_stutter, R.string.hint_echo, R.string.hint_scale, R.string.hint_preset,
            R.string.hint_play};
    /** In Play, past the six pads: the loop's button, then Clear while it has something */
    private static final int LOOP_ITEM = SynthEngine.PADS, CLEAR_ITEM = SynthEngine.PADS + 1;
    /** A wheel click moves the knob by this much of its travel */
    private static final float STEP = 1f / 24;
    /** The pitch moves by whole notes of the scale, seven either way */
    private static final int NOTES = 7;
    /** Wheel clicks closer than this are one flick, which does not leave the pads */
    private static final long FLICK_MS = 350;
    /** The ready-made sounds, in the order of the "presets" names: filter, pitch, stutter, echo
     *  and the scale. The first is how the synth starts out. */
    private static final float[][] PRESETS = {
        {1f, .5f, .35f, 0f, 0},
        {.55f, .5f, .30f, .25f, 0},
        {.30f, .5f - 2f / 14, .35f, .35f, 1},
        {1f, .5f + 3f / 14, .50f, .15f, 4},
        {.85f, .5f, .90f, 0f, 5},
        {.40f, .5f - 3f / 14, .20f, .70f, 2},
    };

    /** Kept over a turn of the screen, with the loop in it */
    private SynthEngine mEngine;
    private final Speaker mSpeaker = new Speaker();
    private PadsView mPads;
    private LoopView mLoop;
    private String[] mLoopSettings;
    private String[] mLoopHints;
    /** The loop's state as last shown */
    private int mLoopState;
    private KnobView mKnob;
    private TextView mSetting;
    private String[] mKnobs;
    private String[] mPadNames;
    private String[] mScales;
    private String[] mPresets;
    private int mWhich = FILTER;
    /** The pad the wheel is on while it plays them */
    private int mSelected;
    /** The pad the side button holds down, -1 none */
    private int mKeyPad = -1;
    /** The side button went down on the pads: its release is theirs, wherever the knob is */
    private boolean mPlayKey;
    private long mTurnedAt;
    private boolean mShown;
    /** Where layout-land puts the knob's lane, under the name, -1 for upright */
    private int mKnobHome = -1;
    /** A turn of 180 degrees is not a new page: the lane follows the wheel's edge here */
    private final DisplayManager.DisplayListener mDisplays = new DisplayManager.DisplayListener() {
        @Override
        public void onDisplayAdded(int displayId) {
        }

        @Override
        public void onDisplayRemoved(int displayId) {
        }

        @Override
        public void onDisplayChanged(int displayId) {
            if (getDisplay() != null && displayId == getDisplay().getDisplayId()) {
                placeKnob();
            }
        }
    };

    private SharedPreferences prefs() {
        return getSharedPreferences("synth", MODE_PRIVATE);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mEngine = getLastNonConfigurationInstance() instanceof SynthEngine kept ? kept
                : new SynthEngine(Speaker.RATE, System.nanoTime());
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        setContentView(R.layout.synth);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        mKnobs = getResources().getStringArray(R.array.knobs);
        mPadNames = getResources().getStringArray(R.array.pads);
        mScales = getResources().getStringArray(R.array.scales);
        mPresets = getResources().getStringArray(R.array.presets);
        mLoopSettings = getResources().getStringArray(R.array.loop_settings);
        mLoopHints = getResources().getStringArray(R.array.loop_hints);
        mPads = findViewById(R.id.pads);
        mKnob = findViewById(R.id.knob);
        final View root = findViewById(R.id.synth_root);
        if (root instanceof ViewGroup group && mKnob.getParent() == group) {
            mKnobHome = group.indexOfChild(mKnob);
        }
        placeKnob();
        mSetting = findViewById(R.id.setting);
        // By finger: drag along the knob to set it where the finger is, tap it for the next
        mKnob.setListener(new KnobView.Listener() {
            @Override
            public void onKnobTouch(float value) {
                touchKnob(value);
            }

            @Override
            public void onKnobTap() {
                next();
            }
        });
        // And the name of what the wheel works on, tapped, moves on as the button does. Not a
        // list: a window of its own would not get the side button, which would turn the
        // screen off instead
        mSetting.setOnClickListener(v -> next());
        findViewById(R.id.knobs).setOnClickListener(v -> to(FILTER));
        // The loop by finger: its button acts on the touch, Clear on letting go
        mLoop = findViewById(R.id.loop);
        mLoopState = mEngine.loopState();
        mLoop.show(mEngine);
        mLoop.setListener(new LoopView.Listener() {
            @Override
            public void onLoopAction() {
                loopAction();
            }

            @Override
            public void onLoopClear() {
                loopClear();
            }
        });
        mPads.set(mPadNames, (pad, down) -> {
            if (down) {
                if (mWhich == PLAY) {
                    mSelected = pad;
                    mPads.select(pad);
                    showKnob();
                }
                mEngine.padDown(pad);
                play();
            } else {
                mEngine.padUp(pad);
            }
        });
        restore();
        if (state != null) {
            // A turn of the screen keeps Play; only a fresh opening starts on the knobs.
            mWhich = Math.max(FILTER, Math.min(PLAY, state.getInt("which", FILTER)));
            mSelected = Math.max(0, Math.min(items() - 1, state.getInt("selected", 0)));
        }
        showKnob();
    }

    /**
     * Turned the other way, the wheel along the bottom edge: the knob's lane goes down there
     * with it, over the hint (layout-land has it along the top, under the name). Again on a
     * turn of 180 degrees, which keeps the page and the sound.
     */
    private void placeKnob() {
        final int edge = KnobView.wheelEdge(this);
        if (mKnobHome >= 0 && mKnob.getParent() instanceof ViewGroup group) {
            final View hint = findViewById(R.id.hint_row);
            final int at = group.indexOfChild(mKnob);
            if (edge == 3 && at != group.indexOfChild(hint) - 1) {
                group.removeView(mKnob);
                group.addView(mKnob, group.indexOfChild(hint));
            } else if (edge != 3 && at != mKnobHome) {
                group.removeView(mKnob);
                group.addView(mKnob, mKnobHome);
            }
        }
        mKnob.setEdge(edge);
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Turned round while away, then on every turn while here
        placeKnob();
        getSystemService(DisplayManager.class).registerDisplayListener(mDisplays, null);
    }

    @Override
    protected void onStop() {
        getSystemService(DisplayManager.class).unregisterDisplayListener(mDisplays);
        super.onStop();
    }

    @Override
    public Object onRetainNonConfigurationInstance() {
        return mEngine;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putInt("which", mWhich);
        state.putInt("selected", mSelected);
    }

    /** The setup the synth was left with, or the first preset. */
    private void restore() {
        final SharedPreferences prefs = prefs();
        final float[] plain = PRESETS[0];
        mEngine.setKnob(FILTER, prefs.getFloat("filter", plain[FILTER]));
        mEngine.setKnob(PITCH, prefs.getFloat("pitch", plain[PITCH]));
        mEngine.setKnob(STUTTER, prefs.getFloat("stutter", plain[STUTTER]));
        mEngine.setKnob(ECHO, prefs.getFloat("echo", plain[ECHO]));
        mEngine.setScale(prefs.getInt("scale", Math.round(plain[4])));
        final int which = prefs.getInt("which", FILTER);
        // Never back on the pads by wheel: the knobs are what is looked for first
        mWhich = which >= 0 && which < PLAY && which < mKnobs.length ? which : FILTER;
    }

    private void save() {
        prefs().edit()
                .putFloat("filter", mEngine.knob(FILTER))
                .putFloat("pitch", mEngine.knob(PITCH))
                .putFloat("stutter", mEngine.knob(STUTTER))
                .putFloat("echo", mEngine.knob(ECHO))
                .putInt("scale", mEngine.scale())
                .putInt("which", mWhich)
                .apply();
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

    /** The loop's button: record, close the take, add on top, done or play again. */
    private void loopAction() {
        mEngine.loopAction();
        play();
        tick();
    }

    private void loopClear() {
        mEngine.loopClear();
        play();
        tick();
    }

    /** What Play goes through: the pads, the loop's button and Clear while it has something. */
    private int items() {
        return SynthEngine.PADS + (LoopView.clearable(mLoopState) ? 2 : 1);
    }

    /** Which preset the knobs are at, -1 where they were turned away from all of them. */
    private int preset() {
        for (int i = 0; i < PRESETS.length; i++) {
            final float[] p = PRESETS[i];
            if (Math.abs(mEngine.knob(FILTER) - p[FILTER]) < .002f
                    && Math.abs(mEngine.knob(PITCH) - p[PITCH]) < .002f
                    && Math.abs(mEngine.knob(STUTTER) - p[STUTTER]) < .002f
                    && Math.abs(mEngine.knob(ECHO) - p[ECHO]) < .002f
                    && mEngine.scale() == Math.round(p[4])) {
                return i;
            }
        }
        return -1;
    }

    private void apply(int preset) {
        final float[] p = PRESETS[preset];
        mEngine.setKnob(FILTER, p[FILTER]);
        mEngine.setKnob(PITCH, p[PITCH]);
        mEngine.setKnob(STUTTER, p[STUTTER]);
        mEngine.setKnob(ECHO, p[ECHO]);
        mEngine.setScale(Math.round(p[4]));
    }

    private int presetCount() {
        return Math.min(PRESETS.length, mPresets.length);
    }

    private int scaleCount() {
        return Math.min(SynthEngine.SCALE_COUNT, mScales.length);
    }

    /** The pitch knob in whole notes from the middle, -NOTES to NOTES. */
    private int notes() {
        return Math.round((mEngine.knob(PITCH) - .5f) * 2 * NOTES);
    }

    private void setNotes(int notes) {
        notes = Math.max(-NOTES, Math.min(NOTES, notes));
        mEngine.setKnob(PITCH, .5f + notes / (2f * NOTES));
    }

    /** Where the knob's lane shows the value, 0 at the bottom to 1 at the top. */
    private float laneValue() {
        switch (mWhich) {
            case SCALE:
                return scaleCount() > 1 ? (float) mEngine.scale() / (scaleCount() - 1) : 0;
            case PRESET: {
                final int preset = preset();
                return preset < 0 || presetCount() < 2 ? 0 : (float) preset / (presetCount() - 1);
            }
            case PLAY:
                // The first pad at the top, as they are laid out, the loop last
                return 1 - (float) mSelected / (items() - 1);
            default:
                return mEngine.knob(mWhich);
        }
    }

    /** What the wheel works on and where it is, in words. */
    private String setting() {
        switch (mWhich) {
            case FILTER:
                return getString(R.string.setting_filter,
                        Math.round(mEngine.knob(FILTER) * 100));
            case PITCH: {
                final int notes = notes();
                return getString(R.string.setting_pitch,
                        notes > 0 ? "+" + notes : String.valueOf(notes));
            }
            case STUTTER:
                // As the engine has it: four repeats a second up to thirty-two
                return getString(R.string.setting_stutter,
                        Math.round(4 * Math.pow(8, mEngine.knob(STUTTER))));
            case ECHO: {
                final int echo = Math.round(mEngine.knob(ECHO) * 100);
                return echo == 0 ? getString(R.string.setting_echo_off)
                        : getString(R.string.setting_echo, echo);
            }
            case SCALE:
                return getString(R.string.setting_scale,
                        mScales[Math.min(mEngine.scale(), mScales.length - 1)]);
            case PRESET: {
                final int preset = preset();
                return preset < 0 || preset >= mPresets.length
                        ? getString(R.string.setting_custom)
                        : getString(R.string.setting_preset, mPresets[preset]);
            }
            default:
                return mSelected == LOOP_ITEM ? mLoopSettings[mLoopState]
                        : mSelected == CLEAR_ITEM ? getString(R.string.setting_loop_clear)
                        : getString(R.string.setting_play, mPadNames[mSelected]);
        }
    }

    private void showKnob() {
        // Playing lasts as long as the physical button is down, as talking does in Walkie.
        // The knobs still use the system's press and hold gestures.
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        final int features = mWhich == PLAY
                ? lp.inputFeatures | WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_HELD
                : lp.inputFeatures & ~WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_HELD;
        if (features != lp.inputFeatures) {
            lp.inputFeatures = features;
            getWindow().setAttributes(lp);
        }
        // Clear goes with what it cleared: the wheel is back on the loop's button
        mSelected = Math.min(mSelected, items() - 1);
        final String setting = setting();
        mKnob.set(mKnobs[mWhich], mWhich, mKnobs.length, laneValue());
        mKnob.setContentDescription(setting);
        mSetting.setText(setting);
        mPads.select(mWhich == PLAY && mSelected < SynthEngine.PADS ? mSelected : -1);
        mLoop.focus(mWhich != PLAY ? LoopView.NONE : mSelected == LOOP_ITEM ? LoopView.ACTION
                : mSelected == CLEAR_ITEM ? LoopView.CLEAR : LoopView.NONE);
        findViewById(R.id.knobs).setVisibility(mWhich == PLAY ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.hint)).setText(mWhich == PLAY && mKeyPad >= 0
                ? getString(mSelected < 2 ? R.string.hint_play_top_held : R.string.hint_play_held)
                : mWhich == PLAY && mSelected == LOOP_ITEM ? mLoopHints[mLoopState]
                : mWhich == PLAY && mSelected == CLEAR_ITEM ? getString(R.string.hint_loop_clear)
                : getString(HINTS[mWhich]));
    }

    private void tick() {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    /** On to the next thing for the wheel to work on. */
    private void next() {
        to((mWhich + 1) % mKnobs.length);
    }

    private void to(int which) {
        release();
        mWhich = which;
        tick();
        showKnob();
    }

    /** The pad the side button held, let go: it was let go of or the wheel moved on. */
    private void release() {
        if (mKeyPad >= 0) {
            mEngine.padUp(mKeyPad);
            mPads.keyDown(mKeyPad, false);
            mKeyPad = -1;
        }
    }

    /** A wheel click, or a slide's worth of one: up is +1. */
    private void turn(int clicks) {
        final long now = SystemClock.uptimeMillis();
        final long since = now - mTurnedAt;
        mTurnedAt = now;
        final String was = setting();
        switch (mWhich) {
            case PITCH:
                setNotes(notes() + clicks);
                break;
            case SCALE:
                mEngine.setScale(Math.max(0, Math.min(scaleCount() - 1,
                        mEngine.scale() + clicks)));
                break;
            case PRESET: {
                final int preset = preset();
                // From an own setup the first click goes to the nearest end of the list
                apply(preset < 0 ? (clicks > 0 ? 0 : presetCount() - 1)
                        : Math.max(0, Math.min(presetCount() - 1, preset + clicks)));
                break;
            }
            case PLAY:
                if (mKeyPad >= 0) {
                    if (mSelected < 2 && clicks > 0) {
                        // Even with the top row held, a turn up is an immediate way out.
                        to(FILTER);
                        return;
                    }
                    // A pad held down: the wheel is how fast it repeats
                    mEngine.setKnob(STUTTER, mEngine.knob(STUTTER) + clicks * STEP);
                    tick();
                    mSetting.setText(getString(R.string.setting_stutter,
                            Math.round(4 * Math.pow(8, mEngine.knob(STUTTER)))));
                    return;
                }
                // Down the pads and on to the loop is the wheel turned down; one click past an
                // end, not part of a flick, goes back to the knobs
                final int pad = mSelected - clicks;
                if (pad < 0 || pad >= items()) {
                    if (since > FLICK_MS) {
                        to(pad < 0 ? PRESET : FILTER);
                    }
                    return;
                }
                mSelected = pad;
                break;
            default:
                mEngine.setKnob(mWhich, mEngine.knob(mWhich) + clicks * STEP);
        }
        if (!was.equals(setting())) {
            tick();
        }
        showKnob();
    }

    /** A finger on the knob's lane, at value 0 at the bottom to 1 at the top. */
    private void touchKnob(float value) {
        final String was = setting();
        switch (mWhich) {
            case PITCH:
                setNotes(Math.round((value - .5f) * 2 * NOTES));
                break;
            case SCALE:
                mEngine.setScale(Math.round(value * (scaleCount() - 1)));
                break;
            case PRESET: {
                final int preset = Math.round(value * (presetCount() - 1));
                if (preset != preset()) {
                    apply(preset);
                }
                break;
            }
            case PLAY:
                mSelected = Math.round((1 - value) * (items() - 1));
                break;
            default:
                mEngine.setKnob(mWhich, value);
        }
        if (!was.equals(setting())) {
            tick();
        }
        showKnob();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mShown = true;
        // Back from a turn of the screen a loop plays on; back from elsewhere it was stopped
        if (mEngine.loopActive()) {
            play();
        }
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onPause() {
        mShown = false;
        Choreographer.getInstance().removeFrameCallback(this);
        release();
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            mEngine.padUp(pad);
        }
        mSpeaker.join();
        // What still rang does not ring on at the next start; leaving stops the loop, a turn
        // of the screen keeps it going
        mEngine.hush();
        if (!isChangingConfigurations()) {
            mEngine.loopPause();
        }
        save();
        super.onPause();
    }

    @Override
    public void doFrame(long nanos) {
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            mPads.show(pad, mEngine.level(pad), mEngine.slice(pad));
        }
        mLoop.show(mEngine);
        final int loop = mEngine.loopState();
        if (loop != mLoopState) {
            mLoopState = loop;
            showKnob();
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
                turn(code == KeyEvent.KEYCODE_DPAD_UP ? 1 : -1);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            final boolean touch = event.getSource() == InputDevice.SOURCE_TOUCHSCREEN;
            if (!touch && (mWhich == PLAY || mPlayKey)) {
                // Playing by wheel: the button is the pad's own, down and up, and a hold
                // repeats it as a finger's would
                if (down) {
                    if (event.getRepeatCount() == 0 && mKeyPad < 0 && mWhich == PLAY
                            && mSelected >= SynthEngine.PADS) {
                        // On the loop: its button or Clear, as it goes down, for the timing
                        mPlayKey = true;
                        if (mSelected == LOOP_ITEM) {
                            loopAction();
                        } else {
                            loopClear();
                        }
                    } else if (event.getRepeatCount() == 0 && mKeyPad < 0 && mWhich == PLAY) {
                        mPlayKey = true;
                        mKeyPad = mSelected;
                        mPads.keyDown(mKeyPad, true);
                        mEngine.padDown(mKeyPad);
                        play();
                        showKnob();
                    }
                } else {
                    mPlayKey = false;
                    release();
                    showKnob();
                }
                return true;
            }
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
            if (!event.isCanceled()) {
                if (held) {
                    // A hold is the way back, as everywhere
                    finish();
                } else {
                    next();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
