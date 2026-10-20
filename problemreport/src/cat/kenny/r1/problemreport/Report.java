// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Makes one report, in ReportService's thread, in 8 steps: 1 the description of this r1
 * (here), 2 to 4 the logs and crash reports (r1-problem-report.sh, see Helper), then
 * Android's crash records, 5 battery and power, 6 devices, 7 apps (Collector) and 8 the
 * .zip: every file goes through the Scrubber on its way in, and the .zip goes to
 * Download/haretic-reports through MediaStore. The page shows state().
 */
final class Report {

    private static final String TAG = "ProblemReport";
    static final int STEPS = 8;
    static final int IDLE = 0, RUNNING = 1, DONE = 2, FAILED = 3;
    static final String KEY_NOTE = "note";
    static final String FOLDER = Environment.DIRECTORY_DOWNLOADS + "/haretic-reports";

    /** The script has this long to answer, and to move on from a step */
    private static final long START_TIMEOUT_MS = 15_000;
    private static final long STEP_TIMEOUT_MS = 120_000;
    private static final long POLL_MS = 300;

    /** What the page shows. */
    static final class State {
        final int state;
        final int step;
        /** The step's name while running, the file when done, the error when failed */
        final String text;

        State(int state, int step, String text) {
            this.state = state;
            this.step = step;
            this.text = text;
        }
    }

    /** A step that failed, with the code the page shows a text for. */
    private static final class Failure extends Exception {
        final String code;

        Failure(String code) {
            super(code);
            this.code = code;
        }
    }

    private static volatile State sState = new State(IDLE, 0, "");
    /** A finished report the page hasn't told about yet */
    private static volatile boolean sNews;
    private static String sNote;

    private Report() {
    }

    static State state() {
        return sState;
    }

    /** True once after a report finished, so the page tells about it once. */
    static boolean takeNews() {
        final boolean news = sNews;
        sNews = false;
        return news;
    }

    /** Starts a report with what the user typed; false if one is being made. */
    static synchronized boolean start(Context context, String note) {
        if (sState.state == RUNNING) return false;
        if (Helper.busy()) {
            sState = new State(FAILED, 0, "busy");
            return false;
        }
        sNote = note;
        sState = new State(RUNNING, 1, "system");
        context.startService(new Intent(context, ReportService.class));
        return true;
    }

    /** In ReportService's thread. */
    static void run(Context context) {
        final File dir = new File(context.getCacheDir(), "report");
        try {
            sState = new State(DONE, 0, make(context, dir));
            // The description belongs to this report, the next one starts empty
            PreferenceManager.getDefaultSharedPreferences(context).edit()
                    .remove(KEY_NOTE).apply();
        } catch (Failure f) {
            Log.w(TAG, "report failed: " + f.code);
            sState = new State(FAILED, 0, f.code);
        } catch (Exception e) {
            Log.w(TAG, "report failed", e);
            sState = new State(FAILED, 0, "stopped");
        } finally {
            deleteTree(dir);
            Helper.clear();
            sNews = true;
        }
    }

    private static void step(int step, String name) {
        sState = new State(RUNNING, step, name);
    }

