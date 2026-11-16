// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone.proto;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** ANCS's little-endian commands and request-directed Data Source parsing. */
public final class Ancs {
    public static final int ADDED = 0, MODIFIED = 1, REMOVED = 2;
    public static final int SILENT = 1, IMPORTANT = 2, PRE_EXISTING = 4;
    public static final int POSITIVE_ACTION = 8, NEGATIVE_ACTION = 16;
    public static final int OTHER = 0, INCOMING_CALL = 1, MISSED_CALL = 2, VOICEMAIL = 3;
    public static final int SOCIAL = 4, SCHEDULE = 5, EMAIL = 6, NEWS = 7;
    public static final int HEALTH_AND_FITNESS = 8, BUSINESS_AND_FINANCE = 9;
    public static final int LOCATION = 10, ENTERTAINMENT = 11;
    public static final int APP_IDENTIFIER = 0, TITLE = 1, SUBTITLE = 2, MESSAGE = 3;
    public static final int MESSAGE_SIZE = 4, DATE = 5, POSITIVE_LABEL = 6, NEGATIVE_LABEL = 7;
    public static final int DISPLAY_NAME = 0;
    public static final int ACTION_POSITIVE = 0, ACTION_NEGATIVE = 1;
    public static final int MAX_ATTRIBUTE_BYTES = 4096;
    public static final int MAX_APP_ID_BYTES = 255;
    public static final int MAX_RESPONSE_BYTES = 5 + MAX_APP_ID_BYTES + 8 * (3 + 4096);
    public static final int MAX_FRAGMENTS = MAX_RESPONSE_BYTES + 1;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("uuuuMMdd'T'HHmmss").withResolverStyle(ResolverStyle.STRICT);

    private Ancs() { }

    public record Event(int eventId, int flags, int category, int categoryCount, long uid) {
        public Event {
            if (eventId < ADDED || eventId > REMOVED || (flags & ~31) != 0
                    || category < 0 || category > ENTERTAINMENT
                    || categoryCount < 0 || categoryCount > 255 || !validUid(uid)) {
                throw new IllegalArgumentException("Invalid notification event");
            }
        }
        public boolean silent() { return (flags & SILENT) != 0; }
        public boolean important() { return (flags & IMPORTANT) != 0; }
        public boolean preExisting() { return (flags & PRE_EXISTING) != 0; }
        public boolean positiveAction() { return (flags & POSITIVE_ACTION) != 0; }
        public boolean negativeAction() { return (flags & NEGATIVE_ACTION) != 0; }
    }

    public static Event parseEvent(byte[] value) {
        if (value == null || value.length != 8 || u(value[0]) > REMOVED
                || (u(value[1]) & ~31) != 0 || u(value[2]) > ENTERTAINMENT) {
            return null;
        }
        return new Event(u(value[0]), u(value[1]), u(value[2]), u(value[3]), uid(value, 4));
    }

