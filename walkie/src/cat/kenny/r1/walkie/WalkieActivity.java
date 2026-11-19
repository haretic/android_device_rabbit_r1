// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.List;

/**
 * The walkie-talkie's page. Opening it turns the walkie-talkie on. The wheel turns through the
 * channels, or the volume while someone speaks; the side button held is talking, pressed is
 * the last message again. Past the channels the drum has the recent messages, then the settings.
 */
public final class WalkieActivity extends Activity {

    /** Down for this long is talking: short enough that the first word is not lost */
    private static final int HOLD_MS = 220;

    private DrumView mDrum;
    private WaveView mWave;
    private TalkView mTalk;
    private boolean mHeld;
    private boolean mHolding;
    /** The side button's down came here: a press is told from its up only then */
    private boolean mDown;
    /** Where the drum is past the channels: on a channel, the recent messages or the settings */
    private static final int ON_CHANNEL = 0, ON_MESSAGES = 1, ON_SETTINGS = 2;
    private int mPast = ON_CHANNEL;
    /** Its own pages are opening: leaving for them is not leaving the walkie-talkie */
    private boolean mToSettings;
    /** Left by the user's own choice, home or another app: not the screen going off */
    private boolean mLeaving;
    /** The channels and, last on the drum, the recent messages and the settings */
    private String[] mDrumItems;
    /** Where layout-land puts the drum in its column, under the name, -1 for upright */
    private int mDrumHome = -1;
    /** A turn of 180 degrees is not a new page: the drum follows the wheel's edge here */
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
                placeDrum();
            }
        }
    };
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mHold = () -> {
        mHeld = true;
        final WalkieService walkie = WalkieService.running();
        if (!mayTalk()) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
        } else if (walkie != null && walkie.talk()) {
            mHolding = true;
            getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        // Talking lasts for as long as the side button is down: its own down and up, not the
        // press or hold the system would make of them
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS
                | WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_HELD;
        getWindow().setAttributes(lp);
        setContentView(R.layout.walkie);
        mDrum = findViewById(R.id.drum);
        if (mDrum.getParent() instanceof ViewGroup column && column.getId() == R.id.listening
                && column.findViewById(R.id.hint_row) != null
                && getResources().getConfiguration().orientation
                        == android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            mDrumHome = column.indexOfChild(mDrum);
        }
        placeDrum();
        mWave = findViewById(R.id.wave);
        mTalk = findViewById(R.id.talking);
        // A new finger on the opaque talk face must not tap a control behind it.
        mTalk.setOnTouchListener((view, event) -> true);
        // By finger too: slide for the channel or the volume, keep a finger down to talk
        new TouchWheel(this, findViewById(android.R.id.content), KeyEvent.KEYCODE_DPAD_CENTER,
                TouchWheel.LIVE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
        final String[] channels = getResources().getStringArray(R.array.channels);
        mDrumItems = java.util.Arrays.copyOf(channels, channels.length + 2);
        mDrumItems[channels.length] = getString(R.string.messages);
        mDrumItems[channels.length + 1] = getString(R.string.settings);
        // The drum itself by finger: it turns under the finger, a tap tunes in what was tapped
        mDrum.setListener(new DrumView.Listener() {
            @Override
            public void onDrumTurn(int way) {
                turn(way);
            }

            @Override
            public void onDrumPick(int item) {
                pick(item);
            }

            @Override
            public void onVolumePick(int volume) {
                getSystemService(AudioManager.class).setStreamVolume(
                        AudioManager.STREAM_MUSIC, volume, 0);
                tick();
                show();
            }

            @Override
            public void onDrumTalk(boolean down) {
                if (down) {
                    mHold.run();
                } else {
                    letGo();
                }
            }
        });
        findViewById(R.id.settings_button).setOnClickListener(v -> openSettings());
        findViewById(R.id.messages_button).setOnClickListener(v -> openMessages());
        findViewById(R.id.again).setOnClickListener(v -> {
            final WalkieService walkie = WalkieService.running();
            if (walkie != null) {
                walkie.again();
            }
        });
    }

    /**
     * Turned the other way, the wheel along the bottom edge: the drum goes down there with it,
     * over the hint (layout-land has it along the top, under the name). Again on a turn of 180
     * degrees, which keeps the page and whatever is playing.
     */
    private void placeDrum() {
        final int edge = DrumView.wheelEdge(this);
        if (mDrumHome >= 0 && mDrum.getParent() instanceof ViewGroup column) {
            final View hint = column.findViewById(R.id.hint_row);
            final int at = column.indexOfChild(mDrum);
            // Moving it drops a finger holding it, so only when it is in the wrong place
            if (edge == 3 && at != column.indexOfChild(hint) - 1) {
                column.removeView(mDrum);
                column.addView(mDrum, column.indexOfChild(hint));
            } else if (edge != 3 && at != mDrumHome) {
                column.removeView(mDrum);
                column.addView(mDrum, mDrumHome);
            }
        }
        mDrum.setEdge(edge);
    }

    private void tick() {
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    private void openSettings() {
        mToSettings = true;
        startActivity(new Intent(this, WalkieSettingsActivity.class));
    }

    private void openMessages() {
        mToSettings = true;
        startActivity(new Intent(this, MessagesActivity.class));
    }

    /** The drum's line for where it is: the channel tuned in, or one past the channels. */
    private int drumAt(int channel) {
        return mPast == ON_CHANNEL ? channel - 1 : WalkieService.CHANNELS - 1 + mPast;
    }

    /** A wheel click, or the drum moved a line by finger: +1 is down the channels. */
    private void turn(int way) {
        final WalkieService walkie = WalkieService.running();
        if (walkie == null) {
            // Settings are useful before the microphone has been allowed too.
            mPast = Math.max(ON_CHANNEL, Math.min(ON_SETTINGS, mPast + way));
            show();
            return;
        }
        if (walkie.talking()) {
            return;
        }
        if (walkie.speaking() != null) {
            // Up is louder
            getSystemService(AudioManager.class).adjustStreamVolume(
                    AudioManager.STREAM_MUSIC, way > 0 ? AudioManager.ADJUST_LOWER
                            : AudioManager.ADJUST_RAISE, 0);
            show();
            return;
        }
        if (mPast != ON_CHANNEL) {
            // Between the messages' and the settings' lines, and back up to the channel the
            // walkie-talkie is still on
            final int past = Math.max(ON_CHANNEL, Math.min(ON_SETTINGS, mPast + way));
            if (past != mPast) {
                mPast = past;
                tick();
                show();
            }
            return;
        }
        final int channel = walkie.channel() + way;
        if (channel >= 1 && channel <= WalkieService.CHANNELS) {
            walkie.setChannel(channel);
            tick();
        } else if (channel == WalkieService.CHANNELS + 1) {
            mPast = ON_MESSAGES;
            tick();
            show();
        }
    }

    /** A line of the drum tapped: a channel tunes in, the last lines open their pages. */
    private void pick(int item) {
        if (item == WalkieService.CHANNELS) {
            openMessages();
            return;
        }
        if (item > WalkieService.CHANNELS) {
            openSettings();
            return;
        }
        final WalkieService walkie = WalkieService.running();
        if (walkie == null || walkie.talking()) {
            return;
        }
        mPast = ON_CHANNEL;
        if (walkie.channel() != item + 1) {
            walkie.setChannel(item + 1);
        }
        tick();
        show();
    }

    private boolean mayTalk() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onStart() {
        super.onStart();
        // Turned round while away, then on every turn while here
        placeDrum();
        getSystemService(DisplayManager.class).registerDisplayListener(mDisplays, mMain);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mToSettings = false;
        mLeaving = false;
        // The service runs with the microphone's type: only once the microphone is allowed
        if (mayTalk() && WalkieService.running() == null) {
            WalkieService.start(this);
        }
        WalkieService.listen(this::show);
        show();
    }

    @Override
    protected void onPause() {
        mDrum.cancelTouch();
        WalkieService.listen(null);
        mMain.removeCallbacks(mHold);
        letGo();
        // A gesture cut off by leaving must not carry over to the first one back here
        mHeld = false;
        mDown = false;
        super.onPause();
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();
        mLeaving = true;
    }

    @Override
    protected void onStop() {
        getSystemService(DisplayManager.class).unregisterDisplayListener(mDisplays);
        // Left for anything but its own settings: off too, where the settings say so. The
        // screen going off is not leaving
        if ((isFinishing() || mLeaving) && !mToSettings && !isChangingConfigurations()
                && !WalkieService.prefs(this)
                .getBoolean(WalkieService.STAY_ON, WalkieService.STAY_ON_DEFAULT)) {
            WalkieService.stop(this);
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        if (mayTalk()) {
            WalkieService.start(this);
        }
        show();
    }

    private void hint(int text) {
        ((TextView) findViewById(R.id.hint)).setText(text);
    }

    private void show() {
        final WalkieService walkie = WalkieService.running();
        final String[] channels = getResources().getStringArray(R.array.channels);
        final boolean talking = walkie != null && walkie.talking();
        mTalk.setVisibility(talking ? View.VISIBLE : View.GONE);
        final View listening = findViewById(R.id.listening);
        // Hiding a held drum's ancestor can cancel the touch that is keeping the mic on.
        // The opaque talk face covers it; keep its gesture alive until release/cancel.
        listening.setVisibility(talking && !mDrum.holding() ? View.INVISIBLE : View.VISIBLE);
        listening.setImportantForAccessibility(talking ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        final TextView nobody = findViewById(R.id.nobody);
        final ViewGroup people = findViewById(R.id.people);
        if (walkie == null) {
            ((TextView) findViewById(R.id.channel)).setText(channels[0]);
            mDrum.channels(mDrumItems, drumAt(1));
            people.removeAllViews();
            nobody.setText("");
            findViewById(R.id.speaker).setVisibility(View.GONE);
            hint(mPast == ON_SETTINGS ? R.string.hint_settings
                    : mPast == ON_MESSAGES ? R.string.hint_messages
                    : mayTalk() ? R.string.hint : R.string.hint_mic);
            return;
        }
        final String channel = channels[walkie.channel() - 1];
        final List<String> names = walkie.people();
        if (talking) {
            final String who = names.isEmpty() ? getString(R.string.hear_nobody)
                    : names.size() == 1 ? getString(R.string.hear_one, names.get(0))
                    : names.size() == 2 ? getString(R.string.hear_two, names.get(0),
                            names.get(1))
                    : names.size() == 3 ? getString(R.string.hear_three, names.get(0),
                            names.get(1), names.get(2))
                    : getResources().getQuantityString(R.plurals.hear_many, names.size(),
                            names.size());
            mTalk.set(getString(R.string.talking), getString(R.string.talking_to, channel), who,
                    getString(R.string.let_go), walkie.talkSince());
            return;
        }
        ((TextView) findViewById(R.id.channel)).setText(channel);
        final String speaking = walkie.speaking();
        people.removeAllViews();
        final long now = System.currentTimeMillis();
        for (String name : names) {
            final View row = getLayoutInflater().inflate(R.layout.person, people, false);
            final boolean speaks = name.equals(speaking);
            final TextView initial = row.findViewById(R.id.initial);
            initial.setText(name.substring(0, name.offsetByCodePoints(0, 1)).toUpperCase());
            if (speaks) {
                initial.getBackground().mutate().setTint(getColorAccent());
            }
            ((TextView) row.findViewById(R.id.name)).setText(name);
            final TextView state = row.findViewById(R.id.state);
            final long spoke = walkie.spoke(name);
            final int minutes = (int) ((now - spoke) / 60_000);
            state.setText(speaks ? getString(R.string.speaking)
                    : spoke == 0 || minutes > 59 ? getString(R.string.here)
                    : minutes == 0 ? getString(R.string.spoke_now)
                    : getResources().getQuantityString(R.plurals.spoke, minutes, minutes));
            if (speaks) {
                state.setTextColor(getColorAccent());
            }
            people.addView(row);
        }
        nobody.setText(!walkie.online() ? getString(R.string.no_wifi)
                : names.isEmpty() ? getString(R.string.nobody) : "");
        nobody.setVisibility(nobody.getText().length() == 0 ? View.GONE : View.VISIBLE);
        final AudioManager audio = getSystemService(AudioManager.class);
        // Who is talking, large, while they talk: the row in the list is small and may be
        // scrolled away
        final TextView speaker = findViewById(R.id.speaker);
        if (speaking != null && !speaking.contentEquals(speaker.getText())) {
            speaker.setText(speaking);
        }
        speaker.setVisibility(speaking != null ? View.VISIBLE : View.GONE);
        if (speaking != null) {
            mWave.setVisibility(View.VISIBLE);
            walkie.levels(mWave.levels());
            mWave.invalidate();
            mDrum.volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } else {
            mWave.setVisibility(View.GONE);
            mDrum.channels(mDrumItems, drumAt(walkie.channel()));
        }
        final String last = walkie.lastFrom();
        final String waiting = walkie.waiting();
        final View again = findViewById(R.id.again);
        again.setVisibility(last != null && speaking == null ? View.VISIBLE : View.GONE);
        if (last != null) {
            ((TextView) findViewById(R.id.again_text)).setText(waiting != null
                    ? getString(R.string.hear_waiting, waiting)
                    : getString(R.string.hear_again, last));
            again.setAlpha(walkie.playingAgain() ? 0.5f : 1);
        }
        hint(!mayTalk() ? R.string.hint_mic : speaking != null ? R.string.hint_speaking
                : mPast == ON_SETTINGS ? R.string.hint_settings
                : mPast == ON_MESSAGES ? R.string.hint_messages
                : last != null ? R.string.hint_replay : R.string.hint);
    }

    private int getColorAccent() {
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }

    private void letGo() {
        final WalkieService walkie = WalkieService.running();
        if (mHolding && walkie != null) {
            walkie.talkEnd();
        }
        mHolding = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final WalkieService walkie = WalkieService.running();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) {
                turn(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (down) {
                if (event.getRepeatCount() == 0) {
                    // Talking starts while the button is still down, whether or not the button
                    // repeats: by the clock
                    mHeld = false;
                    mDown = true;
                    mMain.removeCallbacks(mHold);
                    mMain.postDelayed(mHold, HOLD_MS);
                }
                return true;
            }
            if (mHeld) {
                letGo();
            } else if (!event.isCanceled() && (mMain.hasCallbacks(mHold)
                    // Its down never came (the window can take it to leave touch mode): a
                    // short one is still a press, a long one a talk that did not start
                    || !mDown && event.getEventTime() - event.getDownTime() < HOLD_MS)) {
                mMain.removeCallbacks(mHold);
                if (mPast == ON_SETTINGS) {
                    openSettings();
                } else if (mPast == ON_MESSAGES) {
                    openMessages();
                } else if (!mayTalk()) {
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
                } else if (walkie != null) {
                    walkie.again();
                }
            }
            // The gesture is over, whatever it was: nothing of it may count for the next one,
            // whose down may never come here
            mMain.removeCallbacks(mHold);
            mHeld = false;
            mDown = false;
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
