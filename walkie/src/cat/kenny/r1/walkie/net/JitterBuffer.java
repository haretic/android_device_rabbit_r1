// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.util.Arrays;
import java.util.Objects;

/** Fixed receive window with a small playout delay and bounded loss concealment. */
public final class JitterBuffer {
    private static final int WINDOW = 128;
    private final int target;
    private final byte[][] audio = new byte[WINDOW][Packet.FRAME];
    private final long[] positions = new long[WINDOW];
    private final short[] last = new short[Packet.FRAME];
    private long next;
    private long highest = -1;
    private long end = -1;
    private int queued;
    private int lost;
    private int played;
    private int repeats;
    private boolean anchored;
    private boolean started;

    public JitterBuffer(int targetFrames) {
        if (targetFrames < 1 || targetFrames > WINDOW) {
            throw new IllegalArgumentException("targetFrames");
        }
        target = targetFrames;
        Arrays.fill(positions, -1);
    }

    public synchronized void put(int seq, byte[] mulaw320) {
        Objects.checkFromIndexSize(0, Packet.FRAME, mulaw320.length);
        if (seq < 0 || seq > 65535 || done()) return;
        if (!anchored) {
            // Keep early missing frames, but allow joining a talk near sequence wrap.
            next = seq > 32767 ? seq : 0;
            highest = next - 1;
            anchored = true;
        }
        int distance = (short) (seq - (int) next);
        if (distance < 0 || distance >= WINDOW) return;
        long position = next + distance;
        if (end >= 0 && position >= end) return;
        int slot = (int) position & (WINDOW - 1);
        if (positions[slot] == position) return;
        System.arraycopy(mulaw320, 0, audio[slot], 0, Packet.FRAME);
        positions[slot] = position;
        queued++;
        highest = Math.max(highest, position);
    }

    public synchronized boolean take(short[] pcm320) {
        Objects.checkFromIndexSize(0, Packet.FRAME, pcm320.length);
        if (done()) return false;
        if (!started) {
            if (queued < target && end < 0) return false;
            started = true;
        }
        int slot = (int) next & (WINDOW - 1);
        if (positions[slot] == next) {
            MuLaw.decode(audio[slot], 0, Packet.FRAME, pcm320, 0);
            System.arraycopy(pcm320, 0, last, 0, Packet.FRAME);
            positions[slot] = -1;
            queued--;
            repeats = 0;
        } else {
            lost++;
            repeats++;
            for (int i = 0; i < Packet.FRAME; i++) {
                last[i] = repeats <= 2 ? (short) (last[i] / 2) : 0;
                pcm320[i] = last[i];
            }
        }
        next++;
        played++;
        return true;
    }

    public synchronized void finish(int totalFrames) {
        if (totalFrames < 0 || end >= 0) return;
        long base = Math.max(next, highest + 1);
        long limit = totalFrames > 65535 ? totalFrames
                : base + (short) (totalFrames - (int) base);
        // A stale or contradictory END must not create an extra 65536 silent frames.
        end = Math.max(base, limit);
    }

    public synchronized boolean done() {
        return end >= 0 && next >= end;
    }

    public synchronized int lost() {
        return lost;
    }

    public synchronized int played() {
        return played;
    }

    synchronized int receivedLimit() {
        return (int) Math.max(next, highest + 1);
    }
}
