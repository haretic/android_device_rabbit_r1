// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A tunnel's settings in the wg-quick format, as files and QR codes have them: [Interface]
 * with PrivateKey, Address, DNS and MTU, and one [Peer] with PublicKey, PresharedKey,
 * AllowedIPs, Endpoint and PersistentKeepalive. Keys are base64 there and hex for the tunnel
 * helper. Plain Java with no Android in it; selfCheck() runs the cases below.
 *
 * Nothing here puts a value into a message: a bad line is named by its number and setting.
 */
final class WgConf {

    /** Settings that cannot be used, said with the line they are on. */
    static final class BadConf extends Exception {
        BadConf(String message) {
            super(message);
        }
    }

    private WgConf() {
    }

    // wg-quick's own settings, which do nothing on the r1: taken and left alone
    private static final String[] IGNORED = {
        "listenport", "table", "fwmark", "saveconfig", "preup", "postup", "predown",
        "postdown", "includedapplications", "excludedapplications",
    };

    static Net parse(String text) throws BadConf {
        final Net net = new Net();
        net.kind = Net.WG;
        String section = null;
        boolean sawInterface = false;
        int peers = 0;
        final String[] lines = text.split("\r?\n|\r", -1);
        for (int i = 0; i < lines.length; i++) {
            final int n = i + 1;
            String line = lines[i];
            final int hash = line.indexOf('#');
            if (hash >= 0) {
                line = line.substring(0, hash);
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("[")) {
                if (!line.endsWith("]")) {
                    throw bad(n, null, "a section name needs its closing ]");
                }
                final String name = line.substring(1, line.length() - 1).trim()
                        .toLowerCase(Locale.ROOT);
                if (name.equals("interface")) {
                    if (sawInterface) {
                        throw bad(n, null, "a second [Interface]");
                    }
                    sawInterface = true;
                } else if (name.equals("peer")) {
                    peers++;
                    if (peers > 1) {
                        throw bad(n, null, "only one [Peer] can be used");
                    }
                } else {
                    throw bad(n, null, "unknown section");
                }
                section = name;
                continue;
            }
            final int eq = line.indexOf('=');
            if (eq <= 0) {
                throw bad(n, null, "expected a setting as Name = value");
            }
            final String shown = line.substring(0, eq).trim();
            final String key = shown.toLowerCase(Locale.ROOT);
            final String value = line.substring(eq + 1).trim();
            if (section == null) {
                throw bad(n, shown, "settings go under [Interface] or [Peer]");
            }
            if (isIgnored(key)) {
                continue;
            }
            if (section.equals("interface")) {
                switch (key) {
                    case "privatekey":
                        net.privateKey = key(n, shown, value);
                        break;
                    case "address":
                        for (String item : list(value)) {
                            net.addresses.add(cidr(n, shown, item, true));
                        }
                        break;
                    case "dns":
                        for (String item : list(value)) {
                            if (parseIp(item) == null && !isHostName(item)) {
                                throw bad(n, shown, "not an address or a domain");
                            }
                            net.dns.add(item);
                        }
                        break;
                    case "mtu":
                        net.mtu = number(n, shown, value, 576, 65535);
                        break;
                    default:
                        throw bad(n, shown, "not a setting of [Interface]");
                }
            } else {
                switch (key) {
                    case "publickey":
                        net.peerKey = key(n, shown, value);
                        break;
                    case "presharedkey":
                        net.presharedKey = key(n, shown, value);
                        break;
                    case "allowedips":
                        for (String item : list(value)) {
                            net.allowedIps.add(cidr(n, shown, item, false));
                        }
                        break;
                    case "endpoint":
                        final Object[] endpoint = endpoint(value);
                        if (endpoint == null) {
                            throw bad(n, shown, "expected a server as name:port or [address]:port");
                        }
                        net.host = (String) endpoint[0];
                        net.port = (Integer) endpoint[1];
                        break;
                    case "persistentkeepalive":
                        net.keepalive = value.equalsIgnoreCase("off") ? 0
                                : number(n, shown, value, 0, 65535);
                        break;
                    default:
                        throw bad(n, shown, "not a setting of [Peer]");
                }
            }
        }
        if (!sawInterface) {
            throw new BadConf("There is no [Interface]: this is not a tunnel's settings");
        }
        if (net.privateKey.isEmpty()) {
            throw new BadConf("[Interface] has no PrivateKey");
        }
        if (net.addresses.isEmpty()) {
            throw new BadConf("[Interface] has no Address");
        }
        if (peers == 0) {
            throw new BadConf("There is no [Peer]");
        }
        if (net.peerKey.isEmpty()) {
            throw new BadConf("[Peer] has no PublicKey");
        }
        if (net.host.isEmpty()) {
            throw new BadConf("[Peer] has no Endpoint");
        }
        if (net.allowedIps.isEmpty()) {
            throw new BadConf("[Peer] has no AllowedIPs");
        }
        net.everything = false;
        for (String cidr : net.allowedIps) {
            if (isDefaultRoute(cidr)) {
                net.everything = true;
            }
        }
        return net;
    }

