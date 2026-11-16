// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.util.Objects;

/** Circular PCM storage keeps the most recent part of one received message. */
public final class Recorder {
    private final short[][] audio;
    private int first;
    private int size;
    private String from = "r1";
    private long when;
    private boolean recording;

    public Recorder(int maxSeconds) {
        // Bound even accidental caller input to ten minutes of PCM.
        if (maxSeconds < 1 || maxSeconds > 600) {
            throw new IllegalArgumentException("maxSeconds");
        }
        audio = new short[maxSeconds * 25][Packet.FRAME];
    }

    public synchronized void start(String from, long wallClockMillis) {
        this.from = Peers.clean(from);
        when = wallClockMillis;
        first = size = 0;
        recording = true;
    }

    public synchronized void add(short[] pcm320) {
        Objects.checkFromIndexSize(0, Packet.FRAME, pcm320.length);
        if (!recording) return;
        int index = (first + size) % audio.length;
        System.arraycopy(pcm320, 0, audio[index], 0, Packet.FRAME);
        if (size == audio.length) {
            first = (first + 1) % audio.length;
        } else {
            size++;
        }
    }

    public synchronized void end() {
        recording = false;
    }

    public synchronized boolean has() {
        return size != 0;
    }

    public synchronized String from() {
        return from;
    }

    public synchronized long when() {
        return when;
    }

    public synchronized int millis() {
        return size * 40;
    }

    public synchronized int read(int frameIndex, short[] pcm320) {
        Objects.checkFromIndexSize(0, Packet.FRAME, pcm320.length);
        if (frameIndex < 0 || frameIndex >= size) return 0;
        System.arraycopy(audio[(first + frameIndex) % audio.length], 0,
                pcm320, 0, Packet.FRAME);
        return 1;
    }
}
