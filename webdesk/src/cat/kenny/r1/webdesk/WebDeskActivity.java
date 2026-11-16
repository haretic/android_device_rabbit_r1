// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk;

import android.app.Activity;
import android.os.Bundle;
import android.text.format.DateFormat;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import java.util.Date;
import java.util.List;

/**
 * Web desk's page on the r1. Opening it turns web desk on: where a computer goes, the code to
 * type there, and what that computer may do. With a computer connected: who it is, what it is
 * doing, and the way to end it.
 */
public final class WebDeskActivity extends Activity {

    private static final String[] CAPS = {DeskService.CAP_SCREEN, DeskService.CAP_FILES,
            DeskService.CAP_CLIPBOARD};
    private static final int[] CAP_TITLES = {R.string.cap_screen, R.string.cap_files,
            R.string.cap_clipboard};
    private static final int[] CAP_ICONS = {R.drawable.ic_screen, R.drawable.ic_files,
            R.drawable.ic_clipboard};

    private final Switch[] mToggles = new Switch[CAPS.length];
    private boolean mTurnedOff;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.desk);
        final ViewGroup caps = findViewById(R.id.caps);
        for (int i = 0; i < CAPS.length; i++) {
            final String cap = CAPS[i];
            final View row = getLayoutInflater().inflate(R.layout.cap, caps, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(CAP_ICONS[i]);
            ((TextView) row.findViewById(R.id.title)).setText(CAP_TITLES[i]);
            final Switch toggle = row.findViewById(R.id.toggle);
            toggle.setVisibility(View.VISIBLE);
            mToggles[i] = toggle;
            row.setFocusable(true);
            row.setClickable(true);
            row.setOnClickListener(v -> {
                final boolean on = !DeskService.allowed(this, cap);
                DeskService.prefs(this).edit().putBoolean(cap, on).apply();
                toggle.setChecked(on);
            });
            caps.addView(row);
        }
        findViewById(R.id.turn_off).setOnClickListener(v -> {
            mTurnedOff = true;
            DeskService.stop(this);
            finish();
        });
        findViewById(R.id.disconnect).setOnClickListener(v -> {
            final DeskService desk = DeskService.running();
            if (desk != null) {
                desk.disconnect();
            }
        });
        final ViewGroup code = findViewById(R.id.code);
        final float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 6; i++) {
            final TextView digit = new TextView(this, null, 0, R.style.Number);
            digit.setTextSize(30);
            digit.setGravity(android.view.Gravity.CENTER);
            digit.setBackgroundResource(R.drawable.digit_bg);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round(56 * density), Math.round(68 * density));
            // The code reads as two threes
            lp.setMarginEnd(Math.round((i == 2 ? 22 : 6) * density));
            code.addView(digit, lp);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mTurnedOff && DeskService.running() == null) {
            DeskService.start(this);
        }
        DeskService.listen(this::show);
        show();
    }

    @Override
    protected void onPause() {
        DeskService.listen(null);
        super.onPause();
    }

    private void show() {
        final DeskService desk = DeskService.running();
        final boolean paired = desk != null && desk.paired();
        final View disconnect = findViewById(R.id.disconnect);
        final boolean hadFocus = disconnect.isFocused();
        findViewById(R.id.waiting).setVisibility(paired ? View.GONE : View.VISIBLE);
        findViewById(R.id.connected).setVisibility(paired ? View.VISIBLE : View.GONE);
        disconnect.setVisibility(paired ? View.VISIBLE : View.GONE);
        ((TextView) findViewById(R.id.foot)).setText(paired ? R.string.foot_connected
                : R.string.foot);
        for (int i = 0; i < CAPS.length; i++) {
            mToggles[i].setChecked(DeskService.allowed(this, CAPS[i]));
        }
        if (paired) {
            showConnected(desk);
            return;
        }
        if (hadFocus) {
            findViewById(R.id.turn_off).requestFocus();
        }
        final String address = desk == null ? null : desk.address();
        final boolean online = address != null;
        final String port = desk == null || desk.port() == 80 ? "" : ":" + desk.port();
        ((TextView) findViewById(R.id.open)).setText(online ? R.string.open : R.string.no_wifi);
        final TextView name = findViewById(R.id.address);
        name.setText(DeskService.HOST + ".local" + port);
        name.setVisibility(online ? View.VISIBLE : View.GONE);
        final TextView or = findViewById(R.id.address_or);
        or.setText(online ? getString(R.string.open_or, address + port) : "");
        or.setVisibility(online ? View.VISIBLE : View.GONE);
        final boolean locked = desk != null && desk.locked();
        ((TextView) findViewById(R.id.type_code)).setText(locked ? R.string.locked
                : R.string.type_code);
        final ViewGroup code = findViewById(R.id.code);
        code.setVisibility(online && !locked ? View.VISIBLE : View.INVISIBLE);
        final String digits = desk == null ? "" : desk.code();
        for (int i = 0; i < code.getChildCount(); i++) {
            ((TextView) code.getChildAt(i)).setText(i < digits.length()
                    ? String.valueOf(digits.charAt(i)) : "");
        }
        code.setContentDescription(digits);
    }

    private void showConnected(DeskService desk) {
        ((TextView) findViewById(R.id.since)).setText(getString(R.string.connected_since,
                DateFormat.getTimeFormat(this).format(new Date(desk.since()))));
        ((TextView) findViewById(R.id.who)).setText(who(desk.who()));
        final ViewGroup list = findViewById(R.id.did);
        list.removeAllViews();
        final List<DeskService.Did> did = desk.did();
        final long now = System.currentTimeMillis();
        if (did.isEmpty()) {
            add(list, R.drawable.ic_screen, getString(R.string.nothing_yet), null, null);
        }
        for (DeskService.Did d : did) {
            final boolean fresh = now - d.time < 5000;
            final String time = DateFormat.getTimeFormat(this).format(new Date(d.time));
            switch (d.kind) {
                case "typing":
                    add(list, R.drawable.ic_keyboard, getString(R.string.did_typing),
                            fresh ? getString(R.string.right_now) : null, fresh ? null : time);
                    break;
                case "screen":
                    add(list, R.drawable.ic_screen, getString(R.string.did_screen),
                            fresh ? getString(R.string.right_now) : null, fresh ? null : time);
                    break;
                case "watching":
                    add(list, R.drawable.ic_screen, getString(R.string.did_watching), null, time);
                    break;
                case "received":
                    add(list, R.drawable.ic_files, getResources().getQuantityString(
                            R.plurals.did_received, d.count, d.count),
                            getString(R.string.did_received_where), time);
                    break;
                case "fetched":
                    add(list, R.drawable.ic_files, getResources().getQuantityString(
                            R.plurals.did_fetched, d.count, d.count),
                            getString(R.string.did_fetched_where), time);
                    break;
                case "screenshot":
                    add(list, R.drawable.ic_camera, getString(R.string.did_screenshot),
                            getString(R.string.did_fetched_where), time);
                    break;
                case "clipboard_in":
                    add(list, R.drawable.ic_clipboard, getString(R.string.did_clipboard_in),
                            null, time);
                    break;
                default:
                    add(list, R.drawable.ic_clipboard, getString(R.string.did_clipboard_out),
                            null, time);
            }
        }
        if (getCurrentFocus() == null) {
            findViewById(R.id.disconnect).requestFocus();
        }
    }

    private void add(ViewGroup list, int icon, String title, String sub, String time) {
        final View row = getLayoutInflater().inflate(R.layout.cap, list, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        final TextView subView = row.findViewById(R.id.sub);
        subView.setText(sub);
        subView.setVisibility(sub == null ? View.GONE : View.VISIBLE);
        final TextView timeView = row.findViewById(R.id.time);
        timeView.setText(time);
        timeView.setVisibility(time == null ? View.GONE : View.VISIBLE);
        list.addView(row);
    }

    /** "Firefox on a laptop" from what the browser says it is. */
    private String who(String agent) {
        final String a = agent == null ? "" : agent;
        final String browser = a.contains("Firefox/") ? "Firefox" : a.contains("Edg/") ? "Edge"
                : a.contains("OPR/") ? "Opera" : a.contains("Chrome/") ? "Chrome"
                : a.contains("Safari/") ? "Safari" : getString(R.string.a_browser);
        final int computer = a.contains("Android") || a.contains("iPhone") ? R.string.a_phone
                : a.contains("Macintosh") ? R.string.a_mac
                : a.contains("Windows") ? R.string.a_windows
                : a.contains("Linux") || a.contains("X11") ? R.string.a_linux
                : R.string.a_computer;
        return getString(R.string.who, browser, getString(computer));
    }
}
