// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;

/** The speaker fed by a renderer, block by block, at the media volume. */
final class Speaker {

    interface Renderer {
        /** On the speaker's own thread. Return false once there is nothing more to play. */
        boolean render(short[] out, int frames);
    }

    static final int RATE = 48000;
    /** 10 ms: a tempo or a pitch change is heard at once */
    private static final int BLOCK = RATE / 100;

    private volatile boolean mRunning;
    private Thread mThread;

    void start(Renderer renderer) {
        if (mRunning) {
            return;
        }
        final int min = AudioTrack.getMinBufferSize(RATE, AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        final AudioTrack track;
        try {
            track = new AudioTrack.Builder()
                    .setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                    .setAudioFormat(new AudioFormat.Builder().setSampleRate(RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                    .setBufferSizeInBytes(Math.max(min, BLOCK * 2 * 4))
                    .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
                    .setTransferMode(AudioTrack.MODE_STREAM).build();
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            return;
        }
        mRunning = true;
        mThread = new Thread(() -> {
            final short[] block = new short[BLOCK];
            track.play();
            // After a stop the renderer fades out: it says when it is silent
            while (renderer.render(block, BLOCK)) {
                if (track.write(block, 0, BLOCK) < 0) {
                    break;
                }
            }
            track.stop();
            track.release();
            mRunning = false;
        }, "toolbox-speaker");
        mThread.start();
    }

    boolean running() {
        return mRunning;
    }

    /** Waits for the renderer to end, which it does once its own stop was asked. */
    void join() {
        final Thread thread = mThread;
        mThread = null;
        if (thread != null) {
            try {
                thread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
