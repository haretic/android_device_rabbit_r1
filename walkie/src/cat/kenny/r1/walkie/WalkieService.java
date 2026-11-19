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
    /** The settings, in the service's own preferences, and what each is until it is changed */
    static final String TALK_SOUNDS = "talk_sounds", CHIME = "chime",
            ALOUD_CLOSED = "aloud_closed", STAY_ON = "stay_on";
    static final boolean TALK_SOUNDS_DEFAULT = false, CHIME_DEFAULT = false,
            ALOUD_CLOSED_DEFAULT = true, STAY_ON_DEFAULT = true;
    /** One frame of voice lasts this long */
    private static final long FRAME_MS = 1000L * FRAME / RATE;

    private static volatile WalkieService sRunning;
    /** Held while the notification is posted, so onDestroy's cancel comes after the last one */
    private static final Object sNote = new Object();
    private static Runnable sListener;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    /** How many received messages stay to be heard again, newest first */
    static final int RECENT = 5;
    /** A message as it was heard: its voice, kept up to a minute, and the channel it came on */
    private static final class Heard {
        final Recorder audio = new Recorder(60);
        volatile int channel;
    }

    /** One received message, for the list of recent ones: what the page shows of it. */
    static final class Message {
        /** Hands back to again(Object): the message itself, not where it is in the list */
        final Object key;
        final String from;
        final long when;
        final int channel;
        final int millis;
        final boolean playing;

        Message(Object key, String from, long when, int channel, int millis, boolean playing) {
            this.key = key;
            this.from = from;
            this.when = when;
            this.channel = channel;
            this.millis = millis;
            this.playing = playing;
        }
    }

    /** The last few messages heard, newest first; each one's voice is reused once it is old */
    private final java.util.ArrayDeque<Heard> mRecent = new java.util.ArrayDeque<>();
    /** The one playing again, null for none */
    private volatile Heard mAgain;
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
    /** Who left a message that was kept quiet, the page being closed; null when none waits */
    private volatile String mWaiting;
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

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("walkie", MODE_PRIVATE);
    }

    private boolean setting(String key, boolean otherwise) {
        return mPrefs.getBoolean(key, otherwise);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = prefs(this);
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
        synchronized (sNote) {
            sRunning = null;
        }
        // A message that ended as it went off may have posted it again after the system took it
        getSystemService(NotificationManager.class).cancel(1);
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
                .setContentText(mWaiting != null ? getString(R.string.note_waiting, mWaiting)
                        : getString(R.string.note_text))
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

    /** The newest message with any voice in it, null for none. */
    private Heard last() {
        synchronized (mRecent) {
            for (Heard heard : mRecent) {
                if (heard.audio.has()) {
                    return heard;
                }
            }
            return null;
        }
    }

    String lastFrom() {
        final Heard last = last();
        return last == null ? null : last.audio.from();
    }

    boolean playingAgain() {
        return mPlayingAgain;
    }

    /** The messages still kept, newest first, for the list of recent ones. */
    java.util.List<Message> recent() {
        final java.util.List<Message> out = new java.util.ArrayList<>();
        synchronized (mRecent) {
            for (Heard heard : mRecent) {
                if (heard.audio.has()) {
                    out.add(new Message(heard, heard.audio.from(), heard.audio.when(),
                            heard.channel, heard.audio.millis(), heard == mAgain));
                }
            }
        }
        return out;
    }

    /** A fresh voice for a message to come while fewer than enough are kept, else null. */
    private Heard spare() {
        synchronized (mRecent) {
            return mRecent.size() >= RECENT ? null : new Heard();
        }
    }

    /**
     * A message's voice to record into, taken as its first voice arrives so a message that
     * never brings any keeps the older ones: the spare while there is room, else the oldest.
     */
    private Heard heard(int channel, Heard spare) {
        synchronized (mRecent) {
            final Heard heard = mRecent.size() >= RECENT ? mRecent.removeLast()
                    : spare != null ? spare : new Heard();
            heard.channel = channel;
            mRecent.addFirst(heard);
            return heard;
        }
    }

    /** Who left the message kept quiet while the page was closed, null for none. */
    String waiting() {
        return mWaiting;
    }

    private void waiting(String from) {
        // A voice thread outliving the service must not bring back an "on" with nothing behind it
        synchronized (sNote) {
            if (sRunning != this) {
                return;
            }
            mWaiting = from;
            getSystemService(NotificationManager.class).notify(1, note());
        }
    }

    /** A short tone, rising or falling, fading in and out: about a tenth of a second. */
    private static short[] tone(float from, float to, int millis, float level) {
        final int frames = RATE * millis / 1000;
        final short[] pcm = new short[frames];
        double phase = 0;
        for (int i = 0; i < frames; i++) {
            final double t = (double) i / frames;
            final double hz = from + (to - from) * t;
            final double envelope = Math.sin(Math.PI * t);
            pcm[i] = (short) Math.round(Math.sin(phase) * envelope * level * 32767);
            phase += 2 * Math.PI * hz / RATE;
        }
        return pcm;
    }

    /** The beep as talking starts, rising, or ends, falling; where the settings want it. */
    private void beep(boolean start) {
        if (!setting(TALK_SOUNDS, TALK_SOUNDS_DEFAULT)) {
            return;
        }
        new Thread(() -> {
            final AudioTrack track;
            try {
                track = track();
            } catch (IllegalArgumentException | UnsupportedOperationException e) {
                return;
            }
            final short[] pcm = start ? tone(880, 1320, 90, 0.35f) : tone(1100, 700, 110, 0.35f);
            track.play();
            track.write(pcm, 0, pcm.length);
            // Silence after it, so the tone has played out before the track stops
            final short[] quiet = new short[RATE / 5];
            track.write(quiet, 0, quiet.length);
            track.stop();
            track.release();
        }, "walkie-beep").start();
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
        // With the page closed a message may wait for a press instead of playing aloud
        final boolean aloud = sListener != null
                || setting(ALOUD_CLOSED, ALOUD_CLOSED_DEFAULT);
        final boolean chime = aloud && setting(CHIME, CHIME_DEFAULT);
        mSpeaking = from;
        final int channel = mRadio.channel();
        // Aloud also from a pocket: the r1 stays awake for as long as the message runs
        mWake.acquire(90_000);
        changed();
        new Thread(() -> {
            final short[] pcm = new short[FRAME];
            final AudioTrack track = track();
            final long when = System.currentTimeMillis();
            // Made now, not at the first voice, so that frame is not held up by the allocation
            final Heard spare = spare();
            Heard heard = null;
            track.play();
            if (chime) {
                // Two notes up before the voice, for a message heard from a pocket
                final short[] first = tone(988, 988, 90, 0.3f);
                final short[] second = tone(1319, 1319, 120, 0.3f);
                track.write(first, 0, first.length);
                track.write(second, 0, second.length);
            }
            long quiet = 0;
            // Kept quiet, the voice is taken at its own pace, as the speaker would take it
            long due = System.nanoTime();
            while (sRunning == this && !incoming.done() && quiet < 2000) {
                if (!aloud) {
                    final long wait = (due - System.nanoTime()) / 1_000_000;
                    if (wait > 0) {
                        try {
                            Thread.sleep(wait);
                        } catch (InterruptedException e) {
                            break;
                        }
                    }
                }
                if (incoming.take(pcm)) {
                    if (heard == null) {
                        // Its first voice: only now does it take a slot, mSpeaking having
                        // already stopped any replay of the one it reuses
                        heard = heard(channel, spare);
                        heard.audio.start(from, when);
                        // The replay now belongs to this message, including its waiting marker.
                        if (mWaiting != null) {
                            waiting(null);
                        }
                    }
                    quiet = 0;
                    due += FRAME_MS * 1_000_000;
                    if (aloud) {
                        track.write(pcm, 0, FRAME);
                    }
                    heard.audio.add(pcm);
                    level(pcm);
                    changed();
                } else {
                    quiet += 10;
                    due = System.nanoTime();
                    try {
                        Thread.sleep(10);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
            if (heard != null) {
                heard.audio.end();
            }
            if (!aloud && heard != null && heard.audio.has() && sRunning == this) {
                waiting(from);
            }
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
        final Heard last = last();
        if (last != null) {
            again(last);
        }
    }

    /** One of the recent messages once more, by the key its Message has. */
    void again(Object key) {
        final Heard heard;
        synchronized (mRecent) {
            heard = key instanceof Heard h && mRecent.contains(h) ? h : null;
        }
        if (heard == null || !heard.audio.has() || mPlayingAgain || mSpeaking != null
                || mTalking) {
            return;
        }
        mPlayingAgain = true;
        mAgain = heard;
        if (mWaiting != null) {
            waiting(null);
        }
        changed();
        new Thread(() -> {
            final short[] pcm = new short[FRAME];
            final AudioTrack track = track();
            track.play();
            for (int i = 0; sRunning == this && mSpeaking == null && !mTalking
                    && heard.audio.read(i, pcm) == 1; i++) {
                track.write(pcm, 0, FRAME);
            }
            track.stop();
            track.release();
            mAgain = null;
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
        beep(true);
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
            beep(false);
            changed();
        }, "walkie-talk").start();
        changed();
        return true;
    }

    void talkEnd() {
        mTalking = false;
    }
}
