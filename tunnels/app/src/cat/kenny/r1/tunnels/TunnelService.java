// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnManager;
import android.net.VpnService;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.util.List;
import java.util.HashMap;
import java.util.Map;
import android.net.LinkProperties;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The tunnel. On a start it finds the server, makes the VPN's interface with this app left
 * out of it, so that the helper's own traffic goes around it, starts r1-tunneld and hands it
 * the interface (see Tunneld), gives it the settings and brings it up. While it is up it says
 * when the r1 moves to another network and when the screen comes on, and asks how it is
 * every two seconds while a page shows it.
 *
 * Everything that touches the tunnel runs on one thread, in order. The system starts it as
 * the always-on VPN too, with the network that was on last.
 */
public final class TunnelService extends VpnService {
    private static final String TAG = Store.TAG;
    static final String ACTION_START = "cat.kenny.r1.tunnels.START";
    static final String ACTION_STOP = "cat.kenny.r1.tunnels.STOP";
    static final String EXTRA_ID = "id";
    private static final String CHANNEL = "tunnel";
    private static final int NOTE = 1;
    /** While it fails and is to stay connected: tried again this often */
    private static final long RETRY_S = 30;

    private final ScheduledExecutorService mWork = Executors.newSingleThreadScheduledExecutor();

    // All of these on mWork only
    private Net mNet;
    private Engine mEngine;
    private Engine.Snapshot mLastEngine;
    private int mGeneration;
    private ScheduledFuture<?> mPoll;
    private ScheduledFuture<?> mRetry;
    private int mPolls;
    private Engine.SuspendClock mSuspendClock;
    private volatile Network mUnderlying;
    private ConnectivityManager mConnectivity;
    private ConnectivityManager.NetworkCallback mCallback;
    private BroadcastReceiver mScreen;

    public static void start(Context context, String id) {
        context.startService(new Intent(context, TunnelService.class).setAction(ACTION_START)
                .putExtra(EXTRA_ID, id));
    }

    public static void stop(Context context) {
        context.startService(new Intent(context, TunnelService.class).setAction(ACTION_STOP));
    }

