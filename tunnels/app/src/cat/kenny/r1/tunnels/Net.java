// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * A saved network: a tunnel to one server, from its settings or from Mullvad. Keys are hex,
 * as the tunnel helper takes them; files and QR codes have them in base64, see WgConf.
 */
public final class Net {
    public static final String WG = "wg";
    public static final String MULLVAD = "mullvad";
    public static final String ZEROTIER = "zerotier";
    public static final String TAILSCALE = "tailscale";
    /** Mullvad's location for "Automatic". */
    public static final String AUTO = "auto";

    public String id = "";
    public String kind = WG;
    public String name = "";
    public long created;
    /** This r1's keys, hex. The public one is kept to be shown, and is empty when unknown. */
    public String privateKey = "";
    public String publicKey = "";
    /** This r1's addresses in the tunnel, each with its prefix: 10.8.0.7/24 */
    public List<String> addresses = new ArrayList<>();
    /** DNS servers; names in it are search domains, as wg-quick has them */
    public List<String> dns = new ArrayList<>();
    public int mtu = 1280;
    public String peerKey = "";
    public String presharedKey = "";
    public String host = "";
    public int port = 51820;
    public List<String> allowedIps = new ArrayList<>();
    public int keepalive;
    /** Everything through the tunnel, or only the networks behind it */
    public boolean everything = true;
    public boolean stay;
    // Mullvad only
    public String account = "";
    public String deviceId = "";
    public String location = AUTO;
    /** The location's name as the list had it: Germany */
    public String locationName = "";
    public String lastRelay = "";
    /** ZeroTier network ID, exactly 16 hex digits. Identity is separate app-private state. */
    public String networkId = "";
    /** Tailscale options; auth keys are transient API arguments and never saved here. */
    public String controlUrl = "";
    public String exitNodeId = "";
    public String exitNodeName = "";
    public boolean acceptRoutes = true;
    public boolean acceptDns = true;

    public boolean isMullvad() {
        return MULLVAD.equals(kind);
    }

    /** host:port, with an IPv6 address in brackets */
    public String endpoint() {
        return (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
    }

    /**
     * What goes through the tunnel when not everything: the networks behind the server as the
     * settings list them, or else the tunnel's own network.
     */
    public List<String> someRoutes() {
        final List<String> some = new ArrayList<>();
        for (String cidr : allowedIps) {
            if (!WgConf.isDefaultRoute(cidr)) {
                some.add(cidr);
            }
        }
        if (some.isEmpty()) {
            for (String address : addresses) {
                final String network = WgConf.networkOf(address);
                if (network != null && !some.contains(network)) {
                    some.add(network);
                }
            }
        }
        return some;
    }

    /** The routes the VPN takes: everything, or only the networks behind the server. */
    public List<String> routes() {
        if (everything) {
            final List<String> all = new ArrayList<>();
            all.add("0.0.0.0/0");
            all.add("::/0");
            return all;
        }
        return someRoutes();
    }

    /** What the server is told comes from it: what goes through, at least what it lists. */
    public List<String> peerAllowedIps() {
        final List<String> ips = new ArrayList<>(allowedIps);
        if (everything) {
            if (!ips.contains("0.0.0.0/0")) {
                ips.add("0.0.0.0/0");
            }
            if (!ips.contains("::/0")) {
                ips.add("::/0");
            }
        }
        return ips;
    }

    JSONObject toJson() throws JSONException {
        final JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("kind", kind);
        o.put("name", name);
        o.put("created", created);
        o.put("private_key", privateKey);
        o.put("public_key", publicKey);
        o.put("addresses", new JSONArray(addresses));
        o.put("dns", new JSONArray(dns));
        o.put("mtu", mtu);
        o.put("peer_key", peerKey);
        o.put("preshared_key", presharedKey);
        o.put("host", host);
        o.put("port", port);
        o.put("allowed_ips", new JSONArray(allowedIps));
        o.put("keepalive", keepalive);
        o.put("everything", everything);
        o.put("stay", stay);
        o.put("account", account);
        o.put("device_id", deviceId);
        o.put("location", location);
        o.put("location_name", locationName);
        o.put("last_relay", lastRelay);
        o.put("network_id", networkId);
        o.put("control_url", controlUrl);
        o.put("exit_node_id", exitNodeId);
        o.put("exit_node_name", exitNodeId.isEmpty() ? "" : exitNodeName);
        o.put("accept_routes", acceptRoutes);
        o.put("accept_dns", acceptDns);
        return o;
    }

    static Net fromJson(JSONObject o) {
        final Net net = new Net();
        net.id = o.optString("id", "");
        net.kind = o.optString("kind", WG);
        net.name = o.optString("name", "");
        net.created = o.optLong("created", 0);
        net.privateKey = o.optString("private_key", "");
        net.publicKey = o.optString("public_key", "");
        net.addresses = strings(o.optJSONArray("addresses"));
        net.dns = strings(o.optJSONArray("dns"));
        net.mtu = o.optInt("mtu", 1280);
        net.peerKey = o.optString("peer_key", "");
        net.presharedKey = o.optString("preshared_key", "");
        net.host = o.optString("host", "");
        net.port = o.optInt("port", 51820);
        net.allowedIps = strings(o.optJSONArray("allowed_ips"));
        net.keepalive = o.optInt("keepalive", 0);
        net.everything = o.optBoolean("everything", true);
        net.stay = o.optBoolean("stay", false);
        net.account = o.optString("account", "");
        net.deviceId = o.optString("device_id", "");
        net.location = o.optString("location", AUTO);
        net.locationName = o.optString("location_name", "");
        net.lastRelay = o.optString("last_relay", "");
        net.networkId = o.optString("network_id", "");
        net.controlUrl = o.optString("control_url", "");
        net.exitNodeId = o.optString("exit_node_id", "");
        net.exitNodeName = net.exitNodeId.isEmpty() ? "" : o.optString("exit_node_name", "");
        net.acceptRoutes = o.optBoolean("accept_routes", true);
        net.acceptDns = o.optBoolean("accept_dns", true);
        return net;
    }

    private static List<String> strings(JSONArray array) {
        final List<String> list = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                final String s = array.optString(i, "");
                if (!s.isEmpty()) {
                    list.add(s);
                }
            }
        }
        return list;
    }
}
