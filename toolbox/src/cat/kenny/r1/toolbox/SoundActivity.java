// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateFormat;
import android.widget.TextView;

import cat.kenny.r1.toolbox.engine.SoundMeter;

import java.util.Date;

/** Sound level: the level now, the last minute as bars, the button holds the reading. */
public final class SoundActivity extends ToolActivity {

    static final int BARS = 60;
    static final int BAR_MILLIS = 1000;
    /** The loud part: bars this close under the loudest, when the loudest stands out. */
    private static final float LOUD_WITHIN_DB = 6f;
    private static final float LOUD_OVER_AVERAGE_DB = 10f;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Mic mMic = new Mic();
    private final float[] mBars = new float[BARS];
    private SoundMeter mMeter;
    private boolean mListening;
    private TextView mLevel;
    private TextView mWords;
    private TextView mLoudest;
    private TextView mAverage;
    private BarsView mBarsView;

    private final Runnable mShow = new Runnable() {
        @Override
        public void run() {
            show();
            mHandler.postDelayed(this, 125);
        }
    };

    static SoundMeter newMeter(android.content.Context context) {
        return new SoundMeter(Mic.RATE,
                context.getResources().getInteger(R.integer.sound_offset_db), BAR_MILLIS, BARS);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.sound);
        mLevel = findViewById(R.id.level);
        mWords = findViewById(R.id.words);
        mLoudest = findViewById(R.id.loudest);
        mAverage = findViewById(R.id.average);
        mBarsView = findViewById(R.id.bars);
        mMeter = newMeter(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        listen();
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mShow);
        mMic.stop();
        mListening = false;
        super.onPause();
    }

    private boolean allowed() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void listen() {
        mHandler.removeCallbacks(mShow);
        if (!allowed()) {
            mLevel.setText("–");
            mWords.setText(R.string.sound_denied);
            hint(R.string.sound_denied);
            return;
        }
        final SoundMeter meter = mMeter;
        mListening = mMic.start((pcm, count) -> {
            synchronized (meter) {
                meter.feed(pcm, count, System.currentTimeMillis());
            }
        });
        if (!mListening) {
            mLevel.setText("–");
            mWords.setText(R.string.sound_busy_mic);
        }
        hint(mMeter.held() ? R.string.sound_hint_held : R.string.sound_hint);
        mHandler.post(mShow);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(request, permissions, results);
        listen();
    }

    @Override
    protected boolean onPress() {
        if (!allowed()) {
            requestPermissions(new String[] {Manifest.permission.RECORD_AUDIO}, 1);
            return true;
        }
        synchronized (mMeter) {
            mMeter.hold(!mMeter.held());
        }
        confirm();
        hint(mMeter.held() ? R.string.sound_hint_held : R.string.sound_hint);
        return true;
    }

    private void show() {
        if (!mListening) {
            return;
        }
        final float now;
        final float loudest;
        final float average;
        final long loudestTime;
        final int count;
        synchronized (mMeter) {
            now = mMeter.now();
            count = Math.min(BARS, mMeter.barCount());
            for (int i = 0; i < count; i++) {
                mBars[i] = mMeter.bar(i);
            }
            loudest = mMeter.loudest();
            loudestTime = mMeter.loudestTime();
            average = mMeter.average();
        }
        mLevel.setText(String.valueOf(Math.max(0, Math.round(now))));
        mWords.setText(Words.sound(now));
        final boolean standsOut = count > 0 && loudest >= average + LOUD_OVER_AVERAGE_DB;
        mBarsView.set(mBars, count, BARS,
                standsOut ? loudest - LOUD_WITHIN_DB : Float.MAX_VALUE);
        if (count == 0) {
            mLoudest.setText("");
            mAverage.setText("");
            return;
        }
        mLoudest.setText(getString(R.string.sound_loudest, Math.round(loudest),
                DateFormat.getTimeFormat(this).format(new Date(loudestTime))));
        mAverage.setText(getString(R.string.sound_average, Math.round(average)));
    }
}
