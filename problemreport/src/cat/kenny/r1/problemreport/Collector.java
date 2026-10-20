// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.app.ActivityManager;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.DropBoxManager;
import android.os.Environment;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.Settings;
import android.system.Os;
import android.system.StructUtsname;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.android.internal.os.TransferPipe;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * What the page collects itself, as the system user: the description of this r1, Android's
 * crash records (dropbox), the dumps of the system services that matter for battery,
 * devices and apps, and the list of apps. The Wi-Fi, Bluetooth, connectivity and account
 * dumps are left out: they are full of names.
 */
final class Collector {

    /** One service's dump may take this long */
    private static final long DUMP_TIMEOUT_MS = 20_000;
    /** Crash records this old and older are history */
    private static final long DROPBOX_DAYS = 7;
    private static final int DROPBOX_PER_TAG = 5;
    private static final int DROPBOX_BYTES = 65536;
    private static final String[] DROPBOX_TAGS = {
        "system_server_crash", "system_server_native_crash", "system_server_anr",
        "system_server_watchdog", "system_server_wtf", "system_app_crash",
        "system_app_native_crash", "system_app_anr", "data_app_crash", "data_app_native_crash",
        "data_app_anr", "SYSTEM_TOMBSTONE", "SYSTEM_RESTART", "SYSTEM_BOOT", "SYSTEM_AUDIT",
        "SYSTEM_FSCK",
    };
    /** The r1's own settings: Kernel Tweaks and the CPU profile (performance/) */
    private static final String[] PROPS = {
        "persist.sys.r1.kt.enabled", "persist.sys.r1.kt.paused", "persist.sys.r1.kt.mem_sleep",
        "persist.sys.r1.kt.cores", "persist.sys.r1.kt.trip", "persist.sys.r1.kt.zram",
        "sys.r1.kt.result", "sys.powerhal.held_hints",
    };

    private Collector() {
    }

    private static PrintWriter open(File dir, String name) throws IOException {
        return new PrintWriter(new OutputStreamWriter(
                new FileOutputStream(new File(dir, name), true), StandardCharsets.UTF_8));
    }

    /** summary.txt and system.txt */
    static void system(Context context, File dir, String note) throws IOException {
        final StructUtsname uname = Os.uname();
        try (PrintWriter w = open(dir, "summary.txt")) {
            w.println("r1 problem report");
            w.println("Saved:   " + ZonedDateTime.now());
            w.println("Build:   " + Build.FINGERPRINT);
            w.println("Version: " + Build.DISPLAY + ", Android " + Build.VERSION.RELEASE
                    + " (SDK " + Build.VERSION.SDK_INT + "), security patch "
                    + Build.VERSION.SECURITY_PATCH);
            w.println("Kernel:  " + uname.sysname + " " + uname.release + " " + uname.version
                    + " " + uname.machine);
            w.println("Up:      " + DateUtils.formatElapsedTime(
                    SystemClock.elapsedRealtime() / 1000) + ", awake "
                    + DateUtils.formatElapsedTime(SystemClock.uptimeMillis() / 1000));
            w.println("Last start: " + Helper.lastStart() + " (see last-start.txt)");
            w.println("Battery: " + battery(context));
            final ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            context.getSystemService(ActivityManager.class).getMemoryInfo(mi);
            w.println("Memory:  " + mib(mi.availMem) + " of " + mib(mi.totalMem) + " free"
                    + (mi.lowMemory ? ", low" : ""));
            final StatFs data = new StatFs(Environment.getDataDirectory().getPath());
            w.println("Storage: " + mib(data.getAvailableBytes()) + " of "
                    + mib(data.getTotalBytes()) + " free");
            w.println();
            w.println("What went wrong (typed by the user):");
            w.println(TextUtils.isEmpty(note) ? "(nothing typed)" : note);
        }
        try (PrintWriter w = open(dir, "system.txt")) {
            w.println("Model:    " + Build.MANUFACTURER + " " + Build.MODEL + " (" + Build.DEVICE
                    + ", " + Build.PRODUCT + ")");
            w.println("Hardware: " + Build.HARDWARE + ", board " + Build.BOARD + ", SoC "
                    + Build.SOC_MANUFACTURER + " " + Build.SOC_MODEL + ", "
                    + Runtime.getRuntime().availableProcessors() + " cores");
            w.println("Radio:    " + Build.getRadioVersion());
            w.println("Build:    " + Build.TYPE + " " + Build.TAGS + ", " + Build.ID);
            w.println();
            for (String p : PROPS) {
                w.println("[" + p + "]: [" + SystemProperties.get(p, "") + "]");
            }
            w.println();
            w.println("$ cat /proc/meminfo");
            try {
                w.println(new String(Files.readAllBytes(new File("/proc/meminfo").toPath()),
                        StandardCharsets.UTF_8));
            } catch (IOException e) {
                w.println("(" + e + ")");
            }
        }
    }

