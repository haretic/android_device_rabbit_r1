// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Executors;
import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** A bounded HTTP listener whose sockets can all be cancelled by stop(). */
public final class HttpServer {
    static final int IDLE_MILLIS = 15_000;
    private final Handler handler;
    private final Set<Socket> connections = new HashSet<>();
    private volatile ServerSocket listener;
    private ThreadPoolExecutor workers;
    private Thread acceptor;
    private ScheduledExecutorService timer;
    private final long progressMillis;
    private final long transferMillis;

    HttpServer(Handler handler, long progressMillis, long transferMillis) {
        this.handler = java.util.Objects.requireNonNull(handler);
        this.progressMillis = progressMillis;
        this.transferMillis = transferMillis;
    }

    public HttpServer(Handler handler) {
        this(handler, 30_000, 2 * 60 * 60_000L);
    }

    public synchronized int start(InetAddress bind, int... ports) throws IOException {
        if (running()) {
            throw new IllegalStateException("Already running");
        }
        if (ports.length == 0) {
            throw new IllegalArgumentException("No ports");
        }
        ServerSocket selected = null;
        IOException failure = null;
        for (int port : ports) {
            ServerSocket candidate = new ServerSocket();
            try {
                candidate.setReuseAddress(true);
                candidate.bind(new java.net.InetSocketAddress(bind, port), 16);
                selected = candidate;
                break;
            } catch (IOException e) {
                failure = e;
                candidate.close();
            } catch (RuntimeException e) {
                candidate.close();
                throw e;
            }
        }
        if (selected == null) {
            throw failure;
        }
        workers = new ThreadPoolExecutor(0, 16, 30, TimeUnit.SECONDS,
                new SynchronousQueue<>(), task -> {
                    Thread thread = new Thread(task, "webdesk-connection");
                    thread.setDaemon(true);
                    return thread;
                });
        timer = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "webdesk-deadlines");
            thread.setDaemon(true);
            return thread;
        });
        listener = selected;
        ServerSocket bound = selected;
        ThreadPoolExecutor pool = workers;
        ScheduledExecutorService clock = timer;
        acceptor = new Thread(() -> accept(bound, pool, clock), "webdesk-listener");
        acceptor.setDaemon(true);
        acceptor.start();
        return selected.getLocalPort();
    }

    private void accept(ServerSocket bound, ThreadPoolExecutor pool,
            ScheduledExecutorService clock) {
        while (!bound.isClosed()) {
            Socket socket = null;
            try {
                socket = bound.accept();
                socket.setSoTimeout(IDLE_MILLIS);
                socket.setTcpNoDelay(true);
                synchronized (connections) {
                    if (bound.isClosed() || connections.size() >= 16) {
                        socket.close();
                        continue;
                    }
                    connections.add(socket);
                }
                Socket accepted = socket;
                pool.execute(() -> serve(accepted, clock));
            } catch (IOException | java.util.concurrent.RejectedExecutionException e) {
                if (socket != null) {
                    close(socket);
                    synchronized (connections) {
                        connections.remove(socket);
                    }
                }
            }
        }
    }

    private void serve(Socket socket, ScheduledExecutorService clock) {
        ConnectionIO io = new ConnectionIO(socket, progressMillis, transferMillis);
        java.util.concurrent.ScheduledFuture<?> watch;
        try {
            watch = clock.scheduleAtFixedRate(io::check, 50, 50, TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            close(socket);
            synchronized (connections) {
                connections.remove(socket);
            }
            return;
        }
        try (Socket connection = socket) {
            BufferedInputStream in = new BufferedInputStream(connection.getInputStream(), 8192);
            BufferedOutputStream out = new BufferedOutputStream(
                    io.output(connection.getOutputStream()), 8192);
            while (!connection.isClosed()) {
                Request request;
                try {
                    request = Request.read(connection, in, io);
                    if (request == null) {
                        return;
                    }
                } catch (Request.BadRequest e) {
                    error(out, e.status);
                    return;
                } catch (IOException e) {
                    return;
                }
                if (request.expectsContinue()) {
                    out.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(
                            java.nio.charset.StandardCharsets.US_ASCII));
                    out.flush();
                }
                Response response = new Response(request, out);
                try {
                    handler.handle(request, response);
                    response.finish();
                } catch (Exception e) {
                    if (!response.committed()) {
                        error(out, e instanceof Request.BadRequest ? 400 : 500);
                    }
                    return;
                }
                if (response.closes() || !request.keepAlive() || !request.drain()) {
                    return;
                }
            }
        } catch (IOException e) {
            // Disconnects and stop() both cancel the connection.
        } finally {
            watch.cancel(false);
            synchronized (connections) {
                connections.remove(socket);
            }
        }
    }

    private static void error(java.io.OutputStream out, int code) throws IOException {
        String reason = code == 500 ? "Internal Server Error" : "Bad Request";
        out.write(("HTTP/1.1 " + code + " " + reason + "\r\nContent-Length: 0\r\n"
                + "Connection: close\r\nX-Content-Type-Options: nosniff\r\n"
                + "Cache-Control: no-store\r\n\r\n").getBytes(
                        java.nio.charset.StandardCharsets.US_ASCII));
        out.flush();
    }

    public void stop() {
        Thread thread;
        ThreadPoolExecutor pool;
        synchronized (this) {
            ServerSocket bound = listener;
            listener = null;
            if (bound != null) {
                try {
                    bound.close();
                } catch (IOException e) {
                    // Closing the connections below still releases their workers.
                }
            }
            synchronized (connections) {
                for (Socket socket : connections) {
                    close(socket);
                }
            }
            if (timer != null) {
                timer.shutdownNow();
                timer = null;
            }
            thread = acceptor;
            pool = workers;
            acceptor = null;
            workers = null;
            if (pool != null) {
                pool.shutdownNow();
            }
        }
        try {
            if (thread != null && thread != Thread.currentThread()) {
                thread.join(500);
            }
            if (pool != null) {
                pool.awaitTermination(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void close(Socket socket) {
        try {
            socket.close();
        } catch (IOException e) {
            // Closing is best effort, including sockets already closed by their worker.
        }
    }

    public int port() {
        ServerSocket bound = listener;
        return bound == null ? -1 : bound.getLocalPort();
    }

    public boolean running() {
        ServerSocket bound = listener;
        return bound != null && !bound.isClosed();
    }

    public interface Handler {
        void handle(Request request, Response response) throws IOException;
    }
}
