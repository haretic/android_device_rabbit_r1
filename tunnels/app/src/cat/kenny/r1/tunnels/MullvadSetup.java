// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** A registration owns its remote device until the profile is durably saved.
 * The small intent journal permits bounded reconciliation after a lost POST response or
 * process death. It is in the app's credential-encrypted files and contains no private key.
 * All methods doing I/O run on a worker. Failed remote cleanup retains the journal. */
final class MullvadSetup {
    interface KeyMaker { String[] make() throws IOException; }
    private static final String PENDING = "mullvad-pending.json";
    final Net net;
    final long paidUntil;
    final int deviceNumber;
    private final Context context;
    private final Mullvad client;
    private boolean done;

    private MullvadSetup(Context context, Mullvad client, Net net, long paid, int count) {
        this.context = context.getApplicationContext(); this.client = client;
        this.net = net; paidUntil = paid; deviceNumber = count;
    }
    static synchronized MullvadSetup begin(Context context, Mullvad client, String[] key,
            KeyMaker keyMaker) throws IOException {
        client.login();
        final long paid = client.paidUntil();
        recover(context, client);
        if (paid <= System.currentTimeMillis()) throw new Mullvad.Failure(0, "ACCOUNT_EXPIRED");
        JSONArray devices = client.devices();
        if (devices.length() >= Mullvad.MAX_DEVICES) {
            throw new Mullvad.Failure(0, "MAX_DEVICES_REACHED");
        }
        String[] pair = key == null ? keyMaker.make() : key;
        boolean rotated = false;
        if (hasKey(devices, publicKey(pair))) {
            pair = keyMaker.make(); rotated = true;
            if (hasKey(devices, publicKey(pair))) throw Mullvad.badReply();
        }
        for (;;) {
            final String pubkey = publicKey(pair);
            final JSONObject pending = new JSONObject();
            try { pending.put("account", client.account); pending.put("pubkey", pubkey); }
            catch (JSONException e) { throw storage(); }
            write(context, pending); // Durable intent BEFORE POST; no orphan on a lost reply.
            try {
                final JSONObject device = client.addDevice(pubkey);
                final String id = Mullvad.string(device, "id");
                if (!Mullvad.validId(id)) throw Mullvad.badReply();
                try { pending.put("id", id); }
                catch (JSONException e) { throw storage(); }
                write(context, pending);
                final Net net = fromDevice(client.account, device, pair);
                return new MullvadSetup(context, client, net, paid, devices.length() + 1);
            } catch (IOException failure) {
                if (failure instanceof Mullvad.Failure) {
                    final Mullvad.Failure f = (Mullvad.Failure) failure;
                    // A definite rejection did not create a device. Never delete a key
                    // somebody else already registered during the preflight/POST race.
                    if (f.http >= 400 && f.http < 500) {
                        clear(context);
                        if ("PUBKEY_IN_USE".equals(f.code) && !rotated) {
                            pair = keyMaker.make(); rotated = true;
                            devices = client.devices();
                            if (hasKey(devices, publicKey(pair))) throw Mullvad.badReply();
                            continue;
                        }
                        throw failure;
                    }
                }
                try { recover(context, client); }
                catch (IOException cleanup) { /* Intent retained for the next attempt. */ }
                throw failure;
            }
        }
    }
    synchronized void cancel() throws IOException {
        synchronized (MullvadSetup.class) {
            if (done) return;
            if (ownsPending()) recover(context, client);
            done = true;
        }
    }
    synchronized void save() throws IOException {
        synchronized (MullvadSetup.class) {
            if (done || !ownsPending()) throw storage();
            if (!Store.save(context, net)) {
                // Store.save returned false: this operation definitely did not commit.
                try {
                    client.removeDevice(net.deviceId);
                    clear(context);
                    done = true;
                } catch (IOException ignored) { /* Retry cleanup next setup. */ }
                throw storage();
            }
            // A process death here is safe: recover checks saved profiles before deletion.
            done = true;
            try { clear(context); } catch (IOException ignored) { /* Saved profile protects it. */ }
        }
    }
    private boolean ownsPending() throws IOException {
        final JSONObject pending = read(context);
        return pending != null && client.account.equals(pending.optString("account"))
                && net.deviceId.equals(pending.optString("id"))
                && WgConf.base64FromHex(net.publicKey).equals(pending.optString("pubkey"));
    }
    static synchronized void recover(Context context, Mullvad current) throws IOException {
        final JSONObject pending = read(context);
        if (pending == null) return;
        final String account = Mullvad.string(pending, "account");
        final String key = Mullvad.string(pending, "pubkey");
        if (!account.matches("[0-9]{16}") || WgConf.hexFromBase64(key) == null) throw storage();
        // Store.list deliberately hides read errors for the UI. Cleanup must fail
        // closed instead: an unreadable saved profile may own this device.
        final File profiles = new File(context.getFilesDir(), "networks");
        if (profiles.exists()) {
            final File[] files = profiles.listFiles();
            if (files == null) throw storage();
            for (File file : files) {
                if (!file.getName().endsWith(".json")) continue;
                final Net saved;
                try { saved = Net.fromJson(Mullvad.object(Store.read(file, 1 << 20))); }
                catch (IOException e) { throw storage(); }
                if (saved.isMullvad() && account.equals(saved.account)
                        && key.equals(WgConf.base64FromHex(saved.publicKey))
                        && (pending.optString("id", "").isEmpty()
                            || saved.deviceId.equals(pending.optString("id")))) {
                    clear(context); return;
                }
            }
        }
        final Mullvad client = account.equals(current.account) ? current : new Mullvad(account);
        String id = pending.optString("id", "");
        if (id.isEmpty()) {
            final JSONArray devices = client.devices();
            for (int i = 0; i < devices.length(); i++) {
                final JSONObject device = devices.optJSONObject(i);
                if (key.equals(device.optString("pubkey"))) {
                    id = Mullvad.string(device, "id"); break;
                }
            }
        }
        if (!id.isEmpty()) client.removeDevice(id);
        clear(context);
    }
    private static boolean hasKey(JSONArray devices, String key) {
        for (int i = 0; i < devices.length(); i++) {
            if (key.equals(devices.optJSONObject(i).optString("pubkey"))) return true;
        }
        return false;
    }
    private static String publicKey(String[] pair) throws IOException {
        if (pair == null || pair.length != 2 || !WgConf.isKeyHex(pair[0])
                || !WgConf.isKeyHex(pair[1])) {
            throw new Mullvad.Failure(0, "", Engine.Error.INVALID_CONFIG);
        }
        return WgConf.base64FromHex(pair[1]);
    }
    static Net fromDevice(String account, JSONObject device, String[] pair) throws IOException {
        final Net net = new Net();
        net.kind = Net.MULLVAD; net.name = "Mullvad"; net.account = account;
        net.deviceId = Mullvad.string(device, "id");
        if (!Mullvad.validId(net.deviceId)
                || !publicKey(pair).equals(Mullvad.string(device, "pubkey"))) throw Mullvad.badReply();
        net.privateKey = pair[0]; net.publicKey = pair[1];
        final String v4 = WgConf.cidr(Mullvad.string(device, "ipv4_address"));
        final String v6 = WgConf.cidr(Mullvad.string(device, "ipv6_address"));
        if (v4 == null || v6 == null || v4.indexOf(':') >= 0 || v6.indexOf(':') < 0) {
            throw Mullvad.badReply();
        }
        net.addresses.add(v4); net.addresses.add(v6);
        net.dns.add("10.64.0.1");
        net.allowedIps.add("0.0.0.0/0"); net.allowedIps.add("::/0");
        net.everything = true; net.mtu = 1280; net.location = Net.AUTO;
        return net;
    }
    private static JSONObject read(Context context) throws IOException {
        final File file = new File(context.getFilesDir(), PENDING);
        if (!file.exists()) return null;
        try { return Mullvad.object(Store.read(file, 4096)); }
        catch (IOException e) { throw storage(); }
    }
    private static void write(Context context, JSONObject pending) throws IOException {
        final File file = new File(context.getFilesDir(), PENDING);
        final File temp = new File(context.getFilesDir(), PENDING + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(pending.toString().getBytes(StandardCharsets.UTF_8));
            out.flush(); out.getFD().sync();
        } catch (IOException e) { temp.delete(); throw storage(); }
        if (!temp.renameTo(file)) { temp.delete(); throw storage(); }
    }
    private static void clear(Context context) throws IOException {
        final File file = new File(context.getFilesDir(), PENDING);
        if (file.exists() && !file.delete()) throw storage();
    }
    private static Mullvad.Failure storage() { return new Mullvad.Failure(0, "", Engine.Error.STORAGE); }
}
