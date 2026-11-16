// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import cat.kenny.r1.walkie.net.JitterBuffer;
import cat.kenny.r1.walkie.net.Radio;
import cat.kenny.r1.walkie.net.Recorder;

import java.io.IOException;
import java.net.NetworkInterface;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;

/**
 * The walkie-talkie while it is on: it listens on the channel and plays what comes in aloud,
 * whether or not its page is open, and sends the microphone while the button is held.
 */
public final class WalkieService extends Service implements Radio.Listener {

    private static final String TAG = "Walkie";
    static final int RATE = 8000;
    static final int FRAME = 320;
    static final int CHANNELS = 8;

    private static volatile WalkieService sRunning;
    private static Runnable sListener;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Recorder mLast = new Recorder(60);
    private final Map<String, Long> mSpoke = new HashMap<>();
    /** How loud the last frames were, 0..1, newest last: the wave on the page. */
    private final float[] mLevels = new float[52];
    private Radio mRadio;
    private SharedPreferences mPrefs;
    private WifiManager.MulticastLock mMulticast;
    private PowerManager.WakeLock mWake;
    private volatile String mSpeaking;
    private volatile boolean mTalking;
    private volatile boolean mPlayingAgain;
    private long mTalkSince;
    private boolean mOnline;

    static WalkieService running() {
        return sRunning;
    }

