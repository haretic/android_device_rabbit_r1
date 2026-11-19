// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import cat.kenny.r1.tunnels.zt.ZeroTierEngine;

import java.util.concurrent.CompletableFuture;
import java.lang.ref.WeakReference;

/**
 * A network's own page: whether it is on and how it is going, what goes through it, staying
 * connected, where Mullvad's traffic comes out, its settings, and removing it. Each is a row:
 * the wheel goes from one to the next and a press acts, as a tap does. Removing takes a
 * second press, so a hold, which goes back, never does anything to it. The page starts on
 * the status card, which only reads, so that a first press does not turn it on or off.
 *
 * ZeroTier's page shows this r1's address large and as a code, for the network's owner to
 * approve it by, and removing it leaves the network. Tailscale's has who all traffic goes out
 * through, its two options, and signing out in place of removing.
 */
public final class NetworkActivity extends Page {
    static final String EXTRA_ID = "id";
    private static final long CONFIRM_MS = 4000;

    private String mId;
    private Net mNet;
    private View mStateRow;
    private ViewGroup mCard;
    private View mStayNote;
    private View mRemoveRow;
    private boolean mArmed;
    // ZeroTier's and Tailscale's
    private View mAddressBox;
    private TextView mAddress;
    private QrView mAddressQr;
    private View mWaitNote;
    private View mSignInRow;
    private View mExitRow;
    private boolean mBusy;
    private String mSavedNodeAddress;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mId = getIntent().getStringExtra(EXTRA_ID);
        watchStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mBusy) {
            return;
        }
        mNet = Store.load(this, mId);
        if (mNet == null) {
            finish();
            return;
        }
        mSavedNodeAddress = EngineUi.isZeroTier(mNet)
                ? ZeroTierEngine.nodeAddress(getApplicationContext()) : null;
        build();
        CompletableFuture<Void> pending = NetworkRemoval.pending(mId);
        if (pending != null) observeRemoval(pending);
    }

    private void build() {
        final View focused = getCurrentFocus();
        final Object key = focused != null ? focused.getTag() : null;
        mBody.removeAllViews();
        mArmed = false;
        mAddressBox = null;
        mWaitNote = null;
        mSignInRow = null;
        mExitRow = null;
        final Net net = mNet;
        final boolean zt = EngineUi.isZeroTier(net);
        final boolean ts = EngineUi.isTailscale(net);
        title(net.name);
        final Status.Snapshot now = Status.get();

        mStateRow = row(mBody, EngineUi.icon(net), net.name, "");
        mStateRow.setTag("state");
        switchOf(mStateRow, false);
        pressHint(mStateRow, getText(R.string.hint_toggle));
        mStateRow.setOnClickListener(v -> toggle());
        mCard = card(mBody);
        mCard.setTag("status");
        stop(mCard);
        if (zt) {
            addressBox();
        }
        if (zt || ts) {
            mWaitNote = note(mBody, R.drawable.ic_info, "");
            noteText(mWaitNote, "", false);
        }
        if (ts) {
            mSignInRow = row(mBody, R.drawable.ic_key, getText(R.string.ts_sign_in_again),
                    getText(R.string.ts_sign_in_again_sum));
            mSignInRow.setTag("signin");
            chevron(mSignInRow);
            pressHint(mSignInRow, getText(R.string.hint_row));
            mSignInRow.setOnClickListener(v -> startActivity(new Intent(this,
                    TsSignInActivity.class).putExtra(TsSignInActivity.EXTRA_ID, net.id)));
            mSignInRow.setVisibility(View.GONE);
        }
        show(now);

        if (ts) {
            mExitRow = row(mBody, R.drawable.ic_exit, getText(R.string.ts_exit), "");
            mExitRow.setTag("exit");
            chevron(mExitRow);
            pressHint(mExitRow, getText(R.string.hint_row));
            mExitRow.setOnClickListener(v -> startActivity(new Intent(this,
                    TsExitActivity.class).putExtra(TsExitActivity.EXTRA_ID, net.id)));
            exitLine(now);
            TsOptionsActivity.optionRow(this, mBody, net, true);
            TsOptionsActivity.optionRow(this, mBody, net, false);
        } else if (zt) {
            // What goes through it is the network's to say
        } else if (!net.isMullvad()) {
            final View route = row(mBody, R.drawable.ic_route, getText(R.string.route_title),
                    routeText(net));
            route.setTag("route");
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
                Store.save(this, net);
                summary(route, routeText(net));
                reconnectIfOn();
            });
        } else {
            final View place = row(mBody, R.drawable.ic_flag, getText(R.string.place_title),
                    Net.AUTO.equals(net.location) || net.locationName.isEmpty()
                            ? getText(R.string.auto_title) : net.locationName);
            place.setTag("place");
            chevron(place);
            pressHint(place, getText(R.string.hint_row));
            place.setOnClickListener(v -> startActivity(new Intent(this,
                    MullvadPlaceActivity.class).putExtra(MullvadPlaceActivity.EXTRA_ID, net.id)));
        }

        final View stay = row(mBody, R.drawable.ic_lock, getText(R.string.stay_title),
                getText(R.string.stay_sum));
        stay.setTag("stay");
        switchOf(stay, false);
        switchOn(stay, net.stay);
        pressHint(stay, getText(R.string.hint_toggle));
        stay.setOnClickListener(v -> {
            net.stay = !net.stay;
            Store.save(this, net);
            switchOn(stay, net.stay);
            noteText(mStayNote, net.stay ? getText(R.string.stay_note) : "", false);
            if (Status.isCurrent(Status.get(), net.id)) {
                final boolean on = net.stay;
                new Thread(() -> TunnelService.applyAlwaysOn(getApplicationContext(), on))
                        .start();
            }
        });
        mStayNote = note(mBody, R.drawable.ic_info, "");
        noteText(mStayNote, net.stay ? getText(R.string.stay_note) : "", false);

        if (!zt && !ts) {
            // ZeroTier's and Tailscale's have nothing to show: no keys, no settings file
            final View settings = row(mBody, R.drawable.ic_file,
                    getText(R.string.show_settings), getText(R.string.show_settings_sum));
            settings.setTag("settings");
            chevron(settings);
            pressHint(settings, getText(R.string.hint_row));
            settings.setOnClickListener(v -> startActivity(new Intent(this,
                    ShowSettingsActivity.class).putExtra(ShowSettingsActivity.EXTRA_ID, net.id)));
        }

        if (ts) {
            mRemoveRow = row(mBody, R.drawable.ic_logout, getText(R.string.ts_sign_out),
                    removeLine());
            pressHint(mRemoveRow, getText(R.string.hint_sign_out));
        } else {
            mRemoveRow = row(mBody, R.drawable.ic_delete, getText(R.string.remove),
                    removeLine());
            pressHint(mRemoveRow, getText(R.string.hint_remove));
        }
        mRemoveRow.setTag("remove");
        mRemoveRow.setOnClickListener(v -> remove());
        mRemoveRow.setOnFocusChangeListener((v, has) -> {
            if (!has) {
                disarm();
            }
        });

        hint(R.string.hint_row);
        final View again = key != null ? mBody.findViewWithTag(key) : null;
        // The card is where a press after a tap lands too: leaving touch mode takes the view
        // marked as the default, not the topmost row, which turns the network on or off
        mCard.setFocusedByDefault(true);
        (again != null && again.getVisibility() == View.VISIBLE ? again : mCard)
                .requestFocus();
    }

    /** ZeroTier: this r1's address, large and as a code; beside each other on its side */
    private void addressBox() {
        final boolean wide = getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        final float density = getResources().getDisplayMetrics().density;
        final LinearLayout box = new LinearLayout(this);
        box.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        mBody.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        final View big = getLayoutInflater().inflate(R.layout.big_value, box, false);
        ((TextView) big.findViewById(R.id.big_label)).setText(R.string.kv_zt_address);
        mAddress = big.findViewById(R.id.big_value);
        box.addView(big, wide ? new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1) : big.getLayoutParams());
        mAddressQr = (QrView) getLayoutInflater().inflate(R.layout.qr, box, false);
        final int side = (int) ((wide ? 150 : 200) * density);
        final LinearLayout.LayoutParams qr = new LinearLayout.LayoutParams(side, side);
        qr.gravity = wide ? Gravity.TOP : Gravity.CENTER_HORIZONTAL;
        qr.bottomMargin = (int) (10 * density);
        if (wide) {
            qr.setMarginStart((int) (12 * density));
        }
        box.addView(mAddressQr, qr);
        mAddressBox = box;
    }

    private String routeText(Net net) {
        return net.everything ? getString(R.string.route_all)
                : getString(R.string.route_some, String.join(", ", net.someRoutes()));
    }

    private CharSequence removeLine() {
        if (EngineUi.isTailscale(mNet)) {
            return getText(R.string.ts_sign_out_sum);
        }
        if (EngineUi.isZeroTier(mNet)) {
            return getText(R.string.remove_sum_zt);
        }
        return getText(mNet.isMullvad() ? R.string.remove_sum_mullvad : R.string.remove_sum);
    }

    private void show(Status.Snapshot now) {
        final Net net = mNet;
        final boolean current = Status.isCurrent(now, net.id);
        final boolean on = HomeActivity.isOn(net, now);
        final ImageView icon = mStateRow.findViewById(R.id.icon);
        icon.setBackgroundResource(current && now.state == Status.ON ? R.drawable.icon_bg_on
                : R.drawable.icon_bg);
        pill(mStateRow, current ? now.state : Status.OFF);
        if (EngineUi.isJoined(net)) {
            summary(mStateRow, current ? EngineUi.rowLine(this, now.engine, now)
                    : getString(R.string.state_off));
            switchOn(mStateRow, on);
            // The card is a stop of the wheel: never hidden under it, which would move it on
            if (current && (now.state == Status.FAILED || now.engine != null)) {
                EngineUi.card(this, mCard, net, now, true);
            } else {
                offCard(net, getText(current ? R.string.eng_starting : R.string.state_off));
            }
            joined(net, current ? now.engine : null);
            return;
        }
        if (!current) {
            summary(mStateRow, getString(R.string.state_off));
        } else if (now.state == Status.FAILED) {
            summary(mStateRow, EngineUi.error(this, now.error));
        } else {
            summary(mStateRow, now.address.isEmpty() ? ""
                    : getString(R.string.net_address, now.address));
        }
        switchOn(mStateRow, on);
        if (current) {
            mCard.setVisibility(View.VISIBLE);
            statusCard(mCard, net, now);
        } else {
            offCard(net, getText(R.string.state_off));
        }
    }

    /** Off, or not started yet: the card says so, and where WireGuard's would connect to */
    private void offCard(Net net, CharSequence state) {
        mCard.removeAllViews();
        mCard.setVisibility(View.VISIBLE);
        kv(mCard, getText(R.string.kv_state), state);
        if (!EngineUi.isJoined(net)) {
            if (net.isMullvad()) {
                kv(mCard, getText(R.string.kv_out), Net.AUTO.equals(net.location)
                        || net.locationName.isEmpty() ? getText(R.string.auto_title)
                        : net.locationName);
            } else {
                kv(mCard, getText(R.string.kv_server), net.endpoint());
            }
        } else if (EngineUi.isZeroTier(net)) {
            kv(mCard, getText(R.string.kv_network_id), EngineUi.grouped(net.networkId));
        }
    }

    /** ZeroTier's address and waiting; Tailscale's waiting and signing in again */
    private void joined(Net net, Engine.Snapshot engine) {
        CharSequence wait = "";
        boolean warn = false;
        if (EngineUi.isZeroTier(net)) {
            if (engine != null && !engine.nodeAddress.isEmpty()) mSavedNodeAddress = engine.nodeAddress;
            final String address = mSavedNodeAddress != null ? mSavedNodeAddress : "";
            if (mAddressBox != null) {
                mAddressBox.setVisibility(address.isEmpty() ? View.GONE : View.VISIBLE);
                if (!address.isEmpty()) {
                    mAddress.setText(EngineUi.grouped(address));
                    mAddressQr.set(address);
                }
            }
            if (engine != null && engine.state != Engine.State.FAILED) {
                if (engine.networkState == Engine.NetworkState.ACCESS_DENIED) {
                    wait = getString(R.string.zt_waiting, EngineUi.grouped(address));
                } else if (engine.networkState == Engine.NetworkState.NOT_FOUND) {
                    wait = getText(R.string.zt_not_found);
                    warn = true;
                }
            }
        } else {
            final boolean signedOut = engine != null && engine.state != Engine.State.FAILED
                    && (engine.state == Engine.State.AUTHENTICATING
                    || engine.backendState == Engine.BackendState.NEEDS_LOGIN);
            if (engine != null && engine.state == Engine.State.WAITING_APPROVAL) {
                wait = getText(R.string.ts_waiting_page);
            }
            if (mSignInRow != null) {
                mSignInRow.setVisibility(signedOut ? View.VISIBLE : View.GONE);
            }
            exitLine(Status.get());
        }
        if (mWaitNote != null) {
            noteText(mWaitNote, wait, warn);
            if (!warn && wait.length() > 0) {
                // Waiting is nothing wrong: in the accent, not the warning's colour
                ((TextView) mWaitNote.findViewById(R.id.note_text)).setTextColor(accent());
            }
        }
    }

    /** Who all traffic goes out through: nobody, a device by its name where it is known */
    private void exitLine(Status.Snapshot now) {
        if (mExitRow == null) {
            return;
        }
        final String id = mNet.exitNodeId;
        if (id.isEmpty()) {
            summary(mExitRow, getText(R.string.ts_exit_nobody));
            return;
        }
        final Engine.Snapshot engine = EngineUi.engineOf(mNet, now);
        if (engine != null) {
            for (Engine.Peer peer : engine.peers) {
                if (id.equals(peer.id)) {
                    summary(mExitRow, EngineUi.deviceName(peer));
                    return;
                }
            }
        }
        summary(mExitRow, mNet.exitNodeName.isEmpty()
                ? getText(R.string.ts_exit_someone) : mNet.exitNodeName);
    }

    private void toggle() {
        if (mBusy) {
            return;
        }
        if (HomeActivity.isOn(mNet, Status.get())) {
            TunnelService.stop(this);
        } else {
            turnOn(mNet.id);
        }
        later(this::refresh, 400);
    }

    private void refresh() {
        if (isResumedNow() && mNet != null) {
            show(Status.get());
        }
    }

    @Override
    protected void onTurnedOn(String id, boolean started) {
        later(this::refresh, 400);
    }

    private void reconnectIfOn() {
        final Status.Snapshot now = Status.get();
        if (Status.isCurrent(now, mNet.id) && now.state != Status.FAILED) {
            TunnelService.start(this, mNet.id);
        }
    }

    private void remove() {
        if (mBusy) {
            return;
        }
        if (!mArmed) {
            mArmed = true;
            summary(mRemoveRow, getText(EngineUi.isTailscale(mNet)
                    ? R.string.ts_sign_out_confirm : R.string.remove_confirm));
            ((TextView) mRemoveRow.findViewById(R.id.summary))
                    .setTextColor(getColor(R.color.text_sum));
            later(this::disarm, CONFIRM_MS);
            return;
        }
        mArmed = false;
        if (EngineUi.isTailscale(mNet)) summary(mRemoveRow, getText(R.string.ts_signing_out));
        observeRemoval(NetworkRemoval.remove(getApplicationContext(), mNet));
    }

    /** UI observation only: completion, profile deletion and service shutdown outlive this page. */
    private void observeRemoval(CompletableFuture<Void> operation) {
        mBusy = true;
        final WeakReference<NetworkActivity> page = new WeakReference<>(this);
        operation.whenCompleteAsync((ignored, failure) -> {
            NetworkActivity activity = page.get();
            if (activity == null || activity.isDestroyed()) return;
            activity.mBusy = false;
            if (failure == null) {
                activity.finish();
            } else if (EngineUi.isTailscale(activity.mNet)) {
                activity.signOutFailed();
            } else if (activity.mRemoveRow != null) {
                Page.summary(activity.mRemoveRow, EngineUi.error(activity, EngineUi.errorOf(failure)));
            }
        }, getApplicationContext().getMainExecutor());
    }

    private void signOutFailed() {
        mBusy = false;
        if (mRemoveRow != null) {
            summary(mRemoveRow, getText(R.string.ts_sign_out_failed));
            ((TextView) mRemoveRow.findViewById(R.id.summary))
                    .setTextColor(getColor(R.color.warn));
        }
    }

    private void disarm() {
        if (mArmed && mRemoveRow != null && mNet != null && !mBusy) {
            mArmed = false;
            summary(mRemoveRow, removeLine());
            ((TextView) mRemoveRow.findViewById(R.id.summary))
                    .setTextColor(getColor(R.color.text_sum));
        }
    }

    @Override
    protected void onStatus(Status.Snapshot now) {
        if (mNet != null && mStateRow != null) {
            show(now);
        }
    }
}
