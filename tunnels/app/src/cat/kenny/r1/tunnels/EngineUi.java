// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * The words and icons for the kinds of network, and the engines' states in words: nothing an
 * engine says in English is shown, only what its enums mean, mapped here.
 */
final class EngineUi {
    private EngineUi() {
    }

    static boolean isZeroTier(Net net) {
        return Net.ZEROTIER.equals(net.kind);
    }

    static boolean isTailscale(Net net) {
        return Net.TAILSCALE.equals(net.kind);
    }

    /** ZeroTier and Tailscale: their status comes from the engine's snapshot */
    static boolean isJoined(Net net) {
        return isZeroTier(net) || isTailscale(net);
    }

    static int icon(Net net) {
        if (isZeroTier(net)) {
            return R.drawable.ic_hub;
        }
        if (isTailscale(net)) {
            return R.drawable.ic_mesh;
        }
        return net.isMullvad() ? R.drawable.ic_globe : R.drawable.ic_tunnel;
    }

    static String kindName(Context context, Net net) {
        if (isZeroTier(net)) {
            return context.getString(R.string.kind_zt);
        }
        if (isTailscale(net)) {
            return context.getString(R.string.kind_ts);
        }
        return context.getString(net.isMullvad() ? R.string.kind_mullvad : R.string.kind_wg);
    }

    /** The engine's snapshot where it belongs to this network, else null */
    static Engine.Snapshot engineOf(Net net, Status.Snapshot now) {
        return Status.isCurrent(now, net.id) ? now.engine : null;
    }

    static CharSequence error(Context context, Engine.Error error) {
        final int text;
        switch (error == null ? Engine.Error.NONE : error) {
            case MISSING_PROFILE: text = R.string.err_missing; break;
            case REVOKED: text = R.string.err_revoked; break;
            case INVALID_CONFIG: text = R.string.err_config; break;
            case LIBRARY_UNAVAILABLE: text = R.string.err_library; break;
            case NO_CONSENT: text = R.string.err_consent; break;
            case SETTINGS: text = R.string.err_settings; break;
            case DNS: text = R.string.err_dns; break;
            case RELAYS: text = R.string.err_relays; break;
            case HELPER_NO_START: text = R.string.err_helper_start; break;
            case HELPER_EXITED: text = R.string.err_helper_exit; break;
            case HELPER_STRANGER: text = R.string.err_helper_stranger; break;
            case HELPER_REFUSED: text = R.string.err_helper_refused; break;
            case IO: text = R.string.err_io; break;
            case STORAGE: text = R.string.err_storage; break;
            case CORE: text = R.string.err_core; break;
            case CONTROL: text = R.string.err_control; break;
            case CALLBACK: text = R.string.err_callback; break;
            case TIMEOUT: text = R.string.err_timeout; break;
            case STOP_TIMEOUT: text = R.string.err_stop_timeout; break;
            case API: text = R.string.err_api; break;
            case UNSUPPORTED: text = R.string.err_unsupported; break;
            default: text = R.string.err_other; break;
        }
        return context.getText(text);
    }

    /** What failed a future: the engine's error inside whatever wraps it */
    static Engine.Error errorOf(Throwable failure) {
        Throwable t = failure;
        while ((t instanceof CompletionException || t instanceof ExecutionException)
                && t.getCause() != null) {
            t = t.getCause();
        }
        return t instanceof Engine.Failure ? ((Engine.Failure) t).error : Engine.Error.API;
    }

    static CharSequence networkState(Context context, Engine.NetworkState state) {
        final int text;
        switch (state == null ? Engine.NetworkState.UNKNOWN : state) {
            case REQUESTING_CONFIGURATION: text = R.string.zt_ns_requesting; break;
            case OK: text = R.string.zt_ns_ok; break;
            case ACCESS_DENIED: text = R.string.zt_ns_denied; break;
            case NOT_FOUND: text = R.string.zt_ns_not_found; break;
            case PORT_ERROR: text = R.string.zt_ns_port; break;
            case CLIENT_TOO_OLD: text = R.string.zt_ns_too_old; break;
            case AUTHENTICATION_REQUIRED: text = R.string.zt_ns_auth; break;
            default: text = R.string.zt_ns_unknown; break;
        }
        return context.getText(text);
    }

