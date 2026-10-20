// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.utils.StringUtil;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import lineageos.health.HealthInterface;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Settings > Battery > Battery details. The readings are refreshed every 2 seconds while the
 * page is open, read off the main thread since the charging policy can keep its status file
 * waiting for up to 1.5 s.
 */
public class BatteryDetailsFragment extends SettingsBasePreferenceFragment {

    private static final long REFRESH_MS = 2000;
    /** Opening the page adds a point to the graph too, unless the last one is this fresh. */
    private static final long SAMPLE_ON_OPEN_MS = 5 * 60 * 1000L;
    /** Lineage's Charging control page in LineageParts */
    private static final String ACTION_CHARGING_CONTROL =
            "org.lineageos.lineageparts.CHARGING_CONTROL_SETTINGS";
    private static final String LINEAGE_PARTS = "org.lineageos.lineageparts";
    /** The policy's reasons this page explains (rabbit-r1-charging.c r1_reason_names) */
    private static final String REASON_USB_BUDGET = "usb-budget";
    private static final String REASON_THERMAL = "thermal-limit";
    private static final String REASON_LEVEL_LIMIT = "level-limit";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService mReader = Executors.newSingleThreadExecutor();
    private final Runnable mRefresh = this::refresh;
    private boolean mResumed;
    private boolean mReading;

