// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.util.Objects;

/** G.711 mu-law for the wire's 8 kHz samples. */
public final class MuLaw {
    private MuLaw() {}

    public static void encode(short[] pcm, int offset, int count, byte[] out, int outOffset) {
        Objects.checkFromIndexSize(offset, count, pcm.length);
        Objects.checkFromIndexSize(outOffset, count, out.length);
        for (int i = 0; i < count; i++) {
            int sample = pcm[offset + i];
            int mask = sample < 0 ? 0x7f : 0xff;
            sample = Math.min(Math.abs(sample), 32635) + 132;
            int exponent = 7;
            for (int bit = 0x4000; exponent > 0 && (sample & bit) == 0; bit >>= 1) {
                exponent--;
            }
            int mantissa = (sample >> (exponent + 3)) & 15;
            out[outOffset + i] = (byte) ((exponent << 4 | mantissa) ^ mask);
        }
    }

    public static void decode(byte[] in, int offset, int count, short[] pcm, int pcmOffset) {
        Objects.checkFromIndexSize(offset, count, in.length);
        Objects.checkFromIndexSize(pcmOffset, count, pcm.length);
        for (int i = 0; i < count; i++) {
            int value = ~in[offset + i] & 255;
            int sample = (((value & 15) << 3) + 132) << ((value >> 4) & 7);
            pcm[pcmOffset + i] = (short) ((value & 128) == 0 ? sample - 132 : 132 - sample);
        }
    }
}
