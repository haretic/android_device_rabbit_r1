// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Mullvad's servers, from its list, kept for a day. "Automatic" is a server picked at random
 * by Mullvad's own weights among the active ones in the country of the last one used, or
 * among all of them the first time: without the r1's location, and without a way to time
 * the servers over UDP, that is the honest choice.
 */
final class Relays {
    private static final String FILE = "relays.json";
    private static final long KEEP_MS = 24L * 60 * 60 * 1000;

    static final class Relay {
        String hostname;
        /** Mullvad's location code, country and city: de-fra */
        String location;
        String ip;
        String publicKeyHex;
        int weight;
        int port;
    }

    static final class Country {
        String code;
        String name;
        final Set<String> cities = new TreeSet<>((a, b) -> {
            int order = a.compareToIgnoreCase(b);
            return order != 0 ? order : a.compareTo(b);
        });
        int servers;
    }

    final List<Relay> relays = new ArrayList<>();
    /** Location code to {country, city} */
    final Map<String, String[]> locations = new HashMap<>();
    long fetched;

    /** The list, from the day's copy or else fetched; a copy older than a day if Mullvad
     * cannot be reached. Off the main thread. */
    static synchronized Relays load(Context context) throws IOException {
        final File file = new File(context.getFilesDir(), FILE);
        final long age = System.currentTimeMillis() - file.lastModified();
        if (file.isFile() && age >= 0 && age < KEEP_MS) {
            try {
                return parse(Store.read(file, 32 << 20), file.lastModified());
            } catch (IOException e) {
                Log.w(Store.TAG, "The kept server list could not be read");
            }
        }
        try {
            final String text = Mullvad.relays();
            final Relays relays = parse(text, System.currentTimeMillis());
            final File temp = new File(context.getFilesDir(), FILE + ".tmp");
            try {
                try (FileOutputStream out = new FileOutputStream(temp)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
                if (!temp.renameTo(file)) temp.delete();
            } catch (IOException cacheFailure) {
                temp.delete();
                Log.w(Store.TAG, "The server list could not be cached");
            }
            return relays;
        } catch (IOException e) {
            if (file.isFile()) {
                return parse(Store.read(file, 32 << 20), file.lastModified());
            }
            throw e;
        }
    }

    static Relays parse(String text, long fetched) throws IOException {
        final Relays out = new Relays();
        out.fetched = fetched;
        final JSONObject root = Mullvad.object(text);
        final JSONObject locations = root.optJSONObject("locations");
        if (locations != null) {
            final Iterator<String> codes = locations.keys();
            while (codes.hasNext()) {
                final String code = codes.next();
                final JSONObject location = locations.optJSONObject(code);
                if (location != null) {
                    out.locations.put(code, new String[] {
                        location.optString("country", code),
                        location.optString("city", code)});
                }
            }
        }
        final JSONObject wireguard = root.optJSONObject("wireguard");
        final JSONArray relays = wireguard != null ? wireguard.optJSONArray("relays") : null;
        if (locations == null || relays == null) {
            throw new IOException("No servers in the list");
        }
        final JSONArray ranges = wireguard.optJSONArray("port_ranges");
        int port = 0;
        if (ranges != null) {
            for (int i = 0; i < ranges.length(); i++) {
                final JSONArray range = ranges.optJSONArray(i);
                if (range == null || range.length() != 2) continue;
                final int low = range.optInt(0, 0), high = range.optInt(1, 0);
                if (low < 1 || high > 65535 || high < low) continue;
                if (port == 0) port = low;
                if (low <= 51820 && high >= 51820) { port = 51820; break; }
            }
        }
        if (port == 0) throw new IOException("No WireGuard ports");
        for (int i = 0; i < relays.length(); i++) {
            final JSONObject r = relays.optJSONObject(i);
            if (r == null || !r.optBoolean("active", false)) {
                continue;
            }
            final Relay relay = new Relay();
            relay.hostname = r.optString("hostname", "");
            relay.location = r.optString("location", "");
            relay.ip = r.optString("ipv4_addr_in", "");
            relay.publicKeyHex = WgConf.hexFromBase64(r.optString("public_key", ""));
            relay.weight = r.optInt("weight", 0);
            relay.port = port;
            if (WgConf.isHostName(relay.hostname) && out.locations.containsKey(relay.location)
                    && relay.weight > 0
                    && WgConf.parseIp(relay.ip) != null && relay.ip.indexOf(':') < 0
                    && relay.publicKeyHex != null) {
                out.relays.add(relay);
            }
        }
        if (out.relays.isEmpty()) throw new IOException("No usable WireGuard servers");
        return out;
    }

    static String countryOf(String location) {
        final int dash = location.indexOf('-');
        return dash > 0 ? location.substring(0, dash) : location;
    }

    /** The countries with servers, by name, each with its cities and number of servers. */
    List<Country> countries() {
        final Map<String, Country> byCode = new LinkedHashMap<>();
        for (Relay relay : relays) {
            final String code = countryOf(relay.location);
            Country country = byCode.get(code);
            if (country == null) {
                country = new Country();
                country.code = code;
                final String[] names = locations.get(relay.location);
                country.name = names != null ? names[0] : code.toUpperCase(java.util.Locale.ROOT);
                byCode.put(code, country);
            }
            country.servers++;
            final String[] names = locations.get(relay.location);
            if (names != null) {
                country.cities.add(names[1]);
            }
        }
        final List<Country> list = new ArrayList<>(byCode.values());
        Collections.sort(list, (a, b) -> {
            int order = a.name.compareToIgnoreCase(b.name);
            return order != 0 ? order : a.code.compareTo(b.code);
        });
        return list;
    }

    Country country(String code) {
        for (Country country : countries()) {
            if (country.code.equals(code)) {
                return country;
            }
        }
        return null;
    }

    Relay byHostname(String hostname) {
        for (Relay relay : relays) {
            if (relay.hostname.equals(hostname)) {
                return relay;
            }
        }
        return null;
    }

    /** City and country of a server: Frankfurt, Germany */
    String placeOf(Relay relay) {
        final String[] names = locations.get(relay.location);
        return names != null ? names[1] + ", " + names[0] : relay.location;
    }

    /**
     * A server for a choice: a country code, a city's location code, or automatic, which
     * stays in the country of the last server used.
     */
    Relay pick(String location, String lastRelay) {
        String country = null;
        String city = null;
        final boolean auto = location == null || Net.AUTO.equals(location);
        if (auto) {
            final Relay last = lastRelay != null ? byHostname(lastRelay) : null;
            if (last != null) {
                country = countryOf(last.location);
            }
        } else if (location.indexOf('-') > 0) {
            city = location;
        } else {
            country = location;
        }
        List<Relay> pool = new ArrayList<>();
        for (Relay relay : relays) {
            if (city != null ? relay.location.equals(city)
                    : country == null || countryOf(relay.location).equals(country)) {
                pool.add(relay);
            }
        }
        if (pool.isEmpty() && auto) {
            pool = relays;
        }
        if (pool.isEmpty()) {
            return null;
        }
        long total = 0;
        for (Relay relay : pool) {
            total += relay.weight;
        }
        long at = (long) (new SecureRandom().nextDouble() * total);
        for (Relay relay : pool) {
            at -= relay.weight;
            if (at < 0) {
                return relay;
            }
        }
        return pool.get(pool.size() - 1);
    }
}
