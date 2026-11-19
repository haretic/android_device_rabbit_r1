// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.net.LocalServerSocket;
import android.net.LocalSocket;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.util.Log;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * r1-tunneld, the tunnel itself, and how the app talks to it.
 *
 * The VPN's interface cannot be handed down to it as an open file: Java's way of starting a
 * program closes everything but stdin, stdout and stderr in the child (libcore's
 * UNIXProcess_md.c, closeDescriptors). So the app listens on an abstract socket with a name
 * made for each connection, starts the helper with that name, takes the one connection after
 * checking it comes from this app's own uid, and sends the interface over it with "tun". The
 * same socket then carries the control lines: set, up, get, rebind, wake and quit.
 */
final class Tunneld {
    static final String PATH = "/system_ext/bin/r1-tunneld";
    private static final String TAG = Store.TAG;
    private static final int CONNECT_MS = 5000;
    private static final int REPLY_MS = 5000;

    /** Why the helper could not be used, for the status to say */
    static final class Failure extends IOException {
        static final int NO_START = 0;
        static final int EXITED = 1;
        static final int STRANGER = 2;
        static final int REFUSED = 3;
        final int why;

        Failure(int why, String message) {
            super(message);
            this.why = why;
        }
    }

    private Tunneld() {
    }

    // Keys

    /** A new key pair: {private, public}, both hex. */
    static String[] genkey() throws IOException {
        final String[] lines = run(null, "genkey").split("\n");
        if (lines.length < 2 || !WgConf.isKeyHex(lines[0].trim())
                || !WgConf.isKeyHex(lines[1].trim())) {
            throw new IOException("genkey gave no key");
        }
        return new String[] {lines[0].trim(), lines[1].trim()};
    }

    /** The public key to a private one, hex. */
    static String pubkey(String privateHex) throws IOException {
        final String line = run(privateHex + "\n", "pubkey").trim();
        if (!WgConf.isKeyHex(line)) {
            throw new IOException("pubkey gave no key");
        }
        return line;
    }

