// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Typing a tunnel's settings in: its name, the server and the server's public key, this r1's
 * address and DNS. The key pair is made here on the r1 by r1-tunneld; its public half is
 * shown as text and as a QR code to give to the server's owner. The fields take the system's
 * keyboard: the wheel goes from field to field and a press opens the keyboard on one.
 */
public final class TypeActivity extends Page {
    private EditText mName;
    private EditText mServer;
    private EditText mKey;
    private EditText mAddress;
    private EditText mDns;
    private TextView mPublicKey;
    private QrView mQr;
    private View mProblem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.kind_wg);
        steps(1, 3);
        lead(getText(R.string.type_lead));
        mName = field(R.id.field_name, R.string.field_name, R.string.field_name_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mServer = field(R.id.field_server, R.string.field_server, R.string.field_server_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        mKey = field(R.id.field_key, R.string.field_server_key, 0,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        mAddress = field(R.id.field_address, R.string.field_address,
                R.string.field_address_hint, InputType.TYPE_CLASS_TEXT
                        | InputType.TYPE_TEXT_VARIATION_URI);
        mDns = field(R.id.field_dns, R.string.field_dns, R.string.field_dns_hint,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);

        final TextView keyTitle = (TextView) getLayoutInflater().inflate(R.layout.lead, mBody,
                false);
        keyTitle.setText(R.string.type_key_title);
        mBody.addView(keyTitle);
        mPublicKey = (TextView) getLayoutInflater().inflate(R.layout.text_block, mBody, false);
        mPublicKey.setText(R.string.type_key_making);
        mBody.addView(mPublicKey);
        mQr = (QrView) getLayoutInflater().inflate(R.layout.qr, mBody, false);
        mQr.setVisibility(View.GONE);
        mBody.addView(mQr);

        mProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mProblem, "", true);
        final TextView next = buttons(mBody, R.string.next);
        next.setOnClickListener(v -> next());
        hint(R.string.hint_type_page);
        if (state == null) {
            mName.requestFocus();
        }
        key();
    }

    private EditText field(int id, int label, int example, int type) {
        final View field = getLayoutInflater().inflate(R.layout.field, mBody, false);
        ((TextView) field.findViewById(R.id.label)).setText(label);
        final EditText edit = field.findViewById(R.id.edit);
        edit.setId(id);
        edit.setInputType(type);
        if (example != 0) {
            edit.setHint(example);
        }
        mBody.addView(field);
        return edit;
    }

    /** This r1's key pair for the tunnel, made once for this set-up. */
    private void key() {
        if (Draft.typedKey != null) {
            showKey(Draft.typedKey[1]);
            return;
        }
        new Thread(() -> {
            String[] pair = null;
            try {
                pair = Tunneld.genkey();
            } catch (IOException e) {
                Log.w(Store.TAG, "No key made: " + e.getClass().getSimpleName());
            }
            final String[] made = pair;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (made == null) {
                    mPublicKey.setText(R.string.key_failed);
                    return;
                }
                Draft.typedKey = made;
                showKey(made[1]);
            });
        }).start();
    }

    private void showKey(String publicHex) {
        final String base64 = WgConf.base64FromHex(publicHex);
        mPublicKey.setText(base64);
        mQr.setVisibility(View.VISIBLE);
        mQr.set(base64);
    }

    private void next() {
        if (Draft.typedKey == null) {
            noteText(mProblem, getText(R.string.type_no_key), true);
            return;
        }
        final String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            problem(mName, R.string.name_needed);
            return;
        }
        String server = mServer.getText().toString().trim();
        if (!server.isEmpty() && server.lastIndexOf(':') < 0) {
            server = server + ":51820";
        }
        final Object[] endpoint = WgConf.endpoint(server);
        if (endpoint == null) {
            problem(mServer, R.string.type_bad_server);
            return;
        }
        final String peerKey = WgConf.hexFromBase64(mKey.getText().toString().trim());
        if (peerKey == null) {
            problem(mKey, R.string.type_bad_key);
            return;
        }
        final String address = WgConf.cidr(mAddress.getText().toString().trim());
        if (address == null) {
            problem(mAddress, R.string.type_bad_address);
            return;
        }
        final List<String> dns = new ArrayList<>();
        for (String item : mDns.getText().toString().split(",")) {
            final String trimmed = item.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (WgConf.parseIp(trimmed) == null) {
                problem(mDns, R.string.type_bad_dns);
                return;
            }
            dns.add(trimmed);
        }
        final Net net = new Net();
        net.kind = Net.WG;
        net.name = name;
        net.privateKey = Draft.typedKey[0];
        net.publicKey = Draft.typedKey[1];
        net.host = (String) endpoint[0];
        net.port = (Integer) endpoint[1];
        net.peerKey = peerKey;
        net.addresses.add(address);
        net.dns.addAll(dns);
        net.allowedIps.add("0.0.0.0/0");
        net.allowedIps.add("::/0");
        net.everything = true;
        noteText(mProblem, "", true);
        Draft.net = net;
        Draft.from = Draft.FROM_TYPED;
        startActivity(new Intent(this, ReviewActivity.class));
    }

    private void problem(EditText field, int text) {
        noteText(mProblem, getText(text), true);
        field.requestFocus();
    }

    @Override
    protected void back() {
        // The key made here is for this set-up only
        Draft.typedKey = null;
        super.back();
    }
}
