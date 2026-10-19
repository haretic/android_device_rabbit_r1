// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;

import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.SwitchPreferenceCompat;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * Settings > System > Kernel Tweaks. Every choice goes to persist.sys.r1.kt.* at once and
 * ktweaks/r1-ktweaks.sh applies it; the default is stored as nothing, so a later default
 * change in the build reaches everyone who didn't pick something else.
 */
public class KernelTweaksFragment extends SettingsBasePreferenceFragment {

    private static final String KEY_SHOW_DOCS = "kt_show_docs";
    /** The script needs a moment, then the result is shown. */
    private static final long RESULT_DELAY_MS = 1500;

    private static final String[] TRIPS = {"75", "80", "85", "90"};
    private static final String DEFAULT_TRIP = "85";
    private static final int[] ZRAM_MIB = {0, 512, 1024, 1536, 2048, 3072};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mUpdate = this::update;
    private MainSwitchPreference mEnabled;
    private ListPreference mMemSleep;
    private SwitchPreferenceCompat mPark;
    private ListPreference mCores;
    private ListPreference mTrip;
    private ListPreference mZram;
    private SwitchPreferenceCompat mShowDocs;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.kernel_tweaks, rootKey);
        final Context context = requireContext();

        mEnabled = findPreference(KernelTweaks.ENABLED);
        mEnabled.setOnPreferenceChangeListener((p, value) -> {
            KernelTweaks.setEnabled((Boolean) value);
            changed();
            return true;
        });

        mMemSleep = findPreference(KernelTweaks.MEM_SLEEP);
        mMemSleep.setEntries(new CharSequence[] {
                getString(R.string.kt_sleep_deep), getString(R.string.kt_sleep_s2idle)});
        mMemSleep.setEntryValues(new CharSequence[] {"deep", "s2idle"});
        bind(mMemSleep, "deep");

        mPark = findPreference(KernelTweaks.PARK);
        mPark.setOnPreferenceChangeListener((p, value) -> {
            KernelTweaks.set(KernelTweaks.PARK, (Boolean) value ? "1" : "");
            changed();
            return true;
        });

        mCores = findPreference(KernelTweaks.CORES);
        final CharSequence[] cores = new CharSequence[8];
        final CharSequence[] coreValues = new CharSequence[8];
        for (int i = 0; i < 8; i++) {
            cores[i] = getString(R.string.kt_cores_value, 8 - i);
            coreValues[i] = Integer.toString(8 - i);
        }
        mCores.setEntries(cores);
        mCores.setEntryValues(coreValues);
        bind(mCores, "8");

        mTrip = findPreference(KernelTweaks.TRIP);
        final CharSequence[] trips = new CharSequence[TRIPS.length];
        for (int i = 0; i < TRIPS.length; i++) {
            trips[i] = getString(R.string.kt_trip_value, Integer.parseInt(TRIPS[i]));
        }
        mTrip.setEntries(trips);
        mTrip.setEntryValues(TRIPS);
        bind(mTrip, DEFAULT_TRIP);

        mZram = findPreference(KernelTweaks.ZRAM);
        final List<CharSequence> sizes = new ArrayList<>();
        final List<CharSequence> sizeValues = new ArrayList<>();
        final String defaultZram = KernelTweaks.defaultZram();
        boolean defaultListed = false;
        for (int mib : ZRAM_MIB) {
            sizes.add(zramLabel(mib));
            sizeValues.add(Integer.toString(mib));
            defaultListed |= Integer.toString(mib).equals(defaultZram);
        }
        if (!defaultListed && !TextUtils.isEmpty(defaultZram)) {
            sizes.add(0, zramLabel(Integer.parseInt(defaultZram)));
            sizeValues.add(0, defaultZram);
        }
        mZram.setEntries(sizes.toArray(new CharSequence[0]));
        mZram.setEntryValues(sizeValues.toArray(new CharSequence[0]));
        bind(mZram, defaultZram);

        mShowDocs = findPreference("show_docs");
        mShowDocs.setChecked(Profiles.prefs(context).getBoolean(KEY_SHOW_DOCS, false));
        mShowDocs.setOnPreferenceChangeListener((p, value) -> {
            Profiles.prefs(context).edit().putBoolean(KEY_SHOW_DOCS, (Boolean) value).apply();
            mHandler.post(mUpdate);
            return true;
        });

        findPreference("reset").setOnPreferenceClickListener(p -> {
            final AlertDialog dialog = new AlertDialog.Builder(context)
                    .setMessage(R.string.kt_reset_confirm)
                    .setPositiveButton(R.string.kt_reset, (d, which) -> {
                        KernelTweaks.reset();
                        changed();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.show();
            WheelSelect.attach(dialog);
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

    /** Stores the choice, with {@code defaultValue} stored as nothing. */
    private void bind(ListPreference preference, String defaultValue) {
        preference.setOnPreferenceChangeListener((p, value) -> {
            KernelTweaks.set(p.getKey(), value.equals(defaultValue) ? "" : (String) value);
            changed();
            return true;
        });
    }

    private void changed() {
        KernelTweaks.apply();
        Controller.get(requireContext()).update();
        update();
        mHandler.removeCallbacks(mUpdate);
        mHandler.postDelayed(mUpdate, RESULT_DELAY_MS);
    }

    private String zramLabel(int mib) {
        return mib == 0 ? getString(R.string.kt_zram_off)
                : getString(R.string.kt_zram_value,
                        Formatter.formatShortFileSize(requireContext(), mib * 1048576L));
    }

    private void update() {
        final boolean enabled = KernelTweaks.isEnabled();
        mEnabled.setChecked(enabled);
        final int changed = KernelTweaks.changedCount();
        CharSequence state;
        if (KernelTweaks.isPaused()) {
            state = getString(R.string.kt_paused);
            mEnabled.setChecked(false);
        } else if (!enabled) {
            state = getString(R.string.kt_use_off);
        } else if (changed == 0) {
            state = getString(R.string.kt_use_none);
        } else {
            state = getResources().getQuantityString(R.plurals.kt_use_count, changed, changed);
        }
        final String result = KernelTweaks.result();
        if (enabled && !TextUtils.isEmpty(result) && !"ok".equals(result)) {
            state = state + "\n" + getString(R.string.kt_failed, result);
        }
        mEnabled.setSummary(state);

        show(mMemSleep, "deep", R.string.kt_sleep_what);
        final boolean park = "1".equals(KernelTweaks.get(KernelTweaks.PARK));
        mPark.setChecked(park);
        show(mCores, "8", R.string.kt_cores_what);
        show(mTrip, DEFAULT_TRIP, R.string.kt_trip_what);
        show(mZram, KernelTweaks.defaultZram(), R.string.kt_zram_what);
    }

    /** The value, "(default)" or "changed", and the explanation when they are on. */
    private void show(ListPreference preference, String defaultValue, int docs) {
        final String stored = KernelTweaks.get(preference.getKey());
        final String value = TextUtils.isEmpty(stored) ? defaultValue : stored;
        preference.setValue(value);
        final CharSequence entry = preference.getEntry() != null ? preference.getEntry() : value;
        String summary = TextUtils.isEmpty(stored)
                ? getString(R.string.kt_default, entry) : getString(R.string.kt_changed, entry);
        if (mShowDocs.isChecked()) {
            summary = summary + "\n\n" + getString(docs);
        }
        preference.setSummary(summary);
    }
}
