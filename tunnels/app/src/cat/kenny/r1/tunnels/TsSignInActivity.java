// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import cat.kenny.r1.tunnels.ts.TailscaleClient;
import cat.kenny.r1.tunnels.ts.TsJson;

/**
 * Tailscale, step 1 (N5): how this r1 signs in. With a browser on another device, by default:
 * the network is saved and started, Tailscale gives a sign-in link, shown as a code for a
 * phone's camera and as text, and the page moves on by itself once Tailscale says this r1 is
 * in. Or with a key typed in, which goes to Tailscale and is wiped here. Either with an own
 * control server. Each choice is a row the wheel stops on and a press or a tap picks.
 *
 * Opened with a saved network's id, it signs that one in again and goes back when done.
 */
public final class TsSignInActivity extends Page {
    static final String EXTRA_ID = "id";
    private static final long CLIENT_MS = 20_000;
    private static final long LINK_MS = 30_000;
    private static final long KEY_MS = 60_000;
    private static final long SIGN_IN_MS = 10 * 60_000;
    private static final long POLL_MS = 250;

    /** The network being signed in, and whether this set-up made it */
    private String mId;
    private boolean mMade;
    private boolean mAgain;

    private boolean mUseKey;
    private boolean mOwnServer;
    private String mControl = "";
    private char[] mKey;

    private LinearLayout mChoose;
    private View mBrowserRow;
    private View mKeyRow;
    private View mKeyField;
    private EditText mKeyEdit;
    private View mServerRow;
    private View mServerField;
    private EditText mServerEdit;
    private View mProblem;

    private LinearLayout mWork;
    private TextView mWorkLead;
    private View mWorkNote;
    private LinearLayout mQrBox;
    private QrView mQr;
    private TextView mUrl;
    private View mBack;
    private TextView mRetry;

    /** Working: started and waiting on Tailscale; the time it gives up, and what it waits for */
    private boolean mWorking;
    private boolean mClientReady;
    private boolean mFailed;
    private boolean mDone;
    private long mGiveUp;
    private int mGiveUpText;
    private String mShownUrl = "";
    private AutoCloseable mObserver;

