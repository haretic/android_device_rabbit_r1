// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.GradientDrawable;
import android.net.VpnService;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.TypedValue;
import android.view.HapticFeedbackConstants;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;

/**
 * A page of the app, worked with the wheel and the side button as with a finger. The wheel
 * arrives as D-pad up and down and goes from one thing to the next in the page's order,
 * scrolling on where nothing is left to go to; the side button arrives as the select key once
 * the window asks for it, a press acting on what the wheel is on, a hold going back. A finger
 * taps the same things. Along the bottom, where a page has it, what a press does there and,
 * under it, what a turn does.
 */
abstract class Page extends Activity {
    private static final int REQ_VPN = 41;
    private static final String PENDING = "pending";
    private static final long POLL_MS = 2000;

    protected ViewGroup mBody;
    protected ScrollView mScroll;
    private TextView mHint;
    private TextView mHintTurn;
    private LinearLayout mHintLines;
    private View mHintRow;
    private CharSequence mDefaultHint = "";
    private boolean mHeld;
    private String mPending;
    private boolean mWatching;
    private boolean mResumed;
    protected final Handler mMain = new Handler(Looper.getMainLooper());
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            onStatus(Status.get());
            mMain.postDelayed(this, POLL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        setContentView(R.layout.page);
        mBody = findViewById(R.id.body);
        mScroll = findViewById(R.id.scroll);
        mHint = findViewById(R.id.hint);
        mHintRow = findViewById(R.id.hint_row);
        mHintTurn = findViewById(R.id.hint_turn);
        mHintLines = findViewById(R.id.hint_lines);
        arrangeHint();
        if (state != null) {
            mPending = state.getString(PENDING);
        }
        getWindow().getDecorView().getViewTreeObserver()
                .addOnGlobalFocusChangeListener((was, now) -> showHint(now));
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        arrangeHint();
    }

    /** On its side the r1 is short: the press and the turn go side by side, on one line */
    private void arrangeHint() {
        final boolean wide = isWide();
        mHintLines.setOrientation(wide ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        final float density = getResources().getDisplayMetrics().density;
        final LinearLayout.LayoutParams press = wide
                ? new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1)
                : new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT);
        mHint.setLayoutParams(press);
        final LinearLayout.LayoutParams turn = new LinearLayout.LayoutParams(
                wide ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        if (wide) {
            turn.setMarginStart((int) (12 * density));
        }
        mHintTurn.setLayoutParams(turn);
    }

    /** Lying on its side, wider than tall */
    protected final boolean isWide() {
        return getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
    }

