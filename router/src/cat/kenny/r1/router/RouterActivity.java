// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.router;

import android.app.Activity;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.TetheredClient;
import android.net.TetheringManager;
import android.net.TrafficStats;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

/**
 * The travel router's page: the route from the network the r1 is on, through the VPN if there is
 * one, to the r1's own network and who has joined it. The r1's own network is the system's
 * hotspot, so it stays on when the page is left and is the same one Settings shows.
 */
public final class RouterActivity extends Activity {

    /** Whether what is passed on may leave through the VPN, the networking module's switch */
    private static final String VPN_UPSTREAMS = "tethering_allow_vpn_upstreams";

    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final List<TetheredClient> mClients = new ArrayList<>();
    private ConnectivityManager mConnectivity;
    private TetheringManager mTethering;
    private WifiManager mWifi;
    /** The hotspot's interface while it is up */
    private String mInterface;
    private boolean mStarting;
    private boolean mFailed;
    private Network mUp;
    private NetworkCapabilities mUpCaps;
    private boolean mShown;

    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            show();
            if (mShown) {
                mMain.postDelayed(this, 2000);
            }
        }
    };

    private final ConnectivityManager.NetworkCallback mUpCallback =
            new ConnectivityManager.NetworkCallback() {
        @Override
        public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) {
            mUp = network;
            mUpCaps = caps;
            show();
        }

        @Override
        public void onLost(Network network) {
            mUp = null;
            mUpCaps = null;
            show();
        }
    };

    private final TetheringManager.TetheringEventCallback mTetherCallback =
            new TetheringManager.TetheringEventCallback() {
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            mInterface = null;
            for (String name : interfaces) {
                // The hotspot's, not a cable's
                if (name.startsWith("ap") || name.startsWith("wlan")
                        || name.startsWith("swlan")) {
                    mInterface = name;
                }
            }
            if (mInterface != null) {
                mStarting = false;
                mFailed = false;
            }
            show();
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            mClients.clear();
            for (TetheredClient client : clients) {
                if (client.getTetheringType() == TetheringManager.TETHERING_WIFI) {
                    mClients.add(client);
                }
            }
            show();
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.router);
        mConnectivity = getSystemService(ConnectivityManager.class);
        mTethering = getSystemService(TetheringManager.class);
        mWifi = getSystemService(WifiManager.class);
        findViewById(R.id.sign_in).setOnClickListener(v -> {
            if (mUp != null) {
                mConnectivity.startCaptivePortalApp(mUp);
            }
        });
        findViewById(R.id.pass_on).setOnClickListener(v -> start());
        findViewById(R.id.turn_off).setOnClickListener(v -> {
            mTethering.stopTethering(TetheringManager.TETHERING_WIFI);
            mStarting = false;
            show();
        });
        findViewById(R.id.vpn).setOnClickListener(v -> {
            Settings.Secure.putInt(getContentResolver(), VPN_UPSTREAMS, throughVpn() ? 0 : 1);
            show();
        });
    }

    private void start() {
        mStarting = true;
        mFailed = false;
        mTethering.startTethering(new TetheringManager.TetheringRequest.Builder(
                TetheringManager.TETHERING_WIFI).setShouldShowEntitlementUi(false).build(),
                getMainExecutor(), new TetheringManager.StartTetheringCallback() {
                    @Override
                    public void onTetheringFailed(int error) {
                        mStarting = false;
                        mFailed = true;
                        show();
                    }
                });
        show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        mShown = true;
        mConnectivity.registerDefaultNetworkCallback(mUpCallback, mMain);
        mTethering.registerTetheringEventCallback(getMainExecutor(), mTetherCallback);
        mMain.post(mTick);
    }

    @Override
    protected void onPause() {
        mShown = false;
        mMain.removeCallbacks(mTick);
        mConnectivity.unregisterNetworkCallback(mUpCallback);
        mTethering.unregisterTetheringEventCallback(mTetherCallback);
        super.onPause();
    }

    private boolean throughVpn() {
        return Settings.Secure.getInt(getContentResolver(), VPN_UPSTREAMS, 0) == 1;
    }

    private boolean vpnUp() {
        for (Network network : mConnectivity.getAllNetworks()) {
            final NetworkCapabilities caps = mConnectivity.getNetworkCapabilities(network);
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                return true;
            }
        }
        return false;
    }

    private void text(int id, CharSequence text) {
        final TextView view = findViewById(id);
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private void visible(int id, boolean visible) {
        findViewById(id).setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    private void show() {
        if (!mShown) {
            return;
        }
        final int ok = getColor(R.color.ok);
        final int warn = getColor(R.color.warn);
        final int accent = accent();
        final int idle = getColor(R.color.surface_line);

        // The network the r1 is on
        final boolean wifi = mUpCaps != null
                && mUpCaps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
        final boolean portal = mUpCaps != null
                && mUpCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL);
        final boolean online = mUpCaps != null && !portal
                && mUpCaps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        String name = getString(R.string.up_none);
        String upState = getString(R.string.up_none_sub);
        if (wifi) {
            final WifiInfo info = mWifi.getConnectionInfo();
            final String ssid = info == null ? null : info.getSSID();
            name = ssid == null || ssid.startsWith("<") ? getString(R.string.up_other)
                    : ssid.replaceAll("^\"|\"$", "");
            final int level = info == null ? 4 : mWifi.calculateSignalLevel(info.getRssi());
            final int max = mWifi.getMaxSignalLevel();
            upState = getString(portal ? R.string.up_sign_in : !online ? R.string.up_offline
                    : level * 3 >= max * 2 ? R.string.up_good
                    : level * 3 >= max ? R.string.up_fair : R.string.up_weak);
        } else if (mUpCaps != null) {
            name = getString(mUpCaps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                    ? R.string.up_mobile : R.string.up_other);
            upState = getString(portal ? R.string.up_sign_in : online ? R.string.up_online
                    : R.string.up_offline);
        }
        final int upColor = online ? ok : mUpCaps != null ? warn : idle;
        text(R.id.up_name, name);
        text(R.id.up_state, upState);
        ((TextView) findViewById(R.id.up_state)).setTextColor(portal ? warn
                : getColor(R.color.text_dim));
        ((ImageView) findViewById(R.id.up_icon)).setColorFilter(upColor);
        final View signIn = findViewById(R.id.sign_in);
        final boolean hadSignIn = signIn.getVisibility() == View.VISIBLE;
        signIn.setVisibility(portal ? View.VISIBLE : View.GONE);
        if (portal && !hadSignIn) {
            signIn.requestFocus();
        }

        // The VPN: a stop on the route only when there is one, or one is to come
        final boolean vpn = vpnUp();
        final boolean through = throughVpn();
        final View vpnStop = findViewById(R.id.vpn);
        vpnStop.setVisibility(vpn || (portal && through) ? View.VISIBLE : View.GONE);
        text(R.id.vpn_state, getString(!vpn ? R.string.vpn_waiting
                : through ? R.string.vpn_through : R.string.vpn_around));
        vpnStop.setFocusable(vpn && !through);
        vpnStop.setClickable(vpn);

        // The r1's own network
        final boolean on = mInterface != null;
        final SoftApConfiguration config = mWifi.getSoftApConfiguration();
        final String ssid = config.getWifiSsid() == null ? ""
                : config.getWifiSsid().toString().replaceAll("^\"|\"$", "");
        final String pass = config.getPassphrase();
        text(R.id.own_name, ssid);
        final int count = mClients.size();
        final String devices = getResources().getQuantityString(portal
                ? R.plurals.devices_waiting : R.plurals.devices, count, count);
        String ownState = getString(mFailed ? R.string.own_failed
                : mStarting ? R.string.own_starting : R.string.own_off);
        if (on) {
            final long bytes = TrafficStats.getRxBytes(mInterface)
                    + TrafficStats.getTxBytes(mInterface);
            ownState = count == 0 ? getString(R.string.own_nobody)
                    : portal || bytes <= 0 ? devices
                    : getString(R.string.own_used, devices,
                            Formatter.formatShortFileSize(this, bytes));
        }
        text(R.id.own_state, ownState);
        text(R.id.own_password, on && pass != null && !pass.isEmpty()
                ? getString(R.string.password, pass) : null);
        ((ImageView) findViewById(R.id.own_icon)).setColorFilter(on ? accent : idle);
        final QrView qr = findViewById(R.id.qr);
        qr.setVisibility(on ? View.VISIBLE : View.GONE);
        if (on) {
            qr.set(join(ssid, pass));
        }

        // Under the route: what this is, the sign-in card, or who has joined
        final ViewGroup list = findViewById(R.id.devices);
        list.removeAllViews();
        text(R.id.card, !on ? getString(R.string.card_off)
                : portal ? getString(R.string.card_sign_in) : null);
        if (on && !portal) {
            for (TetheredClient client : mClients) {
                list.addView(device(client, list));
            }
        }
        final View passOn = findViewById(R.id.pass_on);
        final View turnOff = findViewById(R.id.turn_off);
        final boolean hadFocus = passOn.isFocused() || turnOff.isFocused();
        visible(R.id.pass_on, !on && !mStarting && mUpCaps != null);
        visible(R.id.turn_off, on);
        if (hadFocus || getCurrentFocus() == null) {
            (on ? turnOff : passOn).requestFocus();
        }

        final RouteLine line = findViewById(R.id.line);
        line.post(() -> {
            final List<Float> stops = new ArrayList<>();
            final List<Integer> colors = new ArrayList<>();
            stops.add(middle(R.id.up_icon, line));
            colors.add(upColor);
            if (vpnStop.getVisibility() == View.VISIBLE) {
                stops.add(vpnStop.getTop() + vpnStop.getHeight() / 2f);
                colors.add(on ? accent : idle);
            }
            stops.add(middle(R.id.own_icon, line));
            final float[] ys = new float[stops.size()];
            final int[] cs = new int[stops.size()];
            for (int i = 0; i < ys.length; i++) {
                ys[i] = stops.get(i);
                cs[i] = i < colors.size() ? colors.get(i) : idle;
            }
            // The last stretch always ends at the r1's own network: lit once that is on
            if (cs.length >= 2 && !on) {
                cs[cs.length - 2] = cs.length == 2 ? upColor : idle;
            }
            line.set(ys, cs);
        });
    }

    /** The middle of an icon on the route, in the line's own coordinates. */
    private float middle(int id, View line) {
        final View icon = findViewById(id);
        final int[] at = new int[2];
        final int[] origin = new int[2];
        icon.getLocationInWindow(at);
        line.getLocationInWindow(origin);
        return at[1] - origin[1] + icon.getHeight() / 2f;
    }

    private View device(TetheredClient client, ViewGroup list) {
        String name = null;
        String address = null;
        for (TetheredClient.AddressInfo info : client.getAddresses()) {
            if (name == null && info.getHostname() != null && !info.getHostname().isEmpty()) {
                name = info.getHostname();
            }
            if (address == null && info.getAddress() != null) {
                address = info.getAddress().getAddress().getHostAddress();
            }
        }
        final String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        final int icon = lower.contains("phone") || lower.contains("pixel")
                || lower.contains("galaxy") || lower.contains("android") ? R.drawable.ic_phone
                : lower.contains("book") || lower.contains("laptop") || lower.contains("mac")
                || lower.contains("pc") || lower.contains("desktop") || lower.contains("think")
                ? R.drawable.ic_laptop : R.drawable.ic_device;
        final View row = getLayoutInflater().inflate(R.layout.row, list, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(name == null
                ? getString(R.string.device_unnamed) : name);
        final TextView time = row.findViewById(R.id.time);
        time.setText(address);
        time.setVisibility(address == null ? View.GONE : View.VISIBLE);
        row.setFocusable(true);
        return row;
    }

    /** What a phone's camera reads to join: the Wi-Fi code's own escaping of \ ; , : and ". */
    private static String join(String ssid, String pass) {
        final boolean open = pass == null || pass.isEmpty();
        return "WIFI:T:" + (open ? "nopass" : "WPA") + ";S:" + escape(ssid) + ";"
                + (open ? "" : "P:" + escape(pass) + ";") + ";";
    }

    private static String escape(String text) {
        return text.replaceAll("([\\\;,:\"])", "\\\\$1");
    }

    private int accent() {
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }
}
