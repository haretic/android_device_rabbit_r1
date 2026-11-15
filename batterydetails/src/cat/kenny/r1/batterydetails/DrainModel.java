// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Where the charge went: the battery history cut into three states (screen on, awake with
 * the screen off, asleep) with the percent each one cost and who was running in it.
 *
 * What is measured and what is not. The gauge reports whole percents, so the unit of truth is
 * one step: the time between two percent readings cost exactly 1 %. A step spent in one state
 * belongs to that state outright, and those steps also teach this r1's own rate for the state.
 * A step that mixes states is shared out by the time in each, weighted with those rates. So
 * the three numbers always add up to what the gauge lost; only the split inside a mixed step
 * is apportioned. The share of one app within a state goes by its time alone, and the page
 * says "about" for it.
 *
 * Two things the gauge does are kept out of the states. A step that began on the charger
 * counts only for its time off it. And in the minutes after the cable is pulled the reading
 * sinks by several percent while the cell's voltage settles: what such a step lost beyond the
 * usual rate of its states is booked as settling, not blamed on the screen.
 *
 * Plain Java with no Android in it: {@link DrainSource} feeds it on the r1, a text dump feeds
 * it in the host test.
 */
final class DrainModel {

    static final int SCREEN = 0;
    static final int AWAKE = 1;
    static final int ASLEEP = 2;
    /** On the charger: drawn in the bar, counted nowhere. */
    static final int CHARGING = 3;

    /** Who a slice of awake time goes to when nothing held the r1 awake by name. */
    static final int UID_NONE = -1;

    /**
     * Percent an hour for a state until this r1 has a step of its own in it. Only the ratio
     * between them matters, and only inside a step that mixes states.
     */
    private static final double[] FALLBACK_RATE = {14, 5, 0.6};
    /** A step teaches a state's rate when this much of it was that state. */
    private static final double PURE = 0.9;
    private static final int MIN_RATE_SAMPLES = 3;
    /** How long after the charger the gauge's reading may still be settling. */
    private static final long SETTLE_MS = 15 * 60_000L;
    /** A step in that time is settling when it lost this many times the usual. */
    private static final double SETTLE_FACTOR = 3;

    /** One stretch of the bar. */
    static final class Span {
        final long start;
        final long end;
        final int state;

        Span(long start, long end, int state) {
            this.start = start;
            this.end = end;
            this.state = state;
        }
    }

    /** Someone who was running in a state. */
    static final class Who {
        final int uid;
        /** A package for the screen, a wake lock or job name otherwise; "" for nobody. */
        final String name;
        long ms;
        /** How often it started. */
        int count;

        Who(int uid, String name) {
            this.uid = uid;
            this.name = name;
        }
    }

    static final class Result {
        long from;
        long to;
        int levelStart;
        int levelEnd;
        /** Time off the charger in each state. */
        final long[] ms = new long[3];
        /** Percent each state cost; adds up to the steps the gauge took in the window. */
        final double[] percent = new double[3];
        /** Percent the reading sank right after the charger, beyond what the states cost. */
        double settling;
        /** This r1's usual percent an hour for each state, NaN while it is not known. */
        final double[] usualRate = new double[3];
        final List<Span> spans = new ArrayList<>();
        /** Per state, biggest first. */
        final List<List<Who>> who = new ArrayList<>();
        /** Times it woke from sleep with the screen off. */
        int wakeups;

        /** Off-charger time in the window. */
        long totalMs() {
            return ms[SCREEN] + ms[AWAKE] + ms[ASLEEP];
        }

        double totalPercent() {
            return percent[SCREEN] + percent[AWAKE] + percent[ASLEEP];
        }

        /** Percent an hour in a state in this window, NaN when it spent no time there. */
        double rate(int state) {
            return ms[state] < 60_000 ? Double.NaN : percent[state] / (ms[state] / 3_600_000.0);
        }
    }

    // The timeline, a slice per change: [start[i], start[i + 1]) in state[i] for who[i].
    private long[] mStart = new long[4096];
    private byte[] mState = new byte[4096];
    private int[] mWho = new int[4096];
    private int mCount;
    // Percent steps: the gauge read mStepLevel[i] from mStepTime[i] on.
    private long[] mStepTime = new long[512];
    private byte[] mStepLevel = new byte[512];
    private int mSteps;

    private final List<Who> mNames = new ArrayList<>();
    private final Map<String, Integer> mNameIndex = new HashMap<>();

    // What the feed last said
    private long mNow;
    private boolean mScreen;
    private boolean mRunning = true;
    private boolean mPlugged;
    private int mLevel = -1;
    private int mTop = -1;
    private int mLock = -1;
    private final List<Integer> mJobs = new ArrayList<>();
    private long mEnd;
    private final long mPresent;

