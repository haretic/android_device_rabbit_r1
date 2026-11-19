// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Setting up a tunnel, step 2 (N4): what was read, to check, its name to change; what goes
 * through it and staying connected; then Back, or Save and connect. Step 3 is the network
 * coming up on the app's page.
 */
public final class ReviewActivity extends Page {
    private EditText mName;
    private View mProblem;
    private View mNameProblem;
    private View mStayNote;
    private boolean mSaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Net net = Draft.net;
        if (net == null) {
            finish();
            return;
        }
        title(R.string.kind_wg);
        steps(2, 3);
        final int lead;
        switch (Draft.from) {
            case Draft.FROM_FILE:
                lead = R.string.review_from_file;
                break;
            case Draft.FROM_CLIP:
                lead = R.string.review_from_clip;
                break;
            case Draft.FROM_TYPED:
                lead = R.string.review_typed;
                break;
            default:
                lead = R.string.review_from_code;
                break;
        }
        lead(getText(lead));

        final ViewGroup card = card(mBody);
        final View nameLine = getLayoutInflater().inflate(R.layout.kv_edit, card, false);
        ((TextView) nameLine.findViewById(R.id.key)).setText(R.string.kv_name);
        mName = nameLine.findViewById(R.id.edit);
        if (state == null) {
            mName.setText(net.name);
        }
        pressHint(mName, getText(R.string.hint_type_page));
        card.addView(nameLine);
        kv(card, getText(R.string.kv_server), net.endpoint());
        kv(card, getText(R.string.kv_address), String.join(", ", bare(net.addresses)));
        if (!net.dns.isEmpty()) {
            kv(card, getText(R.string.kv_dns), String.join(", ", net.dns));
        }
        // A name left empty is said right under the card it is in
        mNameProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mNameProblem, "", true);

        final View route = row(mBody, R.drawable.ic_route, getText(R.string.route_title),
                routeText(net));
        if (net.someRoutes().isEmpty()) {
            // Its settings send everything: there is nothing to change here
            pressHint(route, getText(R.string.hint_route_fixed));
        } else {
            endText(route, getText(R.string.change));
            pressHint(route, getText(R.string.hint_toggle_route));
        }
        route.setOnClickListener(v -> {
            if (net.someRoutes().isEmpty()) {
                return;
            }
            net.everything = !net.everything;
            summary(route, routeText(net));
        });
        final View stay = row(mBody, R.drawable.ic_lock, getText(R.string.stay_title),
                getText(R.string.stay_sum));
        switchOf(stay, false);
        switchOn(stay, net.stay);
        pressHint(stay, getText(R.string.hint_toggle));
        stay.setOnClickListener(v -> {
            net.stay = !net.stay;
            switchOn(stay, net.stay);
            noteText(mStayNote, net.stay ? getText(R.string.stay_note) : "", false);
        });
        mStayNote = note(mBody, R.drawable.ic_info, "");
        noteText(mStayNote, net.stay ? getText(R.string.stay_note) : "", false);
        mProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mProblem, "", true);
        final TextView save = buttons(mBody, R.string.save_connect);
        pressHint(save, getText(R.string.hint_save_connect));
        save.setOnClickListener(v -> save());
        hint(R.string.hint_choose);
        // On the name, the one thing here to type: a first press changes nothing
        mName.requestFocus();
    }

    /** Addresses as people write them: without the network, as the mockup has them */
    private static List<String> bare(List<String> cidrs) {
        final List<String> out = new ArrayList<>();
        for (String cidr : cidrs) {
            final int slash = cidr.indexOf('/');
            out.add(slash > 0 ? cidr.substring(0, slash) : cidr);
        }
        return out;
    }

    private String routeText(Net net) {
        return net.everything ? getString(R.string.route_all)
                : getString(R.string.route_some, String.join(", ", net.someRoutes()));
    }

    private void save() {
        final Net net = Draft.net;
        if (net == null || mSaving) {
            return;
        }
        final String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            noteText(mNameProblem, getText(R.string.name_needed), true);
            mName.requestFocus();
            return;
        }
        noteText(mNameProblem, "", true);
        mSaving = true;
        net.name = name;
        // This r1's public key, to show later, from the private one in the settings
        new Thread(() -> {
            if (!WgConf.isKeyHex(net.publicKey)) {
                try {
                    net.publicKey = Tunneld.pubkey(net.privateKey);
                } catch (IOException e) {
                    Log.w(Store.TAG, "No public key: " + e.getClass().getSimpleName());
                }
            }
            runOnUiThread(() -> saved(net));
        }).start();
    }

    private void saved(Net net) {
        if (!Store.save(this, net)) {
            mSaving = false;
            noteText(mProblem, getText(R.string.save_failed), true);
            return;
        }
        Draft.clear();
        turnOn(net.id);
    }

    @Override
    protected void onTurnedOn(String id, boolean started) {
        // Step 3: the app's page, the new network coming up on it
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
