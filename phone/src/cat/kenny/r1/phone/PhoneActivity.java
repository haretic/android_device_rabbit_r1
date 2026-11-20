// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothManager;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateUtils;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import cat.kenny.r1.phone.proto.Ams;
import cat.kenny.r1.phone.proto.Ancs;
import cat.kenny.r1.phone.proto.Inbox;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * The Phone page: the phone's music with its keys, its volume on the wheel, and under them
 * what the phone has to tell.
 */
public final class PhoneActivity extends Activity {
    private static final String[] PERMISSIONS = {Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.POST_NOTIFICATIONS};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ViewGroup mNotices;
    private String mListed = "";
    /** The rows opened by a press or a tap: all of their text, and their own Clear. */
    private final java.util.Set<Long> mOpen = new java.util.HashSet<>();
    /** The wheel is the phone's volume until the next press. */
    private boolean mHeld;

    private final Runnable mRender = this::render;
    private final Runnable mClock = new Runnable() {
        @Override
        public void run() {
            progress();
            mHandler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.phone);
        mNotices = findViewById(R.id.notices);
        findViewById(R.id.link).setOnClickListener(v -> {
            if (PhoneService.sService != null) {
                PhoneService.want(this, false);
            } else if (allowed()) {
                PhoneService.want(this, true);
            } else {
                requestPermissions(PERMISSIONS, 1);
            }
        });
        findViewById(R.id.prev).setOnClickListener(v -> press(Ams.PREVIOUS));
        findViewById(R.id.next).setOnClickListener(v -> press(Ams.NEXT));
        findViewById(R.id.toggle).setOnClickListener(v -> press(Ams.TOGGLE));
        findViewById(R.id.big_prev).setOnClickListener(v -> press(Ams.PREVIOUS));
        findViewById(R.id.big_next).setOnClickListener(v -> press(Ams.NEXT));
        findViewById(R.id.big_toggle).setOnClickListener(v -> press(Ams.TOGGLE));
        findViewById(R.id.volume_row).setOnClickListener(v -> hold(!mHeld));
        findViewById(R.id.volume_row).setOnTouchListener(this::slide);
        findViewById(R.id.settings).setOnClickListener(v -> startActivity(
                new android.content.Intent(this, PhoneSettings.class)));
    }

