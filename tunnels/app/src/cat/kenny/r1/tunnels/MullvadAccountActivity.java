// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.widget.TextView;

import org.json.JSONObject;

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
    private static final int DIGITS = 16;
    private static final int[] KEYS = {R.id.key0, R.id.key1, R.id.key2, R.id.key3, R.id.key4,
        R.id.key5, R.id.key6, R.id.key7, R.id.key8, R.id.key9};

    private final StringBuilder mDigits = new StringBuilder();
    private TextView mNumber;
    private TextView mNext;
    private View mStatus;
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
        for (int i = 0; i < KEYS.length; i++) {
            final char digit = (char) ('0' + i);
            final View key = mBody.findViewById(KEYS[i]);
            pressHint(key, getText(R.string.hint_pad));
            key.setOnClickListener(v -> type(digit));
        }
        final View erase = mBody.findViewById(R.id.key_back);
        pressHint(erase, getText(R.string.hint_pad));
        erase.setOnClickListener(v -> erase());
        mNext.setOnClickListener(v -> next());
        mStatus = note(mBody, R.drawable.ic_info, "");
        noteText(mStatus, "", true);
        final View scan = row(mBody, R.drawable.ic_scan, getText(R.string.mullvad_scan), "");
        pressHint(scan, getText(R.string.hint_scan));
        scan.setOnClickListener(v -> startActivityForResult(new Intent(this, ScanActivity.class)
                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_ACCOUNT), REQ_SCAN));
        hint(R.string.hint_pad);
        show();
        mBody.findViewById(R.id.key1).requestFocus();
    }

    private void type(char digit) {
        if (mBusy || mDigits.length() >= DIGITS) {
            return;
        }
        mDigits.append(digit);
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
        if (mBusy || mDigits.length() != DIGITS) {
            return;
        }
        mBusy = true;
        show();
        noteText(mStatus, getText(R.string.mullvad_asking), false);
        final String account = mDigits.toString();
        new Thread(() -> {
            final Mullvad mullvad = Draft.mullvad != null && Draft.mullvad.account.equals(account)
                    ? Draft.mullvad : new Mullvad(account);
            Draft.mullvad = mullvad;
            String registeredId = null;
            try {
                mullvad.login();
                final long paid = mullvad.paidUntil();
                String[] pair = Draft.typedKey;
                if (pair == null) {
                    try {
                        pair = Tunneld.genkey();
                        Draft.typedKey = pair;
                    } catch (IOException e) {
                        Log.w(Store.TAG, "No key made: " + e.getClass().getSimpleName());
                        runOnUiThread(() -> failed(getText(R.string.mullvad_no_key)));
                        return;
                    }
                }
                // Count before registration: a failed list request must not orphan a device.
                final int count = mullvad.devices().length() + 1;
                JSONObject device;
                try {
                    device = mullvad.addDevice(WgConf.base64FromHex(pair[1]));
                } catch (Mullvad.Failure f) {
                    if (!"PUBKEY_IN_USE".equals(f.code)) {
                        throw f;
                    }
                    // A key another device has: a new one, once
                    pair = Tunneld.genkey();
                    Draft.typedKey = pair;
                    device = mullvad.addDevice(WgConf.base64FromHex(pair[1]));
                }
                registeredId = device.optString("id", "");
                final Net net = fromDevice(account, device, pair);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) {
                        discardDevice(mullvad, net.deviceId);
                    } else {
                        registered(net, paid, count);
                    }
                });
                registeredId = null; // The queued UI callback now owns cleanup or handoff.
            } catch (Mullvad.Failure f) {
                runOnUiThread(() -> refused(f));
            } catch (IOException e) {
                Log.w(Store.TAG, "Mullvad not reached: " + e.getClass().getSimpleName());
                runOnUiThread(() -> failed(getText(R.string.mullvad_unreachable)));
            } finally {
                if (registeredId != null && !registeredId.isEmpty()) {
                    discardDevice(mullvad, registeredId);
                }
            }
        }).start();
    }

    /** The network as Mullvad's device for this r1 has it; its server is picked each time. */
    private static void discardDevice(Mullvad mullvad, String id) {
        new Thread(() -> {
            try {
                mullvad.removeDevice(id);
            } catch (IOException e) {
                Log.w(Store.TAG, "Uncommitted device not removed: "
                        + e.getClass().getSimpleName());
            }
        }).start();
    }

    private static Net fromDevice(String account, JSONObject device, String[] pair)
            throws IOException {
        final Net net = new Net();
        net.kind = Net.MULLVAD;
        net.name = "Mullvad";
        net.account = account;
        net.deviceId = device.optString("id", "");
        net.privateKey = pair[0];
        net.publicKey = pair[1];
        final String v4 = WgConf.cidr(device.optString("ipv4_address", ""));
        final String v6 = WgConf.cidr(device.optString("ipv6_address", ""));
        if (v4 == null || net.deviceId.isEmpty()) {
            throw new IOException("Mullvad gave no address");
        }
        net.addresses.add(v4);
        if (v6 != null) {
            net.addresses.add(v6);
        }
        net.dns.add("10.64.0.1");
        net.allowedIps.add("0.0.0.0/0");
        net.allowedIps.add("::/0");
        net.everything = true;
        net.mtu = 1280;
        net.location = Net.AUTO;
        return net;
    }

    private void registered(Net net, long paid, int count) {
        if (isDestroyed()) {
            return;
        }
        mBusy = false;
        show();
        noteText(mStatus, "", true);
        Draft.net = net;
        Draft.paidUntil = paid;
        Draft.deviceNumber = count;
        startActivity(new Intent(this, MullvadPlaceActivity.class)
                .putExtra(MullvadPlaceActivity.EXTRA_SETUP, true));
    }

    private void refused(Mullvad.Failure f) {
        if (isDestroyed()) {
            return;
        }
        if ("MAX_DEVICES_REACHED".equals(f.code)) {
            noteText(mStatus, "", true);
            startActivityForResult(new Intent(this, MullvadDevicesActivity.class), REQ_DEVICES);
            return;
        }
        if (f.isRateLimit()) {
            failed(getText(R.string.mullvad_wait));
        } else if ("INVALID_ACCOUNT".equals(f.code)) {
            failed(getText(R.string.mullvad_bad_account));
        } else {
            failed(getString(R.string.mullvad_error, f.code));
        }
    }

    private void failed(CharSequence text) {
        if (isDestroyed()) {
            return;
        }
        mBusy = false;
        show();
        noteText(mStatus, text, true);
    }

    @Override
    protected void back() {
        Draft.mullvad = null;
        Draft.typedKey = null;
        super.back();
    }
}
