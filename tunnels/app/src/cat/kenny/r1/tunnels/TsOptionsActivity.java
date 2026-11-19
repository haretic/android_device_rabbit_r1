// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import cat.kenny.r1.tunnels.ts.TailscaleClient;

import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Tailscale, step 2: signed in, the two options, both on to start with, each a row whose
 * switch a press or a tap turns; then Done, to the app's page with the network on it.
 */
public final class TsOptionsActivity extends Page {
    static final String EXTRA_ID = "id";

    private Net mNet;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mNet = Store.load(this, getIntent().getStringExtra(EXTRA_ID));
        if (mNet == null || !EngineUi.isTailscale(mNet)) {
            finish();
            return;
        }
        title(R.string.kind_ts);
        steps(2, 2);
        lead(getText(R.string.ts_options_lead));
        final View dns = optionRow(this, mBody, mNet, true);
        optionRow(this, mBody, mNet, false);
        final TextView done = buttons(mBody, R.string.done);
        pressHint(done, getText(R.string.hint_choose));
        done.setOnClickListener(v -> home());
        hint(R.string.hint_toggle);
        dns.requestFocus();
    }

    /** A switch row for one of the options, on the set-up's page and the network's */
    static View optionRow(Page page, android.view.ViewGroup parent, Net net, boolean dns) {
        final View row = page.row(parent, dns ? R.drawable.ic_dns : R.drawable.ic_route,
                page.getText(dns ? R.string.ts_dns : R.string.ts_routes),
                page.getText(dns ? R.string.ts_dns_sum : R.string.ts_routes_sum));
        row.setTag(dns ? "dns" : "routes");
        switchOf(row, false);
        switchOn(row, dns ? net.acceptDns : net.acceptRoutes);
        pressHint(row, page.getText(R.string.hint_toggle));
        row.setOnClickListener(v -> {
            final boolean on = !(dns ? net.acceptDns : net.acceptRoutes);
            switchOn(row, on);
            set(page, net, dns, on, ok -> {
                if (!ok) {
                    switchOn(row, !on);
                    android.widget.Toast.makeText(page, R.string.ts_option_failed,
                            android.widget.Toast.LENGTH_LONG).show();
                }
            });
        });
        return row;
    }

    /**
     * An option changed: through Tailscale where this network is on, which saves it once
     * taken; else saved here, for the next time it starts.
     */
    static void set(Page page, Net net, boolean dns, boolean on, Consumer<Boolean> after) {
        final boolean was = dns ? net.acceptDns : net.acceptRoutes;
        if (dns) {
            net.acceptDns = on;
        } else {
            net.acceptRoutes = on;
        }
        final TailscaleClient client = Status.isCurrent(Status.get(), net.id)
                ? EngineAccess.tailscale() : null;
        if (client == null) {
            final boolean saved = Store.save(page, net);
            if (!saved) {
                undo(net, dns, was);
            }
            after.accept(saved);
            return;
        }
        final CompletableFuture<Void> change = dns ? client.setAcceptDns(on)
                : client.setAcceptRoutes(on);
        change.whenCompleteAsync((ignored, failure) -> {
            if (failure != null) {
                undo(net, dns, was);
            }
            if (!page.isDestroyed()) {
                after.accept(failure == null);
            }
        }, page.getMainExecutor());
    }

    private static void undo(Net net, boolean dns, boolean was) {
        if (dns) {
            net.acceptDns = was;
        } else {
            net.acceptRoutes = was;
        }
    }

    private void home() {
        startActivity(new Intent(this, HomeActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        finish();
    }

    @Override
    protected void back() {
        home();
    }
}
