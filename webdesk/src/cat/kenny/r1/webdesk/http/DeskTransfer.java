// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.function.BooleanSupplier;

/** Checks permission again while an already admitted transfer is in progress. */
public final class DeskTransfer extends FilterInputStream {
    private final BooleanSupplier allowed;
    private final long deadline;
    private long left;

    public DeskTransfer(InputStream in, BooleanSupplier allowed, long limit, long millis) {
        super(in);
        this.allowed = allowed;
        left = limit;
        deadline = System.nanoTime() + millis * 1_000_000;
    }

    private void check() throws IOException {
        if (!allowed.getAsBoolean() || Thread.currentThread().isInterrupted()
                || System.nanoTime() - deadline >= 0) {
            throw new IOException("Transfer ended");
        }
    }

    @Override public int read() throws IOException {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
    }

    @Override public int read(byte[] bytes, int off, int len) throws IOException {
        java.util.Objects.checkFromIndexSize(off, len, bytes.length);
        if (len == 0) {
            return 0;
        }
        check();
        int count = in.read(bytes, off, (int) Math.min(len, Math.min(left, 65_536) + 1));
        check();
        if (count > left) {
            throw new IOException("Transfer too large");
        }
        if (count > 0) {
            left -= count;
        }
        return count;
    }

    @Override public long skip(long count) throws IOException {
        check();
        long skipped = in.skip(Math.min(Math.max(0, count), left));
        check();
        left -= skipped;
        return skipped;
    }
}