    /** @param present the time now: no history entry is later */
    DrainModel(long present) {
        mPresent = present;
    }

    // ---- the feed, in time order ----

    /** Everything after this call happened at this wall-clock time. */
    void at(long time) {
        // A boot before network time is in stamps its entries with whatever the clock chip
        // held, 1970 or 2069: a step back would fold the timeline and a step past the present
        // would swallow what follows, so such entries happen "now" as far as it is known
        if (time > mNow && time <= mPresent) {
            mNow = time;
        }
        mEnd = mNow;
    }

    void level(int percent) {
        if (percent == mLevel) {
            return;
        }
        mLevel = percent;
        if (mSteps == mStepTime.length) {
            mStepTime = Arrays.copyOf(mStepTime, mSteps * 2);
            mStepLevel = Arrays.copyOf(mStepLevel, mSteps * 2);
        }
        mStepTime[mSteps] = mNow;
        mStepLevel[mSteps++] = (byte) percent;
    }

    void screen(boolean on) {
        mScreen = on;
        slice();
    }

    /** Whether the processor runs: false is suspended. */
    void running(boolean running) {
        mRunning = running;
        slice();
    }

    void plugged(boolean plugged) {
        mPlugged = plugged;
        slice();
    }

    /** The app in front. */
    void top(boolean start, int uid, String pkg) {
        final int who = name(uid, pkg);
        if (start) {
            mTop = who;
        } else if (mTop == who) {
            mTop = -1;
        }
        slice();
    }

    /** A wake lock is held (the history names the one that was taken first) or none is. */
    void wakeLock(boolean held, int uid, String tag) {
        mLock = held ? (tag == null ? mLock : name(uid, tag)) : -1;
        slice();
    }

    void job(boolean start, int uid, String job) {
        final Integer who = name(uid, job);
        if (start) {
            mJobs.add(who);
        } else {
            mJobs.remove(who);
        }
        slice();
    }

    /** A boot or a history that starts over: nothing is in front, held or running any more. */
    void reset() {
        mTop = -1;
        mLock = -1;
        mJobs.clear();
        mRunning = true;
        slice();
    }

    private int name(int uid, String name) {
        final String key = uid + "/" + name;
        Integer index = mNameIndex.get(key);
        if (index == null) {
            index = mNames.size();
            mNames.add(new Who(uid, name));
            mNameIndex.put(key, index);
        }
        return index;
    }

    private void slice() {
        final int state = mPlugged ? CHARGING : mScreen ? SCREEN : mRunning ? AWAKE : ASLEEP;
        // Awake goes to the wake lock, else to the job that runs, else to nobody by name
        final int who = state == SCREEN ? mTop
                : state == AWAKE ? (mLock >= 0 ? mLock
                        : mJobs.isEmpty() ? -1 : mJobs.get(mJobs.size() - 1))
                : -1;
        if (mCount > 0 && mState[mCount - 1] == state && mWho[mCount - 1] == who) {
            return;
        }
        if (mCount > 0 && mStart[mCount - 1] == mNow) {
            // Several changes in one instant: the last one stands
            mCount--;
            if (mCount > 0 && mState[mCount - 1] == state && mWho[mCount - 1] == who) {
                return;
            }
        }
        if (mCount == mStart.length) {
            mStart = Arrays.copyOf(mStart, mCount * 2);
            mState = Arrays.copyOf(mState, mCount * 2);
            mWho = Arrays.copyOf(mWho, mCount * 2);
        }
        mStart[mCount] = mNow;
        mState[mCount] = (byte) state;
        mWho[mCount++] = who;
    }

    // ---- questions ----

    /** The time of the last history entry. */
    long end() {
        return mEnd;
    }

    boolean pluggedNow() {
        return mPlugged;
    }

    /**
     * The start and end of the newest run off the charger: until now when it is off it, the
     * last finished one when it is charging. Null when the history holds none.
     */
    long[] lastRun() {
        long end = mEnd;
        for (int i = mCount - 1; i >= 0; i--) {
            if (mState[i] == CHARGING) {
                if (sliceEnd(i) < end) {
                    return new long[] {sliceEnd(i), end};
                }
                end = mStart[i];
            } else if (i == 0 && mStart[0] < end) {
                return new long[] {mStart[0], end};
            }
        }
        return null;
    }

    private long sliceEnd(int i) {
        return i + 1 < mCount ? mStart[i + 1] : mEnd;
    }

