// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import cat.kenny.r1.tunnels.ts.TailscaleClient;

import java.util.ArrayList;
import java.util.List;

/**
 * Tailscale: which of your devices all of this r1's traffic goes out through, or nobody. Each
 * is a choice: the wheel goes from one to the next, a press or a tap chooses it, and once
 * Tailscale has taken it the page goes back. The devices are asked for while it is on.
 */
public final class TsExitActivity extends Page {
    static final String EXTRA_ID = "id";

    private Net mNet;
    private LinearLayout mList;
    private View mStatus;
    private final List<View> mChoices = new ArrayList<>();
    private boolean mBusy;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mNet = Store.load(this, getIntent().getStringExtra(EXTRA_ID));
        if (mNet == null || !EngineUi.isTailscale(mNet)) {
            finish();
            return;
        }
        title(R.string.ts_exit);
        lead(getText(R.string.ts_exit_lead));
        mList = new LinearLayout(this);
        mList.setOrientation(LinearLayout.VERTICAL);
        mBody.addView(mList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        final View nobody = row(mList, R.drawable.ic_mesh, getText(R.string.ts_exit_nobody),
                getText(R.string.ts_exit_nobody_sum));
        choice(nobody, "", getString(R.string.ts_exit_nobody));
        mStatus = note(mBody, R.drawable.ic_info, "");
        hint(R.string.hint_choose);
        mark();
        nobody.requestFocus();

        final TailscaleClient client = client();
        if (client == null) {
            noteText(mStatus, getText(R.string.ts_exit_off), false);
            return;
        }
        noteText(mStatus, getText(R.string.ts_exit_loading), false);
        client.exitNodes().whenCompleteAsync((nodes, failure) -> {
            if (isDestroyed()) {
                return;
            }
            if (failure != null) {
                noteText(mStatus, EngineUi.error(this, EngineUi.errorOf(failure)), true);
                return;
            }
            noteText(mStatus, nodes.isEmpty() ? getText(R.string.ts_exit_none) : "", false);
            View chosen = null;
            for (Engine.Peer peer : nodes) {
                final String name = EngineUi.deviceName(peer);
                final View row = row(mList, R.drawable.ic_device, name,
                        EngineUi.bareAll(peer.addresses));
                choice(row, peer.id, name);
                if (peer.id.equals(mNet.exitNodeId)) {
                    chosen = row;
                }
            }
            mark();
            if (chosen != null) {
                chosen.requestFocus();
            }
        }, getMainExecutor());
    }

    private TailscaleClient client() {
        return Status.isCurrent(Status.get(), mNet.id) ? EngineAccess.tailscale() : null;
    }

    private void choice(View row, String id, String name) {
        row.setTag(id);
        radio(row, false);
        pressHint(row, getText(R.string.hint_choose));
        row.setOnClickListener(v -> choose(id, name));
        mChoices.add(row);
    }

    private void mark() {
        for (View row : mChoices) {
            radio(row, mNet.exitNodeId.equals(row.getTag()));
        }
    }

    private void choose(String id, String name) {
        if (mBusy) {
            return;
        }
        final TailscaleClient client = client();
        if (client == null) {
            // Off: kept for the next time it starts
            mNet.exitNodeId = id;
            mNet.exitNodeName = id.isEmpty() ? "" : name;
            if (!Store.save(this, mNet)) {
                noteText(mStatus, getText(R.string.save_failed), true);
                return;
            }
            mark();
            finish();
            return;
        }
        mBusy = true;
        client.setExitNode(id, name).whenCompleteAsync((ignored, failure) -> {
            mBusy = false;
            if (isDestroyed()) {
                return;
            }
            if (failure != null) {
                noteText(mStatus, getText(R.string.ts_exit_failed), true);
                return;
            }
            // Tailscale saved it on the network; this page's copy follows
            mNet.exitNodeId = id;
            mNet.exitNodeName = id.isEmpty() ? "" : name;
            mark();
            finish();
        }, getMainExecutor());
    }
}
