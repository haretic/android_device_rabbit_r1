// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk.http;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Parsed metadata and a streaming body that cannot consume the next request. */
public final class Request {
    private final String method;
    private final String path;
    private final Map<String, String> queries = new LinkedHashMap<>();
    private final Map<String, String> headers;
    private final InetAddress remote;
    private final long length;
    private final Body body;
    private final boolean keepAlive;
    private final boolean expect;
    private ConnectionIO io;

    private Request(String method, String target, Map<String, String> headers, Socket socket,
            Body body, long length, boolean keepAlive, boolean expect) throws BadRequest {
        this.method = method;
        this.headers = headers;
        this.remote = socket.getInetAddress();
        this.body = body;
        this.length = length;
        this.keepAlive = keepAlive;
        this.expect = expect;
        int question = target.indexOf('?');
        path = decode(question < 0 ? target : target.substring(0, question), false);
        if (!path.startsWith("/") || path.indexOf('\0') >= 0) {
            throw new BadRequest();
        }
        if (question >= 0) {
            for (String item : target.substring(question + 1).split("&", -1)) {
                int equal = item.indexOf('=');
                String name = decode(equal < 0 ? item : item.substring(0, equal), true);
                String value = decode(equal < 0 ? "" : item.substring(equal + 1), true);
                queries.putIfAbsent(name, value);
            }
        }
    }

    static Request read(Socket socket, InputStream in) throws IOException {
        return read(socket, in, null);
    }

    static Request read(Socket socket, InputStream in, ConnectionIO io) throws IOException {
        if (io != null) {
            io.reset();
        }
        Lines lines = new Lines(in, socket, System.nanoTime() + 15_000_000_000L);
        String first = lines.line(4096, true);
        if (first == null) {
            return null;
        }
        String[] words = first.split(" ", -1);
        if (words.length != 3 || !token(words[0]) || !words[1].startsWith("/")
                || words[1].indexOf('#') >= 0 || !(words[2].equals("HTTP/1.1")
                || words[2].equals("HTTP/1.0"))) {
            throw new BadRequest();
        }
        Map<String, String> headers = new LinkedHashMap<>();
        for (int count = 0; ; count++) {
            String line = lines.line(16_384, false);
            if (line.isEmpty()) {
                break;
            }
            if (count >= 100) {
                throw new BadRequest();
            }
            int colon = line.indexOf(':');
            if (colon <= 0 || !token(line.substring(0, colon))) {
                throw new BadRequest();
            }
            String name = line.substring(0, colon).toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (headers.containsKey(name)) {
                if (name.equals("content-length") || name.equals("transfer-encoding")
                        || name.equals("host") || name.equals("origin")) {
                    throw new BadRequest();
                }
                value = headers.get(name) + (name.equals("cookie") ? "; " : ", ") + value;
            }
            headers.put(name, value);
        }
        socket.setSoTimeout(HttpServer.IDLE_MILLIS);
        if (words[2].equals("HTTP/1.1") && !headers.containsKey("host")) {
            throw new BadRequest();
        }
        String cl = headers.get("content-length");
        String te = headers.get("transfer-encoding");
        long length = -1;
        if (cl != null) {
            if (cl.isEmpty() || cl.length() > 19) {
                throw new BadRequest();
            }
            for (int i = 0; i < cl.length(); i++) {
                if (cl.charAt(i) < '0' || cl.charAt(i) > '9') {
                    throw new BadRequest();
                }
            }
            try {
                length = Long.parseLong(cl);
            } catch (NumberFormatException e) {
                throw new BadRequest();
            }
        }
        if (te != null && (cl != null || !te.equalsIgnoreCase("chunked"))) {
            throw new BadRequest();
        }
        String expectation = headers.get("expect");
        if (expectation != null && !expectation.equalsIgnoreCase("100-continue")) {
            throw new BadRequest(417);
        }
        boolean alive = words[2].equals("HTTP/1.1");
        String connection = headers.get("connection");
        if (hasToken(connection, "close")) {
            alive = false;
        } else if (hasToken(connection, "keep-alive")) {
            alive = true;
        }
        if (io != null) {
            in = io.body(in, length >= 0 && length <= 65_536);
        }
        Body body = te == null ? new FixedBody(in, Math.max(0, length)) : new ChunkBody(in);
        Request request = new Request(words[0], words[1], headers, socket, body, length, alive,
                expectation != null);
        request.io = io;
        return request;
    }

