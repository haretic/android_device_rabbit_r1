// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.net.LocalSocket;
import android.os.SystemClock;
import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;

/** Fixed-size reports and US text mapping adapted from UsbMode TextTyper. */
final class HidInput implements AutoCloseable {
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(128);
    private final String generation;
    private volatile boolean closed;
    private volatile LocalSocket socket;
    private final Runnable failure;
    HidInput(String generation, Runnable failure) {
        this.generation = generation; this.failure = failure;
        new Thread(this::run, "USB keyboard and mouse").start();
    }
    private void run() {
        try (LocalSocket s = Transport.connect("r1-usbemu-input", generation)) {
            socket = s;
            if (closed) return;
            while (!closed) {
                byte[] report = queue.take();
                s.getOutputStream().write(report);
                // A key release must not overtake its press on a busy host.
                SystemClock.sleep(10);
            }
        } catch (IOException | InterruptedException e) {
            if (!closed) failure.run();
        } finally { closed = true; socket = null; queue.clear(); }
    }
    private void offer(byte[] packet) {
        if (!closed && !queue.offer(packet)) { close(); failure.run(); }
    }
    void mouse(int buttons, int x, int y, int wheel) {
        offer(new byte[] {2, (byte) buttons, bounded(x), bounded(y), bounded(wheel)});
    }
    private static byte bounded(int n) { return (byte) Math.max(-127, Math.min(127, n)); }
    void click(int button) { mouse(button, 0, 0, 0); mouse(0, 0, 0, 0); }
    static byte[] key(char c) {
        int usage = 0, shift = 0;
        if (c >= 'a' && c <= 'z') usage = 4 + c - 'a';
        else if (c >= 'A' && c <= 'Z') { usage = 4 + c - 'A'; shift = 2; }
        else if (c >= '1' && c <= '9') usage = 30 + c - '1';
        else if (c == '0') usage = 39;
        else if (c == '\n') usage = 40;
        else if (c == '\t') usage = 43;
        else if (c == ' ') usage = 44;
        else {
            String normal = "-=[]\\;'`,./";
            String shifted = "_+{}|:\"~<>?";
            int[] codes = {45,46,47,48,49,51,52,53,54,55,56};
            int i = normal.indexOf(c);
            if (i >= 0) usage = codes[i];
            else if ((i = shifted.indexOf(c)) >= 0) { usage = codes[i]; shift = 2; }
            else if ((i = "!@#$%^&*()".indexOf(c)) >= 0) { usage = 30 + i; shift = 2; }
        }
        return usage == 0 ? null : new byte[] {1, (byte) shift, 0, (byte) usage, 0,0,0,0,0};
    }
    boolean type(String text) {
        // Validate the entire draft before sending; never silently skip text.
        if (text.length() > 60 || queue.remainingCapacity() < text.length() * 2) return false;
        for (char c : text.toCharArray()) if (key(c) == null) return false;
        for (char c : text.toCharArray()) { offer(key(c)); offer(new byte[] {1,0,0,0,0,0,0,0,0}); }
        return !closed;
    }
    @Override public void close() {
        closed = true; queue.clear(); queue.offer(new byte[] {1,0,0,0,0,0,0,0,0});
        LocalSocket s = socket; if (s != null) try { s.close(); } catch (IOException ignored) { }
    }
}
