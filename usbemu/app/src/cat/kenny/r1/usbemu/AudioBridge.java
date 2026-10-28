// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.LocalSocket;
import android.os.SystemClock;
import java.io.IOException;
import java.net.SocketTimeoutException;
import java.util.Arrays;

/** UsbMode UsbAudio socket bridge, with physical mic capture and no soundboard. */
final class AudioBridge implements AutoCloseable {
    static volatile boolean micMuted = true, speakerMuted;
    static volatile int playbackLevel, captureLevel;
    static volatile String status = "connecting";
    private final Context context;
    private final String generation;
    private volatile boolean closed;
    private volatile LocalSocket socket;
    AudioBridge(Context context, String generation) {
        this.context = context; this.generation = generation;
        micMuted = true; speakerMuted = false; playbackLevel = captureLevel = 0; status = "connecting";
        new Thread(this::run, "USB speaker").start();
    }
    private AudioDeviceInfo device(int type, boolean input) {
        for (AudioDeviceInfo d : context.getSystemService(AudioManager.class).getDevices(input
                ? AudioManager.GET_DEVICES_INPUTS : AudioManager.GET_DEVICES_OUTPUTS)) {
            if (d.getType() == type) return d;
        }
        return null;
    }
    private void run() {
        while (!closed) {
            Thread capture = null;
            try (LocalSocket s = Transport.connect("r1-usbemu-audio", generation)) {
                socket = s; if (closed) return;
                capture = new Thread(() -> capture(s), "USB microphone"); capture.start();
                AudioTrack track = new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(3072, AudioTrack.getMinBufferSize(48000,
                                AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT))).build();
                try {
                    AudioDeviceInfo speaker = device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, false);
                    if (speaker == null || !track.setPreferredDevice(speaker)) throw new IOException("audio");
                    track.play(); byte[] buffer = new byte[768];
                    while (!closed && socket == s) {
                        int n;
                        try { n = s.getInputStream().read(buffer); }
                        catch (SocketTimeoutException waitingForHost) {
                            // Host playback and recording are independent. A PC
                            // recording only the microphone must not lose it.
                            status = "connecting"; continue;
                        }
                        if (n <= 0 || n % 4 != 0) throw new IOException("audio");
                        playbackLevel = speakerMuted ? 0 : level(buffer, n);
                        track.setVolume(speakerMuted ? 0 : 1);
                        int offset = 0;
                        while (!closed && offset < n) {
                            int written = track.write(buffer, offset, n - offset, AudioTrack.WRITE_NON_BLOCKING);
                            if (written < 0) throw new IOException("audio");
                            offset += written; if (written == 0) SystemClock.sleep(2);
                        }
                        status = "playing";
                    }
                } finally { track.pause(); track.flush(); track.release(); }
            } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
                if (!closed) status = "error";
            } finally {
                LocalSocket s = socket; socket = null;
                if (s != null) try { s.close(); } catch (IOException ignored) { }
                if (capture != null) try { capture.join(1000); } catch (InterruptedException ignored) { }
            }
            if (!closed) SystemClock.sleep(200);
        }
    }
    private void capture(LocalSocket s) {
        AudioRecord record = null;
        try {
            byte[] stereo = new byte[768]; short[] mono = new short[192];
            long next = SystemClock.elapsedRealtime();
            while (!closed && socket == s) {
                boolean allowed = !micMuted && context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
                if (!allowed && record != null) { release(record); record = null; }
                if (allowed && record == null) {
                    record = new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(new AudioFormat.Builder().setSampleRate(48000)
                                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                            .setBufferSizeInBytes(Math.max(1536, AudioRecord.getMinBufferSize(48000,
                                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT))).build();
                    AudioDeviceInfo mic = device(AudioDeviceInfo.TYPE_BUILTIN_MIC, true);
                    if (mic == null || !record.setPreferredDevice(mic)) throw new IOException("audio");
                    record.startRecording();
                }
                Arrays.fill(stereo, (byte) 0);
                if (allowed && record != null) {
                    int n = record.read(mono, 0, mono.length, AudioRecord.READ_NON_BLOCKING);
                    if (n < 0) throw new IOException("audio");
                    if (!micMuted) for (int i = 0; i < n; i++) {
                        stereo[i*4] = stereo[i*4+2] = (byte) mono[i];
                        stereo[i*4+1] = stereo[i*4+3] = (byte) (mono[i] >> 8);
                    }
                }
                captureLevel = level(stereo, stereo.length);
                s.getOutputStream().write(stereo);
                next += 4; long wait = next - SystemClock.elapsedRealtime();
                if (wait > 0) SystemClock.sleep(wait); else if (wait < -20) next = SystemClock.elapsedRealtime();
            }
        } catch (IOException | IllegalStateException | IllegalArgumentException | SecurityException e) {
            if (!closed) { micMuted = true; status = "error"; }
            try { s.close(); } catch (IOException ignored) { }
        } finally { if (record != null) release(record); }
    }
    private static void release(AudioRecord record) {
        try { if (record.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) record.stop(); }
        catch (IllegalStateException ignored) { }
        finally { record.release(); }
    }
    private static int level(byte[] pcm, int length) {
        double sum = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 255) | (pcm[i+1] << 8));
            sum += (double) sample * sample;
        }
        if (sum == 0) return 0;
        return (int) Math.max(0, Math.min(96, 96 + 20 * Math.log10(Math.sqrt(sum / (length / 2.0)) / 32768)));
    }
    @Override public void close() {
        closed = true; micMuted = true; playbackLevel = captureLevel = 0;
        LocalSocket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
