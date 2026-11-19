// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;

import cat.kenny.r1.toolbox.engine.Note;
import cat.kenny.r1.toolbox.engine.ToneRender;

import java.util.Locale;

/** Tone: one steady note, a semitone a wheel click, the button plays and stops it. */
public final class ToneActivity extends OneReading {

    /** Semitones from A4: A1 to A7, what the r1's speaker and an ear both manage. */
    private static final int LOWEST = -36;
    private static final int HIGHEST = 36;

    private final ToneRender mTone = new ToneRender(Speaker.RATE);
    private final Speaker mSpeaker = new Speaker();
    private StageView mWave;
    private int mSemitone;
    private boolean mPlaying;

    static float hz(int semitone) {
        return (float) (440 * Math.pow(2, semitone / 12.0));
    }

    static int semitone(android.content.Context context) {
        return Math.max(LOWEST, Math.min(HIGHEST, Words.prefs(context).getInt("tone", 0)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.tone, "↑", "↓");
        mUnit.setText(R.string.tone_unit);
        mWave = new StageView(this, StageView.WAVE);
        stage(mWave);
        mSemitone = semitone(this);
        synchronized (mTone) {
            mTone.setFrequency(hz(mSemitone));
        }
        hint(R.string.tone_hint);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // Quiet at any media volume unless the settings page says otherwise: a steady sine is
        // tiring long before music is
        synchronized (mTone) {
            mTone.setLevel(Words.toneLevel(this));
            mTone.setWave(Words.setting(this, Words.TONE_WAVE, ToneRender.WAVES));
        }
    }

    @Override
    protected long period() {
        return 250;
    }

    @Override
    protected void onPause() {
        if (mPlaying) {
            onPress();
        }
        mSpeaker.join();
        super.onPause();
    }

    @Override
    protected void onTurn(int clicks) {
        // Up is higher
        final int semitone = Math.max(LOWEST, Math.min(HIGHEST, mSemitone - clicks));
        if (semitone == mSemitone) {
            return;
        }
        mSemitone = semitone;
        synchronized (mTone) {
            mTone.setFrequency(hz(semitone));
        }
        Words.prefs(this).edit().putInt("tone", semitone).apply();
        tick();
        show();
    }

    @Override
    protected boolean onPress() {
        mPlaying = !mPlaying;
        synchronized (mTone) {
            if (mPlaying) {
                mTone.start();
            } else {
                mTone.stop();
            }
        }
        if (mPlaying) {
            mSpeaker.join();
            mSpeaker.start((out, frames) -> {
                synchronized (mTone) {
                    mTone.render(out, frames);
                    return !mTone.silent();
                }
            });
        }
        playing(mPlaying);
        hint(mPlaying ? R.string.tone_hint_playing : R.string.tone_hint);
        show();
        return true;
    }

    @Override
    protected void show() {
        final float hz = hz(mSemitone);
        final Note note = Note.of(hz, 440);
        mNumber.setText(String.valueOf(Math.round(hz)));
        // "Quietly" only where it is: the settings page can make it louder
        mLine.setText(getString(!mPlaying ? R.string.tone_note
                : Words.toneLevel(this) <= 0.25f ? R.string.tone_note_playing
                : R.string.tone_note_playing_loud, note.name + note.octave));
        mWave.wave(mPlaying);
        // Sound in air at room temperature
        final float metres = 343f / hz;
        mMore.setText((metres < 1 ? getString(R.string.tone_wave_cm, Math.round(metres * 100))
                : getString(R.string.tone_wave_m, String.format(Locale.getDefault(), "%.1f",
                        metres))) + "\n" + getString(R.string.tone_volume));
    }
}
