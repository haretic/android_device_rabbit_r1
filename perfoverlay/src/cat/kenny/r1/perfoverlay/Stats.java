package cat.kenny.r1.perfoverlay;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.BatteryManager;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.NoSuchElementException;

/**
 * Reads what the overlay shows. Everything comes from sysfs and procfs, except the battery
 * (BatteryManager) and the frame rate. That is the app in front's own frame rate (see
 * AppFrames), or where there is no app to measure and SurfaceFlinger still has it, how often
 * the screen changes (without it, the home screen is measured as an app): SurfaceFlinger counts every frame it puts on the screen (its "page
 * flips") and hands the count out to the system user through debug transaction 1013.
 * Runs on the overlay's worker thread, never the main thread.
 */
final class Stats {

    private static final String TAG = "PerfOverlay";

    private static final String SF_DESCRIPTOR = "android.ui.ISurfaceComposer";
    private static final int SF_GET_PAGE_FLIP_COUNT = 1013;

    private static final String CPU_FREQ_DIR = "/sys/devices/system/cpu/cpufreq";
    /** The DRAM clock DVFSRC runs, in Hz. */
    private static final String MEM_FREQ = "/sys/class/devfreq/10012000.dvfsrc:dram/cur_freq";
    /** The PowerVR GPU's clock, scaled by its devfreq, in Hz. */
    private static final String GPU_FREQ = "/sys/class/devfreq/13000000.gpu/cur_freq";

    private static final String STORAGE_STAT = "/sys/block/mmcblk0/stat";

    /** -1 or NaN where a value couldn't be read. */
    int fps = -1;
    /** Screen updates per second, whatever changed on screen. */
    int screenFps = -1;
    int cpuPercent = -1;
    int cpuMhz = -1;
    int gpuMhz = -1;
    int memMhz = -1;
    float tempC = Float.NaN;
    int batteryPercent = -1;
    float batteryWatts = Float.NaN;
    boolean charging;
    /** On a charger; without charging it runs the r1, the battery adds what's left. */
    boolean plugged;
    int ramPercent = -1;
    /** How busy the eMMC was since the last sample, 0..100. */
    int storagePercent = -1;

    private final String[] mCpuFreqPaths = findCpuFreqs();
    private final String mTempPath = findTemperature();

    private final Context mContext;
    private final BatteryManager mBatteryManager;
    private final TaskFps mTaskFps;
    private final AppFrames mAppFrames;
    private IBinder mSurfaceFlinger;
    /** SurfaceFlinger refused the page flip count: newer ones no longer have it. */
    private boolean mNoPageFlips;
    private long mLastFlips = -1;
    private long mLastFlipTime;
    private long mLastCpuBusy = -1;
    private long mLastCpuTotal;
    private long mLastIoTicks = -1;
    private long mLastIoTime;

    Stats(Context context) {
        mContext = context;
        mBatteryManager = context.getSystemService(BatteryManager.class);
        mTaskFps = new TaskFps(context);
        mAppFrames = new AppFrames(context);
    }

    /** Forget the last sample, e.g. after the screen was off. */
    void reset() {
        mTaskFps.stop();
        mAppFrames.reset();
        mLastFlips = -1;
        mLastCpuBusy = -1;
        mLastIoTicks = -1;
        storagePercent = -1;
        fps = -1;
        screenFps = -1;
        cpuPercent = -1;
    }

    void sample(boolean everything) {
        sampleFps();
        if (!everything) {
            return;
        }
        sampleCpu();
        // The fastest cluster's clock
        cpuMhz = -1;
        for (String path : mCpuFreqPaths) {
            cpuMhz = Math.max(cpuMhz, readInt(path, 1000));
        }
        memMhz = readInt(MEM_FREQ, 1000000);
        gpuMhz = readInt(GPU_FREQ, 1000000);
        final int milliC = mTempPath == null ? Integer.MIN_VALUE : readInt(mTempPath, 1);
        tempC = milliC == Integer.MIN_VALUE || milliC < 0 ? Float.NaN : milliC / 1000f;
        sampleBattery();
        sampleRam();
        sampleStorage();
    }

