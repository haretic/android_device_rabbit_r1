package cat.kenny.r1.perfoverlay;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The frame rate of the app in front, counted the way rdevz-ph's FPS-Meter-Android does in
 * its SurfaceFlinger mode: from the frames SurfaceFlinger put on the screen for the layer
 * the app draws into ("dumpsys SurfaceFlinger --latency <layer>"). SurfaceFlinger's own
 * page flips count every screen update of any layer, which is not the game's frame rate.
 * The system user may read these dumps (it holds DUMP). Runs on the overlay's worker
 * thread; every dump is time-boxed, because SurfaceFlinger can be busy.
 */
final class AppFrames {

    private static final String TAG = "PerfOverlay";

    private static final long DUMP_TIMEOUT_MS = 300;
    private static final long SECOND_NS = 1_000_000_000L;
    /** Most layers looked at when picking the one the app draws into. */
    private static final int MAX_CANDIDATES = 6;
    /** Samples without a frame before the layer is picked again (the app may have
     *  switched to another surface). */
    private static final int IDLE_SAMPLES = 3;

    private final Context mContext;
    private final String mHome;
    private IBinder mSurfaceFlinger;
    private String mPackage;
    private String mLayer;
    /** When the last sample was taken, 0 before the first one of a layer. */
    private long mLastNanos;
    /** The newest frame counted so far. */
    private long mLastPresent;
    private int mIdle;
    private int mLogCount;
    private static final int LOG_EVERY = 10;

    AppFrames(Context context) {
        mContext = context;
        mHome = homePackage(context);
    }

    /** Start over, e.g. after the screen was off. */
    void reset() {
        mPackage = null;
        mLayer = null;
        mLastNanos = 0;
        mLastPresent = 0;
        mIdle = 0;
    }

    /** The layer measured last, for the log. */
    String layer() {
        return mLayer;
    }

    /**
     * Frames per second of the app in front since the last call, or -1 when there is no app
     * to measure (home screen, nothing in front, no layer found) or the dump failed.
     */
    int sample() {
        final long now = System.nanoTime();
        final String pkg = foregroundPackage();
        if (pkg == null || pkg.equals(mHome) || pkg.equals(mContext.getPackageName())) {
            reset();
            return -1;
        }
        if (!pkg.equals(mPackage) || mLayer == null || mIdle >= IDLE_SAMPLES) {
            final String before = mLayer;
            mPackage = pkg;
            mLayer = pickLayer(pkg, now);
            if (mLayer != null && !mLayer.equals(before)) {
                Log.i(TAG, "Frame rate of " + pkg + " from layer " + mLayer);
            }
            mIdle = 0;
            mLastNanos = 0;
            mLastPresent = 0;
            if (mLayer == null) {
                return -1;
            }
        }
        final String latency = dump("--latency", mLayer);
        if (latency == null) {
            return -1;
        }
        if (!LayerFrames.hasRecords(latency)) {
            // The layer is gone, e.g. a new activity: pick again next time
            mLayer = null;
            return -1;
        }
        final long[] presents = LayerFrames.presentTimes(latency);
        final int fps;
        if (mLastNanos == 0) {
            // The first sample of a layer looks one second back
            fps = LayerFrames.countBetween(presents, now - SECOND_NS, now);
        } else {
            fps = LayerFrames.perSecond(LayerFrames.countAfter(presents, mLastPresent),
                    now - mLastNanos);
        }
        if (++mLogCount % LOG_EVERY == 0) {
            // A line every 10 s, so a problem report shows what the number is made of
            Log.i(TAG, "fps " + fps + " for " + pkg + " from " + mLayer + ": " + presents.length
                    + " frames with a present time, " + LayerFrames.pending(latency)
                    + " pending, period " + latency.split("\n", 2)[0].trim()
                    + ", newest " + (LayerFrames.newest(presents, 0) - now) / 1_000_000
                    + " ms from now, last sample " + (mLastNanos == 0 ? -1
                    : (now - mLastNanos) / 1_000_000) + " ms ago");
        }
        mLastPresent = LayerFrames.newest(presents, mLastPresent);
        mLastNanos = now;
        mIdle = fps == 0 ? mIdle + 1 : 0;
        return fps;
    }

