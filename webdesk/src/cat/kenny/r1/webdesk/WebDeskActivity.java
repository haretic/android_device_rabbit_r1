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
 * Web desk's page on the r1. Opening it turns web desk on, or with "Ask before starting" waits
 * for Start: where a computer goes, the code to type there, and what that computer may do.
 * With a computer connected: who it is, what it is doing, and the way to end it.
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
    /** Asked to start and not running yet: the waiting page shows, not the off one */
    private boolean mStarting;
    private Switch mScreenOn;
    private Switch mAsk;
    /** The focus was put where the page starts, on a harmless button */
    private boolean mFocusPlaced;
    private TextView mOffAfter;

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
        options(findViewById(R.id.options));
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
        findViewById(R.id.start).setOnClickListener(v -> {
            mStarting = true;
            // The code to scan takes the focus once there is an address
            mFocusPlaced = false;
            DeskService.start(this);
            show();
        });
        findViewById(R.id.scan).setOnClickListener(v -> bigCode());
        final ViewGroup code = findViewById(R.id.code);
        final float density = getResources().getDisplayMetrics().density;
        for (int i = 0; i < 6; i++) {
            final TextView digit = new TextView(this, null, 0, R.style.Number);
            digit.setTextSize(30);
            digit.setGravity(android.view.Gravity.CENTER);
            digit.setBackgroundResource(R.drawable.digit_bg);
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    Math.round(50 * density), Math.round(64 * density));
            // The code reads as two threes
            lp.setMarginEnd(Math.round((i == 2 ? 18 : 5) * density));
            code.addView(digit, lp);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!mTurnedOff && DeskService.running() == null && !DeskService.askFirst(this)) {
            mStarting = true;
            DeskService.start(this);
        }
        DeskService.listen(this::show);
        show();
    }

    @Override
    protected void onPause() {
        DeskService.listen(null);
        mStarting = false;
        super.onPause();
    }

    /** The address as a code for a phone's camera, as big as the screen takes it. */
    private void bigCode() {
        final DeskService desk = DeskService.running();
        final String address = desk == null ? null : desk.address();
        if (address == null) {
            return;
        }
        final String url = "http://" + address + (desk.port() == 80 ? "" : ":" + desk.port())
                + "/";
        final android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
        final float dp = metrics.density;
        final LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        content.setPadding((int) (16 * dp), (int) (16 * dp), (int) (16 * dp), 0);
        final QrView qr = new QrView(this, null);
        // Room left for the line under it and the button, either way the r1 is held
        final int side = Math.max((int) (120 * dp),
                Math.min(metrics.widthPixels, metrics.heightPixels) - (int) (170 * dp));
        qr.setLayoutParams(new LinearLayout.LayoutParams(side, side));
        qr.set(url);
        qr.setContentDescription(url);
        content.addView(qr);
        final TextView under = new TextView(this, null, 0, R.style.Dim);
        under.setText(getString(R.string.scan_under, url));
        under.setGravity(android.view.Gravity.CENTER);
        under.setPadding(0, (int) (10 * dp), 0, 0);
        content.addView(under);
        final android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(content);
        new android.app.AlertDialog.Builder(this).setView(scroll)
                .setPositiveButton(R.string.scan_done, null).show();
    }

    /**
     * The two settings under what the computer may do. A press or a tap changes one: the
     * screen kept on while connected, and how long it waits for a computer before it is off.
     */
    private void options(ViewGroup options) {
        final View screen = getLayoutInflater().inflate(R.layout.cap, options, false);
        ((ImageView) screen.findViewById(R.id.icon)).setImageResource(R.drawable.ic_screen);
        ((TextView) screen.findViewById(R.id.title)).setText(R.string.keep_screen_on);
        final TextView screenSub = screen.findViewById(R.id.sub);
        screenSub.setText(R.string.keep_screen_on_sub);
        screenSub.setVisibility(View.VISIBLE);
        mScreenOn = screen.findViewById(R.id.toggle);
        mScreenOn.setVisibility(View.VISIBLE);
        screen.setFocusable(true);
        screen.setClickable(true);
        screen.setOnClickListener(v -> {
            final boolean on = !DeskService.screenOn(this);
            DeskService.prefs(this).edit().putBoolean(DeskService.PREF_SCREEN_ON, on).apply();
            mScreenOn.setChecked(on);
        });
        options.addView(screen);

        final View off = getLayoutInflater().inflate(R.layout.cap, options, false);
        ((ImageView) off.findViewById(R.id.icon)).setImageResource(R.drawable.ic_timer);
        ((TextView) off.findViewById(R.id.title)).setText(R.string.off_after);
        final TextView offSub = off.findViewById(R.id.sub);
        offSub.setText(R.string.off_after_sub);
        offSub.setVisibility(View.VISIBLE);
        mOffAfter = off.findViewById(R.id.time);
        mOffAfter.setVisibility(View.VISIBLE);
        off.setFocusable(true);
        off.setClickable(true);
        off.setOnClickListener(v -> {
            final int now = DeskService.offMinutes(this);
            int next = DeskService.OFF_MINUTES[0];
            for (int i = 0; i < DeskService.OFF_MINUTES.length; i++) {
                if (DeskService.OFF_MINUTES[i] == now) {
                    next = DeskService.OFF_MINUTES[(i + 1) % DeskService.OFF_MINUTES.length];
                    break;
                }
            }
            DeskService.prefs(this).edit().putInt(DeskService.PREF_OFF_AFTER, next).apply();
            show();
        });
        options.addView(off);

        final View ask = getLayoutInflater().inflate(R.layout.cap, options, false);
        ((ImageView) ask.findViewById(R.id.icon)).setImageResource(R.drawable.ic_start);
        ((TextView) ask.findViewById(R.id.title)).setText(R.string.ask_first);
        final TextView askSub = ask.findViewById(R.id.sub);
        askSub.setText(R.string.ask_first_sub);
        askSub.setVisibility(View.VISIBLE);
        mAsk = ask.findViewById(R.id.toggle);
        mAsk.setVisibility(View.VISIBLE);
        ask.setFocusable(true);
        ask.setClickable(true);
        ask.setOnClickListener(v -> {
            final boolean on = !DeskService.askFirst(this);
            DeskService.prefs(this).edit().putBoolean(DeskService.PREF_ASK, on).apply();
            mAsk.setChecked(on);
        });
        options.addView(ask);
    }

    /** "15 min", "1 hour", "3 hours": how long it waits for a computer. */
    private String minutes(int minutes) {
        return minutes < 60 ? getString(R.string.off_minutes, minutes)
                : getResources().getQuantityString(R.plurals.off_hours, minutes / 60,
                        minutes / 60);
    }

    private void show() {
        final DeskService desk = DeskService.running();
        // A start that found no port leaves the page off, saying so, with Start to try again
        final boolean failed = desk == null && DeskService.failed();
        if (desk != null || failed) {
            if (failed && mStarting) {
                // The press asked for is Start's now, wherever the focus was waiting
                mFocusPlaced = false;
            }
            mStarting = false;
        }
        final boolean off = desk == null && !mStarting;
        final boolean paired = desk != null && desk.paired();
        final View disconnect = findViewById(R.id.disconnect);
        final View start = findViewById(R.id.start);
        final View scan = findViewById(R.id.scan);
        // A button about to go: the focus goes to a harmless one, never on to Turn off
        final boolean hadFocus = disconnect.isFocused() || start.isFocused();
        findViewById(R.id.off).setVisibility(off ? View.VISIBLE : View.GONE);
        findViewById(R.id.waiting).setVisibility(paired || off ? View.GONE : View.VISIBLE);
        findViewById(R.id.connected).setVisibility(paired ? View.VISIBLE : View.GONE);
        disconnect.setVisibility(paired ? View.VISIBLE : View.GONE);
        findViewById(R.id.turn_off).setVisibility(off ? View.GONE : View.VISIBLE);
        final int offAfter = DeskService.offMinutes(this);
        final TextView foot = findViewById(R.id.foot);
        foot.setText(paired ? getString(R.string.foot_connected)
                : offAfter == 60 ? getString(R.string.foot)
                : getString(R.string.foot_after, minutes(offAfter)));
        for (int i = 0; i < CAPS.length; i++) {
            mToggles[i].setChecked(DeskService.allowed(this, CAPS[i]));
        }
        mScreenOn.setChecked(DeskService.screenOn(this));
        mAsk.setChecked(DeskService.askFirst(this));
        mOffAfter.setText(minutes(offAfter));
        final TextView hint = findViewById(R.id.hint);
        if (paired) {
            hint.setText(R.string.hint_connected);
            showConnected(desk);
            return;
        }
        ((TextView) findViewById(R.id.off_text)).setText(failed ? R.string.start_failed
                : R.string.off_text);
        if (off) {
            hint.setText(R.string.hint_off);
            // Start is what the page is for now: asked for, so a press of it is the answer
            if (nothingChosen() || hadFocus || failed && !mFocusPlaced) {
                mFocusPlaced |= start.requestFocus();
            }
            return;
        }
        final String address = desk == null ? null : desk.address();
        final boolean online = address != null;
        hint.setText(online ? R.string.hint_waiting : R.string.hint_no_wifi);
        scan.setVisibility(online ? View.VISIBLE : View.GONE);
        if (online && (nothingChosen() || hadFocus)) {
            mFocusPlaced |= scan.requestFocus();
        } else if (hadFocus) {
            findViewById(R.id.title).requestFocus();
        }
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
        final String clock = DateFormat.getTimeFormat(this).format(new Date(desk.since()));
        final String from = desk.from();
        ((TextView) findViewById(R.id.since)).setText(from == null
                ? getString(R.string.connected_since, clock)
                : getString(R.string.connected_from, clock, from));
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
            // Not on Disconnect: a press that was meant for something else must not end it
            findViewById(R.id.title).requestFocus();
        }
    }

    /**
     * No row or button picked yet as the page opens: nothing focused, or only the title, where
     * it starts. Once the wheel has been turned, the title is a place like any other.
     */
    private boolean nothingChosen() {
        final View focused = getCurrentFocus();
        return !mFocusPlaced && (focused == null || focused.getId() == R.id.title);
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
