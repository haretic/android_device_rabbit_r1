// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.qr;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** QR Code Model 2, UTF-8 byte segments, level M, without a quiet zone. */
public final class Qr {
    // Level M's parity bytes per block and block counts, indexed by version.
    private static final int[] ECC = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26,
        30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28,
        28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5,
        5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25,
        26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49};

    private final int version;
    private final int size;
    private final boolean[][] modules;
    private final boolean[][] function;

    private Qr(int version) {
        this.version = version;
        size = 17 + 4 * version;
        modules = new boolean[size][size];
        function = new boolean[size][size];
    }

    public static boolean[][] encode(String text) {
        Objects.requireNonNull(text, "text");
        // Reject by character count before allocating an arbitrarily large UTF-8 array.
        if (text.length() > 2331) {
            throw new IllegalArgumentException("Text does not fit QR version 40-M");
        }
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean eci = text.chars().anyMatch(c -> c > 127);
        int version = 1;
        for (; version <= 40; version++) {
            int count = version < 10 ? 8 : 16;
            long bits = (eci ? 12 : 0) + 4L + count + 8L * bytes.length;
            if (bytes.length < (1 << count) && bits <= dataWords(version) * 8L) {
                break;
            }
        }
        if (version > 40) {
            throw new IllegalArgumentException("Text does not fit QR version 40-M");
        }
        int capacity = dataWords(version) * 8;
        boolean[] bits = new boolean[capacity];
        int at = 0;
        if (eci) {
            at = append(bits, at, 7, 4);
            at = append(bits, at, 26, 8);
        }
        at = append(bits, at, 4, 4);
        at = append(bits, at, bytes.length, version < 10 ? 8 : 16);
        for (byte value : bytes) {
            at = append(bits, at, value & 255, 8);
        }
        at += Math.min(4, capacity - at);
        at = (at + 7) / 8 * 8;
        for (int pad = 0; at < capacity; pad++) {
            at = append(bits, at, (pad & 1) == 0 ? 0xec : 0x11, 8);
        }
        byte[] data = new byte[capacity / 8];
        for (int i = 0; i < capacity; i++) {
            data[i / 8] |= (byte) (bits[i] ? 1 << (7 - i % 8) : 0);
        }
        Qr qr = new Qr(version);
        qr.patterns();
        qr.place(interleave(data, version));
        int best = Integer.MAX_VALUE;
        int chosen = 0;
        for (int mask = 0; mask < 8; mask++) {
            qr.mask(mask);
            qr.format(mask);
            int score = qr.penalty();
            if (score < best) {
                best = score;
                chosen = mask;
            }
            qr.mask(mask);
        }
        qr.mask(chosen);
        qr.format(chosen);
        return qr.modules;
    }

    private static int append(boolean[] bits, int at, int value, int count) {
        for (int i = count - 1; i >= 0; i--) {
            bits[at++] = ((value >>> i) & 1) != 0;
        }
        return at;
    }

    private static int rawModules(int version) {
        int result = (16 * version + 128) * version + 64;
        if (version >= 2) {
            int count = version / 7 + 2;
            result -= (25 * count - 10) * count - 55;
            if (version >= 7) {
                result -= 36;
            }
        }
        return result;
    }

    private static int dataWords(int version) {
        return rawModules(version) / 8 - ECC[version] * BLOCKS[version];
    }

    private static byte[] interleave(byte[] data, int version) {
        int count = BLOCKS[version];
        int degree = ECC[version];
        int total = rawModules(version) / 8;
        int shortLength = total / count - degree;
        int shortCount = count - total % count;
        byte[][] blocks = new byte[count][];
        byte[][] parity = new byte[count][];
        int[] generator = new int[] {1};
        int root = 1;
        for (int i = 0; i < degree; i++) {
            int[] next = new int[generator.length + 1];
            for (int j = 0; j < generator.length; j++) {
                next[j] ^= generator[j];
                next[j + 1] ^= multiply(generator[j], root);
            }
            generator = next;
            root = multiply(root, 2);
        }
        int offset = 0;
        for (int block = 0; block < count; block++) {
            int length = shortLength + (block < shortCount ? 0 : 1);
            blocks[block] = Arrays.copyOfRange(data, offset, offset + length);
            offset += length;
            int[] dividend = new int[length + degree];
            for (int i = 0; i < length; i++) {
                dividend[i] = blocks[block][i] & 255;
            }
            for (int i = 0; i < length; i++) {
                int factor = dividend[i];
                for (int j = 0; j < generator.length; j++) {
                    dividend[i + j] ^= multiply(generator[j], factor);
                }
            }
            parity[block] = new byte[degree];
            for (int i = 0; i < degree; i++) {
                parity[block][i] = (byte) dividend[length + i];
            }
        }
        byte[] result = new byte[total];
        int at = 0;
        for (int i = 0; i <= shortLength; i++) {
            for (byte[] block : blocks) {
                if (i < block.length) {
                    result[at++] = block[i];
                }
            }
        }
        for (int i = 0; i < degree; i++) {
            for (byte[] block : parity) {
                result[at++] = block[i];
            }
        }
        return result;
    }

    private static int multiply(int a, int b) {
        int result = 0;
        for (; b != 0; b >>>= 1) {
            if ((b & 1) != 0) {
                result ^= a;
            }
            a <<= 1;
            if ((a & 256) != 0) {
                a ^= 0x11d;
            }
        }
        return result;
    }

    private void set(int x, int y, boolean dark) {
        modules[y][x] = dark;
        function[y][x] = true;
    }

    private void patterns() {
        for (int i = 0; i < size; i++) {
            set(6, i, (i & 1) == 0);
            set(i, 6, (i & 1) == 0);
        }
        finder(3, 3);
        finder(size - 4, 3);
        finder(3, size - 4);
        if (version > 1) {
            int count = version / 7 + 2;
            int step = version == 32 ? 26
                    : (version * 4 + count * 2 + 1) / (count * 2 - 2) * 2;
            int[] centers = new int[count];
            centers[0] = 6;
            for (int i = count - 1, center = size - 7; i > 0; i--, center -= step) {
                centers[i] = center;
            }
            for (int y = 0; y < count; y++) {
                for (int x = 0; x < count; x++) {
                    if (x == 0 && (y == 0 || y == count - 1) || y == 0 && x == count - 1) {
                        continue;
                    }
                    for (int dy = -2; dy <= 2; dy++) {
                        for (int dx = -2; dx <= 2; dx++) {
                            set(centers[x] + dx, centers[y] + dy,
                                    Math.max(Math.abs(dx), Math.abs(dy)) != 1);
                        }
                    }
                }
            }
        }
        format(0);
        if (version >= 7) {
            int remainder = version;
            for (int i = 0; i < 12; i++) {
                remainder = (remainder << 1) ^ ((remainder >>> 11) * 0x1f25);
            }
            int bits = version << 12 | remainder;
            for (int i = 0; i < 18; i++) {
                boolean dark = ((bits >>> i) & 1) != 0;
                set(size - 11 + i % 3, i / 3, dark);
                set(i / 3, size - 11 + i % 3, dark);
            }
        }
    }

    private void finder(int x, int y) {
        for (int dy = -4; dy <= 4; dy++) {
            for (int dx = -4; dx <= 4; dx++) {
                int xx = x + dx;
                int yy = y + dy;
                if (xx >= 0 && xx < size && yy >= 0 && yy < size) {
                    int distance = Math.max(Math.abs(dx), Math.abs(dy));
                    set(xx, yy, distance != 2 && distance != 4);
                }
            }
        }
    }

    private void format(int mask) {
        // Level M's two format bits are zero.
        int remainder = mask;
        for (int i = 0; i < 10; i++) {
            remainder = (remainder << 1) ^ ((remainder >>> 9) * 0x537);
        }
        int bits = ((mask << 10) | remainder) ^ 0x5412;
        for (int i = 0; i <= 5; i++) {
            set(8, i, bit(bits, i));
        }
        set(8, 7, bit(bits, 6));
        set(8, 8, bit(bits, 7));
        set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) {
            set(14 - i, 8, bit(bits, i));
        }
        for (int i = 0; i < 8; i++) {
            set(size - 1 - i, 8, bit(bits, i));
        }
        for (int i = 8; i < 15; i++) {
            set(8, size - 15 + i, bit(bits, i));
        }
        set(8, size - 8, true);
    }

    private static boolean bit(int value, int index) {
        return ((value >>> index) & 1) != 0;
    }

    private void place(byte[] words) {
        int at = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) {
                right = 5;
            }
            for (int vertical = 0; vertical < size; vertical++) {
                int y = ((right + 1) & 2) == 0 ? size - 1 - vertical : vertical;
                for (int dx = 0; dx < 2; dx++) {
                    int x = right - dx;
                    if (!function[y][x]) {
                        modules[y][x] = at < words.length * 8
                                && bit(words[at / 8] & 255, 7 - at % 8);
                        at++;
                    }
                }
            }
        }
        if (at != rawModules(version)) {
            throw new IllegalStateException("Incorrect function pattern layout");
        }
    }

    private void mask(int mask) {
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean flip;
                switch (mask) {
                    case 0: flip = (x + y) % 2 == 0; break;
                    case 1: flip = y % 2 == 0; break;
                    case 2: flip = x % 3 == 0; break;
                    case 3: flip = (x + y) % 3 == 0; break;
                    case 4: flip = (y / 2 + x / 3) % 2 == 0; break;
                    case 5: flip = x * y % 2 + x * y % 3 == 0; break;
                    case 6: flip = (x * y % 2 + x * y % 3) % 2 == 0; break;
                    case 7: flip = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
                    default: throw new IllegalArgumentException("Invalid mask");
                }
                if (!function[y][x] && flip) {
                    modules[y][x] = !modules[y][x];
                }
            }
        }
    }

    private int penalty() {
        int score = 0;
        int dark = 0;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (modules[y][x]) {
                    dark++;
                }
                if (x > 0 && y > 0 && modules[y][x] == modules[y][x - 1]
                        && modules[y][x] == modules[y - 1][x]
                        && modules[y][x] == modules[y - 1][x - 1]) {
                    score += 3;
                }
            }
            score += linePenalty(modules[y]);
            boolean[] column = new boolean[size];
            for (int x = 0; x < size; x++) {
                column[x] = modules[x][y];
            }
            score += linePenalty(column);
        }
        // Each full five percent deviation from half dark adds ten.
        score += Math.abs(dark * 20 - size * size * 10) / (size * size) * 10;
        return score;
    }

    private static int linePenalty(boolean[] line) {
        int score = 0;
        int[] lengths = new int[line.length + 2];
        boolean[] colors = new boolean[lengths.length];
        int runs = 0;
        // The quiet zone is light and counts as the margin around finder-like runs.
        lengths[0] = line.length;
        for (boolean dark : line) {
            if (dark != colors[runs]) {
                runs++;
                colors[runs] = dark;
            }
            lengths[runs]++;
        }
        if (colors[runs]) {
            runs++;
        }
        lengths[runs] += line.length;
        for (int i = 0; i <= runs; i++) {
            int inside = lengths[i];
            if (i == 0) {
                inside -= line.length;
            }
            if (i == runs) {
                inside -= line.length;
            }
            if (inside >= 5) {
                score += inside - 2;
            }
            if (i >= 1 && i + 5 <= runs && colors[i]) {
                int n = lengths[i];
                if (lengths[i + 1] == n && lengths[i + 2] == 3 * n
                        && lengths[i + 3] == n && lengths[i + 4] == n) {
                    if (lengths[i - 1] >= 4 * n && lengths[i + 5] >= n) {
                        score += 40;
                    }
                    if (lengths[i + 5] >= 4 * n && lengths[i - 1] >= n) {
                        score += 40;
                    }
                }
            }
        }
        return score;
    }
}
