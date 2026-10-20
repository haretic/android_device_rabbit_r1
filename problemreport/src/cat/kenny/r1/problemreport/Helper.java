// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.os.SystemProperties;

import java.io.File;
import java.io.IOException;

/**
 * Talks to r1-problem-report.sh, the init service that collects what system_app may not
 * read: the Android and kernel logs, tombstones and ANR traces, and through init the logs
 * the last start left in pstore. They meet in /data/misc/r1-problem-report, the system
 * user's; the script reports in sys.r1.report.status.
 */
final class Helper {

    static final File DIR = new File("/data/misc/r1-problem-report");
    /** What the script writes */
    static final File WORK = new File(DIR, "work");
    /** What init copies from /sys/fs/pstore, empty where this start has none */
    static final File PSTORE = new File(DIR, "pstore");
    /** The same names as in r1-problem-report.rc */
    static final String[] PSTORE_FILES = {
        "console-ramoops-0", "pmsg-ramoops-0",
        "dmesg-ramoops-0", "dmesg-ramoops-1", "dmesg-ramoops-2", "dmesg-ramoops-3",
        "console-pstore_blk-0", "pmsg-pstore_blk-0", "dmesg-pstore_blk-0", "dmesg-pstore_blk-1",
    };

    private static final String COLLECT = "sys.r1.report.collect";
    private static final String STATUS = "sys.r1.report.status";
    private static final String LAST_START = "sys.r1.report.last_start";
    /** Until the script answers */
    static final String STARTING = "starting";

    private Helper() {
    }

    /** "running <step> <steps> <name>", "collected", "error <why>" or what start() left. */
    static String status() {
        return SystemProperties.get(STATUS, "");
    }

    /** The script is collecting, maybe for a page that has gone since. */
    static boolean busy() {
        return status().startsWith("running");
    }

    /** "<panic|reset|normal> <kept|none>", empty until Android is up. */
    static String lastStart() {
        return SystemProperties.get(LAST_START, "");
    }

    /**
     * Makes the empty files init copies pstore into, so they are the system user's and not
     * root's, and starts the script.
     */
    static void start() throws IOException {
        clear();
        if (!PSTORE.mkdirs()) throw new IOException("can't make " + PSTORE);
        for (String name : PSTORE_FILES) {
            if (!new File(PSTORE, name).createNewFile()) {
                throw new IOException("can't make " + name);
            }
        }
        SystemProperties.set(STATUS, STARTING);
        // A new value every time: the trigger in r1-problem-report.rc
        SystemProperties.set(COLLECT, Long.toString(System.currentTimeMillis()));
    }

    /** The script didn't move on: no longer block the next report on it. */
    static void abandon() {
        SystemProperties.set(STATUS, "error stopped");
    }

    static void clear() {
        Report.deleteTree(WORK);
        Report.deleteTree(PSTORE);
    }
}
