// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.SystemClock;

import cat.kenny.r1.toolbox.engine.Note;
import cat.kenny.r1.toolbox.engine.PitchDetector;

import java.util.Locale;

/** Tuner: the note it hears and how far off it is. The wheel moves where A is. */
public final class TunerActivity extends OneReading {

    private static final int A_MIN = 415;
    private static final int A_MAX = 466;
    /** How sure the detector has to be that it hears one note */
    private static final float CLEAR = 0.8f;
    /** The note stays up this long after the string dies away */
    private static final long LINGER_MS = 1500;

    private final Mic mMic = new Mic();
    private final PitchDetector mPitch = new PitchDetector(Mic.RATE, 4096);
    private StageView mCents;
    private int mA;
    private boolean mHeld;
    private volatile float mHz;
    private volatile long mHeardAt;

    static int a(android.content.Context context) {
        return Math.max(A_MIN, Math.min(A_MAX, Words.prefs(context).getInt("a4", 440)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.tuner, "−", "+");
        mCents = new StageView(this, StageView.CENTS);
        stage(mCents);
        mA = a(this);
    }

    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    @Override
    protected void onResume() {
        super.onResume();
        listen();
    }

    private void listen() {
        if (!allowed()) {
            hint(R.string.tuner_denied);
            return;
        }
        hint(mHeld ? R.string.tuner_hint_held : R.string.tuner_hint);
        mMic.start((pcm, count) -> {
            if (mHeld) {
                return;
            }
            mPitch.feed(pcm, count);
            if (mPitch.analyse() && mPitch.clarity() >= CLEAR) {
                mHz = mPitch.frequency();
                mHeardAt = SystemClock.elapsedRealtime();
            }
        });
    }

    @Override
    protected void onPause() {
        mMic.stop();
        super.onPause();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        listen();
    }

    @Override
    protected void onTurn(int clicks) {
        final int a = Math.max(A_MIN, Math.min(A_MAX, mA + clicks));
        if (a != mA) {
            mA = a;
            Words.prefs(this).edit().putInt("a4", a).apply();
            show();
        }
    }

    @Override
    protected boolean onPress() {
        if (!allowed()) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
            return true;
        }
        mHeld = !mHeld;
        confirm();
        hint(mHeld ? R.string.tuner_hint_held : R.string.tuner_hint);
        return true;
    }

    @Override
    protected void show() {
        final float hz = mHz;
        final boolean fresh = hz > 0
                && (mHeld || SystemClock.elapsedRealtime() - mHeardAt < LINGER_MS);
        if (!fresh) {
            mNumber.setText("–");
            mUnit.setText("");
            mCents.cents(Float.NaN);
            mLine.setText(allowed() ? R.string.tuner_waiting : R.string.tuner_denied);
            mMore.setText(getString(R.string.tuner_hz, "–", mA));
            return;
        }
        final Note note = Note.of(hz, mA);
        final int cents = Math.round(note.cents);
        mNumber.setText(note.name);
        mUnit.setText(String.valueOf(note.octave));
        mCents.cents(note.cents);
        mLine.setText(Math.abs(cents) <= 3 ? getString(R.string.tuner_in_tune)
                : cents > 0 ? getString(R.string.tuner_is_sharp, cents) + " "
                        + getString(R.string.tuner_loosen)
                : getString(R.string.tuner_is_flat, -cents) + " "
                        + getString(R.string.tuner_tighten));
        mMore.setText(getString(R.string.tuner_hz,
                String.format(Locale.getDefault(), "%.1f", hz), mA));
    }
}
