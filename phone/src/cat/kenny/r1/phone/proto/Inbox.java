// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone.proto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Confined to the caller's thread; snapshots never expose mutable model state. */
public final class Inbox {
    public static final int MAX_NOTIFICATIONS = 64;
    public static final int MAX_CACHED_APPS = 128;
    private final LinkedHashMap<Long, Entry> entries = new LinkedHashMap<>();
    private final LinkedHashMap<String, String> apps = new LinkedHashMap<>();
    private final Ancs.Reassembler reassembler = new Ancs.Reassembler();
    private Flight flight;
    private boolean needsResync;

    public record Notice(long uid, String appId, String appName, String title, String subtitle,
            String message, LocalDateTime date, int category, int flags,
            String positiveLabel, String negativeLabel) {
        public boolean preExisting() { return (flags & Ancs.PRE_EXISTING) != 0; }
        public boolean silent() { return (flags & Ancs.SILENT) != 0; }
        public boolean important() { return (flags & Ancs.IMPORTANT) != 0; }
        public boolean positiveAction() { return (flags & Ancs.POSITIVE_ACTION) != 0; }
        public boolean negativeAction() { return (flags & Ancs.NEGATIVE_ACTION) != 0; }
    }

    public void onEvent(Ancs.Event event) {
        if (event == null) {
            return;
        }
        if (event.eventId() == Ancs.REMOVED) {
            entries.remove(event.uid());
            return;
        }
        Entry entry = entries.get(event.uid());
        if (entry == null) {
            entry = new Entry(event);
            entries.put(event.uid(), entry);
            if (entries.size() > MAX_NOTIFICATIONS) {
                entries.remove(entries.keySet().iterator().next());
            }
        } else {
            entry.event = event;
            entry.revision++;
            entry.wanted = true;
        }
    }

    /** Poll with monotonic milliseconds, including during silence; timeout is per request. */
    public byte[] nextCommand(long now, long timeoutMillis) {
        if (timeoutMillis <= 0) {
            throw new IllegalArgumentException("Timeout must be positive");
        }
        expire(now);
        if (flight != null) {
            return null;
        }
        List<Entry> newest = new ArrayList<>(entries.values());
        Collections.reverse(newest);
        for (Entry entry : newest) {
            if (entry.wanted) {
                byte[] command = Ancs.notificationAttributes(entry.event.uid(), 256, 256, 2048);
                entry.wanted = false;
                flight = new Flight(entry, entry.revision, null, now, timeoutMillis);
                reassembler.expect(command, now, timeoutMillis);
                return command;
            }
        }
        for (Entry entry : newest) {
            if (validApp(entry.appId) && !apps.containsKey(entry.appId)) {
                byte[] command = Ancs.appAttributes(entry.appId);
                flight = new Flight(null, 0, entry.appId, now, timeoutMillis);
                reassembler.expect(command, now, timeoutMillis);
                return command;
            }
        }
        return null;
    }

    public void onData(byte[] packet, long now) {
        expire(now);
        for (Ancs.Response response : reassembler.feed(packet, now)) {
            onResponse(response, now);
        }
        if (flight != null && !reassembler.pending()) {
            abandon();
        }
    }

    /** Also accepts responses parsed by a separate request-directed Reassembler. */
    public void onResponse(Ancs.Response response, long now) {
        expire(now);
        if (response == null || flight == null) {
            return;
        }
        if (flight.entry == null) {
            if (response.commandId() != 1 || !flight.appId.equals(response.appId())) {
                return;
            }
            cache(flight.appId, response.attributes().getOrDefault(Ancs.DISPLAY_NAME, ""));
        } else {
            Entry entry = flight.entry;
            if (response.commandId() != 0 || response.uid() != entry.event.uid()) {
                return;
            }
            if (entries.get(response.uid()) == entry && entry.revision == flight.revision) {
                Map<Integer, String> attributes = response.attributes();
                entry.appId = attributes.getOrDefault(Ancs.APP_IDENTIFIER, "");
                entry.title = attributes.getOrDefault(Ancs.TITLE, "");
                entry.subtitle = attributes.getOrDefault(Ancs.SUBTITLE, "");
                entry.message = attributes.getOrDefault(Ancs.MESSAGE, "");
                entry.date = Ancs.parseDate(attributes.get(Ancs.DATE));
                entry.positive = attributes.getOrDefault(Ancs.POSITIVE_LABEL, "");
                entry.negative = attributes.getOrDefault(Ancs.NEGATIVE_LABEL, "");
            }
        }
        flight = null;
        reassembler.reset();
    }

    /** Call on a Control Point write failure; no Data Source answer will follow that error. */
    public void writeFailed() {
        if (flight != null) {
            abandon();
        }
    }

    private void expire(long now) {
        if (flight != null && (now < flight.started || now - flight.started < 0
                || now - flight.started >= flight.timeout)) {
            abandon();
        }
    }

    private void abandon() {
        if (flight.appId != null) {
            // Cache a failed lookup too, so an absent app cannot stall the queue repeatedly.
            cache(flight.appId, "");
        }
        flight = null;
        reassembler.reset();
        needsResync = true;
    }

    private void cache(String app, String name) {
        apps.put(app, name);
        if (apps.size() > MAX_CACHED_APPS) {
            String discard = null;
            for (String candidate : apps.keySet()) {
                boolean used = false;
                for (Entry entry : entries.values()) {
                    used |= candidate.equals(entry.appId);
                }
                if (!used) {
                    discard = candidate;
                    break;
                }
            }
            if (discard != null) {
                apps.remove(discard);
            }
        }
    }

    private static boolean validApp(String app) {
        if (app.isEmpty() || app.length() > Ancs.MAX_APP_ID_BYTES || app.indexOf('\0') >= 0) {
            return false;
        }
        return app.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= Ancs.MAX_APP_ID_BYTES;
    }

    /** Taken off the list here; the phone is told separately, where it lets itself be told. */
    public void dismiss(long uid) {
        entries.remove(uid);
    }

    public List<Notice> notifications() {
        List<Notice> snapshot = new ArrayList<>();
        for (Entry entry : entries.values()) {
            snapshot.add(notice(entry));
        }
        Collections.reverse(snapshot);
        return List.copyOf(snapshot);
    }

    public Notice incomingCall() {
        Notice call = null;
        for (Entry entry : entries.values()) {
            if (entry.event.category() == Ancs.INCOMING_CALL) {
                call = notice(entry);
            }
        }
        return call;
    }

    private Notice notice(Entry entry) {
        Ancs.Event event = entry.event;
        return new Notice(event.uid(), entry.appId, apps.getOrDefault(entry.appId, ""),
                entry.title, entry.subtitle, entry.message, entry.date, event.category(),
                event.flags(), entry.positive, entry.negative);
    }

    public int size() { return entries.size(); }
    public int cachedAppCount() { return apps.size(); }
    public int bufferedBytes() { return reassembler.bufferedBytes(); }
    public boolean requestInFlight() { return flight != null; }
    public boolean needsResync() { return needsResync; }
    public void acknowledgeResync() { needsResync = false; }

    /** ANCS identifiers and app attributes are only meaningful within one connection session. */
    public void clear() {
        entries.clear();
        apps.clear();
        flight = null;
        reassembler.reset();
        needsResync = false;
    }

    private static final class Entry {
        Ancs.Event event;
        long revision;
        boolean wanted = true;
        String appId = "", title = "", subtitle = "", message = "", positive = "", negative = "";
        LocalDateTime date;
        Entry(Ancs.Event event) { this.event = event; }
    }

    private record Flight(Entry entry, long revision, String appId, long started, long timeout) { }
}
