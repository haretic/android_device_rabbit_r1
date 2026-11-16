// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/** The microphone as blocks of samples, unprocessed where the r1 offers that. */
final class Mic {

    interface Listener {
        /** On the microphone's own thread. */
        void onBlock(short[] pcm, int count);
    }

    static final int RATE = 48000;
    private static final int BLOCK = RATE / 20;

    private volatile boolean mRunning;
    private Thread mThread;

    /** False when the microphone could not be opened: no permission, or something has it. */
    boolean start(Listener listener) {
        if (mRunning) {
            return true;
        }
        final int min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord record;
        try {
            record = new AudioRecord(MediaRecorder.AudioSource.UNPROCESSED, RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(min, BLOCK * 8));
        } catch (IllegalArgumentException | SecurityException e) {
            return false;
        }
        if (record.getState() != AudioRecord.STATE_INITIALIZED) {
            record.release();
            return false;
        }
        record.startRecording();
        if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            record.release();
            return false;
        }
        mRunning = true;
        mThread = new Thread(() -> {
            final short[] block = new short[BLOCK];
            while (mRunning) {
                final int n = record.read(block, 0, BLOCK);
                if (n <= 0) {
                    break;
                }
                listener.onBlock(block, n);
            }
            record.stop();
            record.release();
        }, "toolbox-mic");
        mThread.start();
        return true;
    }

    void stop() {
        mRunning = false;
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
