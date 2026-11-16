// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.media.MediaScannerConnection;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.nsd.NsdManager;
import android.net.nsd.NsdServiceInfo;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import cat.kenny.r1.webdesk.http.HttpServer;
import cat.kenny.r1.webdesk.http.Json;
import cat.kenny.r1.webdesk.http.Origin;
import cat.kenny.r1.webdesk.http.Pairing;
import cat.kenny.r1.webdesk.http.Request;
import cat.kenny.r1.webdesk.http.Response;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Web desk while it is on: the web server, the code, the one computer it let in and what that
 * computer did. It runs in the foreground with a notification, ends a session the computer
 * walked away from, and turns itself off after an hour with no computer.
 */
public final class DeskService extends Service implements HttpServer.Handler {

    private static final String TAG = "WebDesk";
    static final String HOST = "r1";
    static final String CAP_SCREEN = "screen";
    static final String CAP_FILES = "files";
    static final String CAP_CLIPBOARD = "clipboard";
    private static final String COOKIE = "desk";
    private static final long GONE_MS = 2 * 60_000;
    private static final long OFF_MS = 60 * 60_000;
    private static final int CLIPBOARD_MAX = 256 * 1024;

    /** One line of what the computer did; the same kind again counts up instead of repeating. */
    static final class Did {
        final String kind;
        int count = 1;
        long time;
        String detail;

        Did(String kind, String detail, long time) {
            this.kind = kind;
            this.detail = detail;
            this.time = time;
        }
    }

    private static volatile DeskService sRunning;
    private static Runnable sListener;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Pairing mPairing = new Pairing(new SecureRandom());
    private final HttpServer mServer = new HttpServer(this);
    private final List<Did> mDid = new ArrayList<>();
    private Input mInput;
    private Screen mScreen;
    private SharedPreferences mPrefs;
    private PowerManager.WakeLock mWake;
    private NsdManager.RegistrationListener mNsd;
    private int mPort;
    private volatile long mSeen;
    private long mAlone;
    private boolean mWasPaired;

    static DeskService running() {
        return sRunning;
    }

    /** One listener, the page: called on the main thread when anything it shows changed. */
    static void listen(Runnable listener) {
        sListener = listener;
    }

    static void start(Context context) {
        context.startForegroundService(new Intent(context, DeskService.class));
    }

    static void stop(Context context) {
        context.stopService(new Intent(context, DeskService.class));
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("desk", MODE_PRIVATE);
    }

    static boolean allowed(Context context, String cap) {
        // The clipboard is the one that is off until asked for: it may hold a password
        return prefs(context).getBoolean(cap, !CAP_CLIPBOARD.equals(cap));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mPrefs = prefs(this);
        mInput = new Input(this);
        mScreen = new Screen();
        mWake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webdesk:session");
        mAlone = System.currentTimeMillis();
        final NotificationManager notes = getSystemService(NotificationManager.class);
        notes.createNotificationChannel(new NotificationChannel("desk",
                getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        try {
            // 80 so the address needs no number; an image that keeps low ports for root has 8080
            mPort = mServer.start(null, 80, 8080, 8081);
        } catch (IOException e) {
            Log.w(TAG, "no port to listen on", e);
            stopSelf();
            return;
        }
        advertise();
        sRunning = this;
        mMain.postDelayed(mTick, 15_000);
        changed();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        sRunning = null;
        mMain.removeCallbacks(mTick);
        mPairing.end();
        mServer.stop();
        if (mNsd != null) {
            try {
                getSystemService(NsdManager.class).unregisterService(mNsd);
            } catch (IllegalArgumentException e) {
                // Never got as far as registered
            }
        }
        if (mWake.isHeld()) {
            mWake.release();
        }
        changed();
        super.onDestroy();
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            final long now = System.currentTimeMillis();
            if (mPairing.paired()) {
                if (now - mSeen > GONE_MS) {
                    // The computer went away without saying so: its tab closed, its lid shut
                    disconnect();
                }
            } else if (now - mAlone > OFF_MS) {
                stopSelf();
                return;
            }
            mMain.postDelayed(this, 15_000);
        }
    };

    // ------------------------------------------------------------------ what the page shows

    int port() {
        return mPort;
    }

    String code() {
        return mPairing.code();
    }

    boolean locked() {
        return mPairing.locked(System.currentTimeMillis());
    }

    boolean paired() {
        return mPairing.paired();
    }

    String who() {
        return mPairing.who();
    }

    long since() {
        return mPairing.since();
    }

    List<Did> did() {
        synchronized (mDid) {
            return new ArrayList<>(mDid);
        }
    }

    /** The r1's address on the network a computer would be on, null without one. */
    String address() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        final LinkProperties link = cm.getLinkProperties(cm.getActiveNetwork());
        if (link == null) {
            return null;
        }
        for (LinkAddress address : link.getLinkAddresses()) {
            if (address.getAddress() instanceof Inet4Address
                    && !address.getAddress().isLoopbackAddress()) {
                return address.getAddress().getHostAddress();
            }
        }
        return null;
    }

