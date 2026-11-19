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
import cat.kenny.r1.webdesk.http.DeskFiles;
import cat.kenny.r1.webdesk.http.DeskOrigin;
import cat.kenny.r1.webdesk.http.DeskTransfer;
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
import java.util.concurrent.Semaphore;
import java.util.function.BooleanSupplier;

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
    /** How long it waits for a computer before it is off, as the page lets it be set. */
    static final String PREF_OFF_AFTER = "off_after";
    static final int[] OFF_MINUTES = {15, 30, 60, 180};
    /** Whether the r1's screen stays on while a computer is connected. */
    static final String PREF_SCREEN_ON = "screen_on";
    /** Whether the page waits for a press before it starts, instead of on opening. */
    static final String PREF_ASK = "ask_first";
    private static final int CLIPBOARD_MAX = 16 * 1024;
    private static final long FILE_MAX = 256L * 1024 * 1024;
    private static final long UPLOAD_MAX = 512L * 1024 * 1024;
    private static final long FREE_MIN = 256L * 1024 * 1024;
    private static final long TRANSFER_MS = 2 * 60 * 60_000L;

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
    /** The last start found no port to listen on and the service went again at once */
    private static volatile boolean sFailed;
    private static Runnable sListener;

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final Pairing mPairing = new Pairing(new SecureRandom());
    private final HttpServer mServer = new HttpServer(this);
    private final List<Did> mDid = new ArrayList<>();
    private Input mInput;
    private Screen mScreen;
    private SharedPreferences mPrefs;
    private PowerManager.WakeLock mWake;
    /** The screen kept on for the computer, while connected and asked for. */
    private PowerManager.WakeLock mScreenWake;
    private ConnectivityManager.NetworkCallback mNetwork;
    private NsdManager.RegistrationListener mNsd;
    private String mNsdAddress, mWantedAddress;
    private boolean mNsdRemoving;
    private android.net.Network mDefaultNetwork;
    private volatile int mPort;
    private volatile long mSeen;
    private volatile boolean mOn;
    /** The connected computer's address on the network, null when none is */
    private volatile String mFrom;
    private final Semaphore mUploading = new Semaphore(1);
    private long mUploaded;
    private int mUploads;
    private final SharedPreferences.OnSharedPreferenceChangeListener mCapabilities =
            (prefs, key) -> {
                if (CAP_SCREEN.equals(key) && !allowed(this, CAP_SCREEN)) {
                    mInput.cancel();
                }
                if (PREF_SCREEN_ON.equals(key)) {
                    mMain.post(this::keepScreen);
                }
                if (PREF_OFF_AFTER.equals(key)) {
                    // A new choice counts from now: cycling past a shorter one must not turn
                    // the desk off under the finger
                    mAlone = System.currentTimeMillis();
                }
            };
    private volatile long mAlone;
    private boolean mWasPaired;

    static DeskService running() {
        return sRunning;
    }

    /** The last start could not listen on a port: off again, until the next start. */
    static boolean failed() {
        return sFailed;
    }

    /** One listener, the page: called on the main thread when anything it shows changed. */
    static void listen(Runnable listener) {
        sListener = listener;
    }

    static void start(Context context) {
        // Cleared before the start is asked for, so a page waiting on it never reads an old one
        sFailed = false;
        context.startForegroundService(new Intent(context, DeskService.class));
    }

    static void stop(Context context) {
        final DeskService service = sRunning;
        if (service != null) {
            // Revoke now, before Android queues the service's destruction.
            service.mOn = false;
            service.mPairing.end();
            service.mInput.cancel();
            service.mServer.stop();
        }
        context.stopService(new Intent(context, DeskService.class));
    }

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("desk", MODE_PRIVATE);
    }

    static boolean askFirst(Context context) {
        return prefs(context).getBoolean(PREF_ASK, false);
    }

    static boolean screenOn(Context context) {
        return prefs(context).getBoolean(PREF_SCREEN_ON, false);
    }

    /** One of the choices the page offers, an hour unless another was chosen. */
    static int offMinutes(Context context) {
        final int minutes = prefs(context).getInt(PREF_OFF_AFTER, 60);
        for (int one : OFF_MINUTES) {
            if (one == minutes) {
                return minutes;
            }
        }
        return 60;
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
        mPrefs.registerOnSharedPreferenceChangeListener(mCapabilities);
        mWake = getSystemService(PowerManager.class)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "webdesk:session");
        mScreenWake = getSystemService(PowerManager.class).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE,
                "webdesk:screen");
        mAlone = System.currentTimeMillis();
        final NotificationManager notes = getSystemService(NotificationManager.class);
        notes.createNotificationChannel(new NotificationChannel("desk",
                getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        try {
            // 80 so the address needs no number; an image that keeps low ports for root has 8080
            mOn = true;
            mPort = mServer.start(null, 80, 8080, 8081);
        } catch (IOException e) {
            Log.w(TAG, "no port to listen on", e);
            mOn = false;
            sFailed = true;
            // The page waiting for it to start shows that it did not, and Start again
            changed();
            stopSelf();
            return;
        }
        advertise();
        watchNetwork();
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
        mOn = false;
        sRunning = null;
        mPrefs.unregisterOnSharedPreferenceChangeListener(mCapabilities);
        mInput.close();
        mMain.removeCallbacks(mTick);
        mPairing.end();
        mServer.stop();
        if (mNsd != null) {
            try {
                getSystemService(NsdManager.class).unregisterService(mNsd);
            } catch (IllegalArgumentException e) {
                // Never got as far as registered
            }
            mNsd = null;
        }
        if (mWake.isHeld()) {
            mWake.release();
        }
        if (mScreenWake.isHeld()) {
            mScreenWake.release();
        }
        if (mNetwork != null) {
            try {
                getSystemService(ConnectivityManager.class).unregisterNetworkCallback(mNetwork);
            } catch (IllegalArgumentException e) {
                // Never registered
            }
            mNetwork = null;
        }
        changed();
        super.onDestroy();
    }

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            final long now = System.currentTimeMillis();
            final boolean expired;
            synchronized (mPairing) {
                expired = mPairing.paired() && now - mSeen > GONE_MS;
                if (expired) {
                    mPairing.newCode();
                }
            }
            if (expired) {
                disconnected();
            }
            if (!mPairing.paired() && now - mAlone > offMinutes(DeskService.this) * 60_000L) {
                stopSelf();
                return;
            }
            // Retry a failed name registration/unregistration without a busy callback loop.
            advertise(mWantedAddress);
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

    /** The connected computer's address, null without one. */
    String from() {
        return mPairing.paired() ? mFrom : null;
    }

    List<Did> did() {
        synchronized (mDid) {
            final List<Did> copy = new ArrayList<>();
            for (Did did : mDid) {
                final Did item = new Did(did.kind, did.detail, did.time);
                item.count = did.count;
                copy.add(item);
            }
            return copy;
        }
    }

    /** The r1's address on the network a computer would be on, null without one. */
    String address() {
        final ConnectivityManager cm = getSystemService(ConnectivityManager.class);
        final LinkProperties link = cm.getLinkProperties(cm.getActiveNetwork());
        return address(link);
    }

    private static String address(LinkProperties link) {
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
        synchronized (mPairing) {
            mPairing.newCode();
        }
        disconnected();
    }

    private void disconnected() {
        mInput.cancel();
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
            keepScreen();
            if (sListener != null) {
                sListener.run();
            }
        });
    }

    /** On the main thread: the screen held on only while a computer is there and it is asked. */
    private void keepScreen() {
        final boolean want = sRunning == this && mOn && mPairing.paired() && screenOn(this);
        if (want && !mScreenWake.isHeld()) {
            mScreenWake.acquire();
        } else if (!want && mScreenWake.isHeld()) {
            mScreenWake.release();
        }
    }

    /**
     * Wi-Fi that comes or changes while it is on: the page shows the address as it is now, and
     * the name r1.local is taken once there is a network to take it on.
     */
    private void watchNetwork() {
        mNetwork = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(android.net.Network network) {
                mMain.post(() -> {
                    if (!network.equals(mDefaultNetwork)) {
                        mDefaultNetwork = network;
                        // Withdraw the old address until this network supplies its own.
                        advertise(null);
                    }
                    changed();
                });
            }

            @Override
            public void onLinkPropertiesChanged(android.net.Network network,
                    LinkProperties properties) {
                mMain.post(() -> {
                    if (network.equals(mDefaultNetwork)) {
                        advertise(address(properties));
                    }
                    changed();
                });
            }

            @Override
            public void onLost(android.net.Network network) {
                mMain.post(() -> {
                    if (network.equals(mDefaultNetwork)) {
                        mDefaultNetwork = null;
                        advertise(null);
                    }
                    changed();
                });
            }
        };
        try {
            mDefaultNetwork = getSystemService(ConnectivityManager.class).getActiveNetwork();
            getSystemService(ConnectivityManager.class).registerDefaultNetworkCallback(mNetwork,
                    mMain);
        } catch (RuntimeException e) {
            Log.w(TAG, "not told of the network changing", e);
            mNetwork = null;
        }
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
        advertise(address());
    }

    /** Serialise replacement: DNS-SD must retire the old registration before taking the name. */
    private void advertise(String address) {
        mWantedAddress = address;
        if (!mOn) {
            return;
        }
        if (mNsd != null) {
            if (java.util.Objects.equals(address, mNsdAddress) || mNsdRemoving) {
                return;
            }
            mNsdRemoving = true;
            try {
                getSystemService(NsdManager.class).unregisterService(mNsd);
                return;
            } catch (IllegalArgumentException e) {
                // A failed/pending registration may no longer belong to NSD.
                mNsd = null;
                mNsdRemoving = false;
            }
        }
        if (address == null) {
            return;
        }
        try {
            final NsdServiceInfo info = new NsdServiceInfo();
            info.setServiceName("r1 Remote Control");
            info.setServiceType("_http._tcp");
            info.setPort(mPort);
            info.setHostname(HOST);
            info.setHostAddresses(Collections.singletonList(InetAddress.getByName(address)));
            mNsd = new NsdManager.RegistrationListener() {
                @Override
                public void onRegistrationFailed(NsdServiceInfo service, int error) {
                    Log.w(TAG, "the name was not taken: " + error);
                    mMain.post(() -> {
                        if (mNsd == this) {
                            mNsd = null;
                            mNsdAddress = null;
                            mNsdRemoving = false;
                        }
                    });
                }

                @Override
                public void onUnregistrationFailed(NsdServiceInfo service, int error) {
                    Log.w(TAG, "the old name was not released: " + error);
                    mMain.post(() -> {
                        if (mNsd == this) {
                            mNsdRemoving = false;
                        }
                    });
                }

                @Override
                public void onServiceRegistered(NsdServiceInfo service) {
                    mMain.post(() -> {
                        if (mNsd == this) {
                            advertise(mWantedAddress);
                        }
                    });
                }

                @Override
                public void onServiceUnregistered(NsdServiceInfo service) {
                    mMain.post(() -> {
                        if (mNsd == this) {
                            mNsd = null;
                            mNsdAddress = null;
                            mNsdRemoving = false;
                            advertise(mWantedAddress);
                        }
                    });
                }
            };
            mNsdAddress = address;
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
        if (!mOn || !DeskOrigin.allowed(request, mPort, address())) {
            refuse(response);
            return;
        }
        final String path = request.path();
        final boolean get = "GET".equals(request.method());
        final boolean post = "POST".equals(request.method());
        if (get && path.equals("/")) {
            response.header("Connection", "close");
            response.header("Content-Security-Policy", "default-src 'self'; img-src 'self' "
                    + "blob: data:; style-src 'self' 'unsafe-inline'; script-src 'self' "
                    + "'unsafe-inline'; frame-ancestors 'none'");
            sendAsset(response, "desk.html", "text/html; charset=utf-8");
            return;
        }
        if (get && path.startsWith("/font/")) {
            response.header("Connection", "close");
            sendFont(response, path.substring(6));
            return;
        }
        if (post && path.equals("/pair")) {
            pair(request, response);
            return;
        }
        synchronized (mPairing) {
            if (!active(request, null)) {
                response.status(401);
                response.header("Connection", "close");
                response.sendJson("{\"paired\":false}");
                return;
            }
            mSeen = System.currentTimeMillis();
        }
        request.cancelUnless(() -> active(request, null));
        if (get && path.equals("/state")) {
            state(request, response);
        } else if (post && path.equals("/bye")) {
            response.header("Connection", "close");
            response.sendJson("{}");
            disconnect();
        } else if (path.equals("/screen") || path.equals("/screenshot.png")
                || path.equals("/input")) {
            request.cancelUnless(() -> active(request, CAP_SCREEN));
            if (!allowed(this, CAP_SCREEN)) {
                refuse(response);
            } else if (get && path.equals("/screen")) {
                did("watching", null);
                mScreen.stream(response, () -> active(request, CAP_SCREEN));
            } else if (get && path.equals("/screenshot.png")) {
                final byte[] png = mScreen.png();
                if (png == null) {
                    response.status(503);
                    response.sendJson("{}");
                    return;
                }
                if (!active(request, CAP_SCREEN)) {
                    refuse(response);
                    return;
                }
                response.header("Content-Disposition", "attachment; filename=\"r1-"
                        + android.text.format.DateFormat.format("yyyyMMdd-HHmmss",
                                System.currentTimeMillis()) + ".png\"");
                response.send("image/png", png);
                did("screenshot", null);
            } else if (post && path.equals("/input")) {
                final Map<String, Object> event = Json.parse(request.bodyText(64 * 1024));
                final String kind = mInput.handle(event, () -> active(request, CAP_SCREEN));
                if (kind != null) {
                    did(kind, null);
                }
                response.sendJson("{}");
            } else {
                response.status(405);
                response.header("Connection", "close");
                response.sendJson("{}");
            }
        } else if (path.equals("/upload") || path.equals("/files") || path.equals("/file")) {
            request.cancelUnless(() -> active(request, CAP_FILES));
            if (!allowed(this, CAP_FILES)) {
                refuse(response);
            } else if (post && path.equals("/upload")) {
                upload(request, response);
            } else if (get && path.equals("/files")) {
                list(request, response);
            } else if (get && path.equals("/file")) {
                download(request, response);
            } else {
                response.status(405);
                response.header("Connection", "close");
                response.sendJson("{}");
            }
        } else if (post && path.equals("/clipboard")) {
            request.cancelUnless(() -> active(request, CAP_CLIPBOARD));
            if (!allowed(this, CAP_CLIPBOARD)) {
                refuse(response);
                return;
            }
            final Object text = Json.parse(request.bodyText(CLIPBOARD_MAX * 4)).get("text");
            if (text instanceof String s && s.length() <= CLIPBOARD_MAX) {
                mMain.post(() -> {
                    if (active(request, CAP_CLIPBOARD)) {
                        getSystemService(ClipboardManager.class)
                                .setPrimaryClip(ClipData.newPlainText("Remote Control", s));
                    }
                });
                did("clipboard_in", null);
            }
            response.sendJson("{}");
        } else {
            response.status(404);
            response.header("Connection", "close");
            response.sendJson("{}");
        }
    }

    private boolean active(Request request, String cap) {
        return mOn && mPairing.valid(request.cookie(COOKIE))
                && System.currentTimeMillis() - mSeen <= GONE_MS
                && (cap == null || allowed(this, cap));
    }

    private static void refuse(Response response) throws IOException {
        // Switched off on the r1: the page greys that part out
        response.status(403);
        response.header("Connection", "close");
        response.sendJson("{\"off\":true}");
    }

    private void pair(Request request, Response response) throws IOException {
        final long now = System.currentTimeMillis();
        final Map<String, Object> out = new LinkedHashMap<>();
        if (mPairing.locked(now)) {
            response.status(429);
            response.header("Connection", "close");
            out.put("locked", true);
            response.sendJson(Json.write(out));
            return;
        }
        final Object code = Json.parse(request.bodyText(1024)).get("code");
        final String before = mPairing.code();
        final String token;
        synchronized (mPairing) {
            final String typed = code instanceof String value ? value.trim() : "";
            token = mOn ? mPairing.pair(typed, String.valueOf(request.header("User-Agent")),
                    System.currentTimeMillis()) : null;
            if (token != null) {
                mSeen = System.currentTimeMillis();
                mFrom = request.remote() == null ? null : request.remote().getHostAddress();
            }
        }
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
        response.cookie(COOKIE, token, 24 * 3600);
        response.sendJson("{\"paired\":true}");
        changed();
    }

    private void state(Request request, Response response) throws IOException {
        final Map<String, Object> out = new LinkedHashMap<>();
        out.put("paired", true);
        final Map<String, Object> caps = new LinkedHashMap<>();
        for (String cap : new String[] {CAP_SCREEN, CAP_FILES, CAP_CLIPBOARD}) {
            caps.put(cap, allowed(this, cap));
        }
        out.put("caps", caps);
        // How the r1 is turned, in quarter turns: the page turns its drawing of it the same way
        out.put("turn", getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY).getRotation());
        if (allowed(this, CAP_CLIPBOARD)) {
            final String text = clipboard();
            if (text != null) {
                out.put("clipboard", text);
            }
        }
        if (!active(request, null)) {
            refuse(response);
            return;
        }
        if (!allowed(this, CAP_CLIPBOARD)) {
            out.remove("clipboard");
        }
        response.sendJson(Json.write(out));
    }

    private volatile String mClipboardSeen;

    private String clipboard() {
        final ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) {
            return null;
        }
        final CharSequence text = clip.getItemAt(0).getText();
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
        return DeskFiles.resolve(root(), path);
    }

    private void upload(Request request, Response response) throws IOException {
        if (!mUploading.tryAcquire()) {
            response.status(429);
            response.header("Connection", "close");
            response.sendJson("{}");
            return;
        }
        File part = null;
        try {
            final long limit = Math.min(FILE_MAX, UPLOAD_MAX - mUploaded);
            if (++mUploads > 1024 || limit <= 0 || request.contentLength() > limit) {
                response.status(413);
                response.header("Connection", "close");
                response.sendJson("{}");
                return;
            }
            part = DeskFiles.temporary(root(), Environment.DIRECTORY_DOWNLOADS);
            final File temporary = part;
            final BooleanSupplier permission = () -> active(request, CAP_FILES)
                    && temporary.getUsableSpace() >= FREE_MIN;
            long size = 0;
            try (InputStream in = new DeskTransfer(request.body(), permission, limit, TRANSFER_MS);
                    OutputStream out = new FileOutputStream(part)) {
                final byte[] buffer = new byte[64 * 1024];
                for (int n; (n = in.read(buffer)) > 0; ) {
                    // Failed transfers count too, so retries cannot churn the disk without bound.
                    mUploaded += n;
                    out.write(buffer, 0, n);
                    size += n;
                }
            }
            final long expected = request.contentLength();
            if (!permission.getAsBoolean() || expected >= 0 && size != expected) {
                throw new IOException("Upload ended");
            }
            final File file = DeskFiles.publish(part, request.query("name"));
            MediaScannerConnection.scanFile(this, new String[] {file.getPath()}, null, null);
            final Map<String, Object> out = new LinkedHashMap<>();
            out.put("name", file.getName());
            out.put("size", size);
            response.sendJson(Json.write(out));
            did("received", null);
        } finally {
            if (part != null) {
                part.delete();
            }
            mUploading.release();
        }
    }

    private void list(Request request, Response response) throws IOException {
        final File dir = resolve(request.query("path"));
        if (dir == null || !dir.isDirectory()) {
            response.status(404);
            response.sendJson("{}");
            return;
        }
        final List<File> found = new ArrayList<>();
        try (java.nio.file.DirectoryStream<java.nio.file.Path> children =
                java.nio.file.Files.newDirectoryStream(dir.toPath())) {
            for (java.nio.file.Path child : children) {
                if (found.size() == 2048) {
                    response.status(503);
                    response.sendJson("{}");
                    return;
                }
                found.add(child.toFile());
            }
        }
        final File[] files = found.toArray(new File[0]);
        Arrays.sort(files, (a, b) -> a.isDirectory() != b.isDirectory()
                ? (a.isDirectory() ? -1 : 1) : a.getName().compareToIgnoreCase(b.getName()));
        final List<Object> entries = new ArrayList<>();
        for (File file : files) {
            final String path = root().getCanonicalFile().toPath()
                    .relativize(file.toPath()).toString();
            if (file.getName().startsWith(".") || resolve(path) == null) {
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
        if (!active(request, CAP_FILES)) {
            refuse(response);
            return;
        }
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
        try (InputStream in = new DeskTransfer(new FileInputStream(file),
                () -> active(request, CAP_FILES), Long.MAX_VALUE, TRANSFER_MS)) {
            response.send("application/octet-stream", in, file.length());
        }
        if (request.header("Range") == null) {
            did("fetched", null);
        }
    }
}