    private static String battery(Context context) {
        final Intent b = context.registerReceiver(null,
                new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b == null) return "unknown";
        final int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        final int scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        final BatteryManager bm = context.getSystemService(BatteryManager.class);
        return (scale > 0 ? level * 100 / scale : level) + "%"
                + ", status " + b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                + ", plugged " + b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)
                + ", health " + b.getIntExtra(BatteryManager.EXTRA_HEALTH, -1)
                + ", " + b.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10f + " °C"
                + ", " + b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) + " mV"
                + ", " + bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) + " µA"
                + ", cycles " + b.getIntExtra(BatteryManager.EXTRA_CYCLE_COUNT, -1);
    }

    private static String mib(long bytes) {
        return (bytes / 1048576) + " MiB";
    }

    /** dropbox.txt: the newest few crash records of each kind from the last week */
    static void dropbox(Context context, File dir) throws IOException {
        final DropBoxManager db = context.getSystemService(DropBoxManager.class);
        try (PrintWriter w = open(dir, "dropbox.txt")) {
            if (db == null) {
                w.println("(no dropbox)");
                return;
            }
            final long since = System.currentTimeMillis() - DROPBOX_DAYS * DateUtils.DAY_IN_MILLIS;
            for (String tag : DROPBOX_TAGS) {
                final Deque<String> newest = new ArrayDeque<>();
                long t = since;
                int count = 0;
                DropBoxManager.Entry e;
                while ((e = db.getNextEntry(tag, t)) != null) {
                    t = e.getTimeMillis();
                    final String text = e.getText(DROPBOX_BYTES);
                    newest.addLast("== " + tag + " at "
                            + Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault()) + "\n"
                            + (text != null ? text : "(binary)"));
                    if (newest.size() > DROPBOX_PER_TAG) newest.removeFirst();
                    count++;
                    e.close();
                }
                if (count == 0) continue;
                w.println("##### " + tag + ": " + count + " in the last week, the newest "
                        + newest.size());
                for (String s : newest) {
                    w.println(s);
                    w.println();
                }
            }
        }
    }

    /** power.txt */
    static void power(Context context, File dir) {
        final File f = new File(dir, "power.txt");
        dump(f, "battery");
        dump(f, "power");
        dump(f, "thermalservice");
        dump(f, "deviceidle");
        dump(f, "alarm");
    }

    /** devices.txt */
    static void devices(Context context, File dir) throws IOException {
        final File f = new File(dir, "devices.txt");
        try (PrintWriter w = open(dir, f.getName())) {
            final WifiManager wm = context.getSystemService(WifiManager.class);
            final BluetoothManager bm = context.getSystemService(BluetoothManager.class);
            w.println("Wi-Fi on: " + (wm != null && wm.isWifiEnabled()));
            w.println("Bluetooth on: " + (bm != null && bm.getAdapter() != null
                    && bm.getAdapter().isEnabled()));
            w.println("Airplane mode: " + Settings.Global.getInt(context.getContentResolver(),
                    Settings.Global.AIRPLANE_MODE_ON, 0));
            w.println("(the Wi-Fi and Bluetooth dumps are left out: they are full of names)");
            w.println();
        }
        dump(f, "input");
        dump(f, "display");
        dump(f, "sensorservice");
        dump(f, "audio");
        dump(f, "usb");
    }

    /** apps.txt: the installed apps, what runs, the memory they use */
    static void apps(Context context, File dir) throws IOException {
        final PackageManager pm = context.getPackageManager();
        final File f = new File(dir, "apps.txt");
        try (PrintWriter w = open(dir, f.getName())) {
            final List<PackageInfo> packages = pm.getInstalledPackages(
                    PackageManager.PackageInfoFlags.of(PackageManager.MATCH_DISABLED_COMPONENTS));
            packages.sort((a, b) -> a.packageName.compareTo(b.packageName));
            w.println("Installed apps: " + packages.size());
            for (PackageInfo p : packages) {
                final ApplicationInfo ai = p.applicationInfo;
                String kind = "user";
                if (ai != null && (ai.flags & ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0) {
                    kind = "system, updated";
                } else if (ai != null && (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0) {
                    kind = "system";
                }
                w.println(p.packageName + " " + p.versionName + " (" + p.getLongVersionCode()
                        + ") " + kind + (ai != null && !ai.enabled ? ", disabled" : ""));
            }
            w.println();
            w.println("Running:");
            final List<ActivityManager.RunningAppProcessInfo> running =
                    context.getSystemService(ActivityManager.class).getRunningAppProcesses();
            if (running != null) {
                for (ActivityManager.RunningAppProcessInfo r : running) {
                    w.println(r.pid + " " + r.processName + " importance " + r.importance);
                }
            }
            w.println();
        }
        dump(f, "activity", "activities");
        dump(f, "meminfo");
    }

    /**
     * Appends what "dumpsys <service> <args>" prints. The service writes into a pipe that
     * TransferPipe drains, so a long dump can't block it, and gives up after a while.
     */
    private static void dump(File out, String service, String... args) {
        try (FileOutputStream fos = new FileOutputStream(out, true)) {
            fos.write(("$ dumpsys " + service + (args.length > 0 ? " " : "")
                    + String.join(" ", args) + "\n").getBytes(StandardCharsets.UTF_8));
            final IBinder binder = ServiceManager.checkService(service);
            if (binder == null) {
                fos.write("(no such service)\n\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String result = "\n";
            final TransferPipe pipe = new TransferPipe();
            try {
                binder.dumpAsync(pipe.getWriteFd().getFileDescriptor(), args);
                pipe.go(fos.getFD(), DUMP_TIMEOUT_MS);
            } catch (IOException | RemoteException e) {
                result = "\n(" + e + ")\n";
            } finally {
                pipe.kill();
            }
            fos.write((result + "\n").getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            // the file: the zip then has what there is
        }
    }
}