    /** The engine's state as one or two words, for a row's line */
    static CharSequence state(Context context, Engine.Snapshot engine) {
        switch (engine.state) {
            case WAITING_APPROVAL: return context.getText(R.string.eng_waiting);
            case AUTHENTICATING: return context.getText(R.string.eng_signing_in);
            case RUNNING: return context.getText(R.string.eng_running);
            case STOPPED: return context.getText(R.string.eng_stopped);
            case FAILED: return error(context, engine.error);
            default: return context.getText(R.string.eng_starting);
        }
    }

    /** A row's line for a ZeroTier or Tailscale network that is current */
    static CharSequence rowLine(Context context, Engine.Snapshot engine, Status.Snapshot now) {
        if (now.state == Status.FAILED || engine != null && engine.state == Engine.State.FAILED) {
            return error(context, now.error != Engine.Error.NONE ? now.error
                    : engine != null ? engine.error : Engine.Error.NONE);
        }
        if (engine == null) {
            return context.getText(R.string.eng_starting);
        }
        if (engine.state == Engine.State.RUNNING && !engine.addresses.isEmpty()) {
            return context.getString(R.string.net_address, bare(engine.addresses.get(0)));
        }
        return state(context, engine);
    }

    /** "8056c2e21c000001" as "8056 c2e2 1c00 0001"; a node "8a4c1f02de" as "8a4c 1f02 de" */
    static String grouped(String hex) {
        final StringBuilder out = new StringBuilder();
        for (int i = 0; i < hex.length(); i++) {
            if (i > 0 && i % 4 == 0) {
                out.append(' ');
            }
            out.append(hex.charAt(i));
        }
        return out.toString();
    }

    static String bare(String cidr) {
        final int slash = cidr.indexOf('/');
        return slash > 0 ? cidr.substring(0, slash) : cidr;
    }

    static String bareAll(List<String> cidrs) {
        final List<String> out = new ArrayList<>();
        for (String cidr : cidrs) {
            out.add(bare(cidr));
        }
        return String.join(", ", out);
    }

    /** A device's name as people say it: the first part of its DNS name */
    static String deviceName(Engine.Peer peer) {
        String name = peer.name == null ? "" : peer.name;
        final int dot = name.indexOf('.');
        if (dot > 0) {
            name = name.substring(0, dot);
        }
        if (name.isEmpty() && !peer.addresses.isEmpty()) {
            name = bare(peer.addresses.get(0));
        }
        return name;
    }

    /** Through a relay where any device is only reached so, else direct, else null */
    static CharSequence link(Context context, Engine.Snapshot engine) {
        boolean direct = false;
        boolean relay = engine.relayed;
        for (Engine.Peer peer : engine.peers) {
            direct |= peer.path == Engine.Path.DIRECT;
            relay |= peer.path == Engine.Path.RELAY;
        }
        if (relay) {
            return context.getText(R.string.link_relay);
        }
        return direct ? context.getText(R.string.link_direct) : null;
    }

    /**
     * The status card of a ZeroTier or Tailscale network that is current, short on the app's
     * page, whole on the network's own: what went wrong, or how it is going.
     */
    static void card(Page page, ViewGroup card, Net net, Status.Snapshot now, boolean whole) {
        card.removeAllViews();
        if (!Status.isCurrent(now, net.id)) {
            card.setVisibility(View.GONE);
            return;
        }
        final Engine.Snapshot engine = engineOf(net, now);
        if (now.state == Status.FAILED || engine != null && engine.state == Engine.State.FAILED) {
            card.setVisibility(View.VISIBLE);
            page.kv(card, page.getText(R.string.kv_reason), error(page,
                    now.error != Engine.Error.NONE ? now.error
                    : engine != null ? engine.error : Engine.Error.NONE))
                    .setTextColor(page.getColor(R.color.warn));
            return;
        }
        if (engine == null) {
            card.setVisibility(View.GONE);
            return;
        }
        card.setVisibility(View.VISIBLE);
        if (isZeroTier(net)) {
            zeroTier(page, card, net, engine, whole);
        } else {
            tailscale(page, card, engine, whole);
        }
        if (whole && engine.state == Engine.State.RUNNING && (engine.rx > 0 || engine.tx > 0)) {
            page.kv(card, page.getText(R.string.kv_data), page.getString(R.string.data_value,
                    Formatter.formatShortFileSize(page, engine.tx),
                    Formatter.formatShortFileSize(page, engine.rx)));
        }
    }