    private HeaderPreference mHeader;
    private ValuePreference mVoltageCurrent;
    private ValuePreference mPowerTemperature;
    private ValuePreference mCharger;
    /** Null when the kernel has no charging policy */
    private Preference mChargeCurrent;
    /** Null without Lineage's Charging control */
    private Preference mChargeLimit;
    /** The last reading, for the charge current's second line */
    private Reading mLast;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.battery_details, rootKey);
        mHeader = findPreference("header");
        mVoltageCurrent = findPreference("voltage_current");
        mPowerTemperature = findPreference("power_temperature");
        mCharger = findPreference("charger");
        setUpChargeCurrent();
        setUpChargeLimit();
        if (mChargeCurrent == null && mChargeLimit == null) {
            getPreferenceScreen().removePreference(findPreference("care"));
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        final Context context = requireContext();
        SampleJob.schedule(context);
        if (System.currentTimeMillis() - History.lastTime(context) > SAMPLE_ON_OPEN_MS) {
            History.record(context, Reading.battery(context));
        }
        mHeader.reload();
        showChargeLimit();
        showChargeCurrent();
        mResumed = true;
        refresh();
    }

    @Override
    public void onPause() {
        mResumed = false;
        mHandler.removeCallbacks(mRefresh);
        super.onPause();
    }

    @Override
    public void onDestroy() {
        mReader.shutdownNow();
        super.onDestroy();
    }

    private void refresh() {
        mHandler.removeCallbacks(mRefresh);
        if (!mResumed) {
            return;
        }
        if (!mReading) {
            mReading = true;
            final Context app = requireContext().getApplicationContext();
            mReader.execute(() -> {
                final Reading reading = Reading.all(app);
                mHandler.post(() -> {
                    mReading = false;
                    if (mResumed) {
                        show(reading);
                    }
                });
            });
        }
        mHandler.postDelayed(mRefresh, REFRESH_MS);
    }

    private void show(Reading r) {
        mLast = r;
        mHeader.setText(r.percent == Reading.UNKNOWN ? getString(R.string.value_unknown)
                : getString(R.string.percent, r.percent), status(r));

        final String volts = r.millivolts == Reading.UNKNOWN ? getString(R.string.value_unknown)
                : getString(R.string.value_volts, r.millivolts / 1000f);
        final String amps = r.microamps == Reading.UNKNOWN ? getString(R.string.value_unknown)
                : getString(R.string.value_amps, r.microamps / 1e6f);
        mVoltageCurrent.setValue(getString(R.string.value_pair, volts, amps));

        final String watts = r.millivolts == Reading.UNKNOWN || r.microamps == Reading.UNKNOWN
                ? getString(R.string.value_unknown)
                : getString(R.string.value_watts, r.millivolts / 1000f * r.microamps / 1e6f);
        final String celsius = r.tenthsCelsius == Reading.UNKNOWN
                ? getString(R.string.value_unknown)
                : getString(R.string.value_celsius, r.tenthsCelsius / 10f);
        mPowerTemperature.setValue(getString(R.string.value_pair, watts, celsius));

        mCharger.setValue(charger(r));
        showChargeCurrent();
    }

    /** e.g. "Charging · full in about 40 min" */
    private String status(Reading r) {
        if (r.isFull()) {
            return getString(R.string.status_full);
        }
        if (r.isCharging()) {
            final String status = getString(R.string.status_charging);
            return r.chargeTimeMs <= 0 ? status : getString(R.string.status_and_time, status,
                    getString(R.string.time_to_full, duration(r.chargeTimeMs)));
        }
        if (r.isPlugged()) {
            return getString(r.policy != null && REASON_LEVEL_LIMIT.equals(r.policy.reason)
                    ? R.string.status_limit_hold : R.string.status_not_charging);
        }
        final String status = getString(R.string.status_on_battery);
        return r.dischargeTime == null || r.dischargeTime.isNegative()
                || r.dischargeTime.isZero() ? status
                : getString(R.string.status_and_time, status,
                        getString(R.string.time_left, duration(r.dischargeTime.toMillis())));
    }

    /**
     * What is plugged in, as the policy budgets it from the port's type (BC1.2): 1 A for a
     * charger or a charging port, 0.5 A for a computer's port or one it can't tell. Then the
     * USB voltage the r1 measures.
     */
    private String charger(Reading r) {
        if (!r.isPlugged()) {
            return getString(R.string.charger_none);
        }
        final int input = r.policy == null ? Reading.UNKNOWN : r.policy.inputUa;
        final String kind = getString(input >= ChargeCurrent.MAX_MA * 1000
                ? R.string.charger_fast
                : input >= ChargeCurrent.MIN_MA * 1000 ? R.string.charger_port
                : R.string.charger_usb);
        return r.vbusMillivolts == Reading.UNKNOWN ? kind
                : getString(R.string.charger_volts, kind, r.vbusMillivolts / 1000f);
    }

    private CharSequence duration(long millis) {
        return StringUtil.formatElapsedTime(requireContext(), millis, false, false);
    }

    private void setUpChargeCurrent() {
        mChargeCurrent = findPreference("charge_current");
        if (!ChargeCurrent.isSupported()) {
            ((PreferenceCategory) findPreference("care")).removePreference(mChargeCurrent);
            mChargeCurrent = null;
            return;
        }
        mChargeCurrent.setOnPreferenceClickListener(p -> {
            pickChargeCurrent();
            return true;
        });
    }

    /** "0.8 A · gentler, slower", and why it's less right now while plugged in. */
    private void showChargeCurrent() {
        if (mChargeCurrent == null) {
            return;
        }
        final String choice = ChargeCurrent.chosen();
        final int ma = ChargeCurrent.milliamps(choice);
        final String amps = ChargeCurrent.amps(ma);
        String summary = getString(ChargeCurrent.AUTO.equals(choice) ? R.string.current_default
                : ma <= ChargeCurrent.MIN_MA ? R.string.current_slowest
                : R.string.current_slower, amps);
        final ChargeCurrent.State policy = mLast == null ? null : mLast.policy;
        if (mLast != null && mLast.isCharging() && policy != null
                && policy.appliedUa != Reading.UNKNOWN && policy.appliedUa < ma * 1000) {
            final String now = ChargeCurrent.amps(policy.appliedUa / 1000);
            if (REASON_USB_BUDGET.equals(policy.reason)) {
                summary = summary + "\n" + getString(R.string.current_now_usb, now);
            } else if (REASON_THERMAL.equals(policy.reason)) {
                summary = summary + "\n" + getString(R.string.current_now_thermal, now);
            }
        }
        if (!summary.contentEquals(mChargeCurrent.getSummary() == null ? ""
                : mChargeCurrent.getSummary())) {
            mChargeCurrent.setSummary(summary);
        }
    }

    /**
     * The choices, with about how many watts go into the battery. No message: AlertDialog
     * leaves the list out when it has one.
     */
    private void pickChargeCurrent() {
        final Context context = requireContext();
        final String[] choices = ChargeCurrent.CHOICES;
        final CharSequence[] labels = new CharSequence[choices.length];
        final String chosen = ChargeCurrent.chosen();
        int checked = -1;
        for (int i = 0; i < choices.length; i++) {
            final int ma = ChargeCurrent.milliamps(choices[i]);
            labels[i] = getString(ChargeCurrent.AUTO.equals(choices[i])
                    ? R.string.current_choice_default : R.string.current_choice,
                    ChargeCurrent.amps(ma), ChargeCurrent.batteryWatts(ma));
            if (choices[i].equals(chosen)) {
                checked = i;
            }
        }
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.current_title)
                .setSingleChoiceItems(labels, checked, (d, which) -> {
                    if (!ChargeCurrent.choose(choices[which])) {
                        Toast.makeText(context, R.string.current_save_failed,
                                Toast.LENGTH_LONG).show();
                    }
                    d.dismiss();
                    showChargeCurrent();
                    refresh();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.show();
        WheelSelect.attach(dialog);
    }

    private void setUpChargeLimit() {
        mChargeLimit = findPreference("charge_limit");
        final Context context = requireContext();
        final Intent intent = new Intent(ACTION_CHARGING_CONTROL).setPackage(LINEAGE_PARTS);
        if (!HealthInterface.isChargingControlSupported(context)
                || context.getPackageManager().resolveActivity(intent, 0) == null) {
            ((PreferenceCategory) findPreference("care")).removePreference(mChargeLimit);
            mChargeLimit = null;
            return;
        }
        // Lineage's own page, not a second copy of it here
        mChargeLimit.setIntent(intent);
    }

    /** "Stops at 80 %", as Charging control has it. */
    private void showChargeLimit() {
        if (mChargeLimit == null) {
            return;
        }
        final HealthInterface health = HealthInterface.getInstance(requireContext());
        if (!health.getEnabled()) {
            mChargeLimit.setSummary(R.string.limit_off);
        } else if (health.getMode() == HealthInterface.MODE_LIMIT) {
            mChargeLimit.setSummary(getString(R.string.limit_stops_at, health.getLimit()));
        } else {
            mChargeLimit.setSummary(R.string.limit_scheduled);
        }
    }
}
