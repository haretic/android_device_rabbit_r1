// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateUtils;
import android.text.style.StyleSpan;
import android.util.Log;

import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Battery details > What drained me. One sentence says what happened, three rows say what the
 * screen, awake time with the screen off and sleep each cost, and a row opens to who was
 * running. The numbers come from {@link DrainModel}, which says what is measured in them.
 */
public class DrainFragment extends SettingsBasePreferenceFragment {

    /** Which state's page this is; none for the overview. */
    static final String EXTRA_STATE = "state";
    /** 0 since the charger, 1 the last 24 hours, 2 the last 7 days. */
    static final String EXTRA_PERIOD = "period";

    static final int[] STATE_TITLES = {
            R.string.drain_screen, R.string.drain_awake, R.string.drain_asleep};

    private static final String TAG = "R1BatteryDetails";
    private static final long DAY_MS = 24 * 3600 * 1000L;
    /** The history is read again when the last reading is this old. */
    private static final long FRESH_MS = 60 * 1000L;
    /** Awake time with the screen off is called a lot from this share of the screen-off time. */
    private static final double AWAKE_MUCH = 0.2;
    private static final long AWAKE_MUCH_MS = 10 * 60 * 1000L;
    /** Sleep is called faster than usual from this many times the r1's own rate. */
    private static final double RATE_HIGH = 1.5;
    /** An app is named in the sentence when it kept the r1 awake this long. */
    private static final long NAMED_APP_MS = 10 * 60 * 1000L;
    private static final int MAX_WHO = 8;

