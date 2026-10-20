package cat.kenny.r1.perfoverlay;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.TextView;

import java.util.Locale;

/**
 * Draws the overlay: a small non-focusable, non-touchable system window in a screen corner,
 * so it never takes buttons away from the game. Updates once a second while the screen is
 * on and stops completely while it is off. The readings are taken on a worker thread,
 * reading SurfaceFlinger's dumps can take a moment; only the text is set on the main thread.
 */
public class HudService extends Service
        implements SharedPreferences.OnSharedPreferenceChangeListener {

    static final String ACTION_STOP = "cat.kenny.r1.perfoverlay.action.STOP";

    private static final String CHANNEL = "overlay";
    private static final int NOTIFICATION_ID = 1;
    private static final long INTERVAL_MS = 1000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private HandlerThread mWorker;
    private Handler mWorkerHandler;
    private Stats mStats;

    private WindowManager mWindowManager;
    private TextView mView;
    private boolean mTicking;
    private CharSequence mShown;

    private int mLabelColor;
    private int mValueColor;
    private int mGoodColor;
    private int mOkColor;
    private int mBadColor;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            update();
            mWorkerHandler.postDelayed(this, INTERVAL_MS);
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            setTicking(Intent.ACTION_SCREEN_ON.equals(intent.getAction()));
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        mStats = new Stats(this);
        mWorker = new HandlerThread("PerfOverlay");
        mWorker.start();
        mWorkerHandler = new Handler(mWorker.getLooper());

        mLabelColor = getColor(R.color.hud_label);
        mValueColor = getColor(R.color.hud_value);
        mGoodColor = getColor(R.color.hud_good);
        mOkColor = getColor(R.color.hud_ok);
        mBadColor = getColor(R.color.hud_bad);

        mWindowManager = getSystemService(WindowManager.class);
        mView = new TextView(this);
        mView.setBackgroundResource(R.drawable.hud_background);
        mView.setTypeface(Typeface.create(Typeface.MONOSPACE, Typeface.BOLD));
        mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        mView.setIncludeFontPadding(false);
        mView.setShadowLayer(2, 1, 1, 0xFF000000);
        final int padX = dp(5);
        final int padY = dp(3);
        mView.setPadding(padX, padY, padX, padY);
        mWindowManager.addView(mView, layoutParams());

        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        Hud.prefs(this).registerOnSharedPreferenceChangeListener(this);

        setTicking(getSystemService(PowerManager.class).isInteractive());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            // "Turn off" in the notification
            Hud.setEnabled(this, false);
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        setTicking(false);
        Hud.prefs(this).unregisterOnSharedPreferenceChangeListener(this);
        unregisterReceiver(mScreenReceiver);
        mWindowManager.removeViewImmediate(mView);
        mWorker.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (Hud.KEY_POSITION.equals(key)) {
            mWindowManager.updateViewLayout(mView, layoutParams());
        } else if (Hud.KEY_STYLE.equals(key) && mTicking) {
            // Show the new style right away
            mWorkerHandler.removeCallbacks(mTick);
            mWorkerHandler.post(mTick);
        }
    }

    private void setTicking(boolean ticking) {
        if (ticking == mTicking) {
            return;
        }
        mTicking = ticking;
        mWorkerHandler.removeCallbacks(mTick);
        if (ticking) {
            // A first sample now, so the second one a second later has something to compare to.
            mWorkerHandler.post(() -> mStats.reset());
            mWorkerHandler.post(mTick);
        }
    }

    /** On the worker thread: takes the readings and hands the text to the main thread. */
    private void update() {
        final String style = Hud.getStyle(this);
        mStats.sample(!Hud.STYLE_FPS.equals(style));
        final CharSequence text = format(style);
        mHandler.post(() -> {
            // Only redraw on changes, every redraw is a frame SurfaceFlinger has to put up.
            if (mTicking && !TextUtils.equals(text, mShown)) {
                mShown = text;
                mView.setText(text);
            }
        });
    }

    private CharSequence format(String style) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        final Stats s = mStats;
        final int fpsColor = s.fps < 0 ? mValueColor
                : s.fps >= 50 ? mGoodColor : s.fps >= 25 ? mOkColor : mBadColor;
        final String fps = s.fps < 0 ? "--" : String.valueOf(s.fps);
        if (Hud.STYLE_FPS.equals(style)) {
            append(out, fps, fpsColor);
            append(out, " " + getString(R.string.hud_fps), mLabelColor);
            return out;
        }
        final boolean full = Hud.STYLE_FULL.equals(style);

        label(out, R.string.hud_fps);
        append(out, pad(fps, 3), fpsColor);

        label(out, R.string.hud_cpu);
        append(out, s.cpuPercent < 0 ? "--" : pad(s.cpuPercent + "%", 4), mValueColor);
        if (full && s.cpuMhz > 0) {
            append(out, String.format(Locale.US, " %.2fGHz", s.cpuMhz / 1000f), mValueColor);
        }
        if (full && s.gpuMhz > 0) {
            label(out, R.string.hud_gpu);
            append(out, s.gpuMhz + "MHz", mValueColor);
        }
        if (full) {
            out.append('\n');
        }

        if (!Float.isNaN(s.tempC)) {
            label(out, R.string.hud_temp, full);
            append(out, Math.round(s.tempC) + "°C", s.tempC >= 75 ? mBadColor
                    : s.tempC >= 65 ? mOkColor : mValueColor);
        }
        if (s.batteryPercent >= 0) {
            label(out, R.string.hud_bat, full && Float.isNaN(s.tempC));
            append(out, s.batteryPercent + "%", s.charging ? mGoodColor
                    : s.batteryPercent <= 15 ? mBadColor : mValueColor);
            if (full && s.plugged && !s.charging) {
                // The charger runs the r1, the battery only adds what it can't
                append(out, Float.isNaN(s.batteryWatts) || s.batteryWatts < 0.05f ? " USB"
                        : String.format(Locale.US, " USB -%.1fW", s.batteryWatts), mValueColor);
            } else if (full && !Float.isNaN(s.batteryWatts)) {
                append(out, String.format(Locale.US, " %s%.1fW",
                        s.charging ? "+" : "-", s.batteryWatts), mValueColor);
            }
        }
        if (full) {
            if (s.ramPercent >= 0) {
                label(out, R.string.hud_ram);
                append(out, s.ramPercent + "%", s.ramPercent >= 90 ? mOkColor : mValueColor);
                if (s.memMhz > 0) {
                    append(out, " " + s.memMhz + "MHz", mValueColor);
                }
            }
            if (s.storagePercent >= 0) {
                label(out, R.string.hud_storage);
                append(out, s.storagePercent + "%", s.storagePercent >= 90 ? mOkColor
                        : mValueColor);
            }
            append(out, "  " + DateFormat.getTimeFormat(this).format(
                    System.currentTimeMillis()), mLabelColor);
        }
        return out;
    }

    private void label(SpannableStringBuilder out, int label) {
        label(out, label, out.length() == 0);
    }

    /** A label, with a gap before it unless it starts a line. */
    private void label(SpannableStringBuilder out, int label, boolean first) {
        append(out, (first ? "" : "  ") + getString(label) + " ", mLabelColor);
    }

    private static void append(SpannableStringBuilder out, String text, int color) {
        final int start = out.length();
        out.append(text);
        out.setSpan(new ForegroundColorSpan(color), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** Right-aligns short numbers so the rest of the line doesn't jump around. */
    private static String pad(String text, int width) {
        final StringBuilder padded = new StringBuilder();
        for (int i = text.length(); i < width; i++) {
            padded.append(' ');
        }
        return padded.append(text).toString();
    }

    private WindowManager.LayoutParams layoutParams() {
        final WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                // A system overlay, so it stays on top of the shade and of screens that hide
                // app overlays, and doesn't trigger the "displaying over other apps" notice.
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lp.setTitle("PerfOverlay");
        // Clear of the status bar and the navigation handle while they show, at the very edge
        // when a game hides them
        lp.setFitInsetsTypes(WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars());
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        final String position = Hud.getPosition(this);
        lp.gravity = (position.startsWith("bottom") ? Gravity.BOTTOM : Gravity.TOP)
                | (position.endsWith("right") ? Gravity.RIGHT : Gravity.LEFT);
        lp.x = dp(4);
        lp.y = dp(4);
        return lp;
    }

    private Notification buildNotification() {
        final NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW));
        final PendingIntent open = PendingIntent.getActivity(this, 0,
                new Intent(this, OverlayActivity.class), PendingIntent.FLAG_IMMUTABLE);
        final PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, HudService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_perf_overlay)
                .setContentTitle(getString(R.string.notification_title))
                .setContentText(getString(R.string.notification_text))
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null,
                        getString(R.string.notification_turn_off), stop).build())
                .setOngoing(true)
                .setShowWhen(false)
                .build();
    }

    private int dp(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
