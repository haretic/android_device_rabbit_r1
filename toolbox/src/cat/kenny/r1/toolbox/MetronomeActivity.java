// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.text.format.DateUtils;

import cat.kenny.r1.toolbox.engine.ClickTrack;

/** Metronome: the wheel sets the tempo, the button starts and stops, a hold changes the bar. */
public final class MetronomeActivity extends OneReading {

    static final int MIN = 30;
    static final int MAX = 240;
    private static final int[] BARS = {4, 3, 2, 6, 1};

    private final ClickTrack mTrack = new ClickTrack(Speaker.RATE);
    private final Speaker mSpeaker = new Speaker();
    private StageView mLamps;
    private int mTempo;
    private int mBeats;
    private long mStarted;

    static int tempo(android.content.Context context) {
        return Math.max(MIN, Math.min(MAX, Words.prefs(context).getInt("tempo", 96)));
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.metronome, "−", "+");
        mLamps = new StageView(this, StageView.LAMPS);
        stage(mLamps);
        mTempo = tempo(this);
        mBeats = Words.prefs(this).getInt("beats", 4);
        synchronized (mTrack) {
            mTrack.setTempo(mTempo);
            mTrack.setBeatsPerBar(mBeats);
        }
        hint(R.string.metronome_hint);
    }

    @Override
    protected void onPause() {
        stop();
        super.onPause();
    }

    private void stop() {
        synchronized (mTrack) {
            mTrack.stop();
        }
        mSpeaker.join();
        hint(R.string.metronome_hint);
    }

    @Override
    protected void onTurn(int clicks) {
        final int tempo = Math.max(MIN, Math.min(MAX, mTempo + clicks));
        if (tempo == mTempo) {
            return;
        }
        mTempo = tempo;
        synchronized (mTrack) {
            mTrack.setTempo(tempo);
        }
        Words.prefs(this).edit().putInt("tempo", tempo).apply();
        show();
    }

    @Override
    protected boolean onPress() {
        final boolean running;
        synchronized (mTrack) {
            running = mTrack.running();
        }
        if (running) {
            stop();
        } else {
            synchronized (mTrack) {
                mTrack.start();
            }
            mStarted = android.os.SystemClock.elapsedRealtime();
            mSpeaker.start((out, frames) -> {
                synchronized (mTrack) {
                    if (!mTrack.running()) {
                        return false;
                    }
                    mTrack.render(out, frames);
                    return true;
                }
            });
            hint(R.string.metronome_hint_running);
        }
        show();
        return true;
    }

    @Override
    protected boolean onHold() {
        synchronized (mTrack) {
            if (!mTrack.running()) {
                // Stopped, a hold is the way back, as everywhere
                return false;
            }
            int at = 0;
            while (at < BARS.length && BARS[at] != mBeats) {
                at++;
            }
            mBeats = BARS[(at + 1) % BARS.length];
            mTrack.setBeatsPerBar(mBeats);
        }
        Words.prefs(this).edit().putInt("beats", mBeats).apply();
        confirm();
        show();
        return true;
    }

    @Override
    protected void show() {
        final boolean running;
        final int beat;
        final long beats;
        synchronized (mTrack) {
            running = mTrack.running();
            beat = mTrack.beatInBar();
            beats = mTrack.beatCount();
        }
        mNumber.setText(String.valueOf(mTempo));
        mLine.setText(getResources().getQuantityString(R.plurals.metronome_bar, mBeats, mBeats));
        mLamps.lamps(mBeats, running && beats > 0 ? beat : -1);
        mMore.setText(running ? getString(R.string.metronome_running,
                DateUtils.formatElapsedTime(
                        (android.os.SystemClock.elapsedRealtime() - mStarted) / 1000),
                Math.max(1, (beats + mBeats - 1) / mBeats))
                : getString(R.string.metronome_stopped));
    }
}
