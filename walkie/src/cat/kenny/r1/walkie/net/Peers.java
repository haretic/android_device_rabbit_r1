// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded presence table; its version tracks visible changes, not heartbeats. */
public final class Peers {
    private static final class Peer {
        long id;
        int channel;
        String name;
        long heard;
    }

    private final Peer[] entries = new Peer[64];
    private long version;

    public synchronized void heard(long id, int channel, String name, long nowMillis) {
        if (channel < 1 || channel > 8) return;
        int index = index(id);
        Peer peer = index < 0 ? null : entries[index];
        if ((peer == null || peer.channel != channel) && count(channel) == 16) {
            if (peer != null) {
                entries[index] = null;
                version++;
            }
            return;
        }
        if (peer == null) {
            for (int i = 0; i < entries.length; i++) {
                if (entries[i] != null) continue;
                peer = new Peer();
                peer.id = id;
                peer.channel = channel;
                peer.name = clean(name);
                entries[i] = peer;
                version++;
                break;
            }
            if (peer == null) return;
        } else {
            String nextName = name == null ? peer.name : clean(name);
            if (peer.channel != channel || !peer.name.equals(nextName)) {
                peer.channel = channel;
                peer.name = nextName;
                version++;
            }
        }
        peer.heard = nowMillis;
    }

    public synchronized void left(long id) {
        int index = index(id);
        if (index >= 0) {
            entries[index] = null;
            version++;
        }
    }

    public synchronized void expire(long nowMillis) {
        for (int i = 0; i < entries.length; i++) {
            Peer peer = entries[i];
            if (peer != null && nowMillis - peer.heard >= 10000) {
                entries[i] = null;
                version++;
            }
        }
    }

    public synchronized int count(int channel) {
        int count = 0;
        for (Peer peer : entries) if (peer != null && peer.channel == channel) count++;
        return count;
    }

    public synchronized List<String> names(int channel) {
        List<String> names = new ArrayList<>(16);
        for (Peer peer : entries) {
            if (peer != null && peer.channel == channel) names.add(peer.name);
        }
        Collections.sort(names);
        return names;
    }

    public synchronized boolean changedSince(long version) {
        return this.version != version;
    }

    public synchronized long version() {
        return version;
    }

    synchronized String name(long id) {
        int index = index(id);
        return index < 0 ? "r1" : entries[index].name;
    }

    private int index(long id) {
        for (int i = 0; i < entries.length; i++) {
            if (entries[i] != null && entries[i].id == id) return i;
        }
        return -1;
    }

    static String clean(String name) {
        if (name == null || name.isEmpty()) return "r1";
        StringBuilder out = new StringBuilder(24);
        for (int i = 0, chars = 0; i < name.length() && chars < 24;) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isISOControl(cp) || Character.getType(cp) == Character.FORMAT
                    || (cp >= 0xd800 && cp <= 0xdfff)) continue;
            out.appendCodePoint(cp);
            chars++;
        }
        return out.length() == 0 ? "r1" : out.toString();
    }
}