    // The overview and a state's page opened from it show one reading of the history
    private static DrainModel sModel;
    private static long sModelTime;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mReader = Executors.newSingleThreadExecutor();
    private int mState = -1;
    private int mPeriod;
    private boolean mLoading;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        final Bundle args = getArguments();
        if (args != null) {
            mState = args.getInt(EXTRA_STATE, -1);
            mPeriod = args.getInt(EXTRA_PERIOD, 0);
        }
        if (savedInstanceState != null) {
            mPeriod = savedInstanceState.getInt(EXTRA_PERIOD, mPeriod);
        }
        setPreferenceScreen(getPreferenceManager().createPreferenceScreen(requireContext()));
        if (sModel == null) {
            note(R.string.drain_loading);
        } else {
            show();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_PERIOD, mPeriod);
    }

    @Override
    public void onResume() {
        super.onResume();
        if (mLoading || (sModel != null
                && Math.abs(System.currentTimeMillis() - sModelTime) < FRESH_MS)) {
            return;
        }
        mLoading = true;
        final Context app = requireContext().getApplicationContext();
        mReader.execute(() -> {
            final DrainModel model = DrainSource.load(app);
            mHandler.post(() -> {
                mLoading = false;
                if (model != null) {
                    sModel = model;
                    sModelTime = System.currentTimeMillis();
                }
                if (isAdded()) {
                    if (sModel == null) {
                        getPreferenceScreen().removeAll();
                        note(R.string.drain_unavailable);
                    } else {
                        show();
                    }
                }
            });
        });
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mReader.shutdown();
    }

    private void note(int text) {
        final Preference note = new Preference(requireContext());
        note.setSummary(text);
        note.setSelectable(false);
        getPreferenceScreen().addPreference(note);
    }

    /** The window of the chosen period; null when the r1 was never off the charger. */
    private long[] window() {
        final long end = sModel.end();
        return mPeriod == 0 ? sModel.lastRun()
                : new long[] {end - (mPeriod == 1 ? DAY_MS : 7 * DAY_MS), end};
    }

    private void show() {
        final PreferenceScreen screen = getPreferenceScreen();
        screen.removeAll();
        final long[] window = window();
        final DrainModel.Result result =
                window == null ? null : sModel.account(window[0], window[1]);
        if (mState < 0) {
            showOverview(screen, result);
        } else if (result == null) {
            note(R.string.drain_none_yet);
        } else {
            showState(screen, result);
        }
    }

    // ---- the overview ----

    private void showOverview(PreferenceScreen screen, DrainModel.Result r) {
        final Context context = requireContext();
        final DrainHeaderPreference header = new DrainHeaderPreference(context);
        screen.addPreference(header);
        if (r == null || r.totalMs() < 60_000) {
            header.setText(null, null,
                    getString(r == null ? R.string.drain_none_yet : R.string.drain_none));
            // No bar: under a minute off the charger would fill it with one colour
            addPeriods(screen);
            return;
        }
        final int[] percent = DrainModel.rounded(r.percent);
        final int total = percent[0] + percent[1] + percent[2];
        final int settling = (int) Math.round(r.settling);
        final String time = length(r.totalMs());
        if (mPeriod == 0) {
            header.setText(getString(R.string.drain_levels, r.levelStart, r.levelEnd),
                    sModel.pluggedNow()
                            ? getString(R.string.drain_until, time, clock(r.from), clock(r.to))
                            : getString(R.string.drain_since, time, clock(r.from)),
                    story(r, percent, total));
        } else {
            header.setText(getString(R.string.drain_used, total + settling),
                    getString(mPeriod == 1 ? R.string.drain_off_charger_day
                            : R.string.drain_off_charger_week, time),
                    story(r, percent, total));
        }
        header.setBar(r.spans, r.from, r.to, clock(r.from), barEnd(r));

        for (int state = 0; state < 3; state++) {
            final ValuePreference row = new ValuePreference(context, null);
            row.setTitle(STATE_TITLES[state]);
            row.setSummary(rowSummary(r, state));
            row.setValue(getString(R.string.drain_row_percent, percent[state]));
            row.setIcon(chip(context, state));
            row.setSelectable(true);
            row.setIntent(new Intent(context, DrainActivity.class)
                    .putExtra(EXTRA_STATE, state)
                    .putExtra(EXTRA_PERIOD, mPeriod));
            screen.addPreference(row);
        }
        if (settling >= 1) {
            final Preference note = new Preference(context);
            note.setSummary(getString(R.string.drain_settling, settling));
            note.setSelectable(false);
            screen.addPreference(note);
        }
        note(R.string.drain_hint);
        addPeriods(screen);
    }

    private void addPeriods(PreferenceScreen screen) {
        final DrainPeriodPreference periods = new DrainPeriodPreference(requireContext());
        periods.setPeriod(mPeriod, period -> {
            mPeriod = period;
            // After the row has drawn its new choice
            mHandler.post(() -> {
                if (isAdded() && sModel != null) {
                    show();
                }
            });
        });
        screen.addPreference(periods);
    }

    private CharSequence barEnd(DrainModel.Result r) {
        return mPeriod == 0 && sModel.pluggedNow() ? clock(r.to)
                : getString(R.string.drain_bar_now);
    }

    /** The sentence: what took the most, and what stands out besides. */
    private CharSequence story(DrainModel.Result r, int[] percent, int total) {
        final SpannableStringBuilder out = new SpannableStringBuilder();
        if (total < 1) {
            return getString(R.string.drain_story_little);
        }
        int most = 0;
        for (int state = 1; state < 3; state++) {
            if (percent[state] > percent[most]) {
                most = state;
            }
        }
        out.append(getString(percent[most] * 2 < total ? R.string.drain_story_mixed
                : most == DrainModel.SCREEN ? R.string.drain_story_screen
                : most == DrainModel.AWAKE ? R.string.drain_story_awake
                : R.string.drain_story_asleep));

        // An app that kept it awake is the first thing to say; else the wake-ups; else sleep
        final List<DrainModel.Who> awake =
                DrainModel.grouped(r.who.get(DrainModel.AWAKE), true);
        DrainModel.Who app = null;
        DrainModel.Who wakeups = null;
        for (DrainModel.Who who : awake) {
            if (who.uid >= Process.FIRST_APPLICATION_UID && app == null) {
                app = who;
            } else if (who.uid == DrainModel.UID_NONE) {
                wakeups = who;
            }
        }
        if (app != null && app.ms >= NAMED_APP_MS) {
            final String label = label(app.uid, null).toString();
            final String sentence = getString(R.string.drain_story_app, label, length(app.ms));
            final int at = out.length() + 1 + sentence.indexOf(label);
            out.append(' ').append(sentence);
            out.setSpan(new StyleSpan(Typeface.BOLD), at, at + label.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else if (most != DrainModel.AWAKE && wakeups != null && awakeIsMuch(r)) {
            out.append(' ').append(getString(R.string.drain_story_wakeups, wakeups.count,
                    length(wakeups.ms)));
        } else if (most != DrainModel.ASLEEP && sleepIsFast(r)) {
            out.append(' ').append(getString(R.string.drain_story_sleep_fast));
        }
        return out;
    }

    private static boolean awakeIsMuch(DrainModel.Result r) {
        final long off = r.ms[DrainModel.AWAKE] + r.ms[DrainModel.ASLEEP];
        return r.ms[DrainModel.AWAKE] >= AWAKE_MUCH_MS
                && r.ms[DrainModel.AWAKE] >= AWAKE_MUCH * off;
    }

    private static boolean sleepIsFast(DrainModel.Result r) {
        final double rate = r.rate(DrainModel.ASLEEP);
        final double usual = r.usualRate[DrainModel.ASLEEP];
        return !Double.isNaN(rate) && !Double.isNaN(usual) && rate > RATE_HIGH * usual
                && r.percent[DrainModel.ASLEEP] >= 1;
    }

    private CharSequence rowSummary(DrainModel.Result r, int state) {
        final String time = length(r.ms[state]);
        if (state == DrainModel.SCREEN) {
            final List<DrainModel.Who> front = DrainModel.grouped(r.who.get(state), false);
            for (DrainModel.Who who : front) {
                // Named when one app had more of the screen than everything else together
                if (who.uid != DrainModel.UID_NONE && who.ms * 2 > r.ms[state]) {
                    return getString(R.string.drain_row_mostly, time, label(who.uid, who.name));
                }
                break;
            }
            return time;
        }
        if (state == DrainModel.AWAKE) {
            return awakeIsMuch(r) ? getString(R.string.drain_row_awake_much, time) : time;
        }
        final double rate = r.rate(state);
        if (Double.isNaN(rate)) {
            return time;
        }
        final double usual = r.usualRate[state];
        return getString(Double.isNaN(usual) ? R.string.drain_row_rate
                : rate > RATE_HIGH * usual ? R.string.drain_row_rate_high
                : R.string.drain_row_rate_normal, time, rate(rate));
    }

    /** The pill in a state's colour at the start of its row. */
    private static Drawable chip(Context context, int state) {
        final float dp = context.getResources().getDisplayMetrics().density;
        final GradientDrawable chip = new GradientDrawable();
        chip.setColor(DrainBarView.stateColor(context, state));
        chip.setCornerRadius(4 * dp);
        chip.setSize(Math.round(8 * dp), Math.round(30 * dp));
        return chip;
    }

    // ---- the page of one state ----

    private void showState(PreferenceScreen screen, DrainModel.Result r) {
        final Context context = requireContext();
        final int[] percent = DrainModel.rounded(r.percent);
        final DrainHeaderPreference header = new DrainHeaderPreference(context);
        screen.addPreference(header);
        if (r.ms[mState] < 1000) {
            header.setText(null, null, getString(R.string.drain_state_none));
            return;
        }
        final CharSequence story;
        if (mState == DrainModel.ASLEEP) {
            final double rate = r.rate(mState);
            final double usual = r.usualRate[mState];
            story = Double.isNaN(rate) ? null
                    : Double.isNaN(usual) ? getString(R.string.drain_asleep_story, rate(rate))
                    : getString(R.string.drain_asleep_story_usual, rate(rate), rate(usual));
        } else {
            story = getString(mState == DrainModel.SCREEN ? R.string.drain_screen_story
                    : R.string.drain_awake_story);
        }
        header.setText(getString(R.string.drain_row_percent, percent[mState]),
                getString(R.string.drain_state_time, length(r.ms[mState])), story);

        if (mState == DrainModel.ASLEEP) {
            showSleep(screen, r);
            return;
        }
        final List<DrainModel.Who> list =
                DrainModel.grouped(r.who.get(mState), mState == DrainModel.AWAKE);
        boolean restrictable = false;
        int shown = 0;
        long otherMs = 0;
        for (DrainModel.Who who : list) {
            if (shown >= MAX_WHO || (shown >= 3 && who.ms < 60_000)) {
                otherMs += who.ms;
                continue;
            }
            shown++;
            restrictable |= addWho(screen, r, who, percent[mState]);
        }
        if (otherMs >= 60_000) {
            final ValuePreference other = new ValuePreference(context, null);
            other.setTitle(R.string.drain_who_other);
            other.setSummary(share(r, otherMs, percent[mState]));
            screen.addPreference(other);
        }
        note(restrictable ? R.string.drain_restrict_hint : R.string.drain_about_hint);
    }

    /** A row for someone who was running. True when a press restricts it. */
    private boolean addWho(PreferenceScreen screen, DrainModel.Result r, DrainModel.Who who,
            int statePercent) {
        final Context context = requireContext();
        final ValuePreference row = new ValuePreference(context, null);
        final String pkg = packageOf(who);
        row.setTitle(label(who.uid, mState == DrainModel.SCREEN ? who.name : null));
        final String share = share(r, who.ms, statePercent);
        final CharSequence why = mState == DrainModel.AWAKE ? why(who) : null;
        row.setSummary(why == null ? share : share + "\n" + why);
        if (pkg != null) {
            try {
                row.setIcon(context.getPackageManager().getApplicationIcon(pkg));
            } catch (PackageManager.NameNotFoundException e) {
                // Removed since: its name is all there is
            }
        }
        screen.addPreference(row);
        if (mState != DrainModel.AWAKE || pkg == null || !mayRestrict(pkg)) {
            return false;
        }
        final AppOpsManager appOps = context.getSystemService(AppOpsManager.class);
        row.setValue(getString(restricted(appOps, who.uid, pkg) ? R.string.drain_restricted
                : R.string.drain_restrict));
        row.setSelectable(true);
        row.setOnPreferenceClickListener(p -> {
            final boolean restrict = !restricted(appOps, who.uid, pkg);
            // What Settings' "Restricted" battery usage sets
            appOps.setMode(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, who.uid, pkg,
                    restrict ? AppOpsManager.MODE_IGNORED : AppOpsManager.MODE_ALLOWED);
            Log.i(TAG, (restrict ? "Restricted " : "Allowed ") + pkg);
            row.setValue(getString(restrict ? R.string.drain_restricted
                    : R.string.drain_restrict));
            return true;
        });
        return true;
    }

    private static boolean restricted(AppOpsManager appOps, int uid, String pkg) {
        return appOps.checkOpNoThrow(AppOpsManager.OP_RUN_ANY_IN_BACKGROUND, uid, pkg)
                == AppOpsManager.MODE_IGNORED;
    }

    /** Apps the owner installed: restricting a part of the system only breaks it. */
    private boolean mayRestrict(String pkg) {
        try {
            final ApplicationInfo info =
                    requireContext().getPackageManager().getApplicationInfo(pkg, 0);
            return (info.flags & ApplicationInfo.FLAG_SYSTEM) == 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private void showSleep(PreferenceScreen screen, DrainModel.Result r) {
        final Context context = requireContext();
        if (r.wakeups > 0) {
            final ValuePreference woke = new ValuePreference(context, null);
            woke.setTitle(R.string.drain_woke);
            woke.setSummary(getString(R.string.drain_woke_every,
                    length(r.ms[DrainModel.ASLEEP] / r.wakeups)));
            woke.setValue(getResources().getQuantityString(R.plurals.drain_woke_count,
                    r.wakeups, r.wakeups));
            screen.addPreference(woke);
        }
        long longest = 0;
        for (DrainModel.Span span : r.spans) {
            if (span.state == DrainModel.ASLEEP) {
                longest = Math.max(longest, span.end - span.start);
            }
        }
        final ValuePreference sleep = new ValuePreference(context, null);
        sleep.setTitle(R.string.drain_longest_sleep);
        sleep.setValue(length(longest));
        screen.addPreference(sleep);
    }

    // ---- names and words ----

    /** The package a row stands for, null for the system's own. */
    private String packageOf(DrainModel.Who who) {
        if (mState == DrainModel.SCREEN) {
            return who.uid == DrainModel.UID_NONE ? null : who.name;
        }
        if (who.uid < Process.FIRST_APPLICATION_UID) {
            return null;
        }
        final String[] packages = requireContext().getPackageManager().getPackagesForUid(who.uid);
        return packages == null || packages.length == 0 ? null : packages[0];
    }

    private CharSequence label(int uid, String pkg) {
        final PackageManager pm = requireContext().getPackageManager();
        if (uid == DrainModel.UID_NONE) {
            return getString(mState == DrainModel.SCREEN ? R.string.drain_who_front
                    : R.string.drain_who_wakeups);
        }
        if (pkg == null && uid >= Process.FIRST_APPLICATION_UID) {
            final String[] packages = pm.getPackagesForUid(uid);
            pkg = packages == null || packages.length == 0 ? null : packages[0];
        }
        if (pkg != null) {
            // The launcher by what it is, not by the name of its package's project
            final android.content.pm.ResolveInfo home = pm.resolveActivity(
                    new android.content.Intent(android.content.Intent.ACTION_MAIN)
                            .addCategory(android.content.Intent.CATEGORY_HOME),
                    PackageManager.MATCH_DEFAULT_ONLY);
            if (home != null && home.activityInfo != null
                    && pkg.equals(home.activityInfo.packageName)) {
                return getString(R.string.drain_who_home);
            }
            try {
                return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0));
            } catch (PackageManager.NameNotFoundException e) {
                return pkg;
            }
        }
        switch (uid) {
            case Process.SYSTEM_UID:
                return getString(R.string.drain_who_system);
            case Process.PHONE_UID:
                return getString(R.string.drain_who_radio);
            case Process.BLUETOOTH_UID:
                return getString(R.string.drain_who_bluetooth);
            case Process.AUDIOSERVER_UID:
                return getString(R.string.drain_who_audio);
            default:
                final String name = pm.getNameForUid(uid);
                return name != null ? name : getString(R.string.drain_who_system);
        }
    }

    /**
     * What it was doing, from the name of the wake lock or job that held the r1 awake. Names
     * the page has no words for are shown as they are: better a raw name than a guess.
     */
    private CharSequence why(DrainModel.Who who) {
        if (who.uid == DrainModel.UID_NONE) {
            return getResources().getQuantityString(R.plurals.drain_who_wakeups_summary,
                    who.count, who.count);
        }
        final String name = who.name.toLowerCase(Locale.ROOT);
        final int text = name.contains("*alarm*") ? R.string.drain_why_alarm
                : name.contains("telephony") || name.contains("ril") ? R.string.drain_why_radio
                : name.contains("audio") || name.contains("exoplayer") ? R.string.drain_why_audio
                : name.contains("vibrator") ? R.string.drain_why_vibrator
                : name.contains("location") || name.contains("gnss") || name.contains("flp")
                        ? R.string.drain_why_location
                : name.contains("*sync*") ? R.string.drain_why_sync
                : name.contains("download") ? R.string.drain_why_download
                : name.contains("wifi") ? R.string.drain_why_wifi
                : name.contains("connectivity") || name.contains("gcm")
                        ? R.string.drain_why_network
                : name.contains("powerkey") ? R.string.drain_why_power_key
                : name.contains("*job*") || name.contains("/") ? R.string.drain_why_job
                : 0;
        return text != 0 ? getString(text) : who.name;
    }

    /** "31 min - about 5 %": the time is measured, the percent goes by the time. */
    private String share(DrainModel.Result r, long ms, int statePercent) {
        final int percent = (int) Math.round((double) statePercent * ms / r.ms[mState]);
        return percent >= 1 ? getString(R.string.drain_who_share, length(ms), percent)
                : getString(R.string.drain_who_share_small, length(ms));
    }

    private static String rate(double percentPerHour) {
        return String.format(Locale.getDefault(), percentPerHour < 10 ? "%.1f" : "%.0f",
                percentPerHour);
    }

    private String length(long ms) {
        final long minutes = Math.round(ms / 60_000.0);
        if (ms < 1000) {
            return getString(R.string.drain_seconds, 0);
        }
        if (ms < 60_000) {
            return getString(R.string.drain_seconds, Math.max(1, Math.round(ms / 1000.0)));
        }
        if (minutes < 60) {
            return getString(R.string.drain_minutes, minutes);
        }
        return minutes % 60 == 0 ? getString(R.string.drain_hours, minutes / 60)
                : getString(R.string.drain_hours_minutes, minutes / 60, minutes % 60);
    }

    /** "22:40", with the weekday when it was not today. */
    private String clock(long time) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(time)) {
            flags |= DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY;
        }
        return DateUtils.formatDateTime(requireContext(), time, flags);
    }
}
