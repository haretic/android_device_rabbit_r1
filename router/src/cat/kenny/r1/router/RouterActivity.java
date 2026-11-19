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
    /** The focus was put where the page starts: on the next step, not on a switch */
    private boolean mFocusPlaced;
    private Sessions mSessions;
    /** The stay-on row, for the hint */
    private View mStay;

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
        // Who joins and when, also while this page is closed
        mSessions = Sessions.get(this);
        name();
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
        findViewById(R.id.own).setOnClickListener(v -> change());
        // The code, big: from its button, or the small code tapped
        findViewById(R.id.show_code).setOnClickListener(v -> bigCode());
        findViewById(R.id.qr).setOnClickListener(v -> bigCode());
        stayRow();
        findViewById(R.id.vpn).setOnClickListener(v -> {
            Settings.Secure.putInt(getContentResolver(), VPN_UPSTREAMS, throughVpn() ? 0 : 1);
            show();
        });
        // The hint follows the wheel: what a press does on what it is on
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener((was, now) -> hint());
        hint();
    }

    /** Along the bottom, what the wheel and a press do here, for where the focus is. */
    private void hint() {
        final View focused = getCurrentFocus();
        final int id = focused == null ? View.NO_ID : focused.getId();
        final int text = id == R.id.pass_on ? R.string.hint_pass_on
                : id == R.id.turn_off ? R.string.hint_turn_off
                : id == R.id.show_code ? R.string.hint_show_code
                : id == R.id.own ? R.string.hint_own
                : id == R.id.vpn ? R.string.hint_vpn
                : id == R.id.sign_in ? R.string.hint_sign_in
                : focused != null && focused == mStay ? R.string.hint_stay
                : focused != null && focused.getParent() == findViewById(R.id.devices)
                        ? R.string.hint_device
                : R.string.hint_page;
        ((TextView) findViewById(R.id.hint)).setText(text);
    }

    /**
     * An r1 that still has the stock name for its own network gets the r1's: "burrow" and the
     * four digits it had. A name the user chose is left alone.
     */
    private void name() {
        final SoftApConfiguration config = mWifi.getSoftApConfiguration();
        final String ssid = config.getWifiSsid() == null ? ""
                : config.getWifiSsid().toString().replaceAll("^\"|\"$", "");
        if (ssid.startsWith("AndroidAP_")) {
            mWifi.setSoftApConfiguration(new SoftApConfiguration.Builder(config)
                    .setSsid("burrow_" + ssid.substring("AndroidAP_".length())).build());
        }
    }

    private TextView label(int text) {
        final TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(13);
        label.setTextColor(getColor(R.color.text_faint));
        label.setPadding((int) (4 * getResources().getDisplayMetrics().density),
                (int) (8 * getResources().getDisplayMetrics().density), 0, 0);
        return label;
    }

    /** The name and password of the r1's own network, the user's to choose. */
    private void change() {
        final SoftApConfiguration config = mWifi.getSoftApConfiguration();
        final String ssid = config.getWifiSsid() == null ? ""
                : config.getWifiSsid().toString().replaceAll("^\"|\"$", "");
        final float dp = getResources().getDisplayMetrics().density;
        final android.widget.LinearLayout fields = new android.widget.LinearLayout(this);
        fields.setOrientation(android.widget.LinearLayout.VERTICAL);
        fields.setPadding((int) (22 * dp), (int) (6 * dp), (int) (22 * dp), 0);
        // Named above them: a field that is filled in shows no hint
        fields.addView(label(R.string.change_name));
        final android.widget.EditText name = new android.widget.EditText(this);
        name.setHint(R.string.change_name);
        name.setSingleLine(true);
        name.setText(ssid);
        name.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        fields.addView(name);
        fields.addView(label(R.string.change_password));
        final android.widget.EditText password = new android.widget.EditText(this);
        password.setHint(R.string.change_password);
        password.setSingleLine(true);
        password.setText(config.getPassphrase());
        // Shown, as it is on this page anyway for those who join
        password.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        fields.addView(password);
        final android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this)
                .setTitle(R.string.change_title).setView(fields)
                .setPositiveButton(R.string.change_save, null)
                .setNegativeButton(R.string.change_cancel, null).create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    final String newName = name.getText().toString().trim();
                    final String newPassword = password.getText().toString();
                    final int bytes = newName.getBytes(
                            java.nio.charset.StandardCharsets.UTF_8).length;
                    if (bytes < 1 || bytes > 32) {
                        name.setError(getString(R.string.change_name_bad));
                        return;
                    }
                    if (newPassword.length() < 8 || newPassword.length() > 63
                            || !newPassword.chars().allMatch(c -> c >= 32 && c < 127)) {
                        password.setError(getString(R.string.change_password_bad));
                        return;
                    }
                    final boolean on = mInterface != null;
                    mWifi.setSoftApConfiguration(new SoftApConfiguration.Builder(config)
                            .setSsid(newName).setPassphrase(newPassword,
                                    config.getSecurityType()
                                            == SoftApConfiguration.SECURITY_TYPE_OPEN
                                    ? SoftApConfiguration.SECURITY_TYPE_WPA2_PSK
                                    : config.getSecurityType()).build());
                    dialog.dismiss();
                    if (on) {
                        // A running network takes its new name on a fresh start
                        mTethering.stopTethering(TetheringManager.TETHERING_WIFI);
                        mMain.postDelayed(this::start, 1500);
                    }
                    show();
                }));
        dialog.show();
    }

    /** The code to join, as big as the screen takes it, with the name and password under it. */
    private void bigCode() {
        final SoftApConfiguration config = mWifi.getSoftApConfiguration();
        final String ssid = config.getWifiSsid() == null ? ""
                : config.getWifiSsid().toString().replaceAll("^\"|\"$", "");
        final String pass = config.getPassphrase();
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float dp = metrics.density;
        final android.widget.LinearLayout content = new android.widget.LinearLayout(this);
        content.setOrientation(android.widget.LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), 0);
        final QrView qr = new QrView(this, null);
        // Room left for the name, the password and the button, either way the r1 is held
        final int side = Math.max((int) (120 * dp),
                Math.min(metrics.widthPixels, metrics.heightPixels) - (int) (170 * dp));
        qr.setLayoutParams(new android.widget.LinearLayout.LayoutParams(side, side));
        qr.set(join(ssid, pass));
        content.addView(qr);
        final TextView name = new TextView(this, null, 0, R.style.Body);
        name.setText(ssid);
        name.setGravity(android.view.Gravity.CENTER);
        name.setPadding(0, (int) (10 * dp), 0, 0);
        content.addView(name);
        if (pass != null && !pass.isEmpty()) {
            final TextView password = new TextView(this, null, 0, R.style.Dim);
            password.setText(getString(R.string.password, pass));
            password.setGravity(android.view.Gravity.CENTER);
            content.addView(password);
        }
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setView(scroll)
                .setPositiveButton(R.string.code_done, null).show();
    }

    /** Whether the r1's own network stays on with nobody on it: the system's own switch. */
    private void stayRow() {
        final ViewGroup options = findViewById(R.id.options);
        final View row = getLayoutInflater().inflate(R.layout.row, options, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(R.drawable.ic_router);
        ((TextView) row.findViewById(R.id.title)).setText(R.string.stay_on);
        final TextView sub = row.findViewById(R.id.sub);
        sub.setVisibility(View.VISIBLE);
        sub.setMaxLines(2);
        row.findViewById(R.id.toggle).setVisibility(View.VISIBLE);
        row.setFocusable(true);
        row.setOnClickListener(v -> {
            final SoftApConfiguration config = mWifi.getSoftApConfiguration();
            // Staying on is the automatic turning off, turned off
            mWifi.setSoftApConfiguration(new SoftApConfiguration.Builder(config)
                    .setAutoShutdownEnabled(!config.isAutoShutdownEnabled()).build());
            showStay(v);
        });
        options.addView(row);
        mStay = row;
        showStay(row);
    }

    private void showStay(View row) {
        final boolean stays = !mWifi.getSoftApConfiguration().isAutoShutdownEnabled();
        ((android.widget.Switch) row.findViewById(R.id.toggle)).setChecked(stays);
        ((TextView) row.findViewById(R.id.sub)).setText(stays ? R.string.stay_on_yes
                : R.string.stay_on_no);
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
        // A waiting VPN is informational; a live one can be switched either way.
        vpnStop.setFocusable(vpn);
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
            // Since when, where that was seen: the data passed on is this session's
            final long since = mSessions.onSince();
            final String clock = since == 0 ? null
                    : android.text.format.DateFormat.getTimeFormat(this).format(
                            new java.util.Date(since));
            ownState = count == 0 ? getString(R.string.own_nobody)
                    : portal || bytes <= 0
                            ? (clock == null ? devices
                                    : getString(R.string.own_since, devices, clock))
                    : clock == null ? getString(R.string.own_used, devices,
                            Formatter.formatShortFileSize(this, bytes))
                    : getString(R.string.own_used_since, devices,
                            Formatter.formatShortFileSize(this, bytes), clock);
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
        text(R.id.card, !on ? getString(R.string.card_off)
                : portal ? getString(R.string.card_sign_in) : null);
        final List<TetheredClient> shown = on && !portal ? mClients : new ArrayList<>();
        final List<Object> keys = new ArrayList<>();
        for (TetheredClient client : shown) {
            keys.add(key(client));
        }
        final List<Object> had = new ArrayList<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            had.add(list.getChildAt(i).getTag());
        }
        if (!keys.equals(had)) {
            // Who is on it changed: the rows again, the wheel kept on the same device. Rows
            // made anew every tick would drop the focus off them every two seconds
            final View current = getCurrentFocus();
            final Object focusedKey = current != null && current.getParent() == list
                    ? current.getTag() : null;
            final int focusedAt = focusedKey == null ? -1 : list.indexOfChild(current);
            list.removeAllViews();
            boolean kept = false;
            for (TetheredClient client : shown) {
                final View row = getLayoutInflater().inflate(R.layout.row, list, false);
                row.setTag(key(client));
                row.setFocusable(true);
                list.addView(row);
                if (focusedKey != null && focusedKey.equals(row.getTag())) {
                    kept = row.requestFocus();
                }
            }
            if (focusedKey != null && !kept) {
                // The focused device left: the row now in its place, else the last one, else
                // what is just above the list. Not the title, the page scrolled to its top
                if (list.getChildCount() > 0) {
                    list.getChildAt(Math.min(focusedAt, list.getChildCount() - 1))
                            .requestFocus();
                } else {
                    final View above = android.view.FocusFinder.getInstance().findNextFocus(
                            (ViewGroup) list.getParent(), list, View.FOCUS_UP);
                    if (above != null) {
                        above.requestFocus();
                    }
                }
            }
        }
        for (int i = 0; i < shown.size(); i++) {
            device(shown.get(i), list.getChildAt(i));
        }
        final View passOn = findViewById(R.id.pass_on);
        final View turnOff = findViewById(R.id.turn_off);
        final View showCode = findViewById(R.id.show_code);
        final View focused = passOn.isFocused() ? passOn : turnOff.isFocused() ? turnOff
                : showCode.isFocused() ? showCode : null;
        final View current = getCurrentFocus();
        // Only as the page opens: once the wheel has been turned, the title is a place too
        final boolean nothingChosen = !mFocusPlaced
                && (current == null || current.getId() == R.id.title);
        visible(R.id.pass_on, !on && !mStarting && mUpCaps != null);
        visible(R.id.turn_off, on);
        visible(R.id.show_code, on);
        // Focus stays on a button that stays; one that went takes it to the button now in its
        // place, not wherever Android would put it. Never first on Turn off: while on, the
        // code to join is the harmless next step
        if ((focused != null && focused.getVisibility() != View.VISIBLE) || nothingChosen) {
            mFocusPlaced |= (on ? showCode : passOn).requestFocus();
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

    /** Which device a row is: its hardware address, else what it has. */
    private static Object key(TetheredClient client) {
        return client.getMacAddress() != null ? client.getMacAddress().toString()
                : client.getAddresses().toString();
    }

    /** A device's row filled in: its name, since when it is on, its address. */
    private void device(TetheredClient client, View row) {
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
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(name == null
                ? getString(R.string.device_unnamed) : name);
        final TextView time = row.findViewById(R.id.time);
        time.setText(address);
        time.setVisibility(address == null ? View.GONE : View.VISIBLE);
        // Since when, only where it was seen joining: no time rather than a wrong one
        final long joined = mSessions.joined(client);
        final TextView sub = row.findViewById(R.id.sub);
        sub.setText(joined == 0 ? null : getString(R.string.device_joined,
                android.text.format.DateFormat.getTimeFormat(this).format(
                        new java.util.Date(joined))));
        sub.setVisibility(joined == 0 ? View.GONE : View.VISIBLE);
    }

    /** What a phone's camera reads to join, special characters escaped as that format asks. */
    private static String join(String ssid, String pass) {
        final boolean open = pass == null || pass.isEmpty();
        return "WIFI:T:" + (open ? "nopass" : "WPA") + ";S:" + escape(ssid) + ";"
                + (open ? "" : "P:" + escape(pass) + ";") + ";";
    }

    private static String escape(String text) {
        final StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            if (c == 92 || c == ';' || c == ',' || c == ':' || c == '"') {
                // 92 is the backslash, which escapes these five
                out.append((char) 92);
            }
            out.append(c);
        }
        return out.toString();
    }

    private int accent() {
        final android.util.TypedValue value = new android.util.TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }
}