    /** Of the app's layers, the one that put the most frames on screen in the last second. */
    private String pickLayer(String pkg, long now) {
        final String list = dump("--list");
        if (list == null) {
            return null;
        }
        final List<String> layers = LayerFrames.appLayers(list, pkg);
        String best = null;
        int bestFrames = -1;
        for (int i = 0; i < layers.size() && i < MAX_CANDIDATES; i++) {
            final String latency = dump("--latency", layers.get(i));
            if (latency == null) {
                continue;
            }
            final int frames = LayerFrames.countBetween(LayerFrames.presentTimes(latency),
                    now - SECOND_NS, now);
            Log.i(TAG, "Layer " + layers.get(i) + ": " + frames + " frames in the last second");
            // SurfaceViews come first, so on a tie they win
            if (frames > bestFrames) {
                best = layers.get(i);
                bestFrames = frames;
            }
        }
        return best;
    }

    private String foregroundPackage() {
        final List<ActivityManager.RunningTaskInfo> tasks =
                mContext.getSystemService(ActivityManager.class).getRunningTasks(1);
        if (tasks == null || tasks.isEmpty()) {
            return null;
        }
        final ComponentName top = tasks.get(0).topActivity;
        return top == null ? null : top.getPackageName();
    }

    private static String homePackage(Context context) {
        final ResolveInfo home = context.getPackageManager().resolveActivity(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0);
        return home == null || home.activityInfo == null ? null : home.activityInfo.packageName;
    }

    /**
     * SurfaceFlinger's dump for these arguments, null if it can't be read in time. The dump
     * is one-way, so a busy SurfaceFlinger never blocks this thread for longer than the
     * timeout.
     */
    private String dump(String... args) {
        if (mSurfaceFlinger == null) {
            mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            if (mSurfaceFlinger == null) {
                return null;
            }
        }
        ParcelFileDescriptor[] pipe = null;
        try {
            pipe = ParcelFileDescriptor.createPipe();
            mSurfaceFlinger.dumpAsync(pipe[1].getFileDescriptor(), args);
            // Only SurfaceFlinger's copy of the write end is left, the end of its dump
            pipe[1].close();
            pipe[1] = null;
            return read(pipe[0].getFileDescriptor());
        } catch (IOException | RemoteException e) {
            Log.w(TAG, "Couldn't read SurfaceFlinger's " + args[0], e);
            mSurfaceFlinger = null;
            return null;
        } finally {
            if (pipe != null) {
                closeQuietly(pipe[0]);
                closeQuietly(pipe[1]);
            }
        }
    }

    private static String read(FileDescriptor fd) throws IOException {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final byte[] buffer = new byte[8192];
        final long deadline = System.currentTimeMillis() + DUMP_TIMEOUT_MS;
        final StructPollfd poll = new StructPollfd();
        poll.fd = fd;
        poll.events = (short) OsConstants.POLLIN;
        try {
            while (true) {
                final long left = deadline - System.currentTimeMillis();
                if (left <= 0) {
                    Log.w(TAG, "SurfaceFlinger's dump took too long");
                    return null;
                }
                try {
                    if (Os.poll(new StructPollfd[] {poll}, (int) left) == 0) {
                        continue;
                    }
                } catch (ErrnoException e) {
                    if (e.errno == OsConstants.EINTR) {
                        continue;
                    }
                    throw e;
                }
                final int n = Os.read(fd, buffer, 0, buffer.length);
                if (n <= 0) {
                    return out.toString(StandardCharsets.UTF_8.name());
                }
                out.write(buffer, 0, n);
            }
        } catch (ErrnoException e) {
            throw new IOException(e);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor fd) {
        if (fd != null) {
            try {
                fd.close();
            } catch (IOException e) {
                // Nothing to do
            }
        }
    }
}
