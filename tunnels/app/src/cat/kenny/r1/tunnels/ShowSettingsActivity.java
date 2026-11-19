// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.io.IOException;

/**
 * A network's settings as this r1 uses them, the secret keys left out: its facts, this r1's
 * public key as text and as a code for the server's owner, and, behind a row of its own, the
 * settings in wg-quick's format. The wheel stops on the key and on the settings, and scrolls
 * on past them.
 */
public final class ShowSettingsActivity extends Page {
    static final String EXTRA_ID = "id";

    private Net mNet;
    private TextView mPublicKey;
    private QrView mQr;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mNet = Store.load(this, getIntent().getStringExtra(EXTRA_ID));
        if (mNet == null || EngineUi.isJoined(mNet)) {
            // ZeroTier and Tailscale have no settings of theirs to show
            finish();
            return;
        }
        final Net net = mNet;
        title(net.name);
        lead(getText(R.string.settings_lead));
        final ViewGroup card = card(mBody);
        kv(card, getText(R.string.kv_name), net.name);
        kv(card, getText(R.string.kv_server), net.endpoint());
        kv(card, getText(R.string.kv_address), String.join(", ", net.addresses));
        if (!net.dns.isEmpty()) {
            kv(card, getText(R.string.kv_dns), String.join(", ", net.dns));
        }
        if (net.isMullvad()) {
            final String account = net.account;
            kv(card, getText(R.string.kv_account), getString(R.string.account_ending,
                    account.length() > 4 ? account.substring(account.length() - 4) : account));
            kv(card, getText(R.string.kv_location), Net.AUTO.equals(net.location)
                    || net.locationName.isEmpty() ? getText(R.string.auto_title)
                    : net.locationName);
        }

        // This r1's public key, which is what the server's owner needs from it
        final TextView keyTitle = (TextView) getLayoutInflater().inflate(R.layout.lead, mBody,
                false);
        keyTitle.setText(R.string.kv_public_key);
        mBody.addView(keyTitle);
        mPublicKey = (TextView) getLayoutInflater().inflate(R.layout.text_block, mBody, false);
        mBody.addView(mPublicKey);
        mQr = (QrView) getLayoutInflater().inflate(R.layout.qr, mBody, false);
        mBody.addView(mQr);
        showKey(net.publicKey);
        if (!WgConf.isKeyHex(net.publicKey) && WgConf.isKeyHex(net.privateKey)) {
            new Thread(() -> {
                try {
                    final String pub = Tunneld.pubkey(net.privateKey);
                    runOnUiThread(() -> {
                        net.publicKey = pub;
                        Store.save(this, net);
                        showKey(pub);
                    });
                } catch (IOException e) {
                    Log.w(Store.TAG, "No public key: " + e.getClass().getSimpleName());
                }
            }).start();
        }

        // The file itself is in WireGuard's own words: tucked behind a row, shown on a press
        final View details = row(mBody, R.drawable.ic_file, getText(R.string.details_show),
                getText(R.string.details_sum));
        details.setTag("details");
        pressHint(details, getText(R.string.hint_details));
        final TextView conf = (TextView) getLayoutInflater().inflate(R.layout.text_block, mBody,
                false);
        conf.setText(WgConf.write(net, true));
        conf.setVisibility(View.GONE);
        mBody.addView(conf);
        details.setOnClickListener(v -> {
            final boolean show = conf.getVisibility() != View.VISIBLE;
            conf.setVisibility(show ? View.VISIBLE : View.GONE);
            ((TextView) details.findViewById(R.id.title)).setText(show
                    ? R.string.details_hide : R.string.details_show);
        });

        final TextView mark = (TextView) getLayoutInflater().inflate(R.layout.lead, mBody, false);
        mark.setText(R.string.trademark);
        mark.setTextSize(13);
        mark.setTextColor(getColor(R.color.text_faint));
        mBody.addView(mark);

        hint(R.string.hint_read);
        mPublicKey.requestFocus();
    }

    private void showKey(String hex) {
        final String base64 = WgConf.base64FromHex(hex);
        mPublicKey.setText(base64.isEmpty() ? "-" : base64);
        mQr.setVisibility(base64.isEmpty() ? View.GONE : View.VISIBLE);
        if (!base64.isEmpty()) {
            mQr.set(base64);
        }
    }
}