    @Override
    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putString(PENDING, mPending);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        if (mWatching) {
            Status.watch(true);
            mMain.post(mPoll);
        }
    }

    @Override
    protected void onPause() {
        mResumed = false;
        if (mWatching) {
            Status.watch(false);
            mMain.removeCallbacks(mPoll);
        }
        super.onPause();
    }

    protected final boolean isResumedNow() {
        return mResumed;
    }

    // The page's parts

    protected final void title(CharSequence text) {
        ((TextView) findViewById(R.id.title)).setText(text);
    }

    protected final void title(int text) {
        title(getText(text));
    }

    /** The step marks: done ones faded, this one full, the rest grey, and "Step 1 of 3". */
    protected final void steps(int at, int count) {
        final TextView step = findViewById(R.id.step);
        step.setText(getString(R.string.step_of, at, count));
        step.setVisibility(View.VISIBLE);
        final LinearLayout marks = findViewById(R.id.marks);
        marks.removeAllViews();
        marks.setVisibility(View.VISIBLE);
        final float density = getResources().getDisplayMetrics().density;
        final int accent = accent();
        for (int i = 1; i <= count; i++) {
            final View mark = new View(this);
            final GradientDrawable shape = new GradientDrawable();
            shape.setCornerRadius(2 * density);
            shape.setColor(i <= at ? accent : getColor(R.color.surface2));
            mark.setBackground(shape);
            if (i < at) {
                mark.setAlpha(0.55f);
            }
            final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            if (i > 1) {
                params.setMarginStart((int) (4 * density));
            }
            marks.addView(mark, params);
        }
    }

    protected final int accent() {
        final TypedValue value = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.colorAccent, value, true);
        return value.data;
    }

    /** The bar along the bottom, saying what a press does where nothing says otherwise. */
    protected final void hint(CharSequence text) {
        mDefaultHint = text;
        mHintRow.setVisibility(View.VISIBLE);
        showHint(getCurrentFocus());
    }

    protected final void hint(int text) {
        hint(getText(text));
    }

    /** What a press does while the wheel is on this view, for the bar along the bottom. */
    protected static void pressHint(View view, CharSequence text) {
        view.setTag(R.id.press_hint, text);
    }

    /** What a turn does while the wheel is on this view, where it is not just moving on. */
    protected static void turnHint(View view, CharSequence text) {
        view.setTag(R.id.turn_hint, text);
    }

    private void showHint(View focused) {
        if (mHintRow.getVisibility() != View.VISIBLE) {
            return;
        }
        final Object said = focused != null ? focused.getTag(R.id.press_hint) : null;
        final CharSequence press;
        if (said instanceof CharSequence) {
            press = (CharSequence) said;
        } else if (focused instanceof EditText) {
            press = getText(R.string.hint_type_page);
        } else if (focused != null && !focused.isClickable()) {
            press = getText(R.string.hint_read);
        } else {
            press = mDefaultHint;
        }
        mHint.setText(press);
        mHint.setVisibility(press == null || press.length() == 0 ? View.GONE : View.VISIBLE);
        final Object turn = focused != null ? focused.getTag(R.id.turn_hint) : null;
        mHintTurn.setText(turn instanceof CharSequence ? (CharSequence) turn
                : getText(R.string.turn_move));
    }

    /**
     * A card the wheel stops on to read, with nothing to press: where a page would otherwise
     * start on something that acts, it starts here.
     */
    protected final View stop(View view) {
        view.setFocusable(true);
        view.setDefaultFocusHighlightEnabled(false);
        view.setBackgroundResource(R.drawable.stop_bg);
        pressHint(view, getText(R.string.hint_read));
        return view;
    }

    /** What is wrong in a field, said right under it, and the wheel put on it to fix it. */
    protected final void fieldProblem(EditText edit, CharSequence text) {
        final View error = ((View) edit.getParent()).findViewById(R.id.field_error);
        if (error instanceof TextView) {
            ((TextView) error).setText(text);
            error.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
        }
        if (text != null && text.length() > 0) {
            edit.requestFocus();
            // The field and the line under it both in sight
            final View field = (View) edit.getParent();
            field.post(() -> field.requestRectangleOnScreen(
                    new android.graphics.Rect(0, 0, field.getWidth(), field.getHeight())));
        }
    }

    protected final void clearFieldProblem(EditText edit) {
        fieldProblem(edit, "");
    }

    /**
     * A key pad's keys set in rows of so many, in the order given: fewer, wider rows on its
     * side, so the pad fits under the number it types. The wheel stays on the key it was on.
     */
    protected final void arrangeKeys(ViewGroup keys, int[] order, int perRow) {
        final View focused = getCurrentFocus();
        final int focusedId = focused != null ? focused.getId() : View.NO_ID;
        final View[] views = new View[order.length];
        for (int i = 0; i < order.length; i++) {
            views[i] = keys.findViewById(order[i]);
            if (views[i] != null && views[i].getParent() != null) {
                ((ViewGroup) views[i].getParent()).removeView(views[i]);
            }
        }
        keys.removeAllViews();
        LinearLayout line = null;
        for (int i = 0; i < views.length; i++) {
            if (i % perRow == 0) {
                line = new LinearLayout(this);
                keys.addView(line, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (views[i] != null) {
                line.addView(views[i]);
            }
        }
        if (focusedId != View.NO_ID) {
            final View again = keys.findViewById(focusedId);
            if (again != null) {
                again.requestFocus();
            }
        }
    }

    /** Since when a network has been on, from the time since boot it came up: 14:05, or 18 Nov */
    protected final String since(long upSinceElapsed) {
        final long wall = System.currentTimeMillis()
                - (SystemClock.elapsedRealtime() - upSinceElapsed);
        if (DateUtils.isToday(wall)) {
            return DateFormat.getTimeFormat(this).format(new java.util.Date(wall));
        }
        return DateUtils.formatDateTime(this, wall,
                DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_NO_YEAR | DateUtils.FORMAT_ABBREV_MONTH);
    }

    protected final TextView lead(CharSequence text) {
        final TextView lead = (TextView) getLayoutInflater().inflate(R.layout.lead, mBody, false);
        lead.setText(text);
        mBody.addView(lead);
        return lead;
    }

    /** A line of guidance; warn colours it as what went wrong. */
    protected final View note(ViewGroup parent, int icon, CharSequence text) {
        final View note = getLayoutInflater().inflate(R.layout.note, parent, false);
        ((ImageView) note.findViewById(R.id.note_icon)).setImageResource(icon);
        ((TextView) note.findViewById(R.id.note_text)).setText(text);
        parent.addView(note);
        return note;
    }

    protected final void noteText(View note, CharSequence text, boolean warn) {
        final TextView line = note.findViewById(R.id.note_text);
        line.setText(text);
        line.setTextColor(getColor(warn ? R.color.warn : R.color.text_sum));
        note.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    protected final View row(ViewGroup parent, int icon, CharSequence title, CharSequence sum) {
        final View row = getLayoutInflater().inflate(R.layout.row, parent, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        summary(row, sum);
        parent.addView(row);
        return row;
    }

    protected static void summary(View row, CharSequence text) {
        final TextView summary = row.findViewById(R.id.summary);
        summary.setText(text);
        summary.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    protected static void endText(View row, CharSequence text) {
        final TextView end = row.findViewById(R.id.end_text);
        end.setText(text);
        end.setVisibility(View.VISIBLE);
    }

    protected static void radio(View row, boolean on) {
        final View radio = row.findViewById(R.id.radio);
        radio.setVisibility(View.VISIBLE);
        radio.setSelected(on);
    }

    protected static void chevron(View row) {
        row.findViewById(R.id.chevron).setVisibility(View.VISIBLE);
    }

    /**
     * The row's switch. Alone it is a stop of the wheel and a target for a finger of its own,
     * the row then opening something; otherwise a press or a tap anywhere on the row turns it.
     */
    protected static View switchOf(View row, boolean alone) {
        final View hit = row.findViewById(R.id.switch_hit);
        hit.setVisibility(View.VISIBLE);
        hit.setFocusable(alone);
        hit.setClickable(alone);
        return hit;
    }

    protected static void switchOn(View row, boolean on) {
        row.findViewById(R.id.switch_track).setSelected(on);
    }

    /** The pill before a row's line: On, Connecting, Failed */
    protected final void pill(View row, int state) {
        final TextView pill = row.findViewById(R.id.pill);
        if (state == Status.OFF) {
            pill.setVisibility(View.GONE);
            return;
        }
        pill.setVisibility(View.VISIBLE);
        if (state == Status.ON) {
            pill.setText(R.string.state_on);
            pill.setBackgroundResource(R.drawable.pill_ok);
            pill.setTextColor(getColor(R.color.ok_text));
        } else {
            pill.setText(state == Status.CONNECTING ? R.string.state_connecting
                    : R.string.state_failed);
            pill.setBackgroundResource(R.drawable.pill_warn);
            pill.setTextColor(getColor(R.color.warn));
        }
    }

    protected final ViewGroup card(ViewGroup parent) {
        final ViewGroup card = (ViewGroup) getLayoutInflater().inflate(R.layout.card, parent,
                false);
        parent.addView(card);
        return card;
    }

    protected final TextView kv(ViewGroup card, CharSequence key, CharSequence value) {
        final View line = getLayoutInflater().inflate(R.layout.kv, card, false);
        ((TextView) line.findViewById(R.id.key)).setText(key);
        final TextView shown = line.findViewById(R.id.value);
        shown.setText(value);
        card.addView(line);
        return shown;
    }

    /** Back and the way on, side by side; the way on is returned, Back goes back. */
    protected final TextView buttons(ViewGroup parent, int go) {
        final View pair = getLayoutInflater().inflate(R.layout.buttons, parent, false);
        final View back = pair.findViewById(R.id.back);
        back.setOnClickListener(v -> back());
        pressHint(back, getText(R.string.hint_go_back));
        final TextView button = pair.findViewById(R.id.go);
        button.setText(go);
        parent.addView(pair);
        return button;
    }

    /** The network's status, filled into a card: what went wrong, or how it is going. */
    protected final void statusCard(ViewGroup card, Net net, Status.Snapshot now) {
        card.removeAllViews();
        if (now.state == Status.FAILED) {
            kv(card, getText(R.string.kv_reason), EngineUi.error(this, now.error)).setTextColor(getColor(R.color.warn));
            return;
        }
        if (net.isMullvad() && !now.place.isEmpty()) {
            kv(card, getText(R.string.kv_out), now.place);
        } else {
            kv(card, getText(R.string.kv_server), net.endpoint());
        }
        if (now.state != Status.ON) {
            return;
        }
        final TextView answer = kv(card, getText(R.string.kv_last_answer), age(now.handshake));
        if (now.stalled) {
            answer.setText(R.string.no_answer);
            answer.setTextColor(getColor(R.color.warn));
        }
        kv(card, getText(R.string.kv_data), getString(R.string.data_value,
                Formatter.formatShortFileSize(this, now.tx),
                Formatter.formatShortFileSize(this, now.rx)));
    }

    private String age(long handshake) {
        if (handshake <= 0) {
            return getString(R.string.not_yet);
        }
        final long seconds = Math.max(0, System.currentTimeMillis() / 1000 - handshake);
        if (seconds < 120) {
            return getString(R.string.seconds_ago, (int) seconds);
        }
        if (seconds < 7200) {
            return getString(R.string.minutes_ago, (int) (seconds / 60));
        }
        return getString(R.string.hours_ago, (int) (seconds / 3600));
    }

    /** This page shows the status: it is read every two seconds while it is on screen. */
    protected final void watchStatus() {
        mWatching = true;
    }

    protected void onStatus(Status.Snapshot now) {
    }

    // Turning a network on

    /** On, after the system's VPN dialog where the user has not said yes to this app yet. */
    protected final void turnOn(String id) {
        final Intent consent = VpnService.prepare(this);
        if (consent == null) {
            TunnelService.start(this, id);
            onTurnedOn(id, true);
            return;
        }
        mPending = id;
        try {
            startActivityForResult(consent, REQ_VPN);
        } catch (ActivityNotFoundException e) {
            mPending = null;
            onTurnedOn(id, false);
        }
    }

    /** After turnOn: started, or not as the user said no. */
    protected void onTurnedOn(String id, boolean started) {
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_VPN) {
            final String id = mPending;
            mPending = null;
            if (id == null) {
                return;
            }
            if (result == RESULT_OK) {
                TunnelService.start(this, id);
                onTurnedOn(id, true);
            } else {
                Toast.makeText(this, R.string.consent_needed, Toast.LENGTH_LONG).show();
                onTurnedOn(id, false);
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    protected final void later(Runnable work, long ms) {
        mMain.postDelayed(work, ms);
    }

    // The wheel and the side button

    /** A wheel click, down +1, for a page that turns something with it. */
    protected boolean onTurn(int clicks) {
        return false;
    }

    /** A press of the side button. Return false to press what the wheel is on. */
    protected boolean onPress() {
        return false;
    }

    /** A hold of the side button. Return false to go back. */
    protected boolean onHold() {
        return false;
    }

    /** Back, by a hold, a Back button or the system's way back. */
    protected void back() {
        finish();
    }

    @Override
    public void onBackPressed() {
        back();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        // Physical keyboard editing belongs to EditText; the side-button POWER
        // and touchscreen keyboard/wheel sources retain the page contract.
        final InputDevice device = event.getDevice();
        if (getCurrentFocus() instanceof EditText && device != null && !device.isVirtual()
                && device.getKeyboardType() == InputDevice.KEYBOARD_TYPE_ALPHABETIC
                && code != KeyEvent.KEYCODE_POWER) {
            return super.dispatchKeyEvent(event);
        }
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down && !onTurn(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1)) {
                move(code == KeyEvent.KEYCODE_DPAD_DOWN);
            }
            return true;
        }
        // The side button comes as the select key, which a keyboard's Enter is too
        if (code == KeyEvent.KEYCODE_POWER || code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER) {
            // The system says which it was: a hold comes flagged
            final boolean held = (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0;
            final boolean touch = event.getSource() == InputDevice.SOURCE_TOUCHSCREEN;
            if (down) {
                if (event.getRepeatCount() == 0) {
                    mHeld = false;
                } else if (held && !mHeld) {
                    hold(touch);
                }
            } else {
                if (!mHeld && !event.isCanceled()) {
                    if (held) {
                        hold(touch);
                    } else {
                        press();
                    }
                }
                // The gesture is over: a next press whose down the window took to bring
                // the highlight back must not count as part of this hold
                mHeld = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * To the next thing in the page's order, or the one before; past the last, the page
     * scrolls on so that what is under it can be read. Never round to the start.
     */
    private void move(boolean forward) {
        final View focused = getCurrentFocus();
        final ArrayList<View> all = getWindow().getDecorView().getFocusables(View.FOCUS_FORWARD);
        final int at = focused != null ? all.indexOf(focused) : -1;
        if (at < 0) {
            if (!all.isEmpty()) {
                all.get(forward ? 0 : all.size() - 1).requestFocus();
            } else {
                scroll(forward);
            }
            return;
        }
        final int next = at + (forward ? 1 : -1);
        if (next >= 0 && next < all.size()) {
            all.get(next).requestFocus(forward ? View.FOCUS_DOWN : View.FOCUS_UP);
        } else {
            scroll(forward);
        }
    }

    private void scroll(boolean forward) {
        if (mScroll != null) {
            mScroll.smoothScrollBy(0, (forward ? 1 : -1) * Math.max(1, mScroll.getHeight() / 3));
        }
    }

    private void press() {
        if (onPress()) {
            return;
        }
        final View focused = getCurrentFocus();
        if (focused == null) {
            move(true);
            return;
        }
        focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        if (focused instanceof EditText) {
            // Into the field: the system's keyboard comes up
            getSystemService(InputMethodManager.class).showSoftInput(focused, 0);
        } else {
            focused.performClick();
        }
    }

    private void hold(boolean touch) {
        mHeld = true;
        // A resting finger does not go back: the way back by touch is the system's
        if (!onHold() && !touch) {
            back();
        }
    }

    /** Once, for a double press or tap: true when the last one was within the time. */
    protected static boolean again(long[] last, long ms) {
        final long now = SystemClock.elapsedRealtime();
        final boolean within = now - last[0] < ms;
        last[0] = within ? 0 : now;
        return within;
    }
}
