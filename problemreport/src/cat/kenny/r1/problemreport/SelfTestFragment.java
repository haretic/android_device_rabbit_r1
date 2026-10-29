// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.fragment.app.DialogFragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;
import com.android.settingslib.widget.TopIntroPreference;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Starts the runner and follows its files while this page is visible. */
public class SelfTestFragment extends SettingsBasePreferenceFragment {

    private static final long POLL_MS = 500;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    private final Runnable mPoll = this::poll;
    private String mToken;
    private SelfTest.Reader mReader;
    private ProgressPreference mProgress;
    private Preference mStop;
    private boolean mVisible;
    private int mGeneration;
    private List<SelfTest.Test> mShownTests;
    private String mShownSummary;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mToken = SelfTest.token((savedInstanceState != null ? savedInstanceState : requireArguments())
                .getString(SelfTest.EXTRA_TOKEN));
        if (mToken.isEmpty()) {
            showStart();
        } else {
            followRun();
        }
    }

    @Override
    public void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(SelfTest.EXTRA_TOKEN, mToken);
    }

    @Override
    public void onResume() {
        super.onResume();
        mVisible = true;
        poll();
    }

    @Override
    public void onPause() {
        super.onPause();
        mVisible = false;
        mGeneration++;
        mHandler.removeCallbacks(mPoll);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        mWorker.shutdownNow();
        mHandler.removeCallbacksAndMessages(null);
    }

    private PreferenceScreen screen() {
        final PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(
                requireContext());
        screen.setTitle(R.string.self_test_title);
        setPreferenceScreen(screen);
        mShownTests = null;
        mShownSummary = null;
        return screen;
    }

    private Preference row(PreferenceScreen screen, CharSequence title, CharSequence summary,
            Preference.OnPreferenceClickListener click) {
        final Preference row = new TextPreference(requireContext());
        row.setTitle(title);
        row.setSummary(summary);
        row.setIconSpaceReserved(false);
        row.setSingleLineTitle(false);
        row.setPersistent(false);
        row.setSelectable(click != null);
        row.setOnPreferenceClickListener(click);
        screen.addPreference(row);
        return row;
    }

    private void showStart() {
        mReader = null;
        mProgress = null;
        final PreferenceScreen screen = screen();
        final TopIntroPreference intro = new TopIntroPreference(requireContext());
        intro.setTitle(R.string.self_test_intro);
        intro.setSelectable(false);
        screen.addPreference(intro);
        row(screen, getString(R.string.self_test_quick), getString(R.string.self_test_quick_summary),
                p -> confirm("quick"));
        row(screen, getString(R.string.self_test_full), getString(R.string.self_test_full_summary),
                p -> confirm("full"));
    }

    private boolean confirm(String kind) {
        if (getChildFragmentManager().findFragmentByTag("confirm") == null) {
            final Bundle args = new Bundle();
            args.putString("kind", kind);
            final ConfirmDialog dialog = new ConfirmDialog();
            dialog.setArguments(args);
            dialog.show(getChildFragmentManager(), "confirm");
        }
        return true;
    }

    private void start(String kind) {
        try {
            mToken = SelfTest.start(requireContext(), kind);
            followRun();
            poll();
        } catch (RuntimeException e) {
            SelfTestDetailFragment.message(requireContext(), getString(R.string.self_test_title),
                    getString(R.string.self_test_start_error));
        }
    }

    private void followRun() {
        mReader = new SelfTest.Reader(mToken, SelfTest.started(requireContext(), mToken));
        final PreferenceScreen screen = screen();
        mProgress = new ProgressPreference(requireContext());
        mProgress.setTitle(R.string.self_test_starting);
        screen.addPreference(mProgress);
        mStop = row(screen, getString(R.string.self_test_stop), null, p -> {
            try {
                SelfTest.cancel(mToken);
                showStopping();
            } catch (RuntimeException e) {
                SelfTestDetailFragment.message(requireContext(), getString(R.string.self_test_stop),
                        getString(R.string.self_test_stop_error));
            }
            return true;
        });
        if (SelfTest.stopping(mToken)) showStopping();
    }

    private void showStopping() {
        mStop.setEnabled(false);
        mStop.setSummary(R.string.self_test_stopping);
    }

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        if (!mVisible || mReader == null) return;
        final int generation = ++mGeneration;
        final SelfTest.Reader reader = mReader;
        mWorker.execute(() -> {
            final SelfTest.Snapshot snapshot = reader.read();
            mHandler.post(() -> {
                if (!mVisible || generation != mGeneration) return;
                show(snapshot);
                mHandler.postDelayed(mPoll, POLL_MS);
            });
        });
    }

    private void show(SelfTest.Snapshot snapshot) {
        final SelfTest.Status status = snapshot.status;
        if (snapshot.startFailed || (status != null && status.terminal())) {
            showResults(snapshot);
            return;
        }
        if (mProgress == null) followRun();
        if (status == null) return;
        mProgress.setTitle(getString(R.string.self_test_progress,
                status.index, status.total, status.text));
        // The last test is still running
        int passed = 0, failed = 0;
        for (int i = 0; i < snapshot.tests.size() - 1; i++) {
            final SelfTest.Test test = snapshot.tests.get(i);
            if (test.failedFirst) {
                failed++;
            } else if (!test.skipped) {
                passed++;
            }
        }
        mProgress.setSummary(getString(R.string.self_test_counts, passed, failed));
        mProgress.update(status.index - 1, status.total);
    }

    private void showResults(SelfTest.Snapshot snapshot) {
        final SelfTest.Status status = snapshot.status;
        final String summary;
        if (status == null) {
            summary = getString(R.string.self_test_didnt_start);
        } else if ("error".equals(status.state)) {
            summary = getString(R.string.self_test_error, status.text);
        } else {
            final String counts = getString(R.string.self_test_results,
                    status.passed, status.failed, status.skipped);
            summary = "cancelled".equals(status.state)
                    ? getString(R.string.self_test_stopped, counts) : counts;
        }
        if (summary.equals(mShownSummary) && snapshot.tests == mShownTests) return;
        final PreferenceScreen screen = screen();
        mProgress = null;
        row(screen, summary, null, null);
        // A row for each test that failed, or failed and then passed, with its screenshots;
        // the others in a row each for skipped and passed, so the actions are a few steps on
        final List<String> skipped = new ArrayList<>(), passed = new ArrayList<>();
        for (SelfTest.Test test : snapshot.tests) {
            if (test.failed() || test.passedOnRetry()) {
                row(screen, getString(R.string.self_test_test_verdict, test.name,
                        verdict(requireContext(), test)),
                        String.join("\n", test.failures), p -> {
                            getParentFragmentManager().beginTransaction()
                                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                                            SelfTestDetailFragment.forTest(requireContext(),
                                                    mToken, test))
                                    .addToBackStack(null).commit();
                            return true;
                        });
            } else if (test.skippedOnly()) {
                skipped.add(test.reason.isEmpty() ? test.name : test.reason);
            } else {
                passed.add(test.name);
            }
        }
        group(screen, R.string.self_test_skipped_group, skipped);
        group(screen, R.string.self_test_passed_group, passed);
        row(screen, getString(R.string.self_test_add_report), null, p -> {
            startActivity(new Intent(requireContext(), ProblemReportActivity.class)
                    .putExtra(SelfTest.EXTRA_TOKEN, mToken));
            return true;
        });
        row(screen, getString(R.string.self_test_again), null, p -> {
            mGeneration++;
            mHandler.removeCallbacks(mPoll);
            SelfTest.forget(requireContext());
            mToken = "";
            showStart();
            return true;
        });
        mShownSummary = summary;
        mShownTests = snapshot.tests;
    }

    static String verdict(Context context, SelfTest.Test test) {
        return context.getString(test.failed() ? R.string.self_test_failed
                : test.passedOnRetry() ? R.string.self_test_passed_retry
                : test.skippedOnly() ? R.string.self_test_skipped : R.string.self_test_passed);
    }

    /** One row for tests alike: their names, all of them in a dialog when pressed. */
    private void group(PreferenceScreen screen, int title, List<String> names) {
        if (names.isEmpty()) return;
        final String heading = getString(title, names.size());
        final String list = String.join(", ", names);
        row(screen, heading, list, p -> {
            SelfTestDetailFragment.message(requireContext(), heading, list);
            return true;
        }).setSummary(list);
    }

    public static class ConfirmDialog extends DialogFragment {
        @Override
        public void onStart() {
            super.onStart();
            final AlertDialog dialog = (AlertDialog) requireDialog();
            // The wheel moves vertically, including between side-by-side dialog buttons.
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setNextFocusDownId(android.R.id.button1);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setNextFocusUpId(android.R.id.button2);
        }

        @Override
        public Dialog onCreateDialog(Bundle savedInstanceState) {
            final String kind = requireArguments().getString("kind");
            return new AlertDialog.Builder(requireContext())
                    .setTitle("quick".equals(kind)
                            ? R.string.self_test_quick : R.string.self_test_full)
                    .setMessage(R.string.self_test_confirm)
                    .setNegativeButton(android.R.string.cancel, null)
                    .setPositiveButton(R.string.self_test_start, (dialog, which) ->
                            ((SelfTestFragment) requireParentFragment()).start(kind))
                    .create();
        }
    }

    /** Long failure text opens in a scrollable dialog, keeping rows within wheel reach. */
    static class TextPreference extends Preference {
        TextPreference(Context context) {
            super(context);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final TextView summary = (TextView) holder.findViewById(android.R.id.summary);
            if (summary != null) {
                summary.setMaxLines(4);
                summary.setEllipsize(TextUtils.TruncateAt.END);
            }
        }
    }

    private static class ProgressPreference extends Preference {
        private int mDone, mTotal;

        ProgressPreference(Context context) {
            super(context);
            setLayoutResource(R.layout.self_test_progress);
            setSelectable(false);
            setPersistent(false);
        }

        void update(int done, int total) {
            mDone = done;
            mTotal = total;
            notifyChanged();
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            final ProgressBar bar = (ProgressBar) holder.findViewById(R.id.self_test_progress);
            bar.setIndeterminate(mTotal == 0);
            bar.setMax(Math.max(1, mTotal));
            bar.setProgress(mDone);
        }
    }
}
