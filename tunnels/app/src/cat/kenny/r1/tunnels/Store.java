// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The saved networks, a JSON file each in the app's own files, which are in the
 * credential-encrypted storage: there only once the r1 has been unlocked. Which one is on is
 * kept beside them. Nothing here logs what is in a file.
 */
final class Store {
    static final String TAG = "R1Tunnels";
    private static final String DIR = "networks";
    private static final String ACTIVE = "active";

    private Store() {
    }

    private static File dir(Context context) {
        final File dir = new File(context.getFilesDir(), DIR);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            Log.w(TAG, "No directory for the networks");
        }
        return dir;
    }

    /** An id is hex, and so safe as a file's name */
    private static boolean isId(String id) {
        return id != null && id.matches("[0-9a-f]{8,32}");
    }

    static String newId() {
        final byte[] bytes = new byte[8];
        new SecureRandom().nextBytes(bytes);
        final StringBuilder hex = new StringBuilder();
        for (byte b : bytes) {
            hex.append(String.format("%02x", b & 255));
        }
        return hex.toString();
    }

    static List<Net> list(Context context) {
        final List<Net> nets = new ArrayList<>();
        final File[] files = dir(context).listFiles();
        if (files != null) {
            for (File file : files) {
                final String name = file.getName();
                if (name.endsWith(".json")) {
                    final Net net = load(context, name.substring(0, name.length() - 5));
                    if (net != null) {
                        nets.add(net);
                    }
                }
            }
        }
        Collections.sort(nets, (a, b) -> Long.compare(a.created, b.created));
        return nets;
    }

    static Net load(Context context, String id) {
        if (!isId(id)) {
            return null;
        }
        final File file = new File(dir(context), id + ".json");
        if (!file.isFile()) {
            return null;
        }
        try {
            final Net net = Net.fromJson(new JSONObject(read(file, 1 << 20)));
            net.id = id;
            return net;
        } catch (IOException | JSONException e) {
            Log.w(TAG, "A network's file could not be read: " + e.getClass().getSimpleName());
            return null;
        }
    }

    /** Written whole beside it first, then moved over it: never half a file. */
    static synchronized boolean save(Context context, Net net) {
        if (!isId(net.id)) {
            net.id = newId();
        }
        if (net.created == 0) {
            net.created = System.currentTimeMillis();
        }
        final File dir = dir(context);
        final File file = new File(dir, net.id + ".json");
        final File temp = new File(dir, net.id + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(net.toJson().toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        } catch (IOException | JSONException e) {
            Log.w(TAG, "A network could not be saved: " + e.getClass().getSimpleName());
            temp.delete();
            return false;
        }
        if (!temp.renameTo(file)) {
            temp.delete();
            return false;
        }
        return true;
    }

    static void delete(Context context, String id) {
        if (isId(id)) {
            new File(dir(context), id + ".json").delete();
        }
        if (id != null && id.equals(active(context))) {
            setActive(context, null);
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("state", Context.MODE_PRIVATE);
    }

    /** The network that is to be on, which the always-on VPN starts after a restart too. */
    static String active(Context context) {
        return prefs(context).getString(ACTIVE, null);
    }

    static void setActive(Context context, String id) {
        prefs(context).edit().putString(ACTIVE, id).apply();
    }

    static String read(File file, int max) throws IOException {
        try (InputStream in = new FileInputStream(file)) {
            return readAll(in, max);
        }
    }

    static String readAll(InputStream in, int max) throws IOException {
        final byte[] buffer = new byte[8192];
        final java.io.ByteArrayOutputStream all = new java.io.ByteArrayOutputStream();
        int n;
        while ((n = in.read(buffer)) > 0) {
            all.write(buffer, 0, n);
            if (all.size() > max) {
                throw new IOException("Too long");
            }
        }
        return new String(all.toByteArray(), StandardCharsets.UTF_8);
    }
}
