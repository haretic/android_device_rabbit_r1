// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** Strict datagram codec; a reusable audio copy outlives the receive buffer. */
public final class Packet {
    public static final int HELLO = 1, VOICE = 2, END = 3, BYE = 4;
    public static final int FRAME = 320;
    public static final int MAX = 19 + FRAME;
    public int type;
    public long id;
    public int channel;
    public String name;
    public int talk;
    public int seq;
    public int frames;
    private final byte[] audio = new byte[FRAME];

    public static int hello(byte[] out, long id, int channel, String name) {
        Objects.requireNonNull(name);
        Objects.checkFromIndexSize(0, 46, out.length);
        header(out, id, channel, HELLO, 14);
        int end = 14;
        for (int i = 0; i < name.length();) {
            int cp = name.codePointAt(i);
            i += Character.charCount(cp);
            if (cp >= 0xd800 && cp <= 0xdfff) cp = '?';
            int bytes = cp < 128 ? 1 : cp < 2048 ? 2 : cp < 65536 ? 3 : 4;
            if (end + bytes > 46) break;
            if (bytes == 1) {
                out[end++] = (byte) cp;
            } else {
                out[end++] = (byte) ((bytes == 2 ? 0xc0 : bytes == 3 ? 0xe0 : 0xf0)
                        | (cp >> (6 * (bytes - 1))));
                for (int shift = 6 * (bytes - 2); shift >= 0; shift -= 6) {
                    out[end++] = (byte) (0x80 | ((cp >> shift) & 63));
                }
            }
        }
        out[13] = (byte) (end - 14);
        return end;
    }

    public static int voice(byte[] out, long id, int channel, int talk, int seq,
            byte[] mulaw, int offset) {
        Objects.checkFromIndexSize(offset, FRAME, mulaw.length);
        header(out, id, channel, VOICE, MAX);
        putInt(out, 13, talk);
        putShort(out, 17, seq);
        System.arraycopy(mulaw, offset, out, 19, FRAME);
        return MAX;
    }

    public static int end(byte[] out, long id, int channel, int talk, int frames) {
        header(out, id, channel, END, 19);
        putInt(out, 13, talk);
        putShort(out, 17, frames);
        return 19;
    }

    public static int bye(byte[] out, long id, int channel) {
        header(out, id, channel, BYE, 13);
        return 13;
    }

    public boolean parse(byte[] in, int length) {
        type = 0;
        name = null;
        talk = seq = frames = 0;
        id = 0;
        channel = 0;
        if (!headerValid(in, length)) return false;
        int kind = in[3] & 255;
        String parsedName = null;
        if (kind == HELLO) {
            if (length < 14) return false;
            int n = in[13] & 255;
            if (n > 32 || length != 14 + n) return false;
            try {
                parsedName = StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(in, 14, n)).toString();
            } catch (CharacterCodingException e) {
                return false;
            }
        } else if (kind == VOICE) {
            if (length != MAX) return false;
        } else if (kind == END) {
            if (length != 19) return false;
        } else if (kind == BYE) {
            if (length != 13) return false;
        } else {
            return false;
        }
        for (int i = 4; i < 12; i++) id = (id << 8) | (in[i] & 255L);
        channel = in[12] & 255;
        name = parsedName;
        if (kind == VOICE || kind == END) {
            talk = getInt(in, 13);
            if (kind == VOICE) {
                seq = ((in[17] & 255) << 8) | (in[18] & 255);
                System.arraycopy(in, 19, audio, 0, FRAME);
            } else {
                frames = ((in[17] & 255) << 8) | (in[18] & 255);
            }
        }
        type = kind;
        return true;
    }

    public byte[] audio() {
        return audio;
    }

    static boolean headerValid(byte[] in, int length) {
        return in != null && length >= 13 && length <= MAX && length <= in.length
                && in[0] == 'r' && in[1] == '1' && in[2] == 1
                && in[12] >= 1 && in[12] <= 8;
    }

    private static void header(byte[] out, long id, int channel, int type, int size) {
        if (channel < 1 || channel > 8) throw new IllegalArgumentException("channel");
        Objects.checkFromIndexSize(0, size, out.length);
        out[0] = 'r';
        out[1] = '1';
        out[2] = 1;
        out[3] = (byte) type;
        for (int i = 11; i >= 4; i--) {
            out[i] = (byte) id;
            id >>>= 8;
        }
        out[12] = (byte) channel;
    }

    private static void putShort(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >> 8);
        out[offset + 1] = (byte) value;
    }

    private static void putInt(byte[] out, int offset, int value) {
        for (int i = offset + 3; i >= offset; i--) {
            out[i] = (byte) value;
            value >>>= 8;
        }
    }

    private static int getInt(byte[] in, int offset) {
        int value = 0;
        for (int i = offset; i < offset + 4; i++) value = (value << 8) | (in[i] & 255);
        return value;
    }
}