    /** Completion of an asynchronous removal must not stop a newer selected profile. */
    static void stop(Context context, String id) {
        context.startService(new Intent(context, TunnelService.class).setAction(ACTION_STOP)
                .putExtra(EXTRA_ID, id));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mConnectivity = getSystemService(ConnectivityManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final String action = intent != null ? intent.getAction() : null;
        if (ACTION_STOP.equals(action)) {
            final String stoppingId = intent.getStringExtra(EXTRA_ID);
            post(() -> {
                if (stoppingId != null) {
                    String currentId = mNet != null ? mNet.id : Store.active(this);
                    if (currentId != null && !stoppingId.equals(currentId)) return;
                }
                // Lockdown first, or nothing would leave the r1 once the tunnel is down
                applyAlwaysOn(this, false);
                Store.setActive(this, null);
                cancelRetry();
                if (!teardown()) return;
                Status.set(new Status.Snapshot());
                finish();
            });
            return START_NOT_STICKY;
        }
        final boolean bySystem = !ACTION_START.equals(action);
        final String requestedId = bySystem ? null : intent.getStringExtra(EXTRA_ID);
        post(() -> {
            String id = bySystem ? Store.active(this) : requestedId;
            if (id == null && bySystem) {
                final List<Net> nets = Store.list(this);
                id = nets.isEmpty() ? null : nets.get(0).id;
            }
            if (id == null) {
                if (mEngine == null) finish();
                return;
            }
            connect(id, bySystem);
        });
        return START_NOT_STICKY;
    }

    @Override
    public void onRevoke() {
        // Another VPN app took over, or the VPN was turned off in Settings
        post(() -> {
            final String id = mNet != null ? mNet.id : null;
            Engine.Update revoked = mLastEngine == null ? new Engine.Update() : mLastEngine.copy();
            revoked.state = Engine.State.FAILED; revoked.error = Engine.Error.REVOKED;
            mLastEngine = revoked.build();
            cancelRetry();
            if (!teardown()) return;
            Store.setActive(this, null);
            Status.set(snapshot(Status.FAILED, id, Engine.Error.REVOKED));
            finish();
        });
    }

    @Override
    public void onDestroy() {
        post(() -> {
            cancelRetry();
            teardown();
        });
        mWork.shutdown();
        super.onDestroy();
    }

    /** Onto the tunnel's thread, unless the service is already gone */
    private void post(Runnable work) {
        try {
            mWork.execute(work);
        } catch (RejectedExecutionException e) {
            Log.w(TAG, "Too late for the tunnel's thread");
        }
    }

    // Connecting

    private void connect(String id, boolean bySystem) {
        if (bySystem && mEngine != null && mNet != null && id.equals(mNet.id)) {
            return;
        }
        cancelRetry();
        if (!teardown()) return;
        mLastEngine = null;
        final Net net = Store.load(this, id);
        if (net == null) {
            Engine.Update missing = new Engine.Update();
            missing.state = Engine.State.FAILED; missing.error = Engine.Error.MISSING_PROFILE;
            mLastEngine = missing.build();
            Status.set(snapshot(Status.FAILED, id, Engine.Error.MISSING_PROFILE));
            finish();
            return;
        }
        mNet = net;
        Store.setActive(this, id);
        final int generation = ++mGeneration;
        applyAlwaysOn(this, net.stay);
        Engine.Update starting = new Engine.Update(); starting.state = Engine.State.STARTING;
        starting.networkName = net.name; mLastEngine = starting.build();
        Status.set(snapshot(Status.CONNECTING, id, Engine.Error.NONE));
        foreground(getString(R.string.notif_connecting, net.name));
        mUnderlying = mConnectivity.getActiveNetwork();
        try {
            switch (net.kind) {
                case Net.WG: case Net.MULLVAD: mEngine = new EngineWireGuard(); break;
                case Net.ZEROTIER: mEngine = new cat.kenny.r1.tunnels.zt.ZeroTierEngine(); break;
                case Net.TAILSCALE: mEngine = new cat.kenny.r1.tunnels.ts.TailscaleEngine(); break;
                default: throw new Engine.Failure(Engine.Error.INVALID_CONFIG);
            }
            final Engine engine = mEngine;
            EngineAccess.claim(engine);
            engine.start(net, new Engine.BuilderFactory() {
                @Override public Builder create() throws Engine.Failure { return commonBuilder(net); }
                @Override public Context context() { return TunnelService.this.getApplicationContext(); }
                @Override public Network underlyingNetwork() { return mUnderlying; }
                @Override public boolean protect(int fd) { return TunnelService.this.protect(fd); }
                @Override public void saveOptions(String server, String exit, boolean routes,
                        boolean dns) throws Engine.Failure {
                    saveOptions(server, exit, null, routes, dns);
                }
                @Override public void saveOptions(String server, String exit, String name,
                        boolean routes, boolean dns) throws Engine.Failure {
                    synchronized (Store.class) {
                        Net latest = Store.load(TunnelService.this, id);
                        if (latest == null) throw new Engine.Failure(Engine.Error.STORAGE);
                        if (!exit.equals(latest.exitNodeId)) latest.exitNodeName = "";
                        if (name != null) latest.exitNodeName = exit.isEmpty() ? "" : name;
                        latest.controlUrl = server; latest.exitNodeId = exit;
                        latest.acceptRoutes = routes; latest.acceptDns = dns;
                        if (!Store.save(TunnelService.this, latest))
                            throw new Engine.Failure(Engine.Error.STORAGE);
                    }
                }
                @Override public void publish(Engine.Snapshot state) {
                    post(() -> {
                        if (generation == mGeneration && engine == mEngine) engineStatus(generation, state);
                    });
                }
            });
            engineStatus(generation, engine.snapshot());
        } catch (Engine.Failure e) {
            failed(generation, e.error);
            return;
        } catch (RuntimeException | LinkageError e) {
            failed(generation, Engine.Error.LIBRARY_UNAVAILABLE);
            return;
        }
        if (generation != mGeneration || mEngine == null) return;
        listen(generation);
        mPolls = 0;
        mPoll = mWork.scheduleWithFixedDelay(() -> poll(generation), 2, 2, TimeUnit.SECONDS);
    }

    /** Fresh builder; engines supply addresses, routes, DNS and MTU when they know them. */
    private Builder commonBuilder(Net net) throws Engine.Failure {
        final Builder builder = new Builder().setSession(net.name).setMetered(false);
        try { builder.addDisallowedApplication(getPackageName()); }
        catch (PackageManager.NameNotFoundException e) { throw new Engine.Failure(Engine.Error.SETTINGS); }
        Network network = mUnderlying;
        if (network != null) builder.setUnderlyingNetworks(new Network[] {network});
        builder.setConfigureIntent(PendingIntent.getActivity(this, 0,
                new Intent(this, HomeActivity.class), PendingIntent.FLAG_IMMUTABLE));
        return builder;
    }

    private void engineStatus(int generation, Engine.Snapshot engine) {
        if (generation != mGeneration || mNet == null) return;
        mLastEngine = engine;
        if (engine.state == Engine.State.STOPPED) {
            // A leave or an unsolicited backend stop is terminal. There is no tun left
            // to poll or reconnect: in particular, release always-on lockdown first.
            final String id = mNet.id;
            cancelRetry();
            applyAlwaysOn(this, false);
            Store.setActive(this, null);
            if (!teardown()) return;
            Status.Snapshot off = snapshot(Status.OFF, id, engine.error);
            off.engine = engine;
            Status.set(off);
            finish();
            return;
        }
        if (engine.state == Engine.State.FAILED) {
            failed(generation, engine.error);
            return;
        }
        Status.Snapshot was = Status.get();
        int state = engine.state == Engine.State.RUNNING ? Status.ON : Status.CONNECTING;
        Status.Snapshot now = snapshot(state, mNet.id, Engine.Error.NONE);
        now.engine = engine;
        now.place = engine.place;
        if (!engine.addresses.isEmpty()) {
            String address = engine.addresses.get(0);
            int slash = address.indexOf('/');
            now.address = slash < 0 ? address : address.substring(0, slash);
        }
        now.upSince = was.upSince;
        if (state == Status.ON && was.state != Status.ON) now.upSince = SystemClock.elapsedRealtime();
        now.handshake = engine.handshake; now.rx = engine.rx; now.tx = engine.tx; now.stalled = engine.stalled;
        Status.set(now);
        foreground(getString(state == Status.ON ? R.string.notif_on : R.string.notif_connecting, mNet.name));
    }

    private void failed(int generation, Engine.Error error) {
        if (generation != mGeneration) return;
        // Typed error codes only: backend text can contain login URLs, keys or profile data.
        Log.w(TAG, "Engine failed: " + error.name());
        Engine.Update state = mLastEngine == null ? new Engine.Update() : mLastEngine.copy();
        state.state = Engine.State.FAILED; state.error = error; mLastEngine = state.build();
        final Net net = mNet != null ? Store.load(this, mNet.id) : null;
        Engine.Snapshot engineState = mLastEngine;
        if (!teardown()) return;
        Status.Snapshot failure = snapshot(Status.FAILED, net != null ? net.id : null, error);
        failure.engine = engineState;
        Status.set(failure);
        if (net != null && net.stay) {
            // Stay connected: again in a while, as long as it is the one to be on
            mRetry = mWork.schedule(() -> {
                mRetry = null;
                final Net latest = Store.load(this, net.id);
                if (latest != null && latest.stay && net.id.equals(Store.active(this))) {
                    connect(net.id, false);
                } else {
                    finish();
                }
            }, RETRY_S, TimeUnit.SECONDS);
            return;
        }
        finish();
    }

    private void cancelRetry() {
        if (mRetry != null) {
            mRetry.cancel(false);
            mRetry = null;
        }
    }

    // While it is up

    private static LinkProperties copyLinks(LinkProperties properties) {
        android.os.Parcel parcel = android.os.Parcel.obtain();
        try {
            properties.writeToParcel(parcel, 0);
            parcel.setDataPosition(0);
            return LinkProperties.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private void listen(int generation) {
        mSuspendClock = new Engine.SuspendClock(SystemClock.elapsedRealtime(), SystemClock.uptimeMillis());
        // registerDefaultNetworkCallback sends an initial capabilities/link pair even
        // when nothing changed. Seed this network's baseline before registering.
        final Map<Network, NetworkCapabilities> capabilities = new HashMap<>();
        final Map<Network, LinkProperties> links = new HashMap<>();
        if (mUnderlying != null) {
            NetworkCapabilities caps = mConnectivity.getNetworkCapabilities(mUnderlying);
            LinkProperties properties = mConnectivity.getLinkProperties(mUnderlying);
            if (caps != null) capabilities.put(mUnderlying, new NetworkCapabilities(caps));
            if (properties != null) links.put(mUnderlying, copyLinks(properties));
        }
        mCallback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
                if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return;
                final NetworkCapabilities copy = new NetworkCapabilities(caps);
                post(() -> {
                    if (generation != mGeneration || mEngine == null) return;
                    NetworkCapabilities previous = capabilities.put(network, copy);
                    if (!network.equals(mUnderlying) || previous != null && !previous.equals(copy)) {
                        underlying(network, generation);
                    }
                });
            }

            @Override
            public void onLinkPropertiesChanged(Network network, LinkProperties properties) {
                final LinkProperties copy = copyLinks(properties);
                post(() -> {
                    if (generation != mGeneration || mEngine == null) return;
                    LinkProperties previous = links.put(network, copy);
                    if (previous == null || previous.equals(copy) || !network.equals(mUnderlying)) return;
                    try { mEngine.onLinkPropertiesChanged(network); }
                    catch (Engine.Failure e) { failed(generation, e.error); }
                });
            }

            @Override
            public void onLost(Network network) {
                post(() -> {
                    if (generation != mGeneration) return;
                    capabilities.remove(network);
                    links.remove(network);
                    if (network.equals(mUnderlying)) {
                        mUnderlying = null;
                        setUnderlyingNetworks(null);
                        notifyUnderlying(null, generation);
                    }
                });
            }
        };
        try {
            // This app's own default network, which is never the VPN as it is left out of it
            mConnectivity.registerDefaultNetworkCallback(mCallback);
        } catch (RuntimeException e) {
            Log.w(TAG, "No network callback: " + e.getClass().getSimpleName());
            mCallback = null;
        }
        mScreen = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                post(() -> send(generation, "wake"));
            }
        };
        registerReceiver(mScreen, new IntentFilter(Intent.ACTION_SCREEN_ON),
                Context.RECEIVER_NOT_EXPORTED);
    }

    private void underlying(Network network, int generation) {
        if (generation != mGeneration) return;
        if (network.equals(mUnderlying)) {
            // Only a changed capabilities snapshot reaches here for the same Network.
            send(generation, "wake");
            return;
        }
        mUnderlying = network;
        setUnderlyingNetworks(new Network[] {network});
        notifyUnderlying(network, generation);
    }

    private void notifyUnderlying(Network network, int generation) {
        if (generation != mGeneration || mEngine == null) return;
        try { mEngine.onUnderlyingNetwork(network); }
        catch (Engine.Failure e) { failed(generation, e.error); }
    }

    private void send(int generation, String command) {
        if (generation != mGeneration || mEngine == null) return;
        try { mEngine.wake(); }
        catch (Engine.Failure e) { failed(generation, e.error); }
    }

    /** Same visibility-dependent poll cadence as the WireGuard service before the seam. */
    private void poll(int generation) {
        if (generation != mGeneration || mEngine == null || mNet == null) return;
        // No extra timer, alarm or wakelock: this already-scheduled task runs once the CPU
        // resumes, including a quiet screen-off resume with no connectivity callback.
        if (mSuspendClock != null && mSuspendClock.resumed(
                SystemClock.elapsedRealtime(), SystemClock.uptimeMillis())) {
            send(generation, "wake");
            if (generation != mGeneration || mEngine == null) return;
        }
        mPolls++;
        if (!Status.watched() && mPolls % 15 != 0) return;
        try { engineStatus(generation, mEngine.snapshot()); }
        catch (Engine.Failure e) { failed(generation, e.error); }
    }

    // Down

    private boolean teardown() {
        mGeneration++;
        if (mPoll != null) {
            mPoll.cancel(false);
            mPoll = null;
        }
        if (mCallback != null) {
            try {
                mConnectivity.unregisterNetworkCallback(mCallback);
            } catch (RuntimeException e) {
                // was not registered
            }
            mCallback = null;
        }
        if (mScreen != null) {
            try {
                unregisterReceiver(mScreen);
            } catch (RuntimeException e) {
                // was not registered
            }
            mScreen = null;
        }
        // Recover an engine retained after a previous Service instance could not stop it.
        if (mEngine == null) mEngine = EngineAccess.current();
        if (mEngine != null) {
            try { mEngine.stop(); }
            catch (Engine.Failure e) {
                Status.Snapshot failure = snapshot(Status.FAILED, mNet == null ? null : mNet.id,
                        e.error);
                Engine.Update state = new Engine.Update();
                state.state = Engine.State.FAILED; state.error = e.error;
                failure.engine = state.build(); Status.set(failure);
                // Keep ownership: a successor must never start while this engine still owns tun.
                return false;
            }
            EngineAccess.release(mEngine);
            mEngine = null;
        }
        mUnderlying = null;
        return true;
    }

    /** Nothing up and nothing to try again: out of the foreground and gone. */
    private void finish() {
        if (mRetry != null) {
            return;
        }
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    private Status.Snapshot snapshot(int state, String id, Engine.Error error) {
        final Status.Snapshot s = new Status.Snapshot();
        s.state = state;
        s.netId = id;
        s.error = error;
        s.engine = mLastEngine;
        if (mNet != null && mNet.id.equals(id) && !mNet.addresses.isEmpty()) {
            final String first = mNet.addresses.get(0);
            final int slash = first.indexOf('/');
            s.address = slash > 0 ? first.substring(0, slash) : first;
        }
        return s;
    }

    private void foreground(String text) {
        final NotificationManager notes = getSystemService(NotificationManager.class);
        notes.createNotificationChannel(new NotificationChannel(CHANNEL,
                getString(R.string.channel), NotificationManager.IMPORTANCE_LOW));
        final Notification note = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_key)
                .setContentTitle(text)
                .setOngoing(true)
                .setShowWhen(false)
                .setContentIntent(PendingIntent.getActivity(this, 0,
                        new Intent(this, HomeActivity.class), PendingIntent.FLAG_IMMUTABLE))
                .build();
        try {
            startForeground(NOTE, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SYSTEM_EXEMPTED);
        } catch (RuntimeException e) {
            // The system's own binding keeps an established VPN in the foreground regardless
            Log.w(TAG, "Not in the foreground: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Stay connected, as the system's always-on VPN with lockdown: this app, or no longer it.
     * Platform-signed, the app holds CONTROL_ALWAYS_ON_VPN and CONTROL_VPN, which
     * VpnManagerService.setAlwaysOnVpnPackage and Vpn.setAlwaysOnPackage check.
     */
    static void applyAlwaysOn(Context context, boolean stay) {
        final VpnManager vpn = context.getSystemService(VpnManager.class);
        final int user = UserHandle.myUserId();
        final String me = context.getPackageName();
        try {
            final String now = vpn.getAlwaysOnVpnPackageForUser(user);
            if (stay) {
                if (!me.equals(now) || !vpn.isVpnLockdownEnabled(user)) {
                    if (!vpn.setAlwaysOnVpnPackageForUser(user, me, true, null)) {
                        Log.w(TAG, "Always-on VPN refused");
                    }
                }
            } else if (me.equals(now)) {
                vpn.setAlwaysOnVpnPackageForUser(user, null, false, null);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "Always-on VPN not set: " + e.getClass().getSimpleName());
        }
    }
}