    /** Ends the computer's session; the next one needs the new code. */
    void disconnect() {
        mPairing.newCode();
        synchronized (mDid) {
            mDid.clear();
        }
        mAlone = System.currentTimeMillis();
        changed();
    }

    private void changed() {
        mMain.post(() -> {
            final boolean paired = sRunning != null && mPairing.paired();
            if (paired != mWasPaired) {
                mWasPaired = paired;
                if (paired && !mWake.isHeld()) {
                    // The network stays up for the computer while the r1's screen is off
                    mWake.acquire();
                } else if (!paired && mWake.isHeld()) {
                    mWake.release();
                }
                if (sRunning != null) {
                    getSystemService(NotificationManager.class).notify(1, note());
                }
            }
            if (sListener != null) {
                sListener.run();
            }
        });
    }

    private void did(String kind, String detail) {
        final long now = System.currentTimeMillis();
        synchronized (mDid) {
            if (!mDid.isEmpty() && mDid.get(0).kind.equals(kind)) {
                final Did last = mDid.get(0);
                // Typing and pointing are one line that stays "right now"; files count up
                if (!kind.equals("typing") && !kind.equals("screen") && now - last.time > 500) {
                    last.count++;
                }
                last.time = now;
                last.detail = detail;
                if (now - mDidShown < 1000) {
                    return;
                }
            } else {
                mDid.removeIf(d -> d.kind.equals(kind));
                mDid.add(0, new Did(kind, detail, now));
                while (mDid.size() > 4) {
                    mDid.remove(mDid.size() - 1);
                }
            }
            mDidShown = now;
        }
        changed();
    }

    private long mDidShown;

    private Notification note() {
        final boolean paired = mPairing.paired();
        return new Notification.Builder(this, "desk")
                .setSmallIcon(paired ? R.drawable.ic_dot : R.drawable.ic_screen)
                .setContentTitle(getString(paired ? R.string.note_connected
                        : R.string.note_waiting))
                .setContentText(getString(R.string.note_open))
                .setColor(0xff2dc653)
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, WebDeskActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .build();
    }

