// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Owns response framing, including resumable downloads and long lived streams. */
public final class Response {
    private final Request request;
    private final OutputStream out;
    private final Map<String, String[]> headers = new LinkedHashMap<>();
    private final List<String> cookies = new ArrayList<>();
    private int status = 200;
    private boolean committed;
    private boolean ranges;
    private boolean close;
    private boolean finished;
    private ChunkOutput chunks;

    Response(Request request, OutputStream out) {
        this.request = request;
        this.out = out;
        close = !request.keepAlive();
    }

    private void mutable() {
        if (committed) {
            throw new IllegalStateException("Response already sent");
        }
    }

    public void status(int code) {
        mutable();
        if (code < 200 || code > 599) {
            throw new IllegalArgumentException("Invalid status");
        }
        status = code;
    }

    public void header(String name, String value) {
        mutable();
        checkHeader(name, value);
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.equals("content-length") || lower.equals("transfer-encoding")) {
            throw new IllegalArgumentException("Framing is server controlled");
        }
        if (lower.equals("connection")) {
            if (!value.equalsIgnoreCase("close")) {
                throw new IllegalArgumentException("Unsupported connection option");
            }
            close = true;
        }
        headers.put(lower, new String[] {name, value});
    }

    private static void checkHeader(String name, String value) {
        if (name == null || value == null || !Request.token(name)) {
            throw new IllegalArgumentException("Invalid header");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == 127 || c < 32 && c != '\t' || c > 255) {
                throw new IllegalArgumentException("Invalid header");
            }
        }
    }

    public void cookie(String name, String value, int maxAgeSeconds) {
        mutable();
        if (name == null || !Request.token(name) || value == null) {
            throw new IllegalArgumentException("Invalid cookie");
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c == 0x21 || c >= 0x23 && c <= 0x2b || c >= 0x2d && c <= 0x3a
                    || c >= 0x3c && c <= 0x5b || c >= 0x5d && c <= 0x7e)) {
                throw new IllegalArgumentException("Invalid cookie");
            }
        }
        cookies.add(name + "=" + value + "; Max-Age=" + maxAgeSeconds
                + "; HttpOnly; SameSite=Strict; Path=/");
    }

    public void allowRanges() {
        mutable();
        ranges = true;
    }

    public boolean committed() { return committed; }
    boolean closes() { return close; }

    public void send(String contentType, byte[] body) throws IOException {
        send(contentType, new java.io.ByteArrayInputStream(body), body.length);
    }

    public void send(String contentType, String text) throws IOException {
        send(contentType, text.getBytes(StandardCharsets.UTF_8));
    }

    public void sendJson(String json) throws IOException {
        send("application/json; charset=utf-8", json);
    }

    public void send(String contentType, InputStream in, long length) throws IOException {
        mutable();
        if (length < -1) {
            throw new IllegalArgumentException("Invalid length");
        }
        long start = 0;
        long count = length;
        if (ranges && length >= 0) {
            header("Accept-Ranges", "bytes");
            long[] range = range(request.header("range"), length);
            if (range != null) {
                if (range[0] < 0) {
                    status = 416;
                    header("Content-Range", "bytes */" + length);
                    count = 0;
                } else {
                    status = 206;
                    start = range[0];
                    count = range[1] - start + 1;
                    header("Content-Range", "bytes " + start + "-" + range[1] + "/" + length);
                }
            }
        }
        begin(contentType, count);
        if (!hasBody()) {
            finished = true;
            out.flush();
            return;
        }
        while (start > 0) {
            long skipped = in.skip(start);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new IOException("Source ended early");
                }
                skipped = 1;
            }
            start -= skipped;
        }
        byte[] buffer = new byte[16_384];
        OutputStream target = count < 0 ? chunks : out;
        long left = count;
        while (left != 0) {
            int got = in.read(buffer, 0, left < 0 ? buffer.length
                    : (int) Math.min(left, buffer.length));
            if (got < 0) {
                if (left > 0) {
                    throw new IOException("Source ended early");
                }
                break;
            }
            if (got == 0) {
                int one = in.read();
                if (one < 0) {
                    if (left > 0) {
                        throw new IOException("Source ended early");
                    }
                    break;
                }
                target.write(one);
                if (left > 0) {
                    left--;
                }
            } else {
                target.write(buffer, 0, got);
                if (left > 0) {
                    left -= got;
                }
            }
        }
        finish();
    }

    public OutputStream stream(String contentType) throws IOException {
        mutable();
        begin(contentType, -1);
        out.flush();
        if (!hasBody()) {
            return OutputStream.nullOutputStream();
        }
        return chunks;
    }

    private boolean hasBody() {
        return !request.method().equals("HEAD") && status != 204 && status != 304;
    }

    private void begin(String contentType, long length) throws IOException {
        if (contentType != null) {
            header("Content-Type", contentType);
        }
        header("X-Content-Type-Options", "nosniff");
        if (!headers.containsKey("cache-control")) {
            header("Cache-Control", "no-store");
        }
        StringBuilder text = new StringBuilder("HTTP/1.1 ").append(status).append(' ')
                .append(reason(status)).append("\r\n");
        for (String[] entry : headers.values()) {
            text.append(entry[0]).append(": ").append(entry[1]).append("\r\n");
        }
        for (String cookie : cookies) {
            text.append("Set-Cookie: ").append(cookie).append("\r\n");
        }
        if (status != 204 && status != 304) {
            text.append(length >= 0 ? "Content-Length: " + length : "Transfer-Encoding: chunked")
                    .append("\r\n");
        }
        if (close && !headers.containsKey("connection")) {
            text.append("Connection: close\r\n");
        }
        text.append("\r\n");
        committed = true;
        out.write(text.toString().getBytes(StandardCharsets.ISO_8859_1));
        if (length < 0 && hasBody()) {
            chunks = new ChunkOutput(out);
        }
    }

    void finish() throws IOException {
        if (finished) {
            return;
        }
        if (!committed) {
            begin(null, 0);
        }
        if (chunks != null) {
            chunks.close();
        }
        out.flush();
        finished = true;
    }

    private static long[] range(String value, long length) {
        if (value == null || !value.startsWith("bytes=") || value.indexOf(',') >= 0) {
            return null;
        }
        String spec = value.substring(6).trim();
        int dash = spec.indexOf('-');
        if (dash < 0 || dash != spec.lastIndexOf('-')) {
            return null;
        }
        String first = spec.substring(0, dash);
        String last = spec.substring(dash + 1);
        if (!decimal(first) || !decimal(last) || first.isEmpty() && last.isEmpty()) {
            return null;
        }
        try {
            if (first.isEmpty()) {
                long suffix = Long.parseLong(last);
                return suffix == 0 || length == 0 ? new long[] {-1, -1}
                        : new long[] {Math.max(0, length - suffix), length - 1};
            }
            long start = Long.parseLong(first);
            long end = last.isEmpty() ? length - 1 : Long.parseLong(last);
            if (start >= length || end < start) {
                return new long[] {-1, -1};
            }
            return new long[] {start, Math.min(end, length - 1)};
        } catch (NumberFormatException e) {
            return new long[] {-1, -1};
        }
    }

    private static boolean decimal(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 201: return "Created";
            case 204: return "No Content";
            case 206: return "Partial Content";
            case 304: return "Not Modified";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            case 416: return "Range Not Satisfiable";
            case 429: return "Too Many Requests";
            case 500: return "Internal Server Error";
            case 503: return "Service Unavailable";
            default: return "Response";
        }
    }

    /** Flush each chunk so screen frames arrive without another buffered layer. */
    private static final class ChunkOutput extends OutputStream {
        private final OutputStream out;
        private boolean closed;
        ChunkOutput(OutputStream out) { this.out = out; }
        @Override public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, bytes.length);
            if (closed) {
                throw new IOException("Stream closed");
            }
            if (len == 0) {
                return;
            }
            out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes, off, len);
            out.write('\r');
            out.write('\n');
            out.flush();
        }
        @Override public void flush() throws IOException { out.flush(); }
        @Override public void close() throws IOException {
            if (!closed) {
                closed = true;
                out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
            }
        }
    }
}