    /** The settings in wg-quick's format; with hide, the secret keys are left out. */
    static String write(Net net, boolean hide) {
        final StringBuilder out = new StringBuilder();
        out.append("[Interface]\n");
        out.append("PrivateKey = ").append(hide ? "(hidden)" : base64FromHex(net.privateKey))
                .append('\n');
        out.append("Address = ").append(String.join(", ", net.addresses)).append('\n');
        if (!net.dns.isEmpty()) {
            out.append("DNS = ").append(String.join(", ", net.dns)).append('\n');
        }
        out.append("MTU = ").append(net.mtu).append('\n');
        out.append("\n[Peer]\n");
        out.append("PublicKey = ").append(base64FromHex(net.peerKey)).append('\n');
        if (!net.presharedKey.isEmpty()) {
            out.append("PresharedKey = ")
                    .append(hide ? "(hidden)" : base64FromHex(net.presharedKey)).append('\n');
        }
        out.append("AllowedIPs = ").append(String.join(", ", net.allowedIps)).append('\n');
        out.append("Endpoint = ").append(net.endpoint()).append('\n');
        if (net.keepalive > 0) {
            out.append("PersistentKeepalive = ").append(net.keepalive).append('\n');
        }
        return out.toString();
    }

    // Keys

    /** A base64 key as hex, or null when it is not 32 bytes of base64. */
    static String hexFromBase64(String base64) {
        final byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
        if (bytes.length != 32) {
            return null;
        }
        final StringBuilder hex = new StringBuilder(64);
        for (byte b : bytes) {
            hex.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
        }
        return hex.toString();
    }

    /** A hex key as base64, or "" for anything that is not 64 hex digits. */
    static String base64FromHex(String hex) {
        if (!isKeyHex(hex)) {
            return "";
        }
        final byte[] bytes = new byte[32];
        for (int i = 0; i < 32; i++) {
            bytes[i] = (byte) Integer.parseInt(hex.substring(2 * i, 2 * i + 2), 16);
        }
        return Base64.getEncoder().encodeToString(bytes);
    }