    private final Runnable mWaitClient = this::waitClient;
    private final Runnable mTimeout = this::timeout;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mId = getIntent().getStringExtra(EXTRA_ID);
        Net existing = null;
        if (mId != null) {
            existing = Store.load(this, mId);
            if (existing == null || !EngineUi.isTailscale(existing)) {
                finish();
                return;
            }
            mAgain = true;
            mControl = existing.controlUrl;
            mOwnServer = !mControl.isEmpty();
        }
        title(R.string.kind_ts);
        if (!mAgain) {
            steps(1, 2);
        }
        buildChoose();
        buildWork();
        showChoose();
        mBrowserRow.requestFocus();
    }

    // Choosing how

    private void buildChoose() {
        mChoose = new LinearLayout(this);
        mChoose.setOrientation(LinearLayout.VERTICAL);
        mBody.addView(mChoose, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final TextView lead = (TextView) getLayoutInflater().inflate(R.layout.lead, mChoose,
                false);
        lead.setText(R.string.ts_lead);
        mChoose.addView(lead);

        mBrowserRow = row(mChoose, R.drawable.ic_browser, getText(R.string.ts_browser),
                getText(R.string.ts_browser_sum));
        mBrowserRow.setTag("browser");
        radio(mBrowserRow, true);
        pressHint(mBrowserRow, getText(R.string.hint_choose));
        mBrowserRow.setOnClickListener(v -> {
            mUseKey = false;
            mark();
        });
        mKeyRow = row(mChoose, R.drawable.ic_key, getText(R.string.ts_key),
                getText(R.string.ts_key_sum));
        mKeyRow.setTag("key");
        radio(mKeyRow, false);
        pressHint(mKeyRow, getText(R.string.hint_choose));
        mKeyRow.setOnClickListener(v -> {
            mUseKey = true;
            mark();
            mKeyEdit.requestFocus();
        });
        mKeyField = getLayoutInflater().inflate(R.layout.field, mChoose, false);
        ((TextView) mKeyField.findViewById(R.id.label)).setText(R.string.ts_key_field);
        mKeyEdit = mKeyField.findViewById(R.id.edit);
        mKeyEdit.setId(R.id.field_ts_key);
        mKeyEdit.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_VARIATION_PASSWORD | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mKeyEdit.setHint(R.string.ts_key_example);
        mKeyEdit.setSaveEnabled(false);
        pressHint(mKeyEdit, getText(R.string.hint_type_page));
        mChoose.addView(mKeyField);

        mServerRow = row(mChoose, R.drawable.ic_server, getText(R.string.ts_server), "");
        mServerRow.setTag("server");
        switchOf(mServerRow, false);
        pressHint(mServerRow, getText(R.string.hint_toggle));
        mServerRow.setOnClickListener(v -> {
            mOwnServer = !mOwnServer;
            mark();
            if (mOwnServer) {
                mServerEdit.requestFocus();
            }
        });
        mServerField = getLayoutInflater().inflate(R.layout.field, mChoose, false);
        ((TextView) mServerField.findViewById(R.id.label)).setText(R.string.ts_server_field);
        mServerEdit = mServerField.findViewById(R.id.edit);
        mServerEdit.setId(R.id.field_ts_server);
        mServerEdit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mServerEdit.setHint(R.string.ts_server_example);
        mServerEdit.setText(mControl);
        pressHint(mServerEdit, getText(R.string.hint_type_page));
        mChoose.addView(mServerField);

        mProblem = note(mChoose, R.drawable.ic_info, "");
        noteText(mProblem, "", true);
        final TextView go = buttons(mChoose, R.string.ts_sign_in);
        pressHint(go, getText(R.string.hint_choose));
        go.setOnClickListener(v -> start());
        if (!mAgain) {
            EngineUi.footer(this, mChoose, R.string.ts_trademark);
        }
        mark();
    }

    /** The radios, the switch, and the fields they bring */
    private void mark() {
        radio(mBrowserRow, !mUseKey);
        radio(mKeyRow, mUseKey);
        mKeyField.setVisibility(mUseKey ? View.VISIBLE : View.GONE);
        switchOn(mServerRow, mOwnServer);
        summary(mServerRow, getText(mOwnServer ? R.string.ts_server_on : R.string.ts_server_off));
        mServerField.setVisibility(mOwnServer ? View.VISIBLE : View.GONE);
    }

    private void showChoose() {
        mWorking = false;
        mChoose.setVisibility(View.VISIBLE);
        mWork.setVisibility(View.GONE);
        hint(R.string.hint_choose);
    }

    // Signing in

    private void buildWork() {
        mWork = new LinearLayout(this);
        mWork.setOrientation(LinearLayout.VERTICAL);
        mBody.addView(mWork, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mWorkLead = (TextView) getLayoutInflater().inflate(R.layout.lead, mWork, false);
        mWork.addView(mWorkLead);
        mQrBox = new LinearLayout(this);
        mWork.addView(mQrBox, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mQr = (QrView) getLayoutInflater().inflate(R.layout.qr, mQrBox, false);
        mQrBox.addView(mQr);
        final LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        mQrBox.addView(words);
        final TextView orOpen = (TextView) getLayoutInflater().inflate(R.layout.lead, words,
                false);
        orOpen.setText(R.string.ts_or_open);
        orOpen.setTextSize(13);
        orOpen.setTextColor(getColor(R.color.text_faint));
        words.addView(orOpen);
        mUrl = (TextView) getLayoutInflater().inflate(R.layout.text_block, words, false);
        mUrl.setTextIsSelectable(true);
        pressHint(mUrl, getText(R.string.hint_url));
        words.addView(mUrl);
        arrange();
        mWorkNote = note(mWork, R.drawable.ic_info, "");
        noteText(mWorkNote, "", false);
        final View pair = getLayoutInflater().inflate(R.layout.buttons, mWork, false);
        mBack = pair.findViewById(R.id.back);
        mBack.setOnClickListener(v -> back());
        mRetry = pair.findViewById(R.id.go);
        mRetry.setText(R.string.try_again);
        pressHint(mRetry, getText(R.string.hint_choose));
        mRetry.setOnClickListener(v -> retry());
        mWork.addView(pair);
    }

    /** The code beside its words where the r1 lies on its side, over them where it stands */
    private void arrange() {
        final boolean wide = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        mQrBox.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        final float density = getResources().getDisplayMetrics().density;
        final int qr = (int) ((wide ? 180 : 200) * density);
        final LinearLayout.LayoutParams code = new LinearLayout.LayoutParams(qr, qr);
        code.gravity = wide ? android.view.Gravity.TOP : android.view.Gravity.CENTER_HORIZONTAL;
        code.bottomMargin = (int) (10 * density);
        if (wide) {
            code.setMarginEnd((int) (12 * density));
        }
        mQr.setLayoutParams(code);
        final View words = mQrBox.getChildAt(1);
        words.setLayoutParams(wide ? new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1) : new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        arrange();
    }

    private void start() {
        if (mWorking) {
            return;
        }
        String control = "";
        if (mOwnServer) {
            control = mServerEdit.getText().toString().trim();
            try {
                control = TsJson.controlUrl(control);
            } catch (IllegalArgumentException e) {
                control = null;
            }
            if (control == null || control.isEmpty()) {
                noteText(mProblem, getText(R.string.ts_bad_server), true);
                mServerEdit.requestFocus();
                return;
            }
        }
        if (mUseKey) {
            final Editable typed = mKeyEdit.getText();
            if (typed.toString().trim().isEmpty()) {
                noteText(mProblem, getText(R.string.ts_need_key), true);
                mKeyEdit.requestFocus();
                return;
            }
            wipeKey();
            // Kept only until Tailscale has it: the client wipes this array at once
            mKey = new char[typed.length()];
            typed.getChars(0, typed.length(), mKey, 0);
            mKey = trim(mKey);
            typed.clear();
        }
        mControl = control;
        final Net net;
        if (mId != null) {
            net = Store.load(this, mId);
        } else {
            net = new Net();
            net.kind = Net.TAILSCALE;
            net.name = getString(R.string.ts_default_name);
        }
        if (net == null) {
            noteText(mProblem, getText(R.string.err_missing), true);
            wipeKey();
            return;
        }
        net.controlUrl = control;
        if (!Store.save(this, net)) {
            noteText(mProblem, getText(R.string.save_failed), true);
            wipeKey();
            return;
        }
        if (mId == null) {
            mId = net.id;
            mMade = true;
        }
        noteText(mProblem, "", true);
        mWorking = true;
        mChoose.setVisibility(View.GONE);
        mWork.setVisibility(View.VISIBLE);
        begin();
    }

    /** The key without the spaces around it, the first copy wiped */
    private static char[] trim(char[] key) {
        int from = 0;
        int to = key.length;
        while (from < to && Character.isWhitespace(key[from])) {
            from++;
        }
        while (to > from && Character.isWhitespace(key[to - 1])) {
            to--;
        }
        final char[] out = java.util.Arrays.copyOfRange(key, from, to);
        java.util.Arrays.fill(key, '\0');
        return out;
    }

    private void wipeKey() {
        if (mKey != null) {
            java.util.Arrays.fill(mKey, '\0');
            mKey = null;
        }
    }

    /** Started: on after the system's VPN dialog, then waiting for Tailscale on this r1 */
    private void begin() {
        mFailed = false;
        mDone = false;
        mClientReady = false;
        mShownUrl = "";
        mQrBox.setVisibility(View.GONE);
        mRetry.setVisibility(View.GONE);
        mWorkLead.setText(R.string.ts_starting);
        noteText(mWorkNote, "", false);
        hint(R.string.hint_ts_wait);
        mBack.requestFocus();
        turnOn(mId);
    }

    @Override
    protected void onTurnedOn(String id, boolean started) {
        if (!mWorking || !id.equals(mId)) {
            return;
        }
        if (!started) {
            wipeKey();
            showChoose();
            noteText(mProblem, getText(R.string.consent_needed), true);
            mBrowserRow.requestFocus();
            return;
        }
        giveUpIn(CLIENT_MS, R.string.ts_no_start);
        mMain.removeCallbacks(mWaitClient);
        mMain.post(mWaitClient);
    }

    /** Tailscale's client comes once the service has started this network's engine */
    private void waitClient() {
        if (!mWorking || mFailed) {
            return;
        }
        final TailscaleClient client = Status.isCurrent(Status.get(), mId)
                ? EngineAccess.tailscale() : null;
        if (client == null) {
            mMain.postDelayed(mWaitClient, POLL_MS);
            return;
        }
        mClientReady = true;
        if (mUseKey) {
            mWorkLead.setText(R.string.ts_key_checking);
            giveUpIn(KEY_MS, R.string.ts_key_refused);
            final char[] key = mKey;
            mKey = null;
            client.loginWithAuthKey(key != null ? key : new char[0], mControl)
                    .whenCompleteAsync((ignored, failure) -> {
                        if (failure != null) {
                            fail(EngineUi.errorOf(failure) == Engine.Error.API
                                    ? getText(R.string.ts_key_refused)
                                    : EngineUi.error(this, EngineUi.errorOf(failure)));
                        }
                    }, getMainExecutor());
        } else {
            mWorkLead.setText(R.string.ts_asking);
            giveUpIn(LINK_MS, R.string.ts_no_link);
            client.loginInteractive(mControl).whenCompleteAsync((url, failure) -> {
                if (failure != null) {
                    fail(EngineUi.error(this, EngineUi.errorOf(failure)));
                } else if (url == null || url.isEmpty()) {
                    // Signed in already: on once it is up
                    mWorkLead.setText(R.string.ts_signed_in);
                    giveUpIn(KEY_MS, R.string.ts_no_start);
                } else {
                    showUrl(url);
                }
            }, getMainExecutor());
        }
        onEngine(EngineAccess.snapshot());
    }

    private void showUrl(String url) {
        if (!mWorking || mFailed || mDone || url.equals(mShownUrl)) {
            return;
        }
        final boolean first = mShownUrl.isEmpty();
        mShownUrl = url;
        mWorkLead.setText(R.string.ts_open_this);
        mQr.set(url);
        mUrl.setText(url);
        mQrBox.setVisibility(View.VISIBLE);
        if (first) {
            giveUpIn(SIGN_IN_MS, R.string.ts_too_long);
        }
    }

    /** What Tailscale says now: the link, waiting for an admin, in, or what went wrong */
    private void onEngine(Engine.Snapshot engine) {
        if (!mWorking || !mClientReady || mFailed || mDone
                || !Status.isCurrent(Status.get(), mId)) {
            return;
        }
        if (engine.state == Engine.State.FAILED) {
            fail(mUseKey && engine.error == Engine.Error.API ? getText(R.string.ts_key_refused)
                    : EngineUi.error(this, engine.error));
            return;
        }
        if (mUseKey && engine.error == Engine.Error.API) {
            fail(getText(R.string.ts_key_refused));
            return;
        }
        if (engine.backendState == Engine.BackendState.RUNNING
                || engine.state == Engine.State.RUNNING) {
            done();
            return;
        }
        if (engine.state == Engine.State.WAITING_APPROVAL) {
            mQrBox.setVisibility(View.GONE);
            mWorkLead.setText(R.string.ts_signed_in);
            noteText(mWorkNote, getText(R.string.ts_waiting), false);
            giveUpIn(SIGN_IN_MS, R.string.ts_too_long);
            return;
        }
        if (!mUseKey && !engine.loginUrl.isEmpty()) {
            showUrl(engine.loginUrl);
        }
        if (engine.loginComplete) {
            mQrBox.setVisibility(View.GONE);
            mWorkLead.setText(R.string.ts_signed_in);
        }
    }

    private void giveUpIn(long ms, int text) {
        mGiveUp = SystemClock.elapsedRealtime() + ms;
        mGiveUpText = text;
        mMain.removeCallbacks(mTimeout);
        mMain.postDelayed(mTimeout, ms);
    }

    private void timeout() {
        if (mWorking && !mFailed && !mDone && SystemClock.elapsedRealtime() >= mGiveUp) {
            fail(getText(mGiveUpText));
        }
    }

    private void fail(CharSequence text) {
        if (!mWorking || mFailed || mDone || isDestroyed()) {
            return;
        }
        mFailed = true;
        wipeKey();
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mWaitClient);
        mQrBox.setVisibility(View.GONE);
        noteText(mWorkNote, text, true);
        mRetry.setVisibility(View.VISIBLE);
        hint(R.string.hint_choose);
        mRetry.requestFocus();
    }

    /** The key was used up by the try: a new one is typed; a browser sign-in starts again */
    private void retry() {
        if (!mFailed) {
            return;
        }
        if (mUseKey) {
            showChoose();
            mKeyEdit.requestFocus();
            return;
        }
        begin();
    }

    private void done() {
        mDone = true;
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mWaitClient);
        if (mAgain) {
            finish();
            return;
        }
        startActivity(new Intent(this, TsOptionsActivity.class)
                .putExtra(TsOptionsActivity.EXTRA_ID, mId));
        finish();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mObserver = EngineAccess.observe(getMainExecutor(), this::onEngine);
    }

    @Override
    protected void onStop() {
        if (mObserver != null) {
            try {
                mObserver.close();
            } catch (Exception e) {
                // closed already
            }
            mObserver = null;
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        wipeKey();
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mWaitClient);
        super.onDestroy();
    }

    /**
     * Back from signing in stops it, and a network this set-up made goes again; back from the
     * choices leaves the set-up.
     */
    @Override
    protected void back() {
        if (mWorking && !mDone) {
            abandon();
            showChoose();
            mBrowserRow.requestFocus();
            return;
        }
        wipeKey();
        if (!mDone && mMade && mId != null) {
            // Made here and never signed in, as the dialog was said no to
            Store.delete(this, mId);
            mId = null;
            mMade = false;
        }
        super.back();
    }

    private void abandon() {
        wipeKey();
        mMain.removeCallbacks(mTimeout);
        mMain.removeCallbacks(mWaitClient);
        mWorking = false;
        mClientReady = false;
        if (mId != null && Status.isCurrent(Status.get(), mId)) {
            TunnelService.stop(this);
        }
        if (mMade && mId != null) {
            Store.delete(this, mId);
            mId = null;
            mMade = false;
        }
    }
}