    /** This r1's usual percent an hour for each state, from every step the history has. */
    double[] usualRates() {
        return usualRates(unplugTimes());
    }

    /** When the charger came off, oldest first. */
    private long[] unplugTimes() {
        long[] times = new long[16];
        int n = 0;
        for (int i = 0; i + 1 < mCount; i++) {
            if (mState[i] == CHARGING) {
                if (n == times.length) {
                    times = Arrays.copyOf(times, n * 2);
                }
                times[n++] = mStart[i + 1];
            }
        }
        return Arrays.copyOf(times, n);
    }

    /** Whether an instant is within the settling time after the charger came off. */
    private static boolean settling(long[] unplugs, long time) {
        int i = Arrays.binarySearch(unplugs, time);
        if (i < 0) {
            i = -i - 2;
        }
        return i >= 0 && time - unplugs[i] <= SETTLE_MS;
    }

    private double[] usualRates(long[] unplugs) {
        final List<List<Double>> samples = new ArrayList<>();
        for (int s = 0; s < 3; s++) {
            samples.add(new ArrayList<>());
        }
        final long[] ms = new long[4];
        for (int i = 1; i < mSteps; i++) {
            if (mStepLevel[i] != mStepLevel[i - 1] - 1) {
                continue;
            }
            // The step before must be a drop as well, or this one started part-way
            if (i < 2 || mStepLevel[i - 1] != mStepLevel[i - 2] - 1) {
                continue;
            }
            Arrays.fill(ms, 0);
            stateTime(mStepTime[i - 1], mStepTime[i], ms, null);
            final long total = ms[SCREEN] + ms[AWAKE] + ms[ASLEEP];
            if (ms[CHARGING] > 0 || total < 60_000 || settling(unplugs, mStepTime[i])) {
                continue;
            }
            for (int s = 0; s < 3; s++) {
                if (ms[s] >= PURE * total) {
                    samples.get(s).add(3_600_000.0 / total);
                }
            }
        }
        final double[] rate = new double[3];
        for (int s = 0; s < 3; s++) {
            final List<Double> list = samples.get(s);
            if (list.size() < MIN_RATE_SAMPLES) {
                rate[s] = Double.NaN;
            } else {
                list.sort(null);
                rate[s] = list.get(list.size() / 2);
            }
        }
        return rate;
    }

    /** Adds the time in each state between two instants; per-name time too when asked. */
    private void stateTime(long from, long to, long[] ms, long[][] byName) {
        int i = find(from);
        for (; i < mCount && mStart[i] < to; i++) {
            final long a = Math.max(from, mStart[i]);
            final long b = Math.min(to, sliceEnd(i));
            if (b <= a) {
                continue;
            }
            ms[mState[i]] += b - a;
            if (byName != null && mState[i] < CHARGING) {
                byName[mState[i]][mWho[i] + 1] += b - a;
            }
        }
    }

