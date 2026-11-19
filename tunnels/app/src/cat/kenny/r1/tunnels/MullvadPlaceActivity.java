// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Mullvad, step 2 (N10): where the traffic comes out. Automatic, the countries with the most
 * servers and the one chosen, and all of them on a page of their own; each a choice, the
 * wheel going from one to the next and a press or a tap choosing. The same page later changes
 * a saved network's choice.
 *
 * In the set-up, going back removes the device just added to the account, as the key it was
 * added with is not kept.
 */
public final class MullvadPlaceActivity extends Page {
    static final String EXTRA_SETUP = "setup";
    static final String EXTRA_ID = "id";
    private static final String EXTRA_ALL = "all";
    private static final String EXTRA_CHOSEN = "chosen";
    private static final int REQ_ALL = 1;
    private static final int SUGGESTED = 3;

    private boolean mSetup;
    private boolean mAll;
    private Net mNet;
    private MullvadSetup mRegistration;
    private String mChosen = Net.AUTO;
    private String mChosenName = "";
    private Relays mRelays;
    private View mStatus;
    /** The choices, under the page's first line, built again once one comes from the list */
    private LinearLayout mList;
    private final List<View> mChoices = new ArrayList<>();
    private boolean mSaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mSetup = getIntent().getBooleanExtra(EXTRA_SETUP, false);
        mAll = getIntent().getBooleanExtra(EXTRA_ALL, false);
        mRegistration = mSetup ? Draft.mullvadSetup : null;
        if (mAll) {
            mChosen = getIntent().getStringExtra(EXTRA_CHOSEN);
        } else if (mSetup) {
            mNet = Draft.net;
        } else {
            mNet = Store.load(this, getIntent().getStringExtra(EXTRA_ID));
        }
        if (!mAll && (mNet == null || !mNet.isMullvad()
                || (mSetup && mRegistration == null))) {
            finish();
            return;
        }
        if (mNet != null) {
            mChosen = mNet.location;
            mChosenName = mNet.locationName;
        }
        title(R.string.kind_mullvad);
        if (mSetup) {
            steps(2, 2);
            final String paid = Draft.paidUntil > 0 ? new SimpleDateFormat(
                    DateFormat.getBestDateTimePattern(Locale.getDefault(), "dMMMM"),
                    Locale.getDefault()).format(new Date(Draft.paidUntil)) : "-";
            lead(Html.fromHtml(getString(R.string.place_lead, TextUtils.htmlEncode(paid),
                    Math.max(1, Draft.deviceNumber)), Html.FROM_HTML_MODE_LEGACY));
        } else {
            lead(getText(R.string.place_lead_edit));
        }
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mBody.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mStatus = note(mList, R.drawable.ic_info, getText(R.string.relays_loading));
        hint(R.string.hint_place);
        new Thread(() -> {
            Relays relays = null;
            try {
                relays = Relays.load(getApplicationContext());
            } catch (IOException e) {
                Log.w(Store.TAG, "No server list: " + e.getClass().getSimpleName());
            }
            final Relays got = relays;
            runOnUiThread(() -> loaded(got));
        }).start();
    }

    private void loaded(Relays relays) {
        if (isDestroyed()) {
            return;
        }
        mRelays = relays;
        noteText(mStatus, relays == null ? getText(R.string.relays_failed) : "", true);
        if (mAll) {
            all();
        } else {
            choices();
        }
    }

    /** N10: automatic, the chosen country, the countries with most servers, all of them */
    private void choices() {
        mList.removeAllViews();
        mChoices.clear();
        final View auto = row(mList, R.drawable.ic_bolt, getText(R.string.auto_title),
                getText(R.string.auto_sum));
        choice(auto, Net.AUTO, getString(R.string.auto_title));
        if (mRelays != null) {
            final List<Relays.Country> countries = mRelays.countries();
            final List<Relays.Country> shown = new ArrayList<>();
            final Relays.Country chosen = Net.AUTO.equals(mChosen) ? null
                    : mRelays.country(mChosen);
            if (chosen != null) {
                shown.add(chosen);
            }
            final List<Relays.Country> most = new ArrayList<>(countries);
            Collections.sort(most, (a, b) -> Integer.compare(b.servers, a.servers));
            for (Relays.Country country : most) {
                if (shown.size() >= SUGGESTED) {
                    break;
                }
                if (!shown.contains(country) && (chosen == null
                        || !country.code.equals(chosen.code))) {
                    shown.add(country);
                }
            }
            for (Relays.Country country : shown) {
                countryRow(country);
            }
            final View all = row(mList, R.drawable.ic_search,
                    getString(R.string.all_countries, countries.size()), updated());
            chevron(all);
            pressHint(all, getText(R.string.hint_row));
            all.setOnClickListener(v -> startActivityForResult(new Intent(this,
                    MullvadPlaceActivity.class).putExtra(EXTRA_ALL, true)
                    .putExtra(EXTRA_CHOSEN, mChosen), REQ_ALL));
        }
        // The status line under the choices
        mList.addView(mStatus);
        final TextView go = buttons(mList, mSetup ? R.string.save_connect : R.string.save);
        go.setOnClickListener(v -> save());
        mark();
        auto.requestFocus();
    }

    /** The whole list, by name: choosing one goes back with it */
    private void all() {
        if (mRelays == null) {
            return;
        }
        mList.removeAllViews();
        mList.addView(mStatus);
        View first = null;
        for (Relays.Country country : mRelays.countries()) {
            final View row = countryRow(country);
            row.setOnClickListener(v -> {
                setResult(RESULT_OK, new Intent().putExtra(EXTRA_CHOSEN, country.code));
                finish();
            });
            if (first == null || country.code.equals(mChosen)) {
                first = row;
            }
        }
        mark();
        if (first != null) {
            first.requestFocus();
        }
    }

    private View countryRow(Relays.Country country) {
        final String cities = TextUtils.join(", ", country.cities);
        final View row = row(mList, R.drawable.ic_flag, country.name, getResources()
                .getQuantityString(R.plurals.country_sum, country.servers, cities,
                        country.servers));
        choice(row, country.code, country.name);
        return row;
    }

    private void choice(View row, String code, String name) {
        row.setTag(code);
        radio(row, false);
        pressHint(row, getText(R.string.hint_place));
        row.setOnClickListener(v -> {
            mChosen = code;
            mChosenName = name;
            mark();
        });
        mChoices.add(row);
    }

    /** The radios: the chosen one filled */
    private void mark() {
        for (View row : mChoices) {
            radio(row, mChosen != null && mChosen.equals(row.getTag()));
        }
    }

    private String updated() {
        if (mRelays == null || DateUtils.isToday(mRelays.fetched)) {
            return getString(R.string.all_sum_today);
        }
        return getString(R.string.all_sum_older, DateUtils.formatDateTime(this, mRelays.fetched,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR));
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_ALL) {
            if (result == RESULT_OK && data != null && data.getStringExtra(EXTRA_CHOSEN) != null
                    && mRelays != null) {
                final String code = data.getStringExtra(EXTRA_CHOSEN);
                final Relays.Country country = mRelays.country(code);
                if (country != null) {
                    mChosen = code;
                    mChosenName = country.name;
                    // Shown among the choices, chosen
                    choices();
                }
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    private void save() {
        if (mSaving || mNet == null) {
            return;
        }
        mSaving = true;
        mNet.location = mChosen != null ? mChosen : Net.AUTO;
        mNet.locationName = Net.AUTO.equals(mNet.location) ? "" : mChosenName;
        final Net saving = mNet;
        final MullvadSetup setup = mRegistration;
        new Thread(() -> {
            Engine.Error error = Engine.Error.NONE;
            try {
                if (mSetup) {
                    if (setup == null) throw new Mullvad.Failure(0, "", Engine.Error.STORAGE);
                    setup.save();
                } else if (!Store.save(getApplicationContext(), saving)) {
                    throw new Mullvad.Failure(0, "", Engine.Error.STORAGE);
                }
            } catch (IOException e) { error = Mullvad.errorOf(e); }
            final Engine.Error result = error;
            runOnUiThread(() -> saved(saving, result));
        }, "mullvad-save").start();
    }

    private void saved(Net saved, Engine.Error error) {
        mSaving = false;
        if (error != Engine.Error.NONE) {
            if (mSetup) {
                if (Draft.mullvadSetup == mRegistration) {
                    Draft.net = null;
                    Draft.mullvadSetup = null;
                    Draft.typedKey = null;
                }
                setResult(RESULT_FIRST_USER);
                finish();
            } else if (!isDestroyed()) {
                noteText(mStatus, EngineUi.error(this, error), true);
            }
            return;
        }
        if (mSetup) {
            if (Draft.mullvadSetup == mRegistration) Draft.clear();
            if (!isFinishing() && !isDestroyed()) turnOn(saved.id);
            return;
        }
        if (isFinishing() || isDestroyed()) return;
        final Status.Snapshot now = Status.get();
        if (Status.isCurrent(now, saved.id) && now.state != Status.FAILED) {
            TunnelService.start(this, saved.id);
        }
        finish();
    }

    @Override
    protected void onTurnedOn(String id, boolean started) {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void back() {
        if (mSetup && !mSaving) discardSetup();
        super.back();
    }

    private void discardSetup() {
        final MullvadSetup setup = mRegistration;
        if (setup == null) return;
        mRegistration = null;
        MullvadAccountActivity.discard(setup);
        if (Draft.mullvadSetup == setup) {
            Draft.net = null;
            Draft.mullvadSetup = null;
            Draft.typedKey = null;
        }
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && mSetup && !mSaving) discardSetup();
        super.onDestroy();
    }
}
