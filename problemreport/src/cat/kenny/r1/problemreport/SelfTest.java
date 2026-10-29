// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemProperties;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The shell runner owns the files; the app only reads them and sets its two properties. */
final class SelfTest {

    static final String EXTRA_TOKEN = "token";
    private static final String KEY_TOKEN = "self_test_token";
    private static final String KEY_STARTED = "self_test_started";
    private static final String RUN = "sys.r1.selftest.run";
    private static final String CANCEL = "sys.r1.selftest.cancel";
    private static final File ROOT = new File("/data/misc/r1-selftest");
    private static final Pattern SHOT = Pattern.compile("shots/(smoke-[A-Za-z0-9_.-]+\\.png)");
    private static final long START_TIMEOUT_MS = 20_000;

    private SelfTest() {
    }

    static String token(String value) {
        return value != null && value.matches("[a-z0-9]{12}") ? value : "";
    }

    static String current(Context context) {
        return token(prefs(context).getString(KEY_TOKEN, ""));
    }

    static long started(Context context, String token) {
        return token.equals(current(context))
                ? prefs(context).getLong(KEY_STARTED, System.currentTimeMillis())
                : System.currentTimeMillis();
    }

    static String start(Context context, String kind) {
        if (!"quick".equals(kind) && !"full".equals(kind)) {
            throw new IllegalArgumentException("unknown self test");
        }
        final SecureRandom random = new SecureRandom();
        final String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789";
        final StringBuilder value = new StringBuilder();
        for (int i = 0; i < 12; i++) value.append(alphabet.charAt(random.nextInt(36)));
        final String token = value.toString();
        // Keep the token before the runner takes over, even if this process goes away.
        if (!prefs(context).edit().putString(KEY_TOKEN, token)
                .putLong(KEY_STARTED, System.currentTimeMillis()).commit()) {
            throw new IllegalStateException("can't remember the self test");
        }
        try {
            SystemProperties.set(RUN, token + ":" + kind);
        } catch (RuntimeException e) {
            forget(context);
            throw e;
        }
        return token;
    }

    static void cancel(String token) {
        if (!token(token).isEmpty()) SystemProperties.set(CANCEL, token);
    }

    static boolean stopping(String token) {
        return token.equals(SystemProperties.get(CANCEL, ""));
    }

    /** Whether a run's results are there, or it started too recently to have made them yet. */
    static boolean hasResults(Context context, String token) {
        final File dir = directory(token);
        return dir != null && (dir.isDirectory()
                || System.currentTimeMillis() - started(context, token) < START_TIMEOUT_MS);
    }

    static void forget(Context context) {
        prefs(context).edit().remove(KEY_TOKEN).remove(KEY_STARTED).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(context);
    }

    static File directory(String token) {
        return token(token).isEmpty() ? null : new File(ROOT, token);
    }

    static File shot(String token, String name) {
        final File dir = directory(token);
        if (dir == null || !name.matches("smoke-[A-Za-z0-9_.-]+\\.png")) return null;
        final File shots = new File(dir, "shots");
        final File file = new File(shots, name);
        try {
            return file.isFile() && file.getCanonicalFile().getParentFile()
                    .equals(shots.getCanonicalFile()) ? file : null;
        } catch (IOException e) {
            return null;
        }
    }

    static final class Test {
        final String name;
        boolean failedFirst, failedAgain, retried, skipped;
        String reason = "";
        final List<String> failures = new ArrayList<>();
        final List<String> shots = new ArrayList<>();

        Test(String name) {
            this.name = name;
        }

        /** Failed, and its retry at the end, if it had one, failed too. */
        boolean failed() {
            return failedFirst && (failedAgain || !retried);
        }

        /** Failed at first, passed when run again. */
        boolean passedOnRetry() {
            return failedFirst && retried && !failedAgain;
        }

        boolean skippedOnly() {
            return skipped && !failedFirst;
        }
    }

    static final class Status {
        final String state, text;
        final int index, total, passed, failed, skipped;

        Status(String state, String text, int index, int total,
                int passed, int failed, int skipped) {
            this.state = state;
            this.text = text;
            this.index = index;
            this.total = total;
            this.passed = passed;
            this.failed = failed;
            this.skipped = skipped;
        }

        boolean terminal() {
            return !"running".equals(state);
        }
    }

    static final class Snapshot {
        final Status status;
        final List<Test> tests;
        final boolean startFailed;

        Snapshot(Status status, List<Test> tests, boolean startFailed) {
            this.status = status;
            this.tests = tests;
            this.startFailed = startFailed;
        }
    }

    /** Used on one worker thread, only while the page is visible. */
    static final class Reader {
        private final File mDir;
        private final long mStarted;
        private Status mStatus;
        private List<Test> mTests = new ArrayList<>();
        private long mLogLength = -1;
        private long mLogModified;

        Reader(String token, long started) {
            mDir = directory(token);
            mStarted = started;
        }

