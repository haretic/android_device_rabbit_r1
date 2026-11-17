// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
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
 * the last message again.
 */
public final class WalkieActivity extends Activity {

    /** Down for this long is talking: short enough that the first word is not lost */
    private static final int HOLD_MS = 220;

    private DrumView mDrum;
    private WaveView mWave;
    private TalkView mTalk;
    private boolean mHeld;
    private boolean mHolding;
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
        mWave = findViewById(R.id.wave);
        mTalk = findViewById(R.id.talking);
        // By finger too: slide for the channel or the volume, keep a finger down to talk
        new TouchWheel(this, findViewById(android.R.id.content), KeyEvent.KEYCODE_DPAD_CENTER,
                TouchWheel.LIVE);
        setVolumeControlStream(AudioManager.STREAM_MUSIC);
    }

    private boolean mayTalk() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // The service runs with the microphone's type: only once the microphone is allowed
        if (mayTalk() && WalkieService.running() == null) {
            WalkieService.start(this);
        }
        WalkieService.listen(this::show);
        show();
    }

    @Override
    protected void onPause() {
        WalkieService.listen(null);
        mMain.removeCallbacks(mHold);
        letGo();
        super.onPause();
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
        findViewById(R.id.listening).setVisibility(talking ? View.INVISIBLE : View.VISIBLE);
        final TextView nobody = findViewById(R.id.nobody);
        final ViewGroup people = findViewById(R.id.people);
        if (walkie == null) {
            ((TextView) findViewById(R.id.channel)).setText(channels[0]);
            mDrum.channels(channels, 0);
            people.removeAllViews();
            nobody.setText("");
            hint(mayTalk() ? R.string.hint : R.string.hint_mic);
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
        if (speaking != null) {
            mWave.setVisibility(View.VISIBLE);
            walkie.levels(mWave.levels());
            mWave.invalidate();
            mDrum.volume(audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        } else {
            mWave.setVisibility(View.GONE);
            mDrum.channels(channels, walkie.channel() - 1);
        }
        final String last = walkie.lastFrom();
        final View again = findViewById(R.id.again);
        again.setVisibility(last != null && speaking == null ? View.VISIBLE : View.GONE);
        if (last != null) {
            ((TextView) findViewById(R.id.again_text)).setText(
                    getString(R.string.hear_again, last));
            again.setAlpha(walkie.playingAgain() ? 0.5f : 1);
        }
        hint(!mayTalk() ? R.string.hint_mic : speaking != null ? R.string.hint_speaking
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
            if (down && walkie != null && !walkie.talking()) {
                final int way = code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1;
                if (walkie.speaking() != null) {
                    // Up is louder
                    getSystemService(AudioManager.class).adjustStreamVolume(
                            AudioManager.STREAM_MUSIC, way > 0 ? AudioManager.ADJUST_LOWER
                                    : AudioManager.ADJUST_RAISE, 0);
                    show();
                } else {
                    final int channel = walkie.channel() + way;
                    if (channel >= 1 && channel <= WalkieService.CHANNELS) {
                        walkie.setChannel(channel);
                        getWindow().getDecorView().performHapticFeedback(
                                HapticFeedbackConstants.CLOCK_TICK);
                    }
                }
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
                    mMain.removeCallbacks(mHold);
                    mMain.postDelayed(mHold, HOLD_MS);
                }
            } else if (mHeld) {
                letGo();
            } else if (mMain.hasCallbacks(mHold) && !event.isCanceled()) {
                mMain.removeCallbacks(mHold);
                if (!mayTalk()) {
                    requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
                } else if (walkie != null) {
                    walkie.again();
                }
            }
            if (!down) {
                mMain.removeCallbacks(mHold);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