    private void sampleFps() {
        // SurfaceFlinger's count for the app's task, else its layer's frames, else the screen's
        final int task = mTaskFps.sample();
        if (task >= 0) {
            fps = task;
            if (Log.isLoggable(TAG, Log.DEBUG)) {
                Log.d(TAG, "fps: task " + task);
            }
            return;
        }
        sampleScreenFps();
        final int app = mAppFrames.sample();
        fps = app >= 0 ? app : screenFps;
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "fps: app " + app + " (" + mAppFrames.layer() + "), screen " + screenFps);
        }
    }

    private void sampleScreenFps() {
        final long flips = readPageFlips();
        final long now = SystemClock.elapsedRealtime();
        if (flips < 0) {
            screenFps = -1;
            return;
        }
        if (mLastFlips >= 0 && now > mLastFlipTime) {
            // The counter is a uint32 that may wrap.
            final long frames = (flips - mLastFlips) & 0xffffffffL;
            screenFps = Math.round(frames * 1000f / (now - mLastFlipTime));
        }
        mLastFlips = flips;
        mLastFlipTime = now;
    }

    private long readPageFlips() {
        if (mNoPageFlips) {
            return -1;
        }
        if (mSurfaceFlinger == null) {
            mSurfaceFlinger = ServiceManager.getService("SurfaceFlinger");
            if (mSurfaceFlinger == null) {
                return -1;
            }
        }
        final Parcel data = Parcel.obtain();
        final Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(SF_DESCRIPTOR);
            if (!mSurfaceFlinger.transact(SF_GET_PAGE_FLIP_COUNT, data, reply, 0)) {
                return -1;
            }
            return reply.readInt() & 0xffffffffL;
        } catch (NoSuchElementException e) {
            // NAME_NOT_FOUND: the transaction is gone (Android 17), the apps' own frames are
            // all there is, the home screen's among them
            Log.i(TAG, "SurfaceFlinger has no page flip count, only app frame rates");
            mNoPageFlips = true;
            mAppFrames.setMeasureHome(true);
            return -1;
        } catch (RemoteException | SecurityException e) {
            Log.w(TAG, "Couldn't read SurfaceFlinger's frame count", e);
            mSurfaceFlinger = null;
            return -1;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /** Busy share of all cores since the last sample, from the first line of /proc/stat. */
    private void sampleCpu() {
        final String stat = read("/proc/stat");
        if (stat == null || !stat.startsWith("cpu ")) {
            cpuPercent = -1;
            return;
        }
        final String[] fields = stat.substring(0, stat.indexOf('\n')).trim().split("\\s+");
        long total = 0;
        long idle = 0;
        // user nice system idle iowait irq softirq steal
        for (int i = 1; i < fields.length && i <= 8; i++) {
            final long value = Long.parseLong(fields[i]);
            total += value;
            if (i == 4 || i == 5) {
                idle += value;
            }
        }
        final long busy = total - idle;
        if (mLastCpuBusy >= 0 && total > mLastCpuTotal) {
            cpuPercent = (int) (100 * (busy - mLastCpuBusy) / (total - mLastCpuTotal));
        }
        mLastCpuBusy = busy;
        mLastCpuTotal = total;
    }

    /**
     * How much of the time since the last sample the eMMC was busy, like iostat's %util:
     * field 10 of its stat file (io_ticks, milliseconds spent doing I/O) over the wall clock.
     */
    private void sampleStorage() {
        final String stat = read(STORAGE_STAT);
        final String[] fields = stat == null ? new String[0] : stat.split("\\s+");
        long ticks = -1;
        if (fields.length > 9) {
            try {
                ticks = Long.parseLong(fields[9]);
            } catch (NumberFormatException e) {
                // Stays unknown
            }
        }
        if (ticks < 0) {
            storagePercent = -1;
            mLastIoTicks = -1;
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (mLastIoTicks >= 0 && now > mLastIoTime && ticks >= mLastIoTicks) {
            storagePercent = (int) Math.max(0, Math.min(100,
                    100 * (ticks - mLastIoTicks) / (now - mLastIoTime)));
        }
        mLastIoTicks = ticks;
        mLastIoTime = now;
    }

    private void sampleBattery() {
        // Sticky, so this only reads the last broadcast
        final Intent battery = mContext.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED);
        if (battery == null) {
            batteryPercent = -1;
            batteryWatts = Float.NaN;
            return;
        }
        final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        batteryPercent = level < 0 || scale <= 0 ? -1 : 100 * level / scale;
        final int status = battery.getIntExtra(BatteryManager.EXTRA_STATUS,
                BatteryManager.BATTERY_STATUS_UNKNOWN);
        plugged = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                || status == BatteryManager.BATTERY_STATUS_FULL;
        final int milliVolts = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
        final int microAmps = mBatteryManager.getIntProperty(
                BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
        if (microAmps == Integer.MIN_VALUE || milliVolts <= 0) {
            batteryWatts = Float.NaN;
        } else {
            batteryWatts = Math.abs(microAmps / 1e6f * milliVolts / 1e3f);
        }
    }

    private void sampleRam() {
        final String meminfo = read("/proc/meminfo");
        final long total = meminfoKb(meminfo, "MemTotal:");
        final long available = meminfoKb(meminfo, "MemAvailable:");
        ramPercent = total > 0 && available >= 0 ? (int) (100 * (total - available) / total) : -1;
    }

    private static long meminfoKb(String meminfo, String key) {
        if (meminfo == null) {
            return -1;
        }
        final int start = meminfo.indexOf(key);
        if (start < 0) {
            return -1;
        }
        final int end = meminfo.indexOf('\n', start);
        final String line = meminfo.substring(start + key.length(),
                end < 0 ? meminfo.length() : end).trim();
        try {
            return Long.parseLong(line.split("\\s+")[0]);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** scaling_cur_freq of every cpufreq policy, one per cluster. */
    private static String[] findCpuFreqs() {
        final File[] policies = new File(CPU_FREQ_DIR).listFiles(
                (dir, name) -> name.startsWith("policy"));
        if (policies == null) {
            return new String[0];
        }
        final String[] paths = new String[policies.length];
        for (int i = 0; i < policies.length; i++) {
            paths[i] = policies[i].getPath() + "/scaling_cur_freq";
        }
        return paths;
    }

    /** The SoC's thermal zone, or the first one there is. */
    private static String findTemperature() {
        final File[] zones = new File("/sys/class/thermal").listFiles(
                (dir, name) -> name.startsWith("thermal_zone"));
        if (zones == null || zones.length == 0) {
            return null;
        }
        for (File zone : zones) {
            final String type = read(zone.getPath() + "/type");
            if (type != null && type.contains("soc")) {
                return zone.getPath() + "/temp";
            }
        }
        return zones[0].getPath() + "/temp";
    }

    /** The number in the file divided by {@code divisor}, MIN_VALUE if unreadable. */
    private static int readInt(String path, int divisor) {
        final String text = read(path);
        if (text == null) {
            return Integer.MIN_VALUE;
        }
        try {
            return (int) (Long.parseLong(text) / divisor);
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String read(String path) {
        try {
            return new String(Files.readAllBytes(Paths.get(path)), StandardCharsets.UTF_8).trim();
        } catch (IOException | SecurityException e) {
            return null;
        }
    }
}