    private static String run(String input, String command) throws IOException {
        final Process process = new ProcessBuilder(PATH, command).start();
        drain(process.getErrorStream(), command);
        try (OutputStream in = process.getOutputStream()) {
            if (input != null) {
                in.write(input.getBytes(StandardCharsets.US_ASCII));
            }
        }
        try {
            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command + " did not finish");
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new IOException(command + " interrupted");
        }
        // Both key commands produce at most 130 bytes, below the pipe capacity.
        // Wait with a deadline before an EOF read that could otherwise block forever.
        final String out;
        try (InputStream stdout = process.getInputStream()) {
            out = Store.readAll(stdout, 4096);
        }
        if (process.exitValue() != 0) {
            throw new IOException(command + " failed with " + process.exitValue());
        }
        return out;
    }

    /**
     * The helper's own lines, to the log. It says nothing secret, and to be sure nothing
     * long enough to be a key is passed on.
     */
    private static Thread drain(InputStream stream, String what) {
        final Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 200) {
                        line = line.substring(0, 200);
                    }
                    line = line.replaceAll("[A-Za-z0-9+/=]{40,}", "(...)");
                    Log.i(TAG, "r1-tunneld " + what + ": " + line);
                }
            } catch (IOException e) {
                // the helper is gone
            }
        }, "r1-tunneld " + what);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    // The tunnel

    /** A running helper with the interface handed over, and its control socket. */
    static final class Session implements Closeable {
        private final Process mProcess;
        private final LocalSocket mSocket;
        private final InputStream mIn;
        private final OutputStream mOut;
        private final Thread mStdout, mStderr;

        private Session(Process process, LocalSocket socket, Thread stdout, Thread stderr) throws IOException {
            mStdout = stdout;
            mStderr = stderr;
            mProcess = process;
            mSocket = socket;
            mIn = socket.getInputStream();
            mOut = socket.getOutputStream();
        }

        /** Starts the helper and gives it the interface; the session to it, or a Failure. */
        static Session start(FileDescriptor tun) throws IOException {
            final String name = "r1tunnel-" + randomHex(8);
            Process process = null;
            LocalSocket socket = null;
            Thread stdout = null, stderr = null;
            try (LocalServerSocket server = new LocalServerSocket(name)) {
                try {
                    process = new ProcessBuilder(PATH, "wg", "--socket", name).start();
                } catch (IOException e) {
                    throw new Failure(Failure.NO_START, "not started: " + e.getMessage());
                }
                process.getOutputStream().close();
                stdout = drain(process.getInputStream(), "out");
                stderr = drain(process.getErrorStream(), "log");
                waitForConnection(server, process);
                socket = server.accept();
                if (socket.getPeerCredentials().getUid() != android.os.Process.myUid()) {
                    throw new Failure(Failure.STRANGER, "connection from another uid");
                }
                socket.setSoTimeout(REPLY_MS);
                final Session session = new Session(process, socket, stdout, stderr);
                // The interface goes along with this one write, and then no more
                socket.setFileDescriptorsForSend(new FileDescriptor[] {tun});
                session.mOut.write("tun\n".getBytes(StandardCharsets.US_ASCII));
                session.mOut.flush();
                socket.setFileDescriptorsForSend(null);
                final String reply = session.readLine();
                if (!reply.equals("ok")) {
                    throw new Failure(Failure.REFUSED, reply);
                }
                return session;
            } catch (IOException e) {
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ignored) {
                        // closing anyway
                    }
                }
                if (process != null) {
                    process.destroyForcibly();
                    try { process.waitFor(1, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                join(stdout);
                join(stderr);
                if (e instanceof Failure) {
                    throw e;
                }
                throw new Failure(Failure.NO_START, String.valueOf(e.getMessage()));
            }
        }

        /** The helper's connection, five seconds at most, while it runs at all. */
        private static void waitForConnection(LocalServerSocket server, Process process)
                throws IOException {
            final long deadline = SystemClock.elapsedRealtime() + CONNECT_MS;
            final StructPollfd poll = new StructPollfd();
            poll.fd = server.getFileDescriptor();
            poll.events = (short) OsConstants.POLLIN;
            while (true) {
                if (!process.isAlive()) {
                    throw new Failure(Failure.EXITED, "exited with " + process.exitValue());
                }
                final long left = deadline - SystemClock.elapsedRealtime();
                if (left <= 0) {
                    throw new Failure(Failure.NO_START, "no connection in time");
                }
                try {
                    poll.revents = 0;
                    if (Os.poll(new StructPollfd[] {poll}, (int) Math.min(left, 250)) > 0
                            && (poll.revents & OsConstants.POLLIN) != 0) {
                        return;
                    }
                } catch (ErrnoException e) {
                    if (e.errno != OsConstants.EINTR) {
                        throw new Failure(Failure.NO_START, "poll: " + e.getMessage());
                    }
                }
            }
        }

        /** "set", the lines, an empty line; "ok" or why not. */
        synchronized void set(List<String> lines) throws IOException {
            final StringBuilder frame = new StringBuilder("set\n");
            for (String line : lines) {
                frame.append(line).append('\n');
            }
            frame.append('\n');
            expectOk(frame.toString());
        }

        synchronized void command(String command) throws IOException {
            expectOk(command + "\n");
        }

        /** "get": the helper's settings and counters, up to the empty line, keys and all. */
        synchronized String get() throws IOException {
            write("get\n");
            final StringBuilder all = new StringBuilder();
            while (true) {
                final String line = readLine();
                if (line.isEmpty()) {
                    return all.toString();
                }
                if (all.length() == 0 && line.startsWith("err")) {
                    throw new Failure(Failure.REFUSED, line);
                }
                all.append(line).append('\n');
                if (all.length() > 1 << 16) {
                    throw new IOException("get too long");
                }
            }
        }

        private void expectOk(String text) throws IOException {
            write(text);
            final String reply = readLine();
            if (!reply.equals("ok")) {
                throw new Failure(Failure.REFUSED, reply.startsWith("err ")
                        ? reply.substring(4) : reply);
            }
        }

        private void write(String text) throws IOException {
            mOut.write(text.getBytes(StandardCharsets.US_ASCII));
            mOut.flush();
        }

        /** A line without its end; the end of the stream is the helper gone. */
        private String readLine() throws IOException {
            final StringBuilder line = new StringBuilder();
            while (true) {
                final int c = mIn.read();
                if (c < 0) {
                    throw new Failure(Failure.EXITED, "control closed");
                }
                if (c == '\n') {
                    return line.toString();
                }
                if (line.length() > 4096) {
                    throw new IOException("line too long");
                }
                line.append((char) c);
            }
        }

        boolean isAlive() {
            return mProcess.isAlive();
        }

        /** Waits for the helper to end, on the caller's thread */
        int waitFor() throws InterruptedException {
            return mProcess.waitFor();
        }

        /** quit, then the socket closed and the helper made to end if it lingers. */
        @Override
        public void close() {
            try {
                synchronized (this) {
                    mSocket.setSoTimeout(1000);
                    write("quit\n");
                    readLine();
                }
            } catch (IOException e) {
                // gone already
            }
            try {
                mSocket.close();
            } catch (IOException e) {
                // closing anyway
            }
            try {
                if (!mProcess.waitFor(1, TimeUnit.SECONDS)) {
                    mProcess.destroy();
                    if (!mProcess.waitFor(1, TimeUnit.SECONDS)) {
                        mProcess.destroyForcibly();
                        mProcess.waitFor(1, TimeUnit.SECONDS);
                    }
                }
            } catch (InterruptedException e) {
                mProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            join(mStdout);
            join(mStderr);
        }

        boolean hasStopped() {
            return !mProcess.isAlive() && !mStdout.isAlive() && !mStderr.isAlive();
        }
    }

    private static void join(Thread thread) {
        if (thread == null) return;
        try { thread.join(2000); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    private static String randomHex(int bytes) {
        final byte[] random = new byte[bytes];
        new SecureRandom().nextBytes(random);
        final StringBuilder hex = new StringBuilder();
        for (byte b : random) {
            hex.append(String.format("%02x", b & 255));
        }
        return hex.toString();
    }
}