    static boolean hasToken(String value, String token) {
        if (value != null) {
            for (String part : value.split(",")) {
                if (part.trim().equalsIgnoreCase(token)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean token(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9'
                    || "!#$%&'*+-.^_`|~".indexOf(c) >= 0)) {
                return false;
            }
        }
        return true;
    }

    private static String decode(String value, boolean plus) throws BadRequest {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%') {
                if (i + 2 >= value.length()) {
                    throw new BadRequest();
                }
                int a = Character.digit(value.charAt(++i), 16);
                int b = Character.digit(value.charAt(++i), 16);
                if (a < 0 || b < 0) {
                    throw new BadRequest();
                }
                bytes.write(a * 16 + b);
            } else {
                if (c < 33 || c > 126) {
                    throw new BadRequest();
                }
                bytes.write(c == '+' && plus ? ' ' : c);
            }
        }
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        } catch (CharacterCodingException e) {
            throw new BadRequest();
        }
    }

    public String method() { return method; }
    public String path() { return path; }
    public String query(String name) { return queries.get(name); }
    public String header(String name) { return headers.get(name.toLowerCase(Locale.ROOT)); }
    public InetAddress remote() { return remote; }
    public long contentLength() { return length; }
    public InputStream body() { return body; }
    boolean keepAlive() { return keepAlive; }
    boolean expectsContinue() { return expect; }

    /** The watchdog closes blocked I/O when this request loses permission. */
    public void cancelUnless(java.util.function.BooleanSupplier allowed) {
        if (io != null) {
            io.allowed = java.util.Objects.requireNonNull(allowed);
        }
    }

    public String cookie(String name) {
        String value = header("cookie");
        if (value != null) {
            for (String item : value.split(";")) {
                int equal = item.indexOf('=');
                if (equal >= 0 && item.substring(0, equal).trim().equals(name)) {
                    return item.substring(equal + 1).trim();
                }
            }
        }
        return null;
    }

    public String bodyText(int maxBytes) throws IOException {
        if (io != null) {
            io.smallBody();
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("Negative limit");
        }
        if (body instanceof FixedBody && ((FixedBody) body).remaining > maxBytes) {
            throw new IOException("Body too large");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(maxBytes, 8192));
        byte[] buffer = new byte[8192];
        long count = 0;
        int got;
        while ((got = body.read(buffer, 0, (int) Math.min(buffer.length,
                (long) maxBytes - count + 1))) != -1) {
            count += got;
            if (count > maxBytes) {
                throw new IOException("Body too large");
            }
            bytes.write(buffer, 0, got);
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }

    boolean drain() throws IOException {
        long limit = 1_048_576;
        if (body instanceof FixedBody && ((FixedBody) body).remaining > limit) {
            return false;
        }
        byte[] buffer = new byte[8192];
        while (limit >= 0) {
            int got = body.read(buffer, 0, (int) Math.min(buffer.length, limit + 1));
            if (got < 0) {
                return true;
            }
            limit -= got;
        }
        return false;
    }

    /** A protocol error carries only a public status, never parser details. */
    static final class BadRequest extends IOException {
        private static final long serialVersionUID = 1L;
        final int status;
        BadRequest() { this(400); }
        BadRequest(int status) { this.status = status; }
    }

    /** Header deadlines stop trickle traffic from retaining a worker indefinitely. */
    private static final class Lines {
        private final InputStream in;
        private final Socket socket;
        private final long deadline;
        private int total;

        Lines(InputStream in, Socket socket, long deadline) {
            this.in = in;
            this.socket = socket;
            this.deadline = deadline;
        }

        private int next() throws IOException {
            if (socket != null) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new BadRequest(408);
                }
                socket.setSoTimeout((int) Math.max(1, (left + 999_999) / 1_000_000));
            }
            int value = in.read();
            if (++total > 16_384) {
                throw new BadRequest();
            }
            return value;
        }

        String line(int max, boolean eofAllowed) throws IOException {
            StringBuilder line = new StringBuilder();
            while (true) {
                int c = next();
                if (c == -1) {
                    if (eofAllowed && line.length() == 0) {
                        return null;
                    }
                    throw new BadRequest();
                }
                if (c == '\r') {
                    if (next() != '\n') {
                        throw new BadRequest();
                    }
                    return line.toString();
                }
                if (c == '\n' || c == 0 || c == 127 || c < 32 && c != '\t'
                        || line.length() >= max) {
                    throw new BadRequest();
                }
                line.append((char) c);
            }
        }
    }

    /** Closing a body must leave socket ownership with the connection. */
    private abstract static class Body extends InputStream {
        @Override public final int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 255;
        }
        @Override public final void close() { }
    }

    /** Fixed framing prevents an upload reader from consuming a pipelined request. */
    private static final class FixedBody extends Body {
        private final InputStream in;
        private long remaining;
        FixedBody(InputStream in, long length) {
            this.in = in;
            remaining = length;
        }
        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, bytes.length);
            if (len == 0) {
                return 0;
            }
            if (remaining == 0) {
                return -1;
            }
            int got = in.read(bytes, off, (int) Math.min(len, remaining));
            if (got < 0) {
                throw new BadRequest();
            }
            remaining -= got;
            return got;
        }
    }

    /** Chunk metadata has separate bounds so uploads themselves can stay unbounded. */
    private static final class ChunkBody extends Body {
        private final InputStream in;
        private long remaining;
        private boolean separator;
        private boolean done;
        ChunkBody(InputStream in) { this.in = in; }

        @Override public int read(byte[] bytes, int off, int len) throws IOException {
            java.util.Objects.checkFromIndexSize(off, len, bytes.length);
            if (len == 0) {
                return 0;
            }
            if (done) {
                return -1;
            }
            if (remaining == 0) {
                if (separator && (in.read() != '\r' || in.read() != '\n')) {
                    throw new BadRequest();
                }
                Lines lines = new Lines(in, null, 0);
                String line = lines.line(1024, false);
                int semi = line.indexOf(';');
                String digits = semi < 0 ? line : line.substring(0, semi);
                if (digits.isEmpty() || digits.length() > 16) {
                    throw new BadRequest();
                }
                for (int i = 0; i < digits.length(); i++) {
                    int digit = Character.digit(digits.charAt(i), 16);
                    if (digit < 0 || remaining > (Long.MAX_VALUE - digit) / 16) {
                        throw new BadRequest();
                    }
                    remaining = remaining * 16 + digit;
                }
                separator = true;
                if (remaining == 0) {
                    for (int count = 0; ; count++) {
                        String trailer = lines.line(16_384, false);
                        if (trailer.isEmpty()) {
                            break;
                        }
                        int colon = trailer.indexOf(':');
                        if (count >= 100 || colon <= 0 || !token(trailer.substring(0, colon))) {
                            throw new BadRequest();
                        }
                    }
                    done = true;
                    return -1;
                }
            }
            int got = in.read(bytes, off, (int) Math.min(len, remaining));
            if (got < 0) {
                throw new BadRequest();
            }
            remaining -= got;
            return got;
        }
    }
}
