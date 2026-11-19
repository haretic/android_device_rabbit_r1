// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;

import javax.net.ssl.HttpsURLConnection;

/**
 * Mullvad's API, all HTTPS to api.mullvad.net, called off the main thread: a token for the
 * account number, the account's end, its devices, and the server list. The account number and
 * the token go into requests only, never into the log or a message.
 */
final class Mullvad {
    private static final String API = "https://api.mullvad.net";
    /** The most devices an account may have, which the API does not say */
    static final int MAX_DEVICES = 5;

    /** Mullvad said no: its HTTP status and its error code, such as INVALID_ACCOUNT. */
    static final class Failure extends IOException {
        final int http;
        final String code;

        Failure(int http, String code) {
            super("Mullvad " + http + " " + code);
            this.http = http;
            this.code = code;
        }

        boolean isRateLimit() {
            return http == 429;
        }
    }

    final String account;
    private String mToken;

    Mullvad(String account) {
        this.account = account;
    }

    /** Acquire a token; returns its expiry in ms since 1970. Use paidUntil for account time. */
    long login() throws IOException {
        final JSONObject body = new JSONObject();
        try {
            body.put("account_number", account);
        } catch (JSONException e) {
            throw new IOException("request");
        }
        final JSONObject reply = object(request("POST", "/auth/v1/token", body, null));
        final String token = reply.optString("access_token", "");
        if (token.isEmpty()) {
            throw new IOException("Mullvad gave no token");
        }
        mToken = token;
        return time(reply.optString("expiry", ""));
    }

    /** When the account runs out, in ms since 1970, 0 if not said. */
    long paidUntil() throws IOException {
        return time(object(call("GET", "/accounts/v1/accounts/me", null))
                .optString("expiry", ""));
    }

    JSONArray devices() throws IOException {
        final String text = call("GET", "/accounts/v1/devices", null);
        try {
            return new JSONArray(text);
        } catch (JSONException e) {
            throw new IOException("Mullvad's device list was not JSON");
        }
    }

    /** This r1 as a device by its public key: id, name, pubkey, ipv4_address, ipv6_address. */
    JSONObject addDevice(String publicKeyBase64) throws IOException {
        final JSONObject body = new JSONObject();
        try {
            body.put("pubkey", publicKeyBase64);
            body.put("hijack_dns", false);
        } catch (JSONException e) {
            throw new IOException("request");
        }
        return object(call("POST", "/accounts/v1/devices", body));
    }

    void removeDevice(String id) throws IOException {
        if (!id.matches("[A-Za-z0-9-]{1,64}")) {
            throw new IOException("not a device id");
        }
        call("DELETE", "/accounts/v1/devices/" + id, null);
    }

    /** The server list as Mullvad sends it, which needs no account. */
    static String relays() throws IOException {
        return request("GET", "/app/v1/relays", null, null);
    }

    /** With the token, logging in again once when Mullvad says it is no longer good. */
    private String call(String method, String path, JSONObject body) throws IOException {
        if (mToken == null) {
            login();
        }
        try {
            return request(method, path, body, mToken);
        } catch (Failure failure) {
            if (!"INVALID_ACCESS_TOKEN".equals(failure.code)) {
                throw failure;
            }
            login();
            return request(method, path, body, mToken);
        }
    }

    private static String request(String method, String path, JSONObject body, String token)
            throws IOException {
        final HttpsURLConnection connection =
                (HttpsURLConnection) new URL(API + path).openConnection();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(20000);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            if (body != null) {
                final byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(bytes);
                }
            }
            final int status = connection.getResponseCode();
            final InputStream stream = status >= 400 ? connection.getErrorStream()
                    : connection.getInputStream();
            String text = "";
            if (stream != null) {
                try (InputStream in = stream) {
                    text = Store.readAll(in, 16 << 20);
                }
            }
            if (status == 429) {
                throw new Failure(429, "RATE_LIMITED");
            }
            if (status >= 400) {
                String code = "";
                try {
                    code = new JSONObject(text).optString("code", "");
                } catch (JSONException e) {
                    // no code
                }
                throw new Failure(status, code.isEmpty() ? "HTTP_" + status : code);
            }
            return text;
        } finally {
            connection.disconnect();
        }
    }

    private static JSONObject object(String text) throws IOException {
        try {
            return new JSONObject(text);
        } catch (JSONException e) {
            throw new IOException("Mullvad's answer was not JSON");
        }
    }

    /** An ISO 8601 time with its offset, as Mullvad gives them, in ms; 0 if it is none. */
    static long time(String iso) {
        if (iso == null || iso.isEmpty()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(iso).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return 0;
        }
    }
}