    private boolean allowed() {
        for (String permission : PERMISSIONS) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE)
                == PackageManager.PERMISSION_GRANTED) {
            PhoneService.want(this, true);
        }
    }

    /** Whether the music's keys are the large ones under the song, from the settings. */
    static boolean bigKeys(android.content.Context context) {
        return context.getSharedPreferences("phone", 0).getBoolean(BIG_KEYS, false);
    }

    static final String BIG_KEYS = "big_keys";

    /** The play key of the keys shown: the large one or the one beside the song. */
    private int toggleKey() {
        return bigKeys(this) ? R.id.big_toggle : R.id.toggle;
    }

    /** The small keys beside the song, or the large ones under it, as the settings say. */
    private void keySize() {
        final boolean big = bigKeys(this);
        for (int id : new int[] {R.id.prev, R.id.toggle, R.id.next}) {
            findViewById(id).setVisibility(big ? View.GONE : View.VISIBLE);
        }
        findViewById(R.id.big_keys).setVisibility(big ? View.VISIBLE : View.GONE);
        findViewById(R.id.volume_row).setNextFocusUpId(big ? R.id.big_next : R.id.next);
    }

    @Override
    protected void onResume() {
        super.onResume();
        keySize();
        PhoneService.sListener = () -> mHandler.post(mRender);
        render();
        mHandler.postDelayed(mClock, 1000);
    }

    @Override
    protected void onPause() {
        PhoneService.sListener = null;
        mHandler.removeCallbacksAndMessages(null);
        hold(false);
        if (link() != null) {
            link().findable(false);
        }
        super.onPause();
    }

    private Link link() {
        return PhoneService.sService == null ? null : PhoneService.sService.link;
    }

    private void press(int command) {
        final Link link = link();
        if (link != null) {
            link.press(command);
        }
    }

    /** Where the finger last moved the phone's volume a step, or NaN while it only rests. */
    private float mSlideAt = Float.NaN, mSlideFrom;

    /**
     * A finger slid along the volume bar: the phone takes its volume in steps, sixteen from
     * silent to full, so a sixteenth of the bar is one step, louder to the right. A tap stays
     * a tap, which hands the volume to the wheel.
     */
    private boolean slide(View row, android.view.MotionEvent event) {
        final View bar = findViewById(R.id.volume);
        final float step = Math.max(1, bar.getWidth() / 16f);
        switch (event.getActionMasked()) {
            case android.view.MotionEvent.ACTION_DOWN:
                mSlideFrom = event.getX();
                mSlideAt = Float.NaN;
                return false;
            case android.view.MotionEvent.ACTION_MOVE:
                if (Float.isNaN(mSlideAt)) {
                    if (Math.abs(event.getX() - mSlideFrom)
                            < android.view.ViewConfiguration.get(this).getScaledTouchSlop()) {
                        return false;
                    }
                    // A slide from here on: not the tap, and the list under it stays put
                    mSlideAt = mSlideFrom;
                    row.setPressed(false);
                    row.getParent().requestDisallowInterceptTouchEvent(true);
                }
                final boolean rtl = row.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
                while (Math.abs(event.getX() - mSlideAt) >= step) {
                    final boolean right = event.getX() > mSlideAt;
                    mSlideAt += right ? step : -step;
                    press(right != rtl ? Ams.VOLUME_UP : Ams.VOLUME_DOWN);
                }
                return true;
            case android.view.MotionEvent.ACTION_UP:
            case android.view.MotionEvent.ACTION_CANCEL:
                final boolean slid = !Float.isNaN(mSlideAt);
                mSlideAt = Float.NaN;
                return slid;
            default:
                return false;
        }
    }

    private void hold(boolean held) {
        mHeld = held;
        ((TextView) findViewById(R.id.volume_hint)).setText(held ? R.string.volume_held
                : R.string.volume_hint);
        findViewById(R.id.volume_row).setActivated(held);
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent event) {
        if (mHeld && event.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) {
            // A finger elsewhere is done with the wheel as the volume: it lets go, as the
            // hint says a press does, so the wheel moves on the page again
            final android.graphics.Rect row = new android.graphics.Rect();
            if (!findViewById(R.id.volume_row).getGlobalVisibleRect(row)
                    || !row.contains(Math.round(event.getRawX()), Math.round(event.getRawY()))) {
                hold(false);
            }
        }
        return super.dispatchTouchEvent(event);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        if (mHeld && (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN)) {
            // The wheel's steps: up is louder
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                press(code == KeyEvent.KEYCODE_DPAD_UP ? Ams.VOLUME_UP : Ams.VOLUME_DOWN);
            }
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN && (code == KeyEvent.KEYCODE_DPAD_DOWN
                || code == KeyEvent.KEYCODE_DPAD_UP)) {
            // An opened row's Clear sits inside it, where a step down would pass it by
            final View focus = getCurrentFocus();
            if (focus != null && code == KeyEvent.KEYCODE_DPAD_DOWN
                    && focus.getParent() == mNotices && focus.isActivated()
                    && focus.findViewById(R.id.clear).requestFocus()) {
                return true;
            }
            if (focus != null && code == KeyEvent.KEYCODE_DPAD_UP && focus.getId() == R.id.clear
                    && ((View) focus.getParent()).requestFocus()) {
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private String name() {
        String name = null;
        try {
            name = getSystemService(BluetoothManager.class).getAdapter().getName();
        } catch (RuntimeException e) {
            // Not allowed to ask Bluetooth yet: the r1's name as Settings has it
        }
        if (name == null) {
            name = Settings.Global.getString(getContentResolver(), Settings.Global.DEVICE_NAME);
        }
        return name == null ? "r1" : name;
    }

    /** Whether the highlight was moved to the music keys since the phone came. */
    private boolean mShown;

    /** Initial safe focus for each off/waiting entry, without stealing later wheel travel. */
    private int mSetupFocusTarget = View.NO_ID;

    private void render() {
        final Link link = link();
        final int state = link == null ? -1 : link.state();
        final boolean on = state == Link.ON;
        if (link != null) {
            // While this page waits for a phone, the phone can find the r1
            link.findable(isResumed() && (state == Link.WAITING || state == Link.PAIRING));
        }
        ((TextView) findViewById(R.id.link)).setText(link == null ? R.string.turn_on
                : R.string.turn_off);
        findViewById(R.id.setup).setVisibility(on ? View.GONE : View.VISIBLE);
        findViewById(R.id.music).setVisibility(on ? View.VISIBLE : View.GONE);
        mNotices.setVisibility(on ? View.VISIBLE : View.GONE);
        final TextView head = findViewById(R.id.state);
        final TextView text = findViewById(R.id.state_text);
        if (link == null) {
            head.setVisibility(View.GONE);
            text.setText(R.string.intro);
        } else if (!on) {
            head.setVisibility(View.VISIBLE);
            head.setText(state == Link.BLUETOOTH_OFF ? R.string.bluetooth_off
                    : state == Link.PAIRING ? R.string.pairing
                    : state == Link.NO_SERVICES ? R.string.no_services : R.string.waiting);
            text.setText(state == Link.BLUETOOTH_OFF ? getString(R.string.bluetooth_off_text)
                    : state == Link.PAIRING ? getString(R.string.pairing_text)
                    : state == Link.NO_SERVICES ? getString(R.string.no_services_text)
                    : getString(R.string.waiting_text, name()));
        }
        if (!on) {
            findViewById(R.id.empty).setVisibility(View.GONE);
            findViewById(R.id.clear_all).setVisibility(View.GONE);
            findViewById(R.id.notices_hint).setVisibility(View.GONE);
            mOpen.clear();
            mListed = "";
            mNotices.removeAllViews();
            hold(false);
            mShown = false;
            final int target = link == null ? R.id.link : R.id.settings;
            if (mSetupFocusTarget != target || getCurrentFocus() == null) {
                // Hiding music can move focus to the first button (Turn off). Set the
                // safe entry target once even if that automatic focus already exists.
                if (findViewById(target).requestFocus()) {
                    mSetupFocusTarget = target;
                }
            }
            return;
        }
        mSetupFocusTarget = View.NO_ID;
        final Link.Music music = link.music;
        ((TextView) findViewById(R.id.track)).setText(music.title.isEmpty()
                ? getString(R.string.nothing_playing) : music.title);
        final TextView artist = findViewById(R.id.artist);
        artist.setText(music.artist.isEmpty() ? music.player : music.artist);
        artist.setVisibility(artist.getText().length() == 0 ? View.GONE : View.VISIBLE);
        for (int id : new int[] {R.id.toggle, R.id.big_toggle}) {
            final ImageView toggle = findViewById(id);
            toggle.setImageResource(music.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
            toggle.setContentDescription(getString(music.playing() ? R.string.pause
                    : R.string.play));
        }
        key(R.id.prev, music.commands.contains(Ams.PREVIOUS));
        key(R.id.next, music.commands.contains(Ams.NEXT));
        key(R.id.toggle, music.commands.contains(Ams.TOGGLE));
        key(R.id.big_prev, music.commands.contains(Ams.PREVIOUS));
        key(R.id.big_next, music.commands.contains(Ams.NEXT));
        key(R.id.big_toggle, music.commands.contains(Ams.TOGGLE));
        if (!mShown && findViewById(toggleKey()).isFocusable()) {
            // The phone is here: the press belongs to its music, not to Turn off, where it was
            mShown = findViewById(toggleKey()).requestFocus();
        }
        ((ProgressBar) findViewById(R.id.volume)).setProgress(music.volume == null ? 0
                : Math.round(music.volume * 100));
        progress();
        notices(link.inbox.notifications());
    }

    private void key(int id, boolean there) {
        findViewById(id).setAlpha(there ? 1f : 0.35f);
    }

    private void progress() {
        final Link link = link();
        if (link == null || link.state() != Link.ON) {
            return;
        }
        final Link.Music music = link.music;
        final boolean known = music.duration != null && music.duration > 0 && music.info != null;
        final double at = music.elapsed();
        ((ProgressBar) findViewById(R.id.progress)).setProgress(known
                ? (int) (1000 * at / music.duration) : 0);
        ((TextView) findViewById(R.id.elapsed)).setText(music.info == null ? ""
                : DateUtils.formatElapsedTime((long) at));
        ((TextView) findViewById(R.id.duration)).setText(known
                ? DateUtils.formatElapsedTime(music.duration.longValue()) : "");
    }

    /** Newest first by the phone's own time; what has no time yet has only just come in. */
    private static List<Inbox.Notice> newestFirst(List<Inbox.Notice> notices) {
        final List<Inbox.Notice> sorted = new java.util.ArrayList<>(notices);
        sorted.sort((a, b) -> a.date() == null ? (b.date() == null ? 0 : -1)
                : b.date() == null ? 1 : b.date().compareTo(a.date()));
        return sorted;
    }

    private void clear(List<Inbox.Notice> notices) {
        final Link link = link();
        if (link == null) {
            return;
        }
        for (Inbox.Notice notice : notices) {
            if (notice.category() != Ancs.INCOMING_CALL) {
                link.clear(notice);
            }
        }
        getWindow().getDecorView().performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM);
        render();
    }

    /** The list's row a view is in, or the view itself if it is in none. */
    private View rowOf(View view) {
        View at = view;
        while (at != null && at.getParent() != mNotices) {
            at = at.getParent() instanceof View ? (View) at.getParent() : null;
        }
        return at == null ? view : at;
    }

    /**
     * A row opened shows all of what the phone sent and lets the wheel onto its Clear, the step
     * after the row; closed it is two lines, and the wheel goes from row to row.
     */
    private static void open(View row, boolean open) {
        ((TextView) row.findViewById(R.id.title)).setMaxLines(open ? Integer.MAX_VALUE : 1);
        ((TextView) row.findViewById(R.id.text)).setMaxLines(open ? Integer.MAX_VALUE : 2);
        row.findViewById(R.id.clear).setFocusable(open);
        row.setActivated(open);
    }

    private void notices(List<Inbox.Notice> unsorted) {
        final List<Inbox.Notice> notices = newestFirst(unsorted);
        final StringBuilder listed = new StringBuilder();
        for (Inbox.Notice notice : notices) {
            listed.append(notice.uid()).append(notice.title()).append(notice.message())
                    .append(notice.appName()).append(PhoneService.sService != null
                            && PhoneService.sService.icons.get(notice.appId()) != null)
                    .append('\n');
        }
        if (listed.toString().equals(mListed)) {
            return;
        }
        mListed = listed.toString();
        // The wheel stays on the row it was on, by its place in the list, also from its Clear
        final int focused = mNotices.indexOfChild(rowOf(getCurrentFocus()));
        mNotices.removeAllViews();
        final LocalDate today = LocalDate.now();
        final java.util.Set<Long> listedUids = new java.util.HashSet<>();
        for (Inbox.Notice notice : notices) {
            if ((notice.title().isEmpty() && notice.message().isEmpty())
                    || notice.category() == Ancs.INCOMING_CALL) {
                continue;
            }
            final View row = getLayoutInflater().inflate(R.layout.notice, mNotices, false);
            final ImageView icon = row.findViewById(R.id.icon);
            final android.graphics.Bitmap app = PhoneService.sService == null ? null
                    : PhoneService.sService.icons.get(notice.appId());
            if (app != null) {
                icon.setImageTintList(null);
                icon.setImageBitmap(app);
            } else {
                icon.setImageResource(PhoneService.icon(notice.category()));
            }
            ((TextView) row.findViewById(R.id.title)).setText(notice.title().isEmpty()
                    ? notice.appName() : notice.title());
            ((TextView) row.findViewById(R.id.text)).setText(PhoneService.text(notice));
            if (notice.date() != null) {
                ((TextView) row.findViewById(R.id.time)).setText(notice.date().format(
                        DateTimeFormatter.ofPattern(notice.date().toLocalDate().equals(today)
                                ? "HH:mm" : "d MMM")));
            }
            row.findViewById(R.id.clear).setOnClickListener(v -> clear(List.of(notice)));
            final long uid = notice.uid();
            listedUids.add(uid);
            open(row, mOpen.contains(uid));
            row.setOnClickListener(v -> {
                final boolean open = !mOpen.contains(uid);
                if (open) {
                    mOpen.add(uid);
                } else {
                    mOpen.remove(uid);
                }
                open(row, open);
            });
            mNotices.addView(row);
        }
        mOpen.retainAll(listedUids);
        findViewById(R.id.notices_hint).setVisibility(mNotices.getChildCount() == 0
                ? View.GONE : View.VISIBLE);
        final View clearAll = findViewById(R.id.clear_all);
        final boolean hadFocus = clearAll.isFocused();
        clearAll.setVisibility(mNotices.getChildCount() == 0 ? View.GONE : View.VISIBLE);
        clearAll.setOnClickListener(v -> clear(notices));
        if (hadFocus && mNotices.getChildCount() == 0) {
            findViewById(R.id.settings).requestFocus();
        }
        findViewById(R.id.empty).setVisibility(mNotices.getChildCount() == 0 ? View.VISIBLE
                : View.GONE);
        if (focused >= 0 && mNotices.getChildCount() > 0) {
            mNotices.getChildAt(Math.min(focused, mNotices.getChildCount() - 1)).requestFocus();
        }
    }
}
