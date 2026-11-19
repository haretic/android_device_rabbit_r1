// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;

/**
 * Mullvad, step 1 (N9): the account number on a number pad, or scanned from the account page.
 * Opt-in and said so: this talks to Mullvad's servers. With the number, a token, a key made on
 * the r1, and the r1 added to the account as a device by that key; where the account has as
 * many devices as it may, the devices to remove one of first.
 */
public final class MullvadAccountActivity extends Page {
    private static final int REQ_SCAN = 1;
    private static final int REQ_DEVICES = 2;
    private static final int REQ_PLACE = 3;
    private static final int DIGITS = 16;
    private static final int[] KEYS = {R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
        R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9};
    /** The keys in reading order: three to a row standing, six to a row on its side */
    private static final int[] TALL = {R.id.key1, R.id.key2, R.id.key3, R.id.key4, R.id.key5,
        R.id.key6, R.id.key7, R.id.key8, R.id.key9, R.id.key_back, R.id.key0, R.id.key_next};
    private static final int[] WIDE = {R.id.key1, R.id.key2, R.id.key3, R.id.key4, R.id.key5,
        R.id.key6, R.id.key7, R.id.key8, R.id.key9, R.id.key0, R.id.key_back, R.id.key_next};

    private final StringBuilder mDigits = new StringBuilder();
    private TextView mNumber;
    private TextView mNext;
    private View mStatus;
    private ViewGroup mKeys;
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.kind_mullvad);
        steps(1, 2);
        lead(getText(R.string.mullvad_lead));
        note(mBody, R.drawable.ic_info, getText(R.string.mullvad_disclaimer));
        getLayoutInflater().inflate(R.layout.pad, mBody, true);
        mNumber = mBody.findViewById(R.id.number);
        mNext = mBody.findViewById(R.id.key_next);
        mKeys = mBody.findViewById(R.id.keys);
        for (int i = 0; i < KEYS.length; i++) {
            final char digit = (char) ('0' + i);
            final View key = mBody.findViewById(KEYS[i]);
            pressHint(key, getText(R.string.hint_pad));
            key.setOnClickListener(v -> type(digit));
        }
        final View erase = mBody.findViewById(R.id.key_back);
        pressHint(erase, getText(R.string.hint_erase));
        erase.setOnClickListener(v -> erase());
        pressHint(mNext, getText(R.string.hint_next));
        mNext.setOnClickListener(v -> next());
        // Asking, and what went wrong, right under the number it is about
        final ViewGroup pad = (ViewGroup) mNumber.getParent();
        mStatus = note(pad, R.drawable.ic_info, "");
        pad.removeView(mStatus);
        pad.addView(mStatus, pad.indexOfChild(mNumber) + 1);
        noteText(mStatus, "", true);
        arrange();
        final View scan = row(mBody, R.drawable.ic_scan, getText(R.string.mullvad_scan), "");
        pressHint(scan, getText(R.string.hint_scan));
        scan.setOnClickListener(v -> startActivityForResult(new Intent(this, ScanActivity.class)
                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_ACCOUNT), REQ_SCAN));
        hint(R.string.hint_pad);
        show();
        mBody.findViewById(R.id.key1).requestFocus();
    }

    private void arrange() {
        arrangeKeys(mKeys, isWide() ? WIDE : TALL, isWide() ? 6 : 3);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        arrange();
    }

    private void type(char digit) {
        if (mBusy || mDigits.length() >= DIGITS) {
            return;
        }
        mDigits.append(digit);
        if (!mBusy) {
            noteText(mStatus, "", true);
        }
        show();
        if (mDigits.length() == DIGITS) {
            mNext.requestFocus();
        }
    }

    private void erase() {
        if (mBusy || mDigits.length() == 0) {
            return;
        }
        mDigits.setLength(mDigits.length() - 1);
        show();
    }

    /** Four groups of four, the digits typed, the place of the next one, the rest to come */
    private void show() {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        final int faint = getColor(R.color.surface_line);
        for (int i = 0; i < DIGITS; i++) {
            if (i > 0 && i % 4 == 0) {
                text.append("  ");
            }
            final int start = text.length();
            if (i < mDigits.length()) {
                text.append(mDigits.charAt(i));
            } else {
                text.append('_');
                text.setSpan(new ForegroundColorSpan(i == mDigits.length() ? accent() : faint),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i == mDigits.length()) {
                    text.setSpan(new UnderlineSpan(), start, start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        mNumber.setText(text);
        mNumber.setContentDescription(mDigits.length() + " / " + DIGITS);
        mNext.setAlpha(mDigits.length() == DIGITS && !mBusy ? 1f : 0.45f);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A keyboard's digits type too
        final int code = event.getKeyCode();
        if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                type((char) ('0' + code - KeyEvent.KEYCODE_0));
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                erase();
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_SCAN) {
            if (result == RESULT_OK && Draft.scanned != null
                    && Draft.scanned.length() == DIGITS) {
                mDigits.setLength(0);
                mDigits.append(Draft.scanned);
                Draft.scanned = null;
                show();
                mNext.requestFocus();
            }
            return;
        }
        if (request == REQ_PLACE) {
            mBusy = false;
            show();
            if (result == RESULT_FIRST_USER) failed(getText(R.string.save_failed));
            return;
        }
        if (request == REQ_DEVICES) {
            if (result == RESULT_OK) {
                mBusy = false;
                next();
            } else {
                mBusy = false;
                show();
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    private void next() {
        if (mBusy) {
            return;
        }
        if (mDigits.length() != DIGITS) {
            noteText(mStatus, getText(R.string.pad_incomplete), true);
            revealAfterLayout(mNext);
            return;
        }
        mBusy = true;
        show();
        noteText(mStatus, getText(R.string.mullvad_asking), false);
        final String account = mDigits.toString();
        final Mullvad mullvad = Draft.mullvad != null && Draft.mullvad.account.equals(account)
                ? Draft.mullvad : new Mullvad(account);
        Draft.mullvad = mullvad;
        final String[] key = Draft.typedKey;
        new Thread(() -> {
            try {
                final MullvadSetup setup = MullvadSetup.begin(getApplicationContext(), mullvad,
                        key, Tunneld::genkey);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        discard(setup);
                    } else {
                        Draft.mullvadSetup = setup;
                        Draft.typedKey = new String[] {setup.net.privateKey, setup.net.publicKey};
                        registered(setup.net, setup.paidUntil, setup.deviceNumber);
                    }
                });
            } catch (IOException e) {
                final Engine.Error error = Mullvad.errorOf(e);
                runOnUiThread(() -> refused(error));
            }
        }, "mullvad-register").start();
    }

    static void discard(MullvadSetup setup) {
        new Thread(() -> {
            try { setup.cancel(); }
            catch (IOException e) {
                Log.w(Store.TAG, "Uncommitted device cleanup deferred");
            }
        }, "mullvad-cleanup").start();
    }

    private void registered(Net net, long paid, int count) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mBusy = false;
        show();
        noteText(mStatus, "", true);
        Draft.net = net;
        Draft.paidUntil = paid;
        Draft.deviceNumber = count;
        try {
            startActivityForResult(new Intent(this, MullvadPlaceActivity.class)
                    .putExtra(MullvadPlaceActivity.EXTRA_SETUP, true), REQ_PLACE);
        } catch (RuntimeException e) {
            final MullvadSetup setup = Draft.mullvadSetup;
            if (setup != null) discard(setup);
            Draft.net = null;
            Draft.mullvadSetup = null;
            Draft.typedKey = null;
            failed(EngineUi.error(this, Engine.Error.API));
        }
    }

    private void refused(Engine.Error error) {
        if (isFinishing() || isDestroyed()) return;
        if (error == Engine.Error.DEVICE_LIMIT) {
            noteText(mStatus, "", true);
            startActivityForResult(new Intent(this, MullvadDevicesActivity.class), REQ_DEVICES);
            return;
        }
        failed(EngineUi.error(this, error));
        if (error == Engine.Error.ACCOUNT_INVALID) {
            // The number is what is wrong: on the key that takes it back
            final View erase = mBody.findViewById(R.id.key_back);
            erase.requestFocus();
            revealAfterLayout(erase);
        }
    }

    private void failed(CharSequence text) {
        if (isFinishing() || isDestroyed()) {
            return;
        }
        mBusy = false;
        show();
        noteText(mStatus, text, true);
    }

    @Override
    protected void back() {
        final MullvadSetup setup = Draft.mullvadSetup;
        if (setup != null) discard(setup);
        Draft.clear();
        super.back();
    }
}
