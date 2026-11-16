// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;

/** Serializes code attempts and the single paired computer's session. */
public final class Pairing {
    private final SecureRandom random;
    private String code;
    private byte[] token;
    private String who;
    private long since;
    private int wrong;
    private long lockedUntil;
    private boolean spent;

    public Pairing(SecureRandom random) {
        this.random = java.util.Objects.requireNonNull(random);
        newCode();
    }

    public synchronized String code() { return code; }

    public synchronized String newCode() {
        String previous = code;
        do {
            code = String.format(Locale.ROOT, "%06d", random.nextInt(1_000_000));
        } while (code.equals(previous));
        end();
        spent = false;
        wrong = 0;
        lockedUntil = 0;
        return code;
    }

    public synchronized String pair(String typedCode, String who, long nowMillis) {
        boolean matches = MessageDigest.isEqual(code.getBytes(StandardCharsets.US_ASCII),
                typedCode == null ? new byte[0] : boundedBytes(typedCode, 6));
        if (locked(nowMillis) || spent || token != null) {
            return null;
        }
        if (!matches) {
            wrong++;
            if (wrong == 5) {
                newCode();
                lockedUntil = nowMillis > Long.MAX_VALUE - 30_000
                        ? Long.MAX_VALUE : nowMillis + 30_000;
            }
            return null;
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        token = encoded.getBytes(StandardCharsets.US_ASCII);
        java.util.Arrays.fill(bytes, (byte) 0);
        this.who = who;
        since = nowMillis;
        wrong = 0;
        spent = true;
        return encoded;
    }

    private static byte[] boundedBytes(String value, int length) {
        if (value.length() != length) {
            return new byte[0];
        }
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public synchronized boolean valid(String value) {
        byte[] expected = token == null ? new byte[43] : token;
        boolean matches = MessageDigest.isEqual(expected,
                value == null ? new byte[0] : boundedBytes(value, 43));
        return matches && token != null;
    }

    public synchronized boolean paired() { return token != null; }
    public synchronized String who() { return who; }
    public synchronized long since() { return since; }

    public synchronized void end() {
        if (token != null) {
            java.util.Arrays.fill(token, (byte) 0);
        }
        token = null;
        who = null;
        since = 0;
    }

    public synchronized boolean locked(long nowMillis) {
        return lockedUntil != 0 && nowMillis < lockedUntil;
    }

    public synchronized int wrongLeft() { return 5 - wrong; }
}
