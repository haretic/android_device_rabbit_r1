// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.app.AlertDialog;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.TypedValue;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.preference.ListPreference;
import androidx.preference.Preference;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Kernel Tweaks > CPU overclock: the switch, a top speed per cluster (the steps the stress test
 * passed), and the stress test itself, which takes about two minutes for both steps.
 */
public class OverclockFragment extends SettingsBasePreferenceFragment {

    private static final long POLL_MS = 2000;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdate = this::update;
    private MainSwitchPreference mOn;
    private ListPreference mBig;
    private ListPreference mLittle;
    private Preference mTest;
    private Preference mSkip;

    @Override
    public void onViewCreated(android.view.View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        LongSummaries.apply(getListView());
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.overclock, rootKey);
        mOn = findPreference(Overclock.ON);
        mOn.setOnPreferenceChangeListener((p, value) -> {
            KernelTweaks.set(Overclock.ON, (Boolean) value ? "1" : "");
            changed();
            return true;
        });
        mBig = findPreference(Overclock.BIG);
        mLittle = findPreference(Overclock.LITTLE);
        for (ListPreference cluster : new ListPreference[] {mBig, mLittle}) {
            cluster.setOnPreferenceChangeListener((p, value) -> {
                Overclock.pick(p.getKey(), (String) value);
                changed();
                return true;
            });
        }
        mSkip = findPreference("skip");
        mSkip.setOnPreferenceClickListener(p -> {
            if (Overclock.isForced()) {
                Overclock.setForced(false);
                changed();
                return true;
            }
            final AlertDialog dialog = new AlertDialog.Builder(requireContext())
                    .setTitle(R.string.oc_skip_title)
                    .setMessage(R.string.oc_skip_text)
                    .setPositiveButton(R.string.oc_skip_use, (d, w) -> {
                        Overclock.setForced(true);
                        changed();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            // Cancel first: a press of the side button right away must not skip the test
            dialog.setOnShowListener(d ->
                    dialog.getButton(AlertDialog.BUTTON_NEGATIVE).requestFocus());
            dialog.show();
            // The side button presses its buttons, as in the page's other dialogs
            WheelSelect.attach(dialog);
            return true;
        });
        mTest = findPreference("test");
        mTest.setOnPreferenceClickListener(p -> {
            Overclock.startTest();
            mHandler.postDelayed(mUpdate, POLL_MS);
            update();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    @Override
    public void onPause() {
        super.onPause();
        mHandler.removeCallbacks(mUpdate);
    }

    private void changed() {
        KernelTweaks.apply();
        Controller.get(requireContext()).update();
        update();
    }

    private void update() {
        mHandler.removeCallbacks(mUpdate);
        final boolean running = Overclock.testState().startsWith("running");
        final boolean usable = KernelTweaks.isActive();
        mOn.setChecked(Overclock.isOn());
        mOn.setEnabled(usable && !running);
        mOn.setSummary(!usable ? getString(R.string.oc_needs_tweaks)
                : getString(R.string.oc_on_summary, ghz(Overclock.passed(Overclock.BIG)),
                        ghz(Overclock.passed(Overclock.LITTLE))));
        bind(mBig, Overclock.BIG, running);
        bind(mLittle, Overclock.LITTLE, running);

        // A link, not a row: small, in the accent colour, under the test it steps around
        final SpannableString link = new SpannableString(getString(
                Overclock.isForced() ? R.string.oc_skip_undo : R.string.oc_skip));
        link.setSpan(new UnderlineSpan(), 0, link.length(), 0);
        final TypedValue accent = new TypedValue();
        requireContext().getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        link.setSpan(new ForegroundColorSpan(accent.data), 0, link.length(), 0);
        mSkip.setSummary(link);
        mSkip.setEnabled(usable && !running);

        final String state = Overclock.testState();
        mTest.setEnabled(usable && !running);
        if (running) {
            mTest.setSummary(getString(R.string.oc_test_running,
                    state.substring("running".length()).trim()));
            mHandler.postDelayed(mUpdate, POLL_MS);
        } else if ("done".equals(state)) {
            mTest.setSummary(R.string.oc_test_done);
        } else if (state.startsWith("stopped") || state.startsWith("failed")) {
            mTest.setSummary(getString(R.string.oc_test_stopped, state));
        } else {
            mTest.setSummary(R.string.oc_test_summary);
        }
    }

    /**
     * The steps up to the one the test passed, or every step, the others marked, when the user
     * skipped it; the stock one is always there.
     */
    private void bind(ListPreference preference, String cluster, boolean running) {
        final String stock = Overclock.steps(cluster)[0];
        final List<CharSequence> entries = new ArrayList<>();
        final List<CharSequence> values = new ArrayList<>();
        for (String mhz : Overclock.steps(cluster)) {
            if (!Overclock.isAllowed(cluster, mhz)) {
                break;
            }
            entries.add(mhz.equals(stock) ? getString(R.string.oc_stock, ghz(mhz))
                    : Overclock.isPassed(cluster, mhz) ? ghz(mhz)
                    : getString(R.string.oc_untested_step, ghz(mhz)));
            values.add(mhz);
        }
        preference.setEntries(entries.toArray(new CharSequence[0]));
        preference.setEntryValues(values.toArray(new CharSequence[0]));
        final String picked = Overclock.picked(cluster);
        final String value = picked != null && values.contains(picked) ? picked : stock;
        preference.setValue(value);
        preference.setEnabled(Overclock.isOn() && KernelTweaks.isActive() && !running
                && values.size() > 1);
        preference.setSummary(values.size() == 1 ? getString(R.string.oc_untested)
                : value.equals(stock) ? getString(R.string.oc_stock, ghz(stock))
                : Overclock.isPassed(cluster, value)
                        ? getString(R.string.oc_step, ghz(value), ghz(stock))
                : getString(R.string.oc_step_untested, ghz(value), ghz(stock)));
    }

    private String ghz(String mhz) {
        return getString(R.string.oc_ghz, Integer.parseInt(mhz) / 1000f);
    }

    /** "2.4 GHz - stock 2.2 GHz" style summary for the Kernel Tweaks list. */
    static String summary(android.content.Context context) {
        if (!Overclock.isOn()) {
            return context.getString(R.string.oc_off);
        }
        final String big = Overclock.step(Overclock.BIG);
        final String little = Overclock.step(Overclock.LITTLE);
        if (big == null && little == null) {
            return context.getString(R.string.oc_on_stock);
        }
        return context.getString(R.string.oc_list_summary,
                context.getString(R.string.oc_ghz, Integer.parseInt(
                        TextUtils.isEmpty(big) ? Overclock.BIG_STEPS[0] : big) / 1000f),
                context.getString(R.string.oc_ghz, Integer.parseInt(
                        TextUtils.isEmpty(little) ? Overclock.LITTLE_STEPS[0] : little) / 1000f));
    }
}
