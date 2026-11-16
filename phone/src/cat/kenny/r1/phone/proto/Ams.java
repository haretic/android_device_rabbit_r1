// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone.proto;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/** AMS values are UTF-8 text after their small binary headers. */
public final class Ams {
    public static final int PLAY = 0, PAUSE = 1, TOGGLE = 2, NEXT = 3, PREVIOUS = 4;
    public static final int VOLUME_UP = 5, VOLUME_DOWN = 6, ADVANCE_REPEAT = 7;
    public static final int ADVANCE_SHUFFLE = 8, SKIP_FORWARD = 9, SKIP_BACKWARD = 10;
    public static final int LIKE = 11, DISLIKE = 12, BOOKMARK = 13;
    public static final int PLAYER = 0, QUEUE = 1, TRACK = 2;
    public static final int PLAYER_NAME = 0, PLAYBACK_INFO = 1, VOLUME = 2;
    public static final int QUEUE_INDEX = 0, QUEUE_COUNT = 1, SHUFFLE_MODE = 2, REPEAT_MODE = 3;
    public static final int ARTIST = 0, ALBUM = 1, TITLE = 2, DURATION = 3;
    public static final int PAUSED = 0, PLAYING = 1, REWINDING = 2, FAST_FORWARDING = 3;
    public static final int TRUNCATED = 1;
    public static final int MAX_VALUE_BYTES = 512;
    private static final Pattern DECIMAL = Pattern.compile(
            "[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?");

    private Ams() { }

    public static Set<Integer> parseRemoteCommands(byte[] value) {
        if (value == null || value.length > 14) {
            return null;
        }
        Set<Integer> commands = new LinkedHashSet<>();
        for (byte b : value) {
            int command = b & 255;
            if (command > BOOKMARK || !commands.add(command)) {
                return null;
            }
        }
        return Collections.unmodifiableSet(commands);
    }

    public static byte[] command(int command) {
        if (command < PLAY || command > BOOKMARK) {
            throw new IllegalArgumentException("Unknown remote command");
        }
        return new byte[] {(byte) command};
    }

    /** Each entity needs its own subscription write after notifications are enabled. */
    public static byte[] subscription(int entity, int... attributes) {
        if (attributes == null || !validEntity(entity) || attributes.length > 4) {
            throw new IllegalArgumentException("Invalid subscription");
        }
        byte[] value = new byte[1 + attributes.length];
        value[0] = (byte) entity;
        int seen = 0;
        for (int i = 0; i < attributes.length; i++) {
            int id = attributes[i];
            if (!validAttribute(entity, id) || (seen & (1 << id)) != 0) {
                throw new IllegalArgumentException("Invalid or repeated attribute");
            }
            value[i + 1] = (byte) id;
            seen |= 1 << id;
        }
        return value;
    }

    public record Update(int entity, int attribute, int flags, String value) {
        public Update {
            if (!validAttribute(entity, attribute) || (flags & ~TRUNCATED) != 0
                    || value == null || value.length() > MAX_VALUE_BYTES
                    || value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                    > MAX_VALUE_BYTES) {
                throw new IllegalArgumentException("Invalid entity update");
            }
        }
        public boolean truncated() { return (flags & TRUNCATED) != 0; }
    }

    public static Update parseUpdate(byte[] value) {
        if (value == null || value.length < 3 || value.length > MAX_VALUE_BYTES) {
            return null;
        }
        int entity = value[0] & 255;
        int attribute = value[1] & 255;
        int flags = value[2] & 255;
        if (!validAttribute(entity, attribute) || (flags & ~TRUNCATED) != 0) {
            return null;
        }
        // A truncated GATT value may end part way through its final UTF-8 character.
        String text = Ancs.utf8(value, 3, value.length - 3, (flags & TRUNCATED) != 0);
        return text == null ? null : new Update(entity, attribute, flags, text);
    }

    public static byte[] entityAttribute(int entity, int attribute) {
        if (!validAttribute(entity, attribute)) {
            throw new IllegalArgumentException("Unknown entity attribute");
        }
        return new byte[] {(byte) entity, (byte) attribute};
    }

    public static String parseAttributeValue(byte[] value) {
        return value == null || value.length > MAX_VALUE_BYTES ? null
                : Ancs.utf8(value, 0, value.length, false);
    }

    public record PlaybackInfo(int state, double rate, double elapsedSeconds) { }

    public static PlaybackInfo parsePlaybackInfo(String value) {
        if (value == null || value.length() > 128) {
            return null;
        }
        String[] parts = value.split(",", -1);
        if (parts.length != 3 || parts[0].length() != 1
                || parts[0].charAt(0) < '0' || parts[0].charAt(0) > '3') {
            return null;
        }
        Double rate = decimal(parts[1]);
        Double elapsed = decimal(parts[2]);
        if (rate == null || elapsed == null || elapsed < 0) {
            return null;
        }
        return new PlaybackInfo(parts[0].charAt(0) - '0', rate, elapsed);
    }

    public static Float parseVolume(String value) {
        Double number = decimal(value);
        return number == null || number < 0 || number > 1 ? null : number.floatValue();
    }

    public static Float parseDuration(String value) {
        Double number = decimal(value);
        if (number == null || number < 0 || number > Float.MAX_VALUE) {
            return null;
        }
        return number.floatValue();
    }

    private static Double decimal(String value) {
        if (value == null || value.length() > 128 || !DECIMAL.matcher(value).matches()) {
            return null;
        }
        try {
            double number = Double.parseDouble(value);
            return Double.isFinite(number) ? number : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean validEntity(int entity) { return entity >= PLAYER && entity <= TRACK; }
    private static boolean validAttribute(int entity, int attribute) {
        return validEntity(entity) && attribute >= 0 && attribute <= (entity == PLAYER ? 2 : 3);
    }
}