    private static void zeroTier(Page page, ViewGroup card, Net net, Engine.Snapshot engine,
            boolean whole) {
        page.kv(card, page.getText(R.string.kv_network_name), engine.networkName.isEmpty()
                ? page.getText(R.string.not_told_yet) : engine.networkName);
        if (whole) {
            page.kv(card, page.getText(R.string.kv_network_id), net.networkId);
        }
        if (!whole && !engine.nodeAddress.isEmpty()) {
            page.kv(card, page.getText(R.string.kv_zt_address), grouped(engine.nodeAddress));
        }
        final TextView state = page.kv(card, page.getText(R.string.kv_state),
                networkState(page, engine.networkState));
        if (engine.networkState == Engine.NetworkState.ACCESS_DENIED) {
            state.setTextColor(page.accent());
        } else if (engine.networkState == Engine.NetworkState.NOT_FOUND
                || engine.networkState == Engine.NetworkState.PORT_ERROR
                || engine.networkState == Engine.NetworkState.CLIENT_TOO_OLD
                || engine.networkState == Engine.NetworkState.AUTHENTICATION_REQUIRED) {
            state.setTextColor(page.getColor(R.color.warn));
        }
        page.kv(card, page.getText(R.string.kv_addresses_given), engine.addresses.isEmpty()
                ? page.getText(R.string.none_yet) : bareAll(engine.addresses));
        if (engine.state == Engine.State.RUNNING) {
            final CharSequence link = link(page, engine);
            if (link != null) {
                page.kv(card, page.getText(R.string.kv_link), link);
            }
        }
    }

    private static void tailscale(Page page, ViewGroup card, Engine.Snapshot engine,
            boolean whole) {
        if (engine.state != Engine.State.RUNNING) {
            final TextView state = page.kv(card, page.getText(R.string.kv_state),
                    state(page, engine));
            if (engine.state == Engine.State.WAITING_APPROVAL) {
                state.setTextColor(page.accent());
            }
        }
        if (!engine.networkName.isEmpty()) {
            page.kv(card, page.getText(R.string.kv_tailnet), engine.networkName);
        }
        if (whole && !engine.loginName.isEmpty()) {
            page.kv(card, page.getText(R.string.kv_signed_in_as), engine.loginName);
        }
        if (whole && !engine.hostName.isEmpty()) {
            page.kv(card, page.getText(R.string.kv_device_name), engine.hostName);
        }
        if (whole && !engine.addresses.isEmpty()) {
            page.kv(card, page.getText(R.string.kv_ts_name), bareAll(engine.addresses));
        }
        if (engine.state == Engine.State.RUNNING && !engine.peers.isEmpty()) {
            int online = 0;
            for (Engine.Peer peer : engine.peers) {
                if (peer.path != Engine.Path.UNREACHABLE) {
                    online++;
                }
            }
            page.kv(card, page.getText(R.string.kv_reach), page.getString(R.string.reach_value,
                    online, engine.peers.size()));
            final CharSequence link = link(page, engine);
            if (link != null) {
                page.kv(card, page.getText(R.string.kv_link), link);
            }
        }
    }

    /** The small line under a set-up's first page: whose name it is and that it is not theirs */
    static void footer(Page page, ViewGroup parent, int text) {
        final TextView mark = (TextView) page.getLayoutInflater().inflate(R.layout.lead, parent,
                false);
        mark.setText(text);
        mark.setTextSize(13);
        mark.setTextColor(page.getColor(R.color.text_faint));
        parent.addView(mark);
    }
}
