// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import javax.net.ssl.HttpsURLConnection;

/** Account material stays in requests/storage, never in diagnostics or UI errors. */
final class Mullvad {
    private static final String API = "https://api.mullvad.net";
    static final int MAX_DEVICES = 5;
    // Shared across clients and the unauthenticated relay fetch. No automatic 429/5xx retry.
    private static volatile long notBefore;
    private static volatile Engine.Error cooldownError = Engine.Error.RATE_LIMITED;
    static final class Failure extends IOException {
        final int http;
        final String code;
        final Engine.Error error;
        Failure(int http, String code) {
            this(http, code, errorFor(http, code));
        }
        Failure(int http, String code, Engine.Error error) {
            super(error.name());
            this.http = http;
            this.code = safeCode(code);
            this.error = error;
        }
        boolean isRateLimit() { return error == Engine.Error.RATE_LIMITED; }
    }
    final String account;
    private String mToken;
    Mullvad(String account) { this.account = account; }

    long login() throws IOException {
        if (account == null || !account.matches("[0-9]{16}")) {
            throw new Failure(0, "INVALID_ACCOUNT");
        }
        final JSONObject body = new JSONObject();
        try { body.put("account_number", account); }
        catch (JSONException e) { throw badReply(); }
        mToken = null;
        final JSONObject reply = object(request("POST", "/auth/v1/token", body, null));
        final String token = string(reply, "access_token");
        final long expiry = time(string(reply, "expiry"));
        if (!token.matches("[A-Za-z0-9._~+/-]+={0,2}") || expiry <= System.currentTimeMillis()) {
            throw badReply();
        }
        mToken = token;
        return expiry;
    }
    long paidUntil() throws IOException {
        final long expiry = time(string(object(call("GET", "/accounts/v1/accounts/me", null)),
                "expiry"));
        if (expiry == 0) throw badReply();
        return expiry;
    }
    JSONArray devices() throws IOException {
        final Object value = json(call("GET", "/accounts/v1/devices", null));
        if (!(value instanceof JSONArray)) throw badReply();
        final JSONArray devices = (JSONArray) value;
        for (int i = 0; i < devices.length(); i++) {
            final JSONObject device = devices.optJSONObject(i);
            if (device == null || !validId(string(device, "id"))
                    || WgConf.hexFromBase64(string(device, "pubkey")) == null) throw badReply();
        }
        return devices;
    }
    JSONObject addDevice(String key) throws IOException {
        if (WgConf.hexFromBase64(key) == null) throw new Failure(0, "", Engine.Error.INVALID_CONFIG);
        final JSONObject body = new JSONObject();
        try { body.put("pubkey", key); body.put("hijack_dns", false); }
        catch (JSONException e) { throw badReply(); }
        return object(call("POST", "/accounts/v1/devices", body));
    }
    void removeDevice(String id) throws IOException {
        if (!validId(id)) throw new Failure(0, "", Engine.Error.INVALID_CONFIG);
        try { call("DELETE", "/accounts/v1/devices/" + id, null); }
        catch (Failure f) {
            if (f.http != 404 || !"DEVICE_NOT_FOUND".equals(f.code)) throw f;
        } // Only a structured API not-found confirms cleanup; an HTML 404 does not.
    }
    static boolean validId(String id) { return id != null && id.matches("[A-Za-z0-9-]{1,64}"); }
    static String relays() throws IOException { return request("GET", "/app/v1/relays", null, null); }
    private String call(String method, String path, JSONObject body) throws IOException {
        if (mToken == null) login();
        try { return request(method, path, body, mToken); }
        catch (Failure failure) {
            if (failure.http != 401 || !"INVALID_ACCESS_TOKEN".equals(failure.code)) throw failure;
            login(); // Exactly one refresh; even mutation was rejected before authentication.
            return request(method, path, body, mToken);
        }
    }
    static Engine.Error errorOf(IOException e) {
        return e instanceof Failure ? ((Failure) e).error
                : e instanceof SocketTimeoutException ? Engine.Error.TIMEOUT : Engine.Error.IO;
    }
    private static Engine.Error errorFor(int http, String code) {
        if (http == 429) return Engine.Error.RATE_LIMITED;
        if ("INVALID_ACCOUNT".equals(code)) return Engine.Error.ACCOUNT_INVALID;
        if ("ACCOUNT_EXPIRED".equals(code)) return Engine.Error.ACCOUNT_EXPIRED;
        if ("MAX_DEVICES_REACHED".equals(code)) return Engine.Error.DEVICE_LIMIT;
        return Engine.Error.API;
    }
    private static String safeCode(String code) {
        if ("INVALID_ACCOUNT".equals(code) || "INVALID_ACCESS_TOKEN".equals(code)
                || "MAX_DEVICES_REACHED".equals(code) || "PUBKEY_IN_USE".equals(code)
                || "ACCOUNT_EXPIRED".equals(code) || "DEVICE_NOT_FOUND".equals(code)) return code;
        return "";
    }
    private static String request(String method, String path, JSONObject body, String token)
            throws IOException {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            throw new Failure(0, "", Engine.Error.API);
        }
        checkCooldown();
        final HttpsURLConnection c = (HttpsURLConnection) new URL(API + path).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(10000);
            c.setReadTimeout(20000);
            c.setInstanceFollowRedirects(false);
            c.setUseCaches(false);
            c.setRequestProperty("Accept", "application/json");
            if (token != null) c.setRequestProperty("Authorization", "Bearer " + token);
            if (body != null) {
                final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
            }
            final int status = c.getResponseCode();
            if (status == 429 || status >= 500) {
                cooldown(c.getHeaderField("Retry-After"), status == 429 ? 30 : 5,
                        status == 429 ? Engine.Error.RATE_LIMITED : Engine.Error.API);
                throw new Failure(status, "");
            }
            final boolean delete = "DELETE".equals(method);
            final int expected = delete ? 204 : "POST".equals(method)
                    && path.equals("/accounts/v1/devices") ? 201 : 200;
            final InputStream stream = status >= 400 ? c.getErrorStream() : c.getInputStream();
            String text = "";
            if (stream != null) {
                try (InputStream in = stream) { text = Store.readAll(in, path.equals("/app/v1/relays")
                        ? 16 << 20 : 64 << 10); }
            }
            if (status != expected) {
                String code = "";
                if (status >= 400) {
                    try { code = object(text).optString("code", ""); }
                    catch (IOException ignored) { /* Untrusted text never escapes. */ }
                }
                throw new Failure(status, code);
            }
            final String contentType = c.getContentType();
            if (!delete && (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT)
                    .split(";", 2)[0].trim().equals("application/json"))) throw badReply();
            return text;
        } finally { c.disconnect(); }
    }
    private static synchronized void checkCooldown() throws Failure {
        if (notBefore != 0 && System.nanoTime() - notBefore < 0) {
            throw new Failure(0, "", cooldownError);
        }
    }
    private static synchronized void cooldown(String header, int fallback, Engine.Error error) {
        long seconds = fallback;
        if (header != null) {
            try { seconds = Long.parseLong(header.trim()); }
            catch (NumberFormatException e) {
                try { seconds = (ZonedDateTime.parse(header, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - System.currentTimeMillis() + 999) / 1000; }
                catch (DateTimeParseException ignored) { /* Use bounded default. */ }
            }
        }
        seconds = Math.max(1, Math.min(seconds, 3600));
        final long deadline = System.nanoTime() + seconds * 1000000000L;
        if (notBefore == 0 || deadline - notBefore > 0) {
            cooldownError = error;
            notBefore = deadline;
        }
    }
    static Failure badReply() { return new Failure(0, "", Engine.Error.API); }
    static String string(JSONObject object, String key) throws IOException {
        final Object value = object.opt(key);
        if (!(value instanceof String) || ((String) value).isEmpty()) throw badReply();
        return (String) value;
    }
    private static Object json(String text) throws IOException {
        try {
            final JSONTokener parser = new JSONTokener(text);
            final Object value = parser.nextValue();
            if (parser.nextClean() != 0) throw badReply();
            return value;
        } catch (JSONException e) { throw badReply(); }
    }
    static JSONObject object(String text) throws IOException {
        final Object value = json(text);
        if (!(value instanceof JSONObject)) throw badReply();
        return (JSONObject) value;
    }
    static long time(String iso) {
        if (iso == null || iso.isEmpty()) return 0;
        try { return OffsetDateTime.parse(iso).toInstant().toEpochMilli(); }
        catch (DateTimeParseException e) { return 0; }
    }
}
