// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.DiskInfo;
import android.os.storage.StorageManager;
import android.os.storage.VolumeInfo;
import android.provider.DocumentsContract;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The sheet for what is in the port. A computer: what the r1 is to it, power only until chosen
 * otherwise and again once the r1 is locked. A stick: open it, copy the new photos, eject.
 */
public final class PortActivity extends Activity {
    static final String EXTRA_VOLUME = "volume";
    static final String ACTION_GONE = "cat.kenny.r1.port.GONE";

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ViewGroup mRows;
    private String mVolume;
    private Photos mPhotos;
    private View[] mRoles;

    private final BroadcastReceiver mChanged = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_GONE.equals(intent.getAction())) {
                final String what = intent.getStringExtra("what");
                final VolumeInfo volume = volume();
                if (mVolume != null && (volume == null || volume.getPath() == null
                        || volume.getPath().getPath().equals(what))) {
                    finish();
                }
            } else {
                // The role settles a moment after it was asked for
                mHandler.removeCallbacks(mShow);
                mHandler.postDelayed(mShow, 300);
                // A computer that stays away was unplugged: nothing left to choose for
                mHandler.removeCallbacks(mAway);
                if (mVolume == null && mPlugged
                        && !intent.getBooleanExtra(UsbManager.USB_CONNECTED, false)) {
                    mHandler.postDelayed(mAway, Port.AWAY_MS);
                }
                mPlugged |= intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
            }
        }
    };
    private final Runnable mShow = this::showRole;
    private final Runnable mAway = this::finish;
    /** Whether a computer has been there while the sheet was up. */
    private boolean mPlugged;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.sheet);
        mRows = findViewById(R.id.rows);
        findViewById(R.id.outside).setOnClickListener(v -> finish());
        build(getIntent());
        final IntentFilter filter = new IntentFilter(ACTION_GONE);
        filter.addAction(UsbManager.ACTION_USB_STATE);
        registerReceiver(mChanged, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        build(intent);
    }

    @Override
    protected void onDestroy() {
        unregisterReceiver(mChanged);
        mHandler.removeCallbacksAndMessages(null);
        if (mPhotos != null) {
            mPhotos.stop();
        }
        super.onDestroy();
    }

    private void build(Intent intent) {
        if (mPhotos != null) {
            mPhotos.stop();
            mPhotos = null;
        }
        mRows.removeAllViews();
        mVolume = intent.getStringExtra(EXTRA_VOLUME);
        if (mVolume == null) {
            computer();
        } else if (volume() == null) {
            finish();
            return;
        } else {
            stick();
        }
        mRows.getChildAt(0).requestFocus();
    }

    private View row(int icon, int title, int summary, Runnable chosen) {
        final View row = getLayoutInflater().inflate(R.layout.row, mRows, false);
        ((ImageView) row.findViewById(R.id.icon)).setImageResource(icon);
        ((TextView) row.findViewById(R.id.title)).setText(title);
        if (summary != 0) {
            summary(row, getString(summary));
        }
        row.setOnClickListener(v -> chosen.run());
        mRows.addView(row);
        return row;
    }

    private static void summary(View row, String text) {
        final TextView summary = row.findViewById(R.id.summary);
        summary.setText(text);
        summary.setVisibility(View.VISIBLE);
    }

    // ---- a computer

    private void computer() {
        ((TextView) findViewById(R.id.head)).setText(R.string.computer);
        ((TextView) findViewById(R.id.sum)).setText(Port.power(this));
        findViewById(R.id.hint).setVisibility(View.VISIBLE);
        ((TextView) findViewById(R.id.hint_text)).setText(Port.debugging(this)
                ? R.string.debug_hint : R.string.lock_hint);
        mRoles = new View[4];
        mRoles[Port.POWER] = role(R.drawable.ic_bolt, R.string.power_only, 0, Port.POWER);
        mRoles[Port.FILES] = role(R.drawable.ic_folder, R.string.files, R.string.files_sum,
                Port.FILES);
        mRoles[Port.INTERNET] = role(R.drawable.ic_share, R.string.internet,
                R.string.internet_sum, Port.INTERNET);
        if (UsbManager.isUvcSupportEnabled()) {
            mRoles[Port.WEBCAM] = role(R.drawable.ic_videocam, R.string.webcam, 0, Port.WEBCAM);
        }
        // The two the USB devices page runs: it has their settings and what they need chosen
        final Intent emulator = new Intent().setComponent(new ComponentName(
                "cat.kenny.r1.usbemu", "cat.kenny.r1.usbemu.UsbActivity"));
        if (emulator.resolveActivity(getPackageManager()) != null) {
            final Runnable open = () -> {
                startActivity(emulator);
                finish();
            };
            row(R.drawable.ic_speaker, R.string.sound_card, 0, open);
            row(R.drawable.ic_album, R.string.disk, R.string.disk_sum, open);
        }
        showRole();
    }

    private View role(int icon, int title, int summary, int role) {
        final View row = row(icon, title, summary, () -> {
            Port.become(this, role);
            mark(role);
        });
        row.findViewById(R.id.radio).setVisibility(View.VISIBLE);
        return row;
    }

    private void showRole() {
        if (mVolume == null && mRoles != null) {
            mark(Port.role(this));
        }
    }

    private void mark(int role) {
        for (int i = 0; i < mRoles.length; i++) {
            if (mRoles[i] != null) {
                final boolean on = i == role;
                mRoles[i].findViewById(R.id.radio).setSelected(on);
                ((ImageView) mRoles[i].findViewById(R.id.icon)).setColorFilter(on
                        ? getColor(android.R.color.system_accent1_300) : 0xFFD2D2D2);
            }
        }
    }

    // ---- a stick

    private VolumeInfo volume() {
        return mVolume == null ? null
                : getSystemService(StorageManager.class).findVolumeById(mVolume);
    }

    private void stick() {
        final VolumeInfo volume = volume();
        final DiskInfo disk = volume.getDisk();
        final String name = disk != null && disk.getDescription() != null
                ? disk.getDescription() : getString(R.string.stick);
        final long free = volume.getPath() == null ? 0 : volume.getPath().getUsableSpace();
        ((TextView) findViewById(R.id.head)).setText(disk == null || disk.size <= 0 ? name
                : getString(R.string.stick_size, name,
                        Formatter.formatShortFileSize(this, disk.size)));
        ((TextView) findViewById(R.id.sum)).setText(getString(R.string.stick_powered,
                Formatter.formatShortFileSize(this, free)));
        findViewById(R.id.hint).setVisibility(View.GONE);
        mRoles = null;
        row(R.drawable.ic_folder, R.string.open_files, 0, () -> {
            startActivity(new Intent(Intent.ACTION_VIEW).setDataAndType(
                    DocumentsContract.buildRootUri("com.android.externalstorage.documents",
                            volume.getFsUuid()), DocumentsContract.Root.MIME_TYPE_ITEM)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            finish();
        });
        final View copy = row(R.drawable.ic_photo, R.string.copy_photos,
                R.string.copy_counting, () -> mPhotos.copy());
        mPhotos = new Photos(this, volume, text -> mHandler.post(() -> summary(copy, text)));
        mPhotos.count();
        final View[] eject = new View[1];
        eject[0] = row(R.drawable.ic_eject, R.string.eject, 0, () -> {
            if (mPhotos.busy()) {
                return;
            }
            summary(eject[0], getString(R.string.ejecting));
            new Thread(() -> {
                boolean done = true;
                try {
                    getSystemService(StorageManager.class).unmount(volume.getId());
                } catch (RuntimeException e) {
                    done = false;
                }
                final boolean ok = done;
                mHandler.post(() -> {
                    summary(eject[0], getString(ok ? R.string.ejected : R.string.eject_failed));
                    if (ok) {
                        mHandler.postDelayed(this::finish, 1500);
                    }
                });
            }, "eject").start();
        });
    }
}
