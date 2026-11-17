// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.knob;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Three faces: the list of what the r1 can be a knob for, the dial while it is one, and the
 * choice of what the wheel turns. On the dial the wheel and the press go to the other side; a
 * hold brings the choice, where leaving is one of the rows.
 */
public class KnobActivity extends Activity implements Hid.Listener {
    private static final int LIST = 0, DIAL = 1, SHEET = 2;
    /** The wheel's middle, down the screen: config_wheelCentre */
    private static final float WHEEL_CENTRE = 0.4f;

    private Hid mHid;
    private SharedPreferences mPrefs;
    private int mFace = -1;
    private int mMode;
    private BluetoothDevice mComing;
    private boolean mFindable;
    private FrameLayout mRoot;
    private DialView mDial;
    private int mAccent;
    private float mDp;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        mDp = getResources().getDisplayMetrics().density;
        final TypedValue accent = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        mAccent = accent.data;
        mPrefs = getSharedPreferences("knob", 0);
        mMode = Math.max(0, Math.min(Hid.ZOOM, mPrefs.getInt("mode", Hid.SCROLL)));
        mRoot = new FrameLayout(this);
        setContentView(mRoot);
        mHid = new Hid(this, this);
    }

    @Override
    protected void onStart() {
        super.onStart();
        final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_SCAN_MODE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        registerReceiver(mBluetooth, filter);
        mHid.start();
        show(LIST);
    }

    @Override
    protected void onStop() {
        // Android lets only the app in front be an input device: gone with the page
        unregisterReceiver(mBluetooth);
        findable(false);
        mHid.stop();
        mComing = null;
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        super.onStop();
    }

    private final BroadcastReceiver mBluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
                if (state == BluetoothAdapter.STATE_ON) {
                    mHid.start();
                } else if (state == BluetoothAdapter.STATE_OFF) {
                    mHid.stop();
                    mComing = null;
                    mFindable = false;
                }
            } else if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                // The computer asked to pair while this page invited it: the system's question
                // goes on top now, where it would otherwise wait among the notifications
                try {
                    startActivity(new Intent(intent).setComponent(null).setPackage(null)
                            .setClassName("com.android.settings",
                                    "com.android.settings.bluetooth.BluetoothPairingDialog")
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                } catch (RuntimeException e) {
                    // No such dialog on this system: the notification is still there
                }
                return;
            } else if (BluetoothDevice.ACTION_BOND_STATE_CHANGED.equals(intent.getAction())
                    && intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0)
                            == BluetoothDevice.BOND_BONDED && mFindable) {
                // Paired from this page. A computer opens the link itself; a phone pairs and
                // then waits for the keyboard to come to it, so the r1 goes to it
                final BluetoothDevice paired =
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                mRoot.postDelayed(() -> {
                    if (paired != null && mHid.host() == null && mComing == null
                            && !isDestroyed()) {
                        mHid.connect(paired);
                    }
                }, 4000);
            } else if (BluetoothAdapter.ACTION_SCAN_MODE_CHANGED.equals(intent.getAction())) {
                mFindable = intent.getIntExtra(BluetoothAdapter.EXTRA_SCAN_MODE, 0)
                        == BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE;
            }
            if (mFace == LIST) {
                show(LIST);
            }
        }
    };

    @Override
    public void onLink(BluetoothDevice host, boolean connecting) {
        mComing = connecting ? host : null;
        if (host != null && !connecting) {
            final Set<String> known = new HashSet<>(mPrefs.getStringSet("hosts", new HashSet<>()));
            known.add(host.getAddress());
            mPrefs.edit().putStringSet("hosts", known).apply();
            findable(false);
            show(DIAL);
        } else {
            show(LIST);
        }
    }

    private void findable(boolean on) {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled() || on == mFindable) {
            return;
        }
        try {
            if (on) {
                adapter.setDiscoverableTimeout(Duration.ofSeconds(120));
            }
            adapter.setScanMode(on ? BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    : BluetoothAdapter.SCAN_MODE_CONNECTABLE);
            mFindable = on;
        } catch (RuntimeException e) {
            // Bluetooth went away under it: the row says so on the next look
        }
    }

    private String name() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        String name = adapter == null ? null : adapter.getName();
        if (name == null) {
            name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        }
        return name == null ? "r1" : name;
    }

    private static String name(BluetoothDevice device) {
        final String alias = device.getAlias();
        return alias == null || alias.isEmpty() ? device.getAddress() : alias;
    }

    // ------------------------------------------------------------------ faces

    private void show(int face) {
        final View focused = getCurrentFocus();
        final Object keep = face == mFace && focused != null ? focused.getTag() : null;
        mFace = face;
        mRoot.removeAllViews();
        mDial = null;
        if (face == DIAL && mHid.host() != null) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            dial();
            return;
        }
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        final LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(18), 0, dp(18), dp(18));
        if (face == SHEET && mHid.host() != null) {
            list.addView(title(getString(R.string.sheet_title)));
            final String[] modes = getResources().getStringArray(R.array.modes);
            final String[] sums = getResources().getStringArray(R.array.mode_sums);
            for (int i = 0; i < modes.length; i++) {
                final int mode = i;
                list.addView(row("mode" + i, (i == mMode ? "● " : "") + modes[i], sums[i], v -> {
                    mMode = mode;
                    mPrefs.edit().putInt("mode", mode).apply();
                    show(DIAL);
                }));
            }
            list.addView(row("leave", getString(R.string.leave),
                    getString(R.string.leave_sum, name(mHid.host())), v -> mHid.leave()));
        } else {
            mFace = LIST;
            list.addView(title(getString(R.string.app_name)));
            final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null || !adapter.isEnabled()) {
                list.addView(card(getString(R.string.bluetooth_off),
                        getString(R.string.bluetooth_off_text)));
            } else {
                list.addView(card(getString(R.string.intro_title),
                        getString(R.string.intro_text, name())));
                list.addView(row("pair", getString(R.string.pair), mFindable
                        ? getString(R.string.pair_on, name()) : getString(R.string.pair_sum),
                        v -> {
                            findable(true);
                            show(LIST);
                        }));
                final Set<String> known = mPrefs.getStringSet("hosts", new HashSet<>());
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (known.contains(device.getAddress())) {
                        list.addView(row(device.getAddress(), name(device),
                                getString(device.equals(mComing) ? R.string.connecting
                                        : R.string.known_sum), v -> mHid.connect(device)));
                    }
                }
                // Paired some other way, never yet a knob's other side: worth a try
                for (BluetoothDevice device : adapter.getBondedDevices()) {
                    if (!known.contains(device.getAddress())
                            && device.getType() != BluetoothDevice.DEVICE_TYPE_LE) {
                        list.addView(row(device.getAddress(), name(device),
                                getString(device.equals(mComing) ? R.string.connecting
                                        : R.string.paired_sum), v -> mHid.connect(device)));
                    }
                }
            }
        }
        final ScrollView scroll = new ScrollView(this);
        scroll.addView(list);
        mRoot.addView(scroll);
        View first = null;
        for (int i = 0; i < list.getChildCount(); i++) {
            final View child = list.getChildAt(i);
            if (child.isFocusable() && (first == null || (keep != null
                    && keep.equals(child.getTag())) || (keep == null && face == SHEET
                    && ("mode" + mMode).equals(child.getTag())))) {
                first = child;
            }
        }
        if (first != null) {
            first.requestFocus();
        }
    }

    private void dial() {
        final BluetoothDevice host = mHid.host();
        mDial = new DialView(this, mAccent, WHEEL_CENTRE);
        mRoot.addView(mDial, new FrameLayout.LayoutParams(-1, -1));
        // By finger too: slide to turn, tap to press, keep it still for the choice of what turns
        new TouchWheel(this, mDial, KeyEvent.KEYCODE_DPAD_CENTER, TouchWheel.TAP);
        final LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        top.setPadding(dp(18), 0, dp(18), 0);
        top.addView(title(getString(R.string.app_name)));
        final TextView link = text(name(host) + " - " + getString(R.string.connected), 16, 0xFFFFFFFF);
        link.setBackgroundResource(R.drawable.row_bg);
        link.setPadding(dp(18), dp(16), dp(18), dp(16));
        top.addView(link);
        mRoot.addView(top, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        final LinearLayout mid = new LinearLayout(this);
        mid.setOrientation(LinearLayout.VERTICAL);
        mid.addView(text(getString(R.string.turns), 15, 0xFFD2D2D2));
        final TextView what = text(getResources().getStringArray(R.array.modes)[mMode], 30,
                0xFFFFFFFF);
        what.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        mid.addView(what);
        final TextView on = text(getString(R.string.turns_on, name(host)), 14, 0xFF8D8D8D);
        on.setPadding(0, dp(6), 0, 0);
        mid.addView(on);
        // Beside the dial, level with the wheel, and clear of the arc
        final FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(dp(196), -2,
                Gravity.TOP | Gravity.START);
        at.leftMargin = dp(24);
        at.topMargin = Math.round(getResources().getDisplayMetrics().heightPixels
                * WHEEL_CENTRE) - dp(84);
        mRoot.addView(mid, at);
        final LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.VERTICAL);
        foot.setPadding(dp(18), 0, dp(18), dp(14));
        for (String hint : new String[] {getResources().getStringArray(R.array.mode_hints)[mMode],
                getString(R.string.hold_hint)}) {
            final TextView line = text(hint, 15, 0xFFD2D2D2);
            line.setBackgroundResource(R.drawable.row_bg);
            line.setPadding(dp(18), dp(12), dp(18), dp(12));
            final LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
            gap.topMargin = dp(6);
            foot.addView(line, gap);
        }
        mRoot.addView(foot, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
    }

    private TextView text(String s, int sp, int color) {
        final TextView view = new TextView(this);
        view.setText(s);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private TextView title(String s) {
        final TextView view = text(s, 19, 0xFFFFFFFF);
        view.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        view.setPadding(dp(6), dp(14), 0, dp(14));
        return view;
    }

    private View card(String head, String body) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundResource(R.drawable.row_bg);
        card.setPadding(dp(20), dp(16), dp(20), dp(16));
        final TextView h = text(head, 18, 0xFFFFFFFF);
        h.setTypeface(null, Typeface.BOLD);
        card.addView(h);
        final TextView b = text(body, 15, 0xFFD2D2D2);
        b.setPadding(0, dp(6), 0, 0);
        card.addView(b);
        return card;
    }

    private View row(String tag, String head, String sum, View.OnClickListener click) {
        final LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setTag(tag);
        row.setBackgroundResource(R.drawable.row_bg);
        row.setPadding(dp(20), dp(11), dp(20), dp(11));
        row.setFocusable(true);
        row.setClickable(true);
        row.setOnClickListener(click);
        final TextView h = text(head, 17, 0xFFFFFFFF);
        h.setTypeface(null, Typeface.BOLD);
        row.addView(h);
        row.addView(text(sum, 14, 0xFFD2D2D2));
        final LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2);
        gap.topMargin = dp(4);
        row.setLayoutParams(gap);
        return row;
    }

    private int dp(int value) {
        return Math.round(value * mDp);
    }

    // ------------------------------------------------------------------ the wheel and the press

    private boolean mLongPress;

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        final boolean dial = mFace == DIAL && mDial != null;
        if (dial && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            if (down) {
                final boolean up = code == KeyEvent.KEYCODE_DPAD_UP;
                mHid.turn(mMode, up);
                mDial.step(up);
                mDial.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            if (down) {
                mLongPress = event.getRepeatCount() > 0
                        && (mLongPress || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0);
                return true;
            }
            if (!down && !event.isCanceled()) {
                // The system says which it was: a hold's repeat comes flagged, and the flag is
                // kept from there as the release may come without it. Not told by the times: a
                // press arrives only once a second press can no longer follow, a wait that alone
                // made every real press look like a hold
                final boolean held = mLongPress
                        || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
                if (dial) {
                    if (held) {
                        show(SHEET);
                    } else {
                        mHid.press(mMode);
                        mDial.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                    }
                } else if (held) {
                    // A hold is the way back, as everywhere
                    if (mFace == SHEET) {
                        show(DIAL);
                    } else {
                        finish();
                    }
                } else if (getCurrentFocus() != null) {
                    getCurrentFocus().performClick();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
}
