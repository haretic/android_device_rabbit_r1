// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/**
 * ZeroTier, step 2: the name and the network's ID to check, staying connected, then Back or
 * Save and join. Joining starts it after the system's VPN dialog, and the app's page shows it
 * coming up, waiting for the network's owner to approve this r1 where they have to.
 */
public final class ZtReviewActivity extends Page {
    private View mProblem;
    private View mStayNote;
    private boolean mSaving;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final Net net = Draft.net;
        if (net == null || !EngineUi.isZeroTier(net)) {
            finish();
            return;
        }
        title(R.string.kind_zt);
        steps(2, 2);
        lead(getText(R.string.zt_review_lead));
        final ViewGroup card = card(mBody);
        kv(card, getText(R.string.kv_name), net.name);
        kv(card, getText(R.string.kv_network_id), EngineUi.grouped(net.networkId));

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
        final TextView save = buttons(mBody, R.string.save_join);
        pressHint(save, getText(R.string.hint_choose));
        save.setOnClickListener(v -> save());
        hint(R.string.hint_choose);
        save.requestFocus();
    }

    private void save() {
        final Net net = Draft.net;
        if (net == null || mSaving) {
            return;
        }
        mSaving = true;
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
        // The app's page, the network coming up on it
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }
}
