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
            Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.POST_NOTIFICATIONS};

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ViewGroup mNotices;
    private String mListed = "";
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
        findViewById(R.id.volume_row).setOnClickListener(v -> hold(!mHeld));
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

    @Override
    protected void onResume() {
        super.onResume();
        PhoneService.sListener = () -> mHandler.post(mRender);
        render();
        mHandler.postDelayed(mClock, 1000);
    }

    @Override
    protected void onPause() {
        PhoneService.sListener = null;
        mHandler.removeCallbacksAndMessages(null);
        hold(false);
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

    private void hold(boolean held) {
        mHeld = held;
        ((TextView) findViewById(R.id.volume_hint)).setText(held ? R.string.volume_held
                : R.string.volume_hint);
        findViewById(R.id.volume_row).setActivated(held);
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

    private void render() {
        final Link link = link();
        final int state = link == null ? -1 : link.state();
        final boolean on = state == Link.ON;
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
            mListed = "";
            mNotices.removeAllViews();
            hold(false);
            mShown = false;
            if (getCurrentFocus() == null) {
                findViewById(R.id.link).requestFocus();
            }
            return;
        }
        final Link.Music music = link.music;
        ((TextView) findViewById(R.id.track)).setText(music.title.isEmpty()
                ? getString(R.string.nothing_playing) : music.title);
        final TextView artist = findViewById(R.id.artist);
        artist.setText(music.artist.isEmpty() ? music.player : music.artist);
        artist.setVisibility(artist.getText().length() == 0 ? View.GONE : View.VISIBLE);
        final ImageView toggle = findViewById(R.id.toggle);
        toggle.setImageResource(music.playing() ? R.drawable.ic_pause : R.drawable.ic_play);
        toggle.setContentDescription(getString(music.playing() ? R.string.pause
                : R.string.play));
        key(R.id.prev, music.commands.contains(Ams.PREVIOUS));
        key(R.id.next, music.commands.contains(Ams.NEXT));
        key(R.id.toggle, music.commands.contains(Ams.TOGGLE));
        if (!mShown && findViewById(R.id.toggle).isFocusable()) {
            // The phone is here: the press belongs to its music, not to Turn off, where it was
            mShown = findViewById(R.id.toggle).requestFocus();
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

    private void notices(List<Inbox.Notice> notices) {
        final StringBuilder listed = new StringBuilder();
        for (Inbox.Notice notice : notices) {
            listed.append(notice.uid()).append(notice.title()).append(notice.message())
                    .append(notice.appName()).append('\n');
        }
        if (listed.toString().equals(mListed)) {
            return;
        }
        mListed = listed.toString();
        // The wheel stays on the row it was on, by its place in the list
        final int focused = mNotices.indexOfChild(getCurrentFocus());
        mNotices.removeAllViews();
        final LocalDate today = LocalDate.now();
        for (Inbox.Notice notice : notices) {
            if ((notice.title().isEmpty() && notice.message().isEmpty())
                    || notice.category() == Ancs.INCOMING_CALL) {
                continue;
            }
            final View row = getLayoutInflater().inflate(R.layout.notice, mNotices, false);
            ((ImageView) row.findViewById(R.id.icon)).setImageResource(
                    PhoneService.icon(notice.category()));
            ((TextView) row.findViewById(R.id.title)).setText(notice.title().isEmpty()
                    ? notice.appName() : notice.title());
            ((TextView) row.findViewById(R.id.text)).setText(PhoneService.text(notice));
            if (notice.date() != null) {
                ((TextView) row.findViewById(R.id.time)).setText(notice.date().format(
                        DateTimeFormatter.ofPattern(notice.date().toLocalDate().equals(today)
                                ? "HH:mm" : "d MMM")));
            }
            mNotices.addView(row);
        }
        findViewById(R.id.empty).setVisibility(mNotices.getChildCount() == 0 ? View.VISIBLE
                : View.GONE);
        if (focused >= 0 && mNotices.getChildCount() > 0) {
            mNotices.getChildAt(Math.min(focused, mNotices.getChildCount() - 1)).requestFocus();
        }
    }
}