    /** The slice an instant falls in. */
    private int find(long time) {
        int lo = 0;
        int hi = mCount - 1;
        while (lo < hi) {
            final int mid = (lo + hi + 1) >>> 1;
            if (mStart[mid] <= time) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return lo;
    }

    /** The account of a window. */
    Result account(long from, long to) {
        final Result r = new Result();
        r.from = Math.max(from, mCount > 0 ? mStart[0] : from);
        r.to = Math.min(to, mEnd);
        final long[] unplugs = unplugTimes();
        final double[] usual = usualRates(unplugs);
        System.arraycopy(usual, 0, r.usualRate, 0, 3);
        final double[] weight = new double[3];
        for (int s = 0; s < 3; s++) {
            weight[s] = Double.isNaN(usual[s]) ? FALLBACK_RATE[s] : usual[s];
        }

        // Time, who, the bar
        final long[] ms = new long[4];
        final long[][] byName = new long[3][mNames.size() + 1];
        stateTime(r.from, r.to, ms, byName);
        System.arraycopy(ms, 0, r.ms, 0, 3);
        final int[][] starts = new int[3][mNames.size() + 1];
        for (int i = find(r.from); i < mCount && mStart[i] < r.to; i++) {
            final long a = Math.max(r.from, mStart[i]);
            final long b = Math.min(r.to, sliceEnd(i));
            if (b <= a) {
                continue;
            }
            final int state = mState[i];
            final Span last = r.spans.isEmpty() ? null : r.spans.get(r.spans.size() - 1);
            if (last != null && last.state == state && last.end == a) {
                r.spans.set(r.spans.size() - 1, new Span(last.start, b, state));
            } else {
                r.spans.add(new Span(a, b, state));
            }
            if (state < CHARGING) {
                starts[state][mWho[i] + 1]++;
            }
            if (state == AWAKE && i > 0 && mState[i - 1] == ASLEEP && mStart[i] >= r.from) {
                r.wakeups++;
            }
        }
        for (int s = 0; s < 3; s++) {
            final List<Who> list = new ArrayList<>();
            for (int n = 0; n <= mNames.size(); n++) {
                if (byName[s][n] > 0) {
                    final Who who = n == 0 ? new Who(UID_NONE, "")
                            : new Who(mNames.get(n - 1).uid, mNames.get(n - 1).name);
                    who.ms = byName[s][n];
                    who.count = starts[s][n];
                    list.add(who);
                }
            }
            list.sort((a, b) -> Long.compare(b.ms, a.ms));
            r.who.add(list);
        }

        // The steps: each drop of one percent is shared among the states of its interval
        r.levelStart = levelAt(r.from);
        r.levelEnd = levelAt(r.to);
        for (int i = 1; i < mSteps; i++) {
            final long a = mStepTime[i - 1];
            final long b = mStepTime[i];
            final int drop = mStepLevel[i - 1] - mStepLevel[i];
            if (drop <= 0 || b <= r.from || a >= r.to) {
                continue;
            }
            Arrays.fill(ms, 0);
            stateTime(a, b, ms, null);
            final long off = ms[SCREEN] + ms[AWAKE] + ms[ASLEEP];
            if (off <= 0) {
                continue;
            }
            // What the step lost off the charger, and what its states usually cost
            double lost = (double) drop * off / (off + ms[CHARGING]);
            double whole = 0;
            for (int s = 0; s < 3; s++) {
                whole += ms[s] * weight[s];
            }
            final double usualLoss = whole / 3_600_000.0;
            double settled = 0;
            if (settling(unplugs, b) && lost > SETTLE_FACTOR * usualLoss) {
                settled = lost - usualLoss;
                lost = usualLoss;
            }
            // The part of the step that lies inside the window
            Arrays.fill(ms, 0);
            stateTime(Math.max(a, r.from), Math.min(b, r.to), ms, null);
            for (int s = 0; s < 3; s++) {
                r.percent[s] += lost * ms[s] * weight[s] / whole;
            }
            r.settling += settled * (ms[SCREEN] + ms[AWAKE] + ms[ASLEEP]) / off;
        }
        return r;
    }

    /** What the gauge read at an instant, -1 before its first reading. */
    private int levelAt(long time) {
        int level = -1;
        for (int i = 0; i < mSteps && mStepTime[i] <= time; i++) {
            level = mStepLevel[i];
        }
        return level < 0 && mSteps > 0 ? mStepLevel[0] : level;
    }

    /**
     * A state's list with the entries of one owner added up: by package for the screen, by
     * uid otherwise. Each keeps the name it spent the most time under. Biggest first.
     */
    static List<Who> grouped(List<Who> list, boolean byUid) {
        final Map<String, Who> biggest = new HashMap<>();
        final Map<String, long[]> sums = new HashMap<>();
        for (Who who : list) {
            final String key = byUid ? String.valueOf(who.uid) : who.uid + "/" + who.name;
            final Who seen = biggest.get(key);
            if (seen == null || seen.ms < who.ms) {
                biggest.put(key, who);
            }
            final long[] sum = sums.computeIfAbsent(key, k -> new long[2]);
            sum[0] += who.ms;
            sum[1] += who.count;
        }
        final List<Who> out = new ArrayList<>();
        for (Map.Entry<String, Who> entry : biggest.entrySet()) {
            final Who group = new Who(entry.getValue().uid, entry.getValue().name);
            group.ms = sums.get(entry.getKey())[0];
            group.count = (int) sums.get(entry.getKey())[1];
            out.add(group);
        }
        out.sort((x, y) -> Long.compare(y.ms, x.ms));
        return out;
    }

    /**
     * Whole percents for the three rows that add up to the rounded total (largest remainder),
     * so the page never shows 24 + 9 + 8 under a 42.
     */
    static int[] rounded(double[] percent) {
        final int total = (int) Math.round(percent[0] + percent[1] + percent[2]);
        final int[] out = new int[3];
        int sum = 0;
        for (int s = 0; s < 3; s++) {
            out[s] = (int) Math.floor(percent[s]);
            sum += out[s];
        }
        while (sum < total) {
            int best = 0;
            for (int s = 1; s < 3; s++) {
                if (percent[s] - out[s] > percent[best] - out[best]) {
                    best = s;
                }
            }
            out[best]++;
            sum++;
        }
        return out;
    }
}