    /** Requests the seven display attributes; only title, subtitle and message have limits. */
    public static byte[] notificationAttributes(long uid, int title, int subtitle, int message) {
        checkUid(uid);
        checkLimit(title);
        checkLimit(subtitle);
        checkLimit(message);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);
        writeUid(out, uid);
        out.write(APP_IDENTIFIER);
        out.write(TITLE);
        word(out, title);
        out.write(SUBTITLE);
        word(out, subtitle);
        out.write(MESSAGE);
        word(out, message);
        out.write(DATE);
        out.write(POSITIVE_LABEL);
        out.write(NEGATIVE_LABEL);
        return out.toByteArray();
    }

    public static byte[] appAttributes(String appId) {
        byte[] name = appBytes(appId);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(1);
        out.writeBytes(name);
        out.write(0);
        out.write(DISPLAY_NAME);
        return out.toByteArray();
    }

    public static byte[] action(long uid, int action) {
        checkUid(uid);
        if (action != ACTION_POSITIVE && action != ACTION_NEGATIVE) {
            throw new IllegalArgumentException("Unknown notification action");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(2);
        writeUid(out, uid);
        out.write(action);
        return out.toByteArray();
    }

    public static LocalDateTime parseDate(String value) {
        if (value == null || value.length() != 15 || value.charAt(8) != 'T') {
            return null;
        }
        for (int i = 0; i < value.length(); i++) {
            if (i != 8 && (value.charAt(i) < '0' || value.charAt(i) > '9')) {
                return null;
            }
        }
        try {
            LocalDateTime date = LocalDateTime.parse(value, DATE_FORMAT);
            return date.getYear() == 0 ? null : date;
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    public record Response(int commandId, long uid, String appId, Map<Integer, String> attributes) {
        public Response {
            if (attributes == null || attributes.size() > 8
                    || commandId != 0 && commandId != 1) {
                throw new IllegalArgumentException("Invalid attribute response");
            }
            if (commandId == 0) {
                checkUid(uid);
                if (appId != null) {
                    throw new IllegalArgumentException("Notification response has an app header");
                }
            } else {
                appBytes(appId);
            }
            for (Map.Entry<Integer, String> entry : attributes.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null
                        || entry.getKey() < 0 || entry.getKey() > (commandId == 0 ? 7 : 0)
                        || entry.getValue().length() > MAX_ATTRIBUTE_BYTES
                        || entry.getValue().getBytes(StandardCharsets.UTF_8).length
                        > MAX_ATTRIBUTE_BYTES) {
                    throw new IllegalArgumentException("Invalid attribute");
                }
            }
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }

    /** One expected response, with no search for headers inside untrusted attribute strings. */
    public static final class Reassembler {
        private byte[] request;
        private final byte[] buffer = new byte[MAX_RESPONSE_BYTES];
        private int count;
        private int fragments;
        private int command;
        private long expectedUid;
        private String expectedApp;
        private int[] wanted;
        private int[] limits;
        private long started;
        private long timeout;
        private boolean timed;
        private boolean headerDone;
        private int position;
        private int attributeIndex;
        private final Map<Integer, String> received = new LinkedHashMap<>();

        /** Use immediately before writing a read command; replacing an expectation resets it. */
        public boolean expect(byte[] command) {
            reset();
            if (command == null || command.length == 0 || command.length > 258) {
                return false;
            }
            if (!describe(command)) {
                reset();
                return false;
            }
            request = command.clone();
            return true;
        }

        public boolean expect(byte[] command, long now, long timeoutMillis) {
            if (timeoutMillis <= 0 || !expect(command)) {
                reset();
                return false;
            }
            started = now;
            timeout = timeoutMillis;
            timed = true;
            return true;
        }

        private boolean describe(byte[] bytes) {
            command = u(bytes[0]);
            int at;
            if (command == 0 && bytes.length >= 6) {
                expectedUid = uid(bytes, 1);
                at = 5;
            } else if (command == 1) {
                int end = 1;
                while (end < bytes.length && bytes[end] != 0) {
                    end++;
                }
                if (end == bytes.length || end == 1 || end - 1 > MAX_APP_ID_BYTES) {
                    return false;
                }
                expectedApp = utf8(bytes, 1, end - 1, false);
                if (expectedApp == null) {
                    return false;
                }
                at = end + 1;
            } else {
                return false;
            }
            int[] ids = new int[8];
            int[] max = new int[8];
            int size = 0;
            int seen = 0;
            while (at < bytes.length) {
                int id = u(bytes[at++]);
                if (id > (command == 0 ? 7 : 0) || (seen & (1 << id)) != 0) {
                    return false;
                }
                seen |= 1 << id;
                int limit = MAX_ATTRIBUTE_BYTES;
                if (command == 0 && id >= TITLE && id <= MESSAGE) {
                    if (at + 2 > bytes.length) {
                        return false;
                    }
                    limit = u(bytes[at]) | u(bytes[at + 1]) << 8;
                    at += 2;
                    limit = Math.min(limit, MAX_ATTRIBUTE_BYTES);
                }
                ids[size] = id;
                max[size++] = limit;
            }
            if (size == 0) {
                return false;
            }
            wanted = Arrays.copyOf(ids, size);
            limits = Arrays.copyOf(max, size);
            return true;
        }

        /** A GATT value is a fragment of one response, never a coalesced stream of responses. */
        public List<Response> feed(byte[] fragment) {
            if (request == null) {
                return List.of();
            }
            if (fragment == null || ++fragments > MAX_FRAGMENTS
                    || fragment.length > buffer.length - count) {
                reset();
                return List.of();
            }
            System.arraycopy(fragment, 0, buffer, count, fragment.length);
            count += fragment.length;
            return parse();
        }

        public List<Response> feed(byte[] fragment, long now) {
            expire(now);
            return feed(fragment);
        }

        /** Tick even when no packets arrive, so silence cannot retain an expectation forever. */
        public boolean expire(long now) {
            if (request != null && timed && (now < started || now - started < 0
                    || now - started >= timeout)) {
                reset();
                return true;
            }
            return false;
        }

        private List<Response> parse() {
            if (!headerDone) {
                if (count < 1) {
                    return List.of();
                }
                if (u(buffer[0]) != command) {
                    return invalid();
                }
                if (command == 0) {
                    if (count < 5) {
                        return List.of();
                    }
                    if (uid(buffer, 1) != expectedUid) {
                        return invalid();
                    }
                    position = 5;
                } else {
                    int end = 1;
                    while (end < count && buffer[end] != 0) {
                        end++;
                    }
                    if (end - 1 > MAX_APP_ID_BYTES) {
                        return invalid();
                    }
                    if (end == count) {
                        return List.of();
                    }
                    String app = utf8(buffer, 1, end - 1, false);
                    if (!expectedApp.equals(app)) {
                        return invalid();
                    }
                    position = end + 1;
                }
                headerDone = true;
            }
            while (attributeIndex < wanted.length) {
                int id = wanted[attributeIndex];
                if (position < count && u(buffer[position]) != id) {
                    return invalid();
                }
                if (count - position < 3) {
                    return List.of();
                }
                int length = u(buffer[position + 1]) | u(buffer[position + 2]) << 8;
                if (length > limits[attributeIndex]) {
                    return invalid();
                }
                if (count - position - 3 < length) {
                    return List.of();
                }
                String value = utf8(buffer, position + 3, length, false);
                if (value == null) {
                    return invalid();
                }
                // Decode each completed attribute once, even with one-byte fragments.
                received.put(id, value);
                position += 3 + length;
                attributeIndex++;
            }
            if (position != count) {
                return invalid();
            }
            Response result = new Response(command, expectedUid, expectedApp, received);
            reset();
            return List.of(result);
        }

        private List<Response> invalid() {
            reset();
            return List.of();
        }

        /** After error/timeout, discard subsequent fragments until expect() arms a new request. */
        public void reset() {
            Arrays.fill(buffer, 0, count, (byte) 0);
            request = null;
            wanted = null;
            limits = null;
            expectedApp = null;
            expectedUid = 0;
            count = 0;
            fragments = 0;
            timed = false;
            headerDone = false;
            position = 0;
            attributeIndex = 0;
            received.clear();
        }

        public int bufferedBytes() { return count; }
        public boolean pending() { return request != null; }
    }

    static String utf8(byte[] bytes, int offset, int length, boolean allowPartialSuffix) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer input = ByteBuffer.wrap(bytes, offset, length);
        try {
            if (!allowPartialSuffix) {
                return decoder.decode(input).toString();
            }
            CharBuffer output = CharBuffer.allocate(length);
            if (decoder.decode(input, output, false).isError()) {
                return null;
            }
            output.flip();
            return output.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    static boolean validUid(long uid) { return uid >= 0 && uid <= 0xffff_ffffL; }
    private static int u(byte value) { return value & 255; }
    private static long uid(byte[] bytes, int offset) {
        long uid = 0;
        for (int i = 0; i < 4; i++) {
            uid |= (long) u(bytes[offset + i]) << (i * 8);
        }
        return uid;
    }
    private static void checkUid(long uid) {
        if (!validUid(uid)) {
            throw new IllegalArgumentException("UID must be unsigned 32-bit");
        }
    }
    private static void checkLimit(int value) {
        if (value < 0 || value > 65535) {
            throw new IllegalArgumentException("Attribute limit must be unsigned 16-bit");
        }
    }
    private static byte[] appBytes(String appId) {
        if (appId == null || appId.isEmpty() || appId.length() > MAX_APP_ID_BYTES
                || appId.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid app identifier");
        }
        byte[] result = appId.getBytes(StandardCharsets.UTF_8);
        if (result.length > MAX_APP_ID_BYTES
                || !appId.equals(utf8(result, 0, result.length, false))) {
            throw new IllegalArgumentException("Invalid UTF-8 app identifier");
        }
        return result;
    }
    private static void word(ByteArrayOutputStream out, int value) {
        out.write(value & 255);
        out.write(value >>> 8 & 255);
    }
    private static void writeUid(ByteArrayOutputStream out, long uid) {
        for (int i = 0; i < 4; i++) {
            out.write((int) (uid >>> (i * 8)) & 255);
        }
    }
}