    /** The name "r1.local" on the network, for computers that look names up that way. */
    private void advertise() {
        final String address = address();
        if (address == null) {
            return;
        }
        try {
            final NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("r1 web desk");
            info.setServiceType("_http._tcp");
            info.setPort(mPort);
            info.setHostname(HOST);
            info.setHostAddresses(Collections.singletonList(InetAddress.getByName(address)));
            mNsd = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo service, int error) {
                    Log.w(TAG, "the name was not taken: " + error);
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo service, int error) {
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo service) {
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo service) {
                }
            };
            getSystemService(NsdManager.class).registerService(info,
                    NsdManager.PROTOCOL_DNS_SD, mNsd);
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "no name on the network", e);
            mNsd = null;
        }
    }

    // ------------------------------------------------------------------ the web side

    @Override
    public void handle(Request request, Response response) throws IOException {
        final String path = request.path();
        final boolean get = "GET".equals(request.method());
        final boolean post = "POST".equals(request.method());
        if (get && path.equals("/")) {
            response.header("Content-Security-Policy", "default-src 'self'; img-src 'self' "
                    + "blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' "
                    + "'unsafe-inline'; frame-ancestors 'none'");
            sendAsset(response, "desk.html", "text/html; charset=utf-8");
            return;
        }
        if (get && path.startsWith("/font/")) {
            sendFont(response, path.substring(6));
            return;
        }
        if (!get && !Origin.same(request)) {
            response.status(403);
            response.sendJson("{}");
            return;
        }
        if (post && path.equals("/pair")) {
            pair(request, response);
            return;
        }
        if (!mPairing.valid(request.cookie(COOKIE))) {
            response.status(401);
            response.sendJson("{\"paired\":false}");
            return;
        }
        mSeen = System.currentTimeMillis();
        if (get && path.equals("/state")) {
            state(response);
        } else if (post && path.equals("/bye")) {
            response.sendJson("{}");
            disconnect();
        } else if (path.equals("/screen") || path.equals("/screenshot.png")
                || path.equals("/input")) {
            if (!allowed(this, CAP_SCREEN)) {
                refuse(response);
            } else if (get && path.equals("/screen")) {
                did("watching", null);
                mScreen.stream(response, () -> {
                    mSeen = System.currentTimeMillis();
                    return sRunning == this && mPairing.valid(request.cookie(COOKIE))
                            && allowed(this, CAP_SCREEN);
                });
            } else if (get && path.equals("/screenshot.png")) {
                final byte[] png = mScreen.png();
                if (png == null) {
                    response.status(503);
                    response.sendJson("{}");
                    return;
                }
                response.header("Content-Disposition", "attachment; filename=\"r1-"
                        + android.text.format.DateFormat.format("yyyyMMdd-HHmmss",
                                System.currentTimeMillis()) + ".png\"");
                response.send("image/png", png);
                did("screenshot", null);
            } else if (post) {
                final Map<String, Object> event = Json.parse(request.bodyText(64 * 1024));
                final String kind = mInput.handle(event);
                if (kind != null) {
                    did(kind, null);
                }
                response.sendJson("{}");
            } else {
                response.status(405);
                response.sendJson("{}");
            }
        } else if (path.equals("/upload") || path.equals("/files") || path.equals("/file")) {
            if (!allowed(this, CAP_FILES)) {
                refuse(response);
            } else if (post && path.equals("/upload")) {
                upload(request, response);
            } else if (get && path.equals("/files")) {
                list(request, response);
            } else if (get) {
                download(request, response);
            } else {
                response.status(405);
                response.sendJson("{}");
            }
        } else if (post && path.equals("/clipboard")) {
            if (!allowed(this, CAP_CLIPBOARD)) {
                refuse(response);
                return;
            }
            final Object text = Json.parse(request.bodyText(CLIPBOARD_MAX * 4)).get("text");
            if (text instanceof String s) {
                mMain.post(() -> getSystemService(ClipboardManager.class)
                        .setPrimaryClip(ClipData.newPlainText("Web desk", s)));
                did("clipboard_in", null);
            }
            response.sendJson("{}");
        } else {
            response.status(404);
            response.sendJson("{}");
        }
    }

    private static void refuse(Response response) throws IOException {
        // Switched off on the r1: the page greys that part out
        response.status(403);
        response.sendJson("{\"off\":true}");
    }

    private void pair(Request request, Response response) throws IOException {
        final long now = System.currentTimeMillis();
        final Map<String, Object> out = new LinkedHashMap<>();
        if (mPairing.locked(now)) {
            response.status(429);
            out.put("locked", true);
            response.sendJson(Json.write(out));
            return;
        }
        final Object code = Json.parse(request.bodyText(1024)).get("code");
        final String before = mPairing.code();
        final String token = code instanceof String typed
                ? mPairing.pair(typed.trim(), String.valueOf(request.header("User-Agent")), now)
                : null;
        if (token == null) {
            response.status(403);
            out.put("busy", mPairing.paired());
            out.put("left", mPairing.wrongLeft());
            out.put("locked", mPairing.locked(now));
            response.sendJson(Json.write(out));
            if (!before.equals(mPairing.code()) || mPairing.locked(now)) {
                changed();
                mMain.postDelayed(this::changed, 31_000);
            }
            return;
        }
        mSeen = now;
        response.cookie(COOKIE, token, 24 * 3600);
        response.sendJson("{\"paired\":true}");
        changed();
    }

    private void state(Response response) throws IOException {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("paired", true);
        final Map<String, Object> caps = new LinkedHashMap<>();
        for (String cap : new String[] {CAP_SCREEN, CAP_FILES, CAP_CLIPBOARD}) {
            caps.put(cap, allowed(this, cap));
        }
        out.put("caps", caps);
        if (allowed(this, CAP_CLIPBOARD)) {
            final String text = clipboard();
            if (text != null) {
                out.put("clipboard", text);
            }
        }
        response.sendJson(Json.write(out));
    }

    private String mClipboardSeen;

    private String clipboard() {
        final ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        final CharSequence text = clip.getItemAt(0).coerceToText(this);
        if (text == null) {
            return null;
        }
        final String out = text.length() > CLIPBOARD_MAX
                ? text.subSequence(0, CLIPBOARD_MAX).toString() : text.toString();
        if (!out.equals(mClipboardSeen)) {
            mClipboardSeen = out;
            did("clipboard_out", null);
        }
        return out;
    }

    private void sendAsset(Response response, String name, String type) throws IOException {
        try (InputStream in = getAssets().open(name)) {
            response.send(type, in.readAllBytes());
        }
    }

    private void sendFont(Response response, String which) throws IOException {
        final String name = which.equals("display") ? "Michroma-Regular.ttf"
                : which.equals("text") ? "AtkinsonHyperlegibleNext-VF.ttf" : null;
        for (String dir : new String[] {"/product/fonts/", "/system/fonts/"}) {
            final File file = name == null ? null : new File(dir + name);
            if (file != null && file.isFile()) {
                response.header("Cache-Control", "max-age=86400");
                try (InputStream in = new FileInputStream(file)) {
                    response.send("font/ttf", in, file.length());
                }
                return;
            }
        }
        response.status(404);
        response.sendJson("{}");
    }

    // ------------------------------------------------------------------ files

    private static File root() {
        return Environment.getExternalStorageDirectory();
    }

    /** The file a path from the computer names, null when it would be outside the storage. */
    private static File resolve(String path) throws IOException {
        final File root = root().getCanonicalFile();
        final File file = new File(root, path == null ? "" : path).getCanonicalFile();
        return file.equals(root) || file.getPath().startsWith(root.getPath() + File.separator)
                ? file : null;
    }

    private void upload(Request request, Response response) throws IOException {
        String name = request.query("name");
        name = name == null ? "" : new File(name).getName().replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]",
                "_").trim();
        if (name.isEmpty() || name.startsWith(".")) {
            name = "file" + name;
        }
        final File dir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        dir.mkdirs();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final String ext = dot > 0 ? name.substring(dot) : "";
        File file = new File(dir, name);
        for (int n = 2; file.exists(); n++) {
            file = new File(dir, stem + " (" + n + ")" + ext);
        }
        // Under a name of its own till it is whole: half a file is never taken for the file
        final File part = new File(dir, "." + file.getName() + ".part");
        long size = 0;
        try (InputStream in = request.body(); OutputStream out = new FileOutputStream(part)) {
            final byte[] buffer = new byte[64 * 1024];
            for (int n; (n = in.read(buffer)) > 0; ) {
                out.write(buffer, 0, n);
                size += n;
                mSeen = System.currentTimeMillis();
            }
        } catch (IOException e) {
            part.delete();
            throw e;
        }
        final long expected = request.contentLength();
        if (expected >= 0 && size != expected || !part.renameTo(file)) {
            part.delete();
            response.status(500);
            response.sendJson("{}");
            return;
        }
        MediaScannerConnection.scanFile(this, new String[] {file.getPath()}, null, null);
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", file.getName());
        out.put("size", size);
        response.sendJson(Json.write(out));
        did("received", null);
    }

    private void list(Request request, Response response) throws IOException {
        final File dir = resolve(request.query("path"));
        final File[] files = dir == null || !dir.isDirectory() ? null : dir.listFiles();
        if (files == null) {
            response.status(404);
            response.sendJson("{}");
            return;
        }
        Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory()
                ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        final List<Object> entries = new ArrayList<>();
        for (File file : files) {
            if (file.getName().startsWith(".")) {
                continue;
            }
            final Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("name", file.getName());
            entry.put("dir", file.isDirectory());
            entry.put("size", file.isDirectory() ? 0 : file.length());
            entry.put("time", file.lastModified());
            entries.add(entry);
        }
        final Map<String, Object> out = new LinkedHashMap<>();
        final String rel = dir.getPath().substring(root().getCanonicalPath().length());
        out.put("path", rel.startsWith("/") ? rel.substring(1) : rel);
        out.put("entries", entries);
        response.sendJson(Json.write(out));
    }

    private void download(Request request, Response response) throws IOException {
        final File file = resolve(request.query("path"));
        if (file == null || !file.isFile()) {
            response.status(404);
            response.sendJson("{}");
            return;
        }
        // The name as the header's plain form and its UTF-8 form, quotes and controls taken out
        final String plain = file.getName().replaceAll("[^\\x20-\\x7e]|[\"\\\\]", "_");
        response.header("Content-Disposition", "attachment; filename=\"" + plain
                + "\"; filename*=UTF-8''" + java.net.URLEncoder.encode(file.getName(),
                        java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20"));
        response.allowRanges();
        try (InputStream in = new FileInputStream(file)) {
            response.send("application/octet-stream", in, file.length());
        }
        if (request.header("Range") == null) {
            did("fetched", null);
        }
    }
}