    /** Returns where the report went, as the user sees it. */
    private static String make(Context context, File dir) throws Exception {
        deleteTree(dir);
        if (!dir.mkdirs()) throw new Failure("cant_write");
        final String name = "report-"
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));

        step(1, "system");
        final Scrubber scrubber = Scrubber.forDevice(context);
        Collector.system(context, dir, sNote);

        try {
            Helper.start();
        } catch (IOException e) {
            Log.w(TAG, "can't start the collector", e);
            throw new Failure("start");
        }
        waitForHelper();

        step(4, "crashes");
        Collector.dropbox(context, dir);
        step(5, "power");
        Collector.power(context, dir);
        step(6, "devices");
        Collector.devices(context, dir);
        step(7, "apps");
        Collector.apps(context, dir);

        step(8, "saving");
        for (String p : Helper.PSTORE_FILES) {
            final File f = new File(Helper.PSTORE, p);
            if (p.startsWith("pmsg-") && f.length() > 0) {
                try (Writer w = new BufferedWriter(new OutputStreamWriter(
                        new FileOutputStream(new File(dir, "last-start-logcat.txt")),
                        StandardCharsets.UTF_8))) {
                    Pmsg.decode(f, w);
                }
                break;
            }
        }
        save(context, dir, name, scrubber);
        return FOLDER + "/" + name + ".zip";
    }

    /** Follows the script through steps 2 to 4 until it has collected. */
    private static void waitForHelper() throws Failure, InterruptedException {
        final long started = SystemClock.elapsedRealtime();
        long changed = started;
        String last = Helper.STARTING;
        while (true) {
            final String s = Helper.status();
            final long now = SystemClock.elapsedRealtime();
            if ("collected".equals(s)) return;
            if (s.startsWith("error ")) throw new Failure(s.substring("error ".length()));
            if (!s.equals(last)) {
                last = s;
                changed = now;
                // "running <step> <steps> <name>"
                final String[] parts = s.split(" ", 4);
                if (parts.length == 4 && "running".equals(parts[0])) {
                    try {
                        step(Integer.parseInt(parts[1]), parts[3]);
                    } catch (NumberFormatException e) {
                        // shown as it was
                    }
                }
            }
            if (Helper.STARTING.equals(s) && now - started > START_TIMEOUT_MS) {
                throw new Failure("start");
            }
            if (now - changed > STEP_TIMEOUT_MS) {
                Helper.abandon();
                throw new Failure("stopped");
            }
            Thread.sleep(POLL_MS);
        }
    }

    /** The .zip, through the Scrubber, into Download/haretic-reports. */
    private static void save(Context context, File dir, String name, Scrubber scrubber)
            throws Failure {
        final List<String> names = new ArrayList<>();
        final List<File> files = new ArrayList<>();
        final File work = Helper.WORK;
        add(names, files, "summary.txt", new File(dir, "summary.txt"));
        add(names, files, "system.txt", new File(dir, "system.txt"));
        add(names, files, "logcat.txt", new File(work, "logcat.txt"));
        add(names, files, "kernel.txt", new File(work, "kernel.txt"));
        add(names, files, "last-start.txt", new File(work, "last-start.txt"));
        for (String p : Helper.PSTORE_FILES) {
            final File f = new File(Helper.PSTORE, p);
            if (p.startsWith("console-")) {
                add(names, files, "last-start-kernel.txt", f);
            } else if (p.startsWith("dmesg-")) {
                add(names, files, "last-start-crash-" + p + ".txt", f);
            }
        }
        add(names, files, "last-start-logcat.txt", new File(dir, "last-start-logcat.txt"));
        add(names, files, "selinux-denials.txt", new File(work, "selinux-denials.txt"));
        add(names, files, "crashes.txt", new File(work, "crashes.txt"));
        add(names, files, "dropbox.txt", new File(dir, "dropbox.txt"));
        add(names, files, "power.txt", new File(dir, "power.txt"));
        add(names, files, "devices.txt", new File(dir, "devices.txt"));
        add(names, files, "apps.txt", new File(dir, "apps.txt"));

        final ContentResolver resolver = context.getContentResolver();
        final ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name + ".zip");
        values.put(MediaStore.MediaColumns.MIME_TYPE, "application/zip");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, FOLDER + "/");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        final Uri uri;
        try {
            uri = resolver.insert(
                    MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values);
        } catch (RuntimeException e) {
            Log.w(TAG, "can't make the file", e);
            throw new Failure("cant_write");
        }
        if (uri == null) throw new Failure("cant_write");

        try (OutputStream out = resolver.openOutputStream(uri);
                ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(out))) {
            final Writer w = new BufferedWriter(
                    new OutputStreamWriter(zip, StandardCharsets.UTF_8));
            zip.putNextEntry(new ZipEntry(name + "/README.txt"));
            try (InputStream in = context.getResources().openRawResource(R.raw.readme)) {
                in.transferTo(zip);
            }
            zip.closeEntry();
            for (int i = 0; i < files.size(); i++) {
                final FileInputStream in;
                try {
                    in = new FileInputStream(files.get(i));
                } catch (FileNotFoundException e) {
                    Log.w(TAG, "left out " + names.get(i) + ": " + e);
                    continue;
                }
                zip.putNextEntry(new ZipEntry(name + "/" + names.get(i)));
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(in, StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        w.write(scrubber.line(line));
                        w.write('\n');
                    }
                }
                w.flush();
                zip.closeEntry();
            }
            zip.finish();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "can't write the file", e);
            resolver.delete(uri, null, null);
            throw new Failure("cant_write");
        }
        values.clear();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        Log.i(TAG, "saved " + FOLDER + "/" + name + ".zip");
    }

    /** Only files with something in them; the first of a name wins. */
    private static void add(List<String> names, List<File> files, String name, File f) {
        if (f.isFile() && f.length() > 0 && !names.contains(name)) {
            names.add(name);
            files.add(f);
        }
    }

    static void deleteTree(File f) {
        final File[] children = f.listFiles();
        if (children != null) {
            for (File c : children) deleteTree(c);
        }
        f.delete();
    }
}
