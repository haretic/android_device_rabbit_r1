// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.function.BooleanSupplier;

/** A separate clock can close a socket even when its worker is blocked in native I/O. */
final class ConnectionIO {
    private final Socket socket;
    private final long idle;
    private final long cap;
    private final Deadline read = new Deadline();
    private final Deadline write = new Deadline();
    volatile BooleanSupplier allowed = () -> true;

    ConnectionIO(Socket socket, long idleMillis, long capMillis) {
        this.socket = socket;
        idle = idleMillis * 1_000_000;
        cap = capMillis * 1_000_000;
    }

    void reset() {
        allowed = () -> true;
        read.reset(cap);
        write.reset(cap);
    }

    void check() {
        try {
            if (allowed.getAsBoolean() && !read.expired() && !write.expired()) {
                return;
            }
        } catch (RuntimeException e) {
            // A failed permission check must not leave an admitted socket alive.
        }
        try {
            socket.close();
        } catch (IOException e) {
            // The worker also closes the socket.
        }
    }

    void smallBody() {
        read.shorten(60_000_000_000L);
    }

    InputStream body(InputStream in, boolean small) {
        read.reset(small ? Math.min(cap, 60_000_000_000L) : cap);
        return new InputStream() {
            @Override public int read() throws IOException {
                byte[] one = new byte[1];
                return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
            }
            @Override public int read(byte[] bytes, int off, int len) throws IOException {
                java.util.Objects.checkFromIndexSize(off, len, bytes.length);
                if (len == 0) {
                    return 0;
                }
                read.enter();
                try {
                    int count = in.read(bytes, off, len);
                    read.progress(count > 0);
                    return count;
                } finally {
                    read.leave();
                }
            }
        };
    }

    OutputStream output(OutputStream out) {
        return new OutputStream() {
            @Override public void write(int value) throws IOException {
                write(new byte[] {(byte) value}, 0, 1);
            }
            @Override public void write(byte[] bytes, int off, int len) throws IOException {
                java.util.Objects.checkFromIndexSize(off, len, bytes.length);
                while (len > 0) {
                    // Small writes expose progress instead of hiding it in one giant call.
                    int count = Math.min(4096, len);
                    write.enter();
                    try {
                        out.write(bytes, off, count);
                        write.progress(true);
                    } finally {
                        write.leave();
                    }
                    off += count;
                    len -= count;
                }
            }
            @Override public void flush() throws IOException {
                write.enter();
                try {
                    out.flush();
                } finally {
                    write.leave();
                }
            }
        };
    }

    private final class Deadline {
        private long start;
        private long last;
        private long limit = cap;
        private boolean busy;

        synchronized void reset(long nanos) {
            start = 0;
            last = 0;
            busy = false;
            limit = nanos;
        }

        synchronized void shorten(long nanos) {
            limit = Math.min(limit, nanos);
        }

        synchronized void enter() throws IOException {
            long now = System.nanoTime();
            if (start == 0) {
                start = now;
            }
            // Idle time is time waiting for bytes, rather than time spent in the handler.
            if (!busy) {
                last = now;
            }
            busy = true;
            check();
            if (socket.isClosed()) {
                throw new IOException("Transfer ended");
            }
        }

        synchronized void progress(boolean bytes) {
            if (bytes) {
                last = System.nanoTime();
            }
        }

        synchronized void leave() {
            busy = false;
        }

        synchronized boolean expired() {
            long now = System.nanoTime();
            return busy && (now - last >= idle || now - start >= limit);
        }
    }
}