    static void listen(Runnable listener) {
        sListener = listener;
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, WalkieService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, WalkieService.class));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = getSharedPreferences("walkie", MODE_PRIVATE);
        long id = mPrefs.getLong("id", 0);
        if (id == 0) {
            id = new SecureRandom().nextLong();
            mPrefs.edit().putLong("id", id).apply();
        }
        String name = Settings.Global.getString(getContentResolver(),
                Settings.Global.DEVICE_NAME);
        mRadio = new Radio(id, name == null ? "r1" : name, this);
        mRadio.setChannel(Math.max(1, Math.min(CHANNELS, mPrefs.getInt("channel", 1))));
        mMulticast = getSystemService(WifiManager.class).createMulticastLock("walkie");
        mWake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "walkie:voice");
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel("walkie", getString(R.string.channel),
                        NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        mMulticast.acquire();
        connect();
        sRunning = this;
        mMain.postDelayed(mTick, 1000);
        changed();
    }

    /** Joins the r1s on the Wi-Fi the r1 is on; again later when there was none yet. */
    private void connect() {
        if (mOnline) {
            return;
        }
        try {
            final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
            final LinkProperties link = cm.getLinkProperties(cm.getActiveNetwork());
            final NetworkInterface nif = link == null || link.getInterfaceName() == null ? null
                    : NetworkInterface.getByName(link.getInterfaceName());
            if (nif != null && nif.supportsMulticast()) {
                mRadio.start(nif);
                mOnline = true;
            }
        } catch (IOException e) {
            Log.w(TAG, "not on the network yet", e);
        }
    }

    private final Runnable mTick = new Runnable() {
        private long mVersion = -1;

        @Override
        public void run() {
            connect();
            // The radio drops who has gone quiet itself, on its own clock
            if (mRadio.peers().changedSince(mVersion) || mTalking) {
                mVersion = mRadio.peers().version();
                changed();
            }
            mMain.postDelayed(this, 1000);
        }
    };

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "off".equals(intent.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = null;
        mMain.removeCallbacks(mTick);
        mTalking = false;
        mSpeaking = null;
        mRadio.stop();
        if (mMulticast.isHeld()) {
            mMulticast.release();
        }
        changed();
        super.onDestroy();
    }

    private Notification note() {
        return new Notification.Builder(this, "walkie")
                .setSmallIcon(R.drawable.ic_launcher_fg)
                .setContentTitle(getString(R.string.note, channelName()))
                .setContentText(getString(R.string.note_text))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, WalkieActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .addAction(new Notification.Action.Builder(null, getString(R.string.turn_off),
                        PendingIntent.getService(this, 1, new Intent(this, WalkieService.class)
                                .setAction("off"), PendingIntent.FLAG_IMMUTABLE)).build())
                .build();
    }

    private void changed() {
        mMain.post(() -> {
            if (sListener != null) {
                sListener.run();
            }
        });
    }

    // ------------------------------------------------------------------ what the page shows

    boolean online() {
        return mOnline;
    }

    int channel() {
        return mRadio.channel();
    }

    String channelName() {
        return getResources().getStringArray(R.array.channels)[mRadio.channel() - 1];
    }

    void setChannel(int channel) {
        mRadio.setChannel(channel);
        mPrefs.edit().putInt("channel", channel).apply();
        getSystemService(NotificationManager.class).notify(1, note());
        changed();
    }

    java.util.List<String> people() {
        return mRadio.peers().names(mRadio.channel());
    }

    String speaking() {
        return mSpeaking;
    }

    boolean talking() {
        return mTalking;
    }

    long talkSince() {
        return mTalkSince;
    }

    /** When the name last spoke, 0 for never while this was on. */
    long spoke(String name) {
        synchronized (mSpoke) {
            final Long at = mSpoke.get(name);
            return at == null ? 0 : at;
        }
    }

    String lastFrom() {
        return mLast.has() ? mLast.from() : null;
    }

    boolean playingAgain() {
        return mPlayingAgain;
    }

    void levels(float[] out) {
        synchronized (mLevels) {
            System.arraycopy(mLevels, 0, out, 0, Math.min(out.length, mLevels.length));
        }
    }

    private void level(short[] pcm) {
        long sum = 0;
        for (int i = 0; i < FRAME; i++) {
            sum += (long) pcm[i] * pcm[i];
        }
        final float level = (float) Math.min(1, Math.sqrt(sum / (double) FRAME) / 9000);
        synchronized (mLevels) {
            System.arraycopy(mLevels, 1, mLevels, 0, mLevels.length - 1);
            mLevels[mLevels.length - 1] = level;
        }
    }

    // ------------------------------------------------------------------ hearing

    private static AudioTrack track() {
        return new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                .setBufferSizeInBytes(Math.max(FRAME * 2 * 4, AudioTrack.getMinBufferSize(RATE,
                        AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
    }

    @Override
    public void onPeers() {
        changed();
    }

    @Override
    public void onTalkStart(long id, String name) {
        final JitterBuffer incoming = mRadio.incoming();
        if (incoming == null || mTalking) {
            // Talking ourselves: what the other said meanwhile is not played over our own voice
            return;
        }
        final String from = name == null || name.isEmpty() ? "r1" : name;
        mSpeaking = from;
        // Aloud also from a pocket: the r1 stays awake for as long as the message runs
        mWake.acquire(90_000);
        changed();
        new Thread(() -> {
            final short[] pcm = new short[FRAME];
            final AudioTrack track = track();
            mLast.start(from, System.currentTimeMillis());
            track.play();
            long quiet = 0;
            while (sRunning == this && !incoming.done() && quiet < 2000) {
                if (incoming.take(pcm)) {
                    quiet = 0;
                    track.write(pcm, 0, FRAME);
                    mLast.add(pcm);
                    level(pcm);
                    changed();
                } else {
                    quiet += 10;
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            mLast.end();
            track.stop();
            track.release();
            synchronized (mSpoke) {
                mSpoke.put(from, System.currentTimeMillis());
            }
            mSpeaking = null;
            if (mWake.isHeld()) {
                mWake.release();
            }
            changed();
        }, "walkie-hear").start();
    }

    @Override
    public void onTalkEnd(long id, int frames, int lost) {
    }

    /** The last message once more. */
    void again() {
        if (!mLast.has() || mPlayingAgain || mSpeaking != null || mTalking) {
            return;
        }
        mPlayingAgain = true;
        changed();
        new Thread(() -> {
            final short[] pcm = new short[FRAME];
            final AudioTrack track = track();
            track.play();
            for (int i = 0; sRunning == this && mSpeaking == null && !mTalking
                    && mLast.read(i, pcm) == 1; i++) {
                track.write(pcm, 0, FRAME);
            }
            track.stop();
            track.release();
            mPlayingAgain = false;
            changed();
        }, "walkie-again").start();
    }

    // ------------------------------------------------------------------ talking

    /** False when the microphone could not be had. */
    boolean talk() {
        if (mTalking || !mOnline) {
            return mTalking;
        }
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(FRAME * 2 * 4, AudioRecord.getMinBufferSize(RATE,
                            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)));
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            return false;
        }
        mTalking = true;
        mTalkSince = System.currentTimeMillis();
        mRadio.talkStart();
        new Thread(() -> {
            final short[] pcm = new short[FRAME];
            record.startRecording();
            while (mTalking) {
                int have = 0;
                while (have < FRAME) {
                    final int n = record.read(pcm, have, FRAME - have);
                    if (n <= 0) {
                        mTalking = false;
                        break;
                    }
                    have += n;
                }
                if (have == FRAME) {
                    mRadio.talkFrame(pcm);
                }
            }
            mRadio.talkEnd();
            record.stop();
            record.release();
            changed();
        }, "walkie-talk").start();
        changed();
        return true;
    }

    void talkEnd() {
        mTalking = false;
    }
}
