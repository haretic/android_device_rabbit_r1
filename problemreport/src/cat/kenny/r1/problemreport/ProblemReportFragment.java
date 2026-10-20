// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.TextUtils;
import android.widget.Toast;

import androidx.preference.EditTextPreference;
import androidx.preference.Preference;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

/**
 * Settings > System > Problem report: what went wrong (optional), Save with its progress,
 * and what the last start left behind. The report is made by Report in ReportService; this
 * page only shows how far it is.
 */
public class ProblemReportFragment extends SettingsBasePreferenceFragment {

    private static final long POLL_MS = 500;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = this::poll;
    private EditTextPreference mNote;
    private Preference mSave;
    private Preference mLastStart;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.problem_report, rootKey);
        mNote = findPreference(Report.KEY_NOTE);
        mSave = findPreference("save");
        mLastStart = findPreference("last_start");

        mNote.setOnBindEditTextListener(edit -> {
            edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            edit.setMinLines(3);
        });
        mNote.setOnPreferenceChangeListener((p, value) -> {
            showNote((String) value);
            return true;
        });
        mSave.setOnPreferenceClickListener(p -> {
            Report.start(requireContext(), mNote.getText());
            poll();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        showNote(mNote.getText());
        showLastStart();
        poll();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mPoll);
    }

    private void showNote(String note) {
        mNote.setSummary(TextUtils.isEmpty(note) ? getString(R.string.note_hint) : note);
    }

    /** From r1-problem-report.sh check: "<panic|reset|normal> <kept|none>" */
    private void showLastStart() {
        final String s = Helper.lastStart();
        final boolean kept = s.endsWith(" kept");
        final int text;
        if (s.isEmpty()) {
            text = R.string.last_start_unknown;
        } else if (s.startsWith("panic ")) {
            text = kept ? R.string.last_start_panic : R.string.last_start_panic_lost;
        } else if (s.startsWith("reset ")) {
            text = kept ? R.string.last_start_reset : R.string.last_start_reset_lost;
        } else {
            text = kept ? R.string.last_start_kept : R.string.last_start_none;
        }
        mLastStart.setSummary(text);
    }

    private void poll() {
        mHandler.removeCallbacks(mPoll);
        final Report.State s = Report.state();
        if (s.state == Report.RUNNING) {
            mSave.setEnabled(false);
            mSave.setSummary(getString(R.string.save_running, stepName(s.text), s.step,
                    Report.STEPS));
            mHandler.postDelayed(mPoll, POLL_MS);
            return;
        }
        if (s.state == Report.IDLE && Helper.busy()) {
            // Collecting for a page that went, the report it was for is lost
            mSave.setEnabled(false);
            mSave.setSummary(R.string.error_busy);
            mHandler.postDelayed(mPoll, POLL_MS);
            return;
        }
        mSave.setEnabled(true);
        final boolean news = Report.takeNews();
        switch (s.state) {
            case Report.DONE:
                mSave.setSummary(getString(R.string.save_done, s.text));
                if (news) {
                    Toast.makeText(requireContext(), getString(R.string.save_done_toast, s.text),
                            Toast.LENGTH_LONG).show();
                    // Report took the description out of the settings with this report
                    mNote.setText(null);
                    showNote(null);
                }
                break;
            case Report.FAILED:
                mSave.setSummary(getString(R.string.save_failed, errorText(s.text)));
                break;
            default:
                mSave.setSummary(R.string.save_summary);
                break;
        }
    }

    private String stepName(String step) {
        switch (step) {
            case "system": return getString(R.string.step_system);
            case "logcat": return getString(R.string.step_logcat);
            case "kernel": return getString(R.string.step_kernel);
            case "crashes": return getString(R.string.step_crashes);
            case "power": return getString(R.string.step_power);
            case "devices": return getString(R.string.step_devices);
            case "apps": return getString(R.string.step_apps);
            case "saving": return getString(R.string.step_saving);
            default: return step;
        }
    }

    private String errorText(String code) {
        switch (code) {
            case "busy": return getString(R.string.error_busy);
            case "start": return getString(R.string.error_start);
            case "cant_write": return getString(R.string.error_cant_write);
            default: return getString(R.string.error_stopped);
        }
    }
}