        Snapshot read() {
            if (mDir != null) {
                try (BufferedReader r = open(new File(mDir, "status"))) {
                    final Status status = parseStatus(r.readLine());
                    // A rewrite can briefly leave an empty or partial file.
                    if (status != null) mStatus = status;
                } catch (IOException e) {
                    // The runner has not made it yet, or it is being replaced.
                }
                final File log = new File(mDir, "log.txt");
                final long length = log.length(), modified = log.lastModified();
                if (length != mLogLength || modified != mLogModified) {
                    try {
                        mTests = readTests(log);
                        mLogLength = length;
                        mLogModified = modified;
                    } catch (IOException e) {
                        // Keep what was read last time.
                    }
                }
            }
            return new Snapshot(mStatus, mTests, mStatus == null
                    && System.currentTimeMillis() - mStarted >= START_TIMEOUT_MS);
        }
    }

    private static Status parseStatus(String line) {
        if (line == null) return null;
        final String[] parts = line.trim().split("\\s+", 4);
        try {
            if (parts.length == 4 && "running".equals(parts[0])) {
                final int index = Integer.parseInt(parts[1]);
                final int total = Integer.parseInt(parts[2]);
                if (index > 0 && total >= index && !parts[3].isEmpty()) {
                    return new Status("running", parts[3], index, total, 0, 0, 0);
                }
            } else if (parts.length == 4
                    && ("done".equals(parts[0]) || "cancelled".equals(parts[0]))) {
                final int passed = Integer.parseInt(parts[1]);
                final int failed = Integer.parseInt(parts[2]);
                final int skipped = Integer.parseInt(parts[3]);
                if (passed >= 0 && failed >= 0 && skipped >= 0) {
                    return new Status(parts[0], "", 0, 0, passed, failed, skipped);
                }
            } else if (line.startsWith("error ") && !line.substring(6).trim().isEmpty()) {
                return new Status("error", line.substring(6).trim(), 0, 0, 0, 0, 0);
            }
        } catch (NumberFormatException e) {
            // A partial status is read again on the next poll.
        }
        return null;
    }

    private static BufferedReader open(File file) throws IOException {
        return new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8));
    }

    /**
     * The tests in the order they ran, as r1-selftest.sh counts them: "== <test>" starts one
     * and "== retry <test>" its second run at the end; a FAIL fails it unless its retry has
     * none, a SKIP skips it, and one without either passed.
     */
    private static List<Test> readTests(File log) throws IOException {
        final Map<String, Test> tests = new LinkedHashMap<>();
        Test current = null;
        boolean retrying = false;
        try (BufferedReader r = open(log)) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("== ")) {
                    final String name = line.substring(3).trim();
                    retrying = name.startsWith("retry ");
                    if (retrying) {
                        current = tests.get(name.substring(6).trim());
                        if (current != null) current.retried = true;
                    } else if (name.equals("cancelled") || name.isEmpty()) {
                        current = null;
                    } else {
                        current = tests.computeIfAbsent(name, Test::new);
                    }
                    continue;
                }
                if (current == null) continue;
                if (retrying) {
                    if (line.startsWith("RETRY FAIL ")) {
                        current.failedAgain = true;
                        current.failures.add(line);
                    }
                } else if (line.startsWith("FAIL ")) {
                    current.failedFirst = true;
                    current.failures.add(line);
                } else if (line.startsWith("SKIP ")) {
                    current.skipped = true;
                    if (current.reason.isEmpty()) current.reason = line.substring(5).trim();
                }
                final Matcher shot = SHOT.matcher(line);
                while (shot.find()) {
                    if (!current.shots.contains(shot.group(1))) current.shots.add(shot.group(1));
                }
            }
        }
        // The screenshots each test took, by name, for those the log doesn't name
        final File[] files = log.getParentFile() == null ? null
                : new File(log.getParentFile(), "shots").listFiles();
        if (files != null) {
            for (File file : files) {
                final Test owner = shotOwner(tests.values(), file.getName());
                if (owner != null && file.getName().endsWith(".png")
                        && !owner.shots.contains(file.getName())) {
                    owner.shots.add(file.getName());
                }
            }
        }
        return new ArrayList<>(tests.values());
    }

    private static Test shotOwner(Iterable<Test> tests, String name) {
        Test owner = null;
        for (Test test : tests) {
            if (name.startsWith("smoke-" + test.name)
                    && (owner == null || test.name.length() > owner.name.length())) {
                owner = test;
            }
        }
        return owner;
    }

    /** Relative zip names, with screenshots only from tests whose final verdict failed. */
    static Map<String, File> files(String token) {
        final Map<String, File> files = new LinkedHashMap<>();
        final File dir = directory(token);
        if (dir == null) return files;
        final File log = new File(dir, "log.txt");
        files.put("log.txt", log);
        files.put("status", new File(dir, "status"));
        try {
            final List<Test> tests = readTests(log);
            for (Test test : tests) {
                if (!test.failed()) continue;
                for (String name : test.shots) {
                    final File file = shot(token, name);
                    if (file != null) files.put("shots/" + name, file);
                }
            }
            final File[] shots = new File(dir, "shots").listFiles();
            if (shots != null) {
                for (File file : shots) {
                    final Test owner = shotOwner(tests, file.getName());
                    final File image = shot(token, file.getName());
                    if (owner != null && owner.failed() && image != null) {
                        files.put("shots/" + file.getName(), image);
                    }
                }
            }
        } catch (IOException e) {
            // Missing results should not stop an otherwise useful problem report.
        }
        return files;
    }
}