    static boolean isKeyHex(String hex) {
        if (hex == null || hex.length() != 64) {
            return false;
        }
        for (int i = 0; i < 64; i++) {
            if (Character.digit(hex.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    // Addresses

    static boolean isDefaultRoute(String cidr) {
        final int slash = cidr.indexOf('/');
        return slash > 0 && cidr.substring(slash + 1).equals("0");
    }

    /** The network an address is in, 10.8.0.7/24 being in 10.8.0.0/24; null if it is none. */
    static String networkOf(String cidr) {
        final int slash = cidr.indexOf('/');
        if (slash <= 0) {
            return null;
        }
        final byte[] ip = parseIp(cidr.substring(0, slash));
        final int prefix;
        try {
            prefix = Integer.parseInt(cidr.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (ip == null || isMappedV4(ip) || prefix < 0 || prefix > ip.length * 8) {
            return null;
        }
        for (int bit = prefix; bit < ip.length * 8; bit++) {
            ip[bit / 8] &= (byte) ~(0x80 >> (bit % 8));
        }
        return format(ip) + "/" + prefix;
    }

    /** An address with its prefix; a bare address is the one address, /32 or /128. */
    static String cidr(String item) {
        final int slash = item.indexOf('/');
        final String address = slash >= 0 ? item.substring(0, slash) : item;
        final byte[] ip = parseIp(address);
        if (ip == null || isMappedV4(ip)) {
            return null;
        }
        final String canonical = ip.length == 4 ? format(ip) : address;
        if (slash < 0) {
            return canonical + "/" + (ip.length * 8);
        }
        final int prefix;
        try {
            prefix = Integer.parseInt(item.substring(slash + 1));
        } catch (NumberFormatException e) {
            return null;
        }
        if (prefix < 0 || prefix > ip.length * 8) {
            return null;
        }
        return canonical + "/" + prefix;
    }

    /** The bytes of a numeric IPv4 or IPv6 address, or null; never a name lookup. */
    static byte[] parseIp(String s) {
        if (s == null || s.isEmpty()) {
            return null;
        }
        if (s.indexOf(':') < 0) {
            return parseV4(s);
        }
        return parseV6(s);
    }

    // Android InetAddress collapses these to IPv4, invalidating /96../128 prefixes.
    private static boolean isMappedV4(byte[] ip) {
        if (ip.length != 16 || ip[10] != (byte) 0xff || ip[11] != (byte) 0xff) {
            return false;
        }
        for (int i = 0; i < 10; i++) {
            if (ip[i] != 0) return false;
        }
        return true;
    }

    private static byte[] parseV4(String s) {
        final String[] parts = s.split("\\.", -1);
        if (parts.length != 4) {
            return null;
        }
        final byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            final String part = parts[i];
            if (part.isEmpty() || part.length() > 3) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < part.length(); j++) {
                final char c = part.charAt(j);
                if (c < '0' || c > '9') {
                    return null;
                }
                value = value * 10 + (c - '0');
            }
            if (value > 255) {
                return null;
            }
            out[i] = (byte) value;
        }
        return out;
    }

    private static byte[] parseV6(String s) {
        final int gap = s.indexOf("::");
        if (gap >= 0 && s.indexOf("::", gap + 1) >= 0) {
            return null;
        }
        final List<Integer> head = groups(gap >= 0 ? s.substring(0, gap) : s, gap < 0);
        final List<Integer> tail = gap >= 0 ? groups(s.substring(gap + 2), true)
                : new ArrayList<>();
        if (head == null || tail == null) {
            return null;
        }
        final int count = head.size() + tail.size();
        if (gap >= 0 ? count > 7 : count != 8) {
            return null;
        }
        final byte[] out = new byte[16];
        for (int i = 0; i < head.size(); i++) {
            out[2 * i] = (byte) (head.get(i) >> 8);
            out[2 * i + 1] = (byte) (int) head.get(i);
        }
        final int start = 8 - tail.size();
        for (int i = 0; i < tail.size(); i++) {
            out[2 * (start + i)] = (byte) (tail.get(i) >> 8);
            out[2 * (start + i) + 1] = (byte) (int) tail.get(i);
        }
        return out;
    }

    /** Groups of up to four hex digits; the last may be an IPv4 address, two groups. */
    private static List<Integer> groups(String part, boolean v4Last) {
        final List<Integer> out = new ArrayList<>();
        if (part.isEmpty()) {
            return out;
        }
        final String[] items = part.split(":", -1);
        for (int i = 0; i < items.length; i++) {
            final String item = items[i];
            if (item.indexOf('.') >= 0) {
                if (!v4Last || i != items.length - 1) {
                    return null;
                }
                final byte[] v4 = parseV4(item);
                if (v4 == null) {
                    return null;
                }
                out.add(((v4[0] & 255) << 8) | (v4[1] & 255));
                out.add(((v4[2] & 255) << 8) | (v4[3] & 255));
                continue;
            }
            if (item.isEmpty() || item.length() > 4) {
                return null;
            }
            int value = 0;
            for (int j = 0; j < item.length(); j++) {
                final char c = item.charAt(j);
                if (!(c >= '0' && c <= '9') && !(c >= 'a' && c <= 'f')
                        && !(c >= 'A' && c <= 'F')) {
                    return null;
                }
                final int digit = Character.digit(c, 16);
                if (digit < 0) {
                    return null;
                }
                value = value * 16 + digit;
            }
            out.add(value);
        }
        return out;
    }

    /** An address as text; from bytes, so no name is looked up. */
    static String format(byte[] ip) {
        try {
            return InetAddress.getByAddress(ip).getHostAddress();
        } catch (UnknownHostException e) {
            return "";
        }
    }

    /** A server's name: letters, digits, dots and dashes. */
    static boolean isHostName(String s) {
        if (s.isEmpty() || s.length() > 253 || s.startsWith(".") || s.startsWith("-")) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (!(c >= 'a' && c <= 'z') && !(c >= 'A' && c <= 'Z') && !(c >= '0' && c <= '9')
                    && c != '.' && c != '-') {
                return false;
            }
        }
        return true;
    }

    /** host:port or [address]:port as {host, port}, or null. */
    static Object[] endpoint(String value) {
        final String host;
        final String port;
        if (value.startsWith("[")) {
            final int close = value.indexOf(']');
            if (close < 0 || close + 1 >= value.length() || value.charAt(close + 1) != ':') {
                return null;
            }
            host = value.substring(1, close);
            if (parseIp(host) == null || host.indexOf(':') < 0) {
                return null;
            }
            port = value.substring(close + 2);
        } else {
            final int colon = value.lastIndexOf(':');
            if (colon <= 0) {
                return null;
            }
            host = value.substring(0, colon);
            if (host.indexOf(':') >= 0 || !(parseIp(host) != null || isHostName(host))) {
                return null;
            }
            port = value.substring(colon + 1);
        }
        try {
            final int number = Integer.parseInt(port);
            if (number < 1 || number > 65535) {
                return null;
            }
            return new Object[] {host, number};
        } catch (NumberFormatException e) {
            return null;
        }
    }

    // The parser's pieces

    private static boolean isIgnored(String key) {
        for (String ignored : IGNORED) {
            if (ignored.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> list(String value) {
        final List<String> items = new ArrayList<>();
        for (String item : value.split(",")) {
            final String trimmed = item.trim();
            if (!trimmed.isEmpty()) {
                items.add(trimmed);
            }
        }
        return items;
    }

    private static String key(int n, String shown, String value) throws BadConf {
        final String hex = hexFromBase64(value);
        if (hex == null) {
            throw bad(n, shown, "not a valid key, which is 44 characters of base64");
        }
        return hex;
    }

    private static String cidr(int n, String shown, String item, boolean address)
            throws BadConf {
        final String cidr = cidr(item);
        if (cidr == null) {
            throw bad(n, shown, address ? "not an address such as 10.8.0.2/32"
                    : "not a network such as 10.8.0.0/24");
        }
        return cidr;
    }

    private static int number(int n, String shown, String value, int min, int max)
            throws BadConf {
        try {
            final int number = Integer.parseInt(value);
            if (number >= min && number <= max) {
                return number;
            }
        } catch (NumberFormatException e) {
            // said below
        }
        throw bad(n, shown, "expected a number from " + min + " to " + max);
    }

    private static BadConf bad(int line, String setting, String what) {
        // The setting's name only when it is one: what is left of a = may be a pasted key
        final boolean named = setting != null && setting.matches("[A-Za-z]{1,24}");
        return new BadConf("Line " + line + (named ? " (" + setting + ")" : "") + ": " + what);
    }

    // The self-check

    /**
     * Runs the parser and writer on known cases. Returns "ok", or what failed first. Started
     * with HomeActivity's self_check extra, see HomeActivity.
     */
    static String selfCheck() {
        final String zero = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        final String count = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8=";
        final String countHex = "000102030405060708090a0b0c0d0e0f"
                + "101112131415161718191a1b1c1d1e1f";
        if (!countHex.equals(hexFromBase64(count))) {
            return "base64 to hex";
        }
        if (!count.equals(base64FromHex(countHex))) {
            return "hex to base64";
        }
        if (hexFromBase64("AAAA") != null || hexFromBase64("not base64!") != null) {
            return "short or bad keys taken";
        }
        final String conf = "# a comment\n"
                + "[Interface]\n"
                + "PrivateKey = " + count + "\n"
                + "Address = 10.8.0.7/24, fd00::7\n"
                + "DNS = 10.8.0.1, home.lan\n"
                + "ListenPort = 51820\n"
                + "\n"
                + "[Peer]\r\n"
                + "PublicKey = " + zero + "  # the server\n"
                + "PresharedKey = " + count + "\n"
                + "AllowedIPs = 0.0.0.0/0, ::/0\n"
                + "Endpoint = vpn.example.net:51820\n"
                + "PersistentKeepalive = 25\n";
        final Net net;
        try {
            net = parse(conf);
        } catch (BadConf e) {
            return "good settings refused: " + e.getMessage();
        }
        if (!net.privateKey.equals(countHex) || !net.presharedKey.equals(countHex)
                || !net.peerKey.equals(hexFromBase64(zero))) {
            return "keys";
        }
        if (!net.addresses.get(0).equals("10.8.0.7/24")
                || !net.addresses.get(1).equals("fd00::7/128")) {
            return "addresses";
        }
        if (net.dns.size() != 2 || !net.host.equals("vpn.example.net") || net.port != 51820
                || net.keepalive != 25 || net.mtu != 1280 || !net.everything) {
            return "settings";
        }
        try {
            final Net again = parse(write(net, false));
            if (!again.privateKey.equals(net.privateKey) || !again.host.equals(net.host)
                    || !again.allowedIps.equals(net.allowedIps)) {
                return "written settings read back differently";
            }
        } catch (BadConf e) {
            return "written settings refused: " + e.getMessage();
        }
        if (write(net, true).contains(count)) {
            return "a hidden key was written";
        }
        if (!"10.8.0.0/24".equals(networkOf("10.8.0.7/24"))) {
            return "network of an address";
        }
        final Object[] v6 = endpoint("[fd00::1]:51820");
        if (v6 == null || !"fd00::1".equals(v6[0]) || endpoint("fd00::1:51820") != null
                || endpoint("host:0") != null || endpoint("host") != null) {
            return "endpoints";
        }
        final String[] good = {"1.2.3.4", "::1", "::", "fd00::7", "1:2:3:4:5:6:7:8",
            "::ffff:1.2.3.4"};
        for (String ip : good) {
            if (parseIp(ip) == null) {
                return "address refused: " + ip;
            }
        }
        final String[] badIps = {"1.2.3", "256.1.1.1", "1:::2", "1::2::3", "1:2:3:4:5:6:7:8:9",
            "12345::", "1.2.3.4::", "g::1"};
        for (String ip : badIps) {
            if (parseIp(ip) != null) {
                return "address taken: " + ip;
            }
        }
        final String[][] broken = {
            {"[Interface]\nPrivateKey " + count + "\n", "Line 2"},
            {"[Interface]\nPrivateKey = abc\n", "Line 2 (PrivateKey)"},
            {"[Interface]\nPrivateKey = " + count + "\nAddress = 10.8.0.7\n[Peer]\n"
                + "PublicKey = " + zero + "\nEndpoint = h:1\nAllowedIPs = 0.0.0.0/0\n[Peer]\n",
                "Line 8"},
            {"[Interface]\nPrivateKey = " + count + "\nAddress = 10.8.0.7\n[Peer]\n"
                + "PublicKey = " + zero + "\nEndpoint = h:99999\n", "Line 6 (Endpoint)"},
            {"[Interface]\nPrivateKey = " + count + "\nAddress = 10.8.0.300\n",
                "Line 3 (Address)"},
            {"[Interface]\nMTU = 12\n", "Line 2 (MTU)"},
            {"[Interfaces]\n", "Line 1"},
            {"hello\n", "Line 1"},
        };
        for (String[] c : broken) {
            try {
                parse(c[0]);
                return "bad settings taken, expected " + c[1];
            } catch (BadConf e) {
                if (!e.getMessage().startsWith(c[1])) {
                    return "expected " + c[1] + ", said " + e.getMessage();
                }
                if (e.getMessage().contains(count)) {
                    return "a key in a message";
                }
            }
        }
        return "ok";
    }
}
