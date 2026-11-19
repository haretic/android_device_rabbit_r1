// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.Toast;

import java.util.List;

/**
 * The app's page. With nothing set up, or opened to add one, the kinds of network there are;
 * otherwise the saved networks, the one that is on first with how it is going, each with its
 * switch, and adding another. A row opens its network's page, its switch turns it on or off:
 * both stops of the wheel, both targets for a finger. One network is on at a time.
 *
 * Started with the boolean extra self_check, it runs WgConf's self-check and says the result.
 */
public final class HomeActivity extends Page {
    static final String EXTRA_ADD = "add";
    private static final String SELF_CHECK = "self_check";

    private boolean mAdding;
    /** What the rows were built for: the network that is current and its state */
    private String mBuiltFor = "";
    private long mRemovalRevision;
    private View mCurrentRow;
    private ViewGroup mCurrentCard;
    private Net mCurrent;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mAdding = getIntent().getBooleanExtra(EXTRA_ADD, false);
        title(mAdding ? R.string.add_title : R.string.app_name);
        if (!mAdding) {
            watchStatus();
        }
        selfCheck(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        selfCheck(intent);
    }

    private void selfCheck(Intent intent) {
        if (intent.getBooleanExtra(SELF_CHECK, false)) {
            final String result = WgConf.selfCheck();
            Log.i(Store.TAG, "self check: " + result);
            Toast.makeText(this, "Self check: " + result, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
    }

    private void build() {
        mRemovalRevision = NetworkRemoval.revision();
        final View focused = getCurrentFocus();
        final Object key = focused != null ? focused.getTag() : null;
        mBody.removeAllViews();
        mCurrentRow = null;
        mCurrentCard = null;
        mCurrent = null;
        final List<Net> nets = Store.list(this);
        final Status.Snapshot now = Status.get();
        mBuiltFor = builtFor(now);
        if (mAdding || nets.isEmpty()) {
            kinds();
        } else {
            networks(nets, now);
        }
        final View again = key != null ? mBody.findViewWithTag(key) : null;
        if (again != null) {
            again.requestFocus();
        } else if (mBody.getChildCount() > 0) {
            final View first = firstFocusable(mBody);
            if (first != null) {
                first.requestFocus();
            }
        }
    }

    private static View firstFocusable(ViewGroup group) {
        for (int i = 0; i < group.getChildCount(); i++) {
            final View child = group.getChildAt(i);
            if (child.isFocusable() && child.getVisibility() == View.VISIBLE) {
                return child;
            }
        }
        return null;
    }

    private static String builtFor(Status.Snapshot now) {
        return now.netId + "/" + now.state;
    }

    /** N1: what there is to set up */
    private void kinds() {
        lead(getText(R.string.home_lead));
        final View wg = row(mBody, R.drawable.ic_tunnel, getText(R.string.kind_wg),
                getText(R.string.kind_wg_sum));
        wg.setTag("kind:wg");
        pressHint(wg, getString(R.string.hint_set_up, getString(R.string.kind_wg)));
        wg.setOnClickListener(v -> {
            startActivity(new Intent(this, WgStartActivity.class));
            if (mAdding) {
                finish();
            }
        });
        final View mullvad = row(mBody, R.drawable.ic_globe, getText(R.string.kind_mullvad),
                getText(R.string.kind_mullvad_sum));
        mullvad.setTag("kind:mullvad");
        pressHint(mullvad, getString(R.string.hint_set_up, getString(R.string.kind_mullvad)));
        mullvad.setOnClickListener(v -> {
            startActivity(new Intent(this, MullvadAccountActivity.class));
            if (mAdding) {
                finish();
            }
        });
        kind(R.drawable.ic_mesh, R.string.kind_ts, R.string.kind_ts_sum, "kind:tailscale",
                TsSignInActivity.class);
        kind(R.drawable.ic_hub, R.string.kind_zt, R.string.kind_zt_sum, "kind:zerotier",
                ZtJoinActivity.class);
        note(mBody, R.drawable.ic_info, getText(R.string.home_note));
        hint(getString(R.string.hint_set_up, getString(R.string.kind_wg)));
    }

    private void kind(int icon, int name, int sum, String tag, Class<?> first) {
        final View row = row(mBody, icon, getText(name), getText(sum));
        row.setTag(tag);
        pressHint(row, getString(R.string.hint_set_up, getString(name)));
        row.setOnClickListener(v -> {
            Draft.clear();
            startActivity(new Intent(this, first));
            if (mAdding) {
                finish();
            }
        });
    }

    /** N8: the networks, the current one first with its status */
    private void networks(List<Net> nets, Status.Snapshot now) {
        Net current = null;
        for (Net net : nets) {
            if (Status.isCurrent(now, net.id)) {
                current = net;
            }
        }
        if (current != null) {
            mCurrent = current;
            mCurrentRow = networkRow(current, now);
            mCurrentCard = card(mBody);
            card(mCurrentCard, current, now);
        }
        for (Net net : nets) {
            if (net != current) {
                networkRow(net, now);
            }
        }
        final View add = row(mBody, R.drawable.ic_add, getText(R.string.add_title),
                getText(R.string.add_sum));
        add.setTag("add");
        pressHint(add, getText(R.string.hint_add));
        add.setOnClickListener(v -> startActivity(new Intent(this, HomeActivity.class)
                .putExtra(EXTRA_ADD, true)));
        hint(R.string.hint_add);
    }

    private View networkRow(Net net, Status.Snapshot now) {
        final View row = row(mBody, EngineUi.icon(net), net.name, "");
        row.setTag("open:" + net.id);
        pressHint(row, getString(R.string.hint_open, net.name));
        row.setOnClickListener(v -> startActivity(new Intent(this, NetworkActivity.class)
                .putExtra(NetworkActivity.EXTRA_ID, net.id)));
        final View hit = switchOf(row, true);
        hit.setTag("switch:" + net.id);
        hit.setOnClickListener(v -> toggle(net));
        show(row, net, now);
        return row;
    }

    /** The row's line, pill and switch for the state. */
    private void show(View row, Net net, Status.Snapshot now) {
        final boolean current = Status.isCurrent(now, net.id);
        final boolean on = isOn(net, now);
        final ImageView icon = row.findViewById(R.id.icon);
        icon.setBackgroundResource(current && now.state == Status.ON ? R.drawable.icon_bg_on
                : R.drawable.icon_bg);
        icon.setColorFilter(current && now.state == Status.ON ? getColor(R.color.ok_text)
                : 0xFFFFFFFF);
        pill(row, current ? now.state : Status.OFF);
        if (!current) {
            summary(row, getString(R.string.net_off, kindName(net)));
        } else if (EngineUi.isJoined(net)) {
            summary(row, EngineUi.rowLine(this, now.engine, now));
        } else if (now.state == Status.FAILED) {
            summary(row, EngineUi.error(this, now.error));
        } else {
            summary(row, now.address.isEmpty() ? kindName(net)
                    : getString(R.string.net_address, now.address));
        }
        switchOn(row, on);
        pressHint(row.findViewById(R.id.switch_hit), getString(on ? R.string.hint_turn_off
                : R.string.hint_turn_on, net.name));
    }

    /** On or on its way; failed and trying again counts, as only off stops that */
    static boolean isOn(Net net, Status.Snapshot now) {
        if (!Status.isCurrent(now, net.id)) {
            return false;
        }
        return now.state == Status.ON || now.state == Status.CONNECTING
                || (now.state == Status.FAILED && net.stay);
    }

    private String kindName(Net net) {
        return EngineUi.kindName(this, net);
    }

    /** The status of the network that is on: WireGuard's own, or what its engine says */
    private void card(ViewGroup card, Net net, Status.Snapshot now) {
        if (EngineUi.isJoined(net)) {
            EngineUi.card(this, card, net, now, false);
        } else {
            statusCard(card, net, now);
        }
    }

    private void toggle(Net net) {
        if (isOn(net, Status.get())) {
            TunnelService.stop(this);
        } else {
            turnOn(net.id);
        }
        later(this::refresh, 400);
    }

    private void refresh() {
        if (isResumedNow()) {
            onStatus(Status.get());
        }
    }

    @Override
    protected void onTurnedOn(String id, boolean started) {
        later(this::refresh, 400);
    }

    @Override
    protected void onStatus(Status.Snapshot now) {
        if (mAdding) {
            return;
        }
        if (mRemovalRevision != NetworkRemoval.revision() || !builtFor(now).equals(mBuiltFor)) {
            build();
            return;
        }
        if (mCurrent != null && mCurrentRow != null) {
            show(mCurrentRow, mCurrent, now);
            card(mCurrentCard, mCurrent, now);
        }
    }
}
