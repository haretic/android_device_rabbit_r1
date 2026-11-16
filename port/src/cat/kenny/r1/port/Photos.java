// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.port;

import android.content.Context;
import android.os.UserHandle;
import android.os.storage.VolumeInfo;
import android.text.format.Formatter;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * The r1's photos and videos that are not on the stick yet, and their copying: into DCIM/r1 on
 * the stick, under the names they have, one already there with the same size left alone. Works
 * on the storage itself, under what apps see of it, as the stick may not be shown to apps yet.
 */
final class Photos {
    private final Context mContext;
    private final File mFrom;
    private final File mTo;
    private final File mStick;
    private final Consumer<String> mSay;
    private final List<File> mNew = new ArrayList<>();
    private long mBytes;
    private volatile boolean mStopped;
    private volatile boolean mBusy;
    private boolean mCounted;

    Photos(Context context, VolumeInfo stick, Consumer<String> say) {
        mContext = context;
        mFrom = new File("/data/media/" + UserHandle.myUserId() + "/DCIM");
        mStick = stick.getInternalPath();
        mTo = new File(mStick, "DCIM/r1");
        mSay = say;
    }

    boolean busy() {
        return mBusy;
    }

    void stop() {
        mStopped = true;
    }

    /** Finds what is new, and says how much that is. */
    void count() {
        mBusy = true;
        new Thread(() -> {
            find(mFrom, "");
            mCounted = true;
            mBusy = false;
            if (mStopped) {
                return;
            }
            final long free = mStick.getUsableSpace();
            mSay.accept(mNew.isEmpty() ? mContext.getString(R.string.copy_none)
                    : mBytes > free ? mContext.getString(R.string.copy_no_room,
                            Formatter.formatShortFileSize(mContext, mBytes),
                            Formatter.formatShortFileSize(mContext, free))
                    : mContext.getResources().getQuantityString(R.plurals.copy_sum, mNew.size(),
                            mNew.size(), Formatter.formatShortFileSize(mContext, mBytes)));
        }, "photos-count").start();
    }

    private void find(File dir, String under) {
        final File[] files = dir.listFiles();
        if (files == null || mStopped) {
            return;
        }
        for (File file : files) {
            final String name = file.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (file.isDirectory()) {
                find(file, under + name + "/");
            } else if (file.length() > 0
                    && new File(mTo, under + name).length() != file.length()) {
                mNew.add(file);
                mBytes += file.length();
            }
        }
    }

    void copy() {
        if (mBusy || !mCounted || mNew.isEmpty() || mBytes > mStick.getUsableSpace()) {
            return;
        }
        mBusy = true;
        new Thread(() -> {
            final int all = mNew.size();
            int done = 0;
            final byte[] buffer = new byte[1 << 16];
            final int root = mFrom.getPath().length() + 1;
            try {
                for (File file : mNew) {
                    if (mStopped) {
                        break;
                    }
                    mSay.accept(mContext.getString(R.string.copy_progress, done + 1, all));
                    final File to = new File(mTo, file.getPath().substring(root));
                    to.getParentFile().mkdirs();
                    // Under another name until it is whole: a stick pulled out leaves no half
                    // photo that looks like one
                    final File part = new File(to.getPath() + ".part");
                    try (FileInputStream in = new FileInputStream(file);
                            FileOutputStream out = new FileOutputStream(part)) {
                        for (int n; (n = in.read(buffer)) > 0 && !mStopped; ) {
                            out.write(buffer, 0, n);
                        }
                        out.getFD().sync();
                    }
                    if (mStopped) {
                        part.delete();
                        break;
                    }
                    if (!part.renameTo(to)) {
                        throw new IOException("rename");
                    }
                    to.setLastModified(file.lastModified());
                    done++;
                }
                if (!mStopped) {
                    mSay.accept(mContext.getResources().getQuantityString(R.plurals.copy_done,
                            done, done));
                }
            } catch (IOException | RuntimeException e) {
                mSay.accept(mContext.getString(R.string.copy_failed, done, all));
            }
            mNew.clear();
            mBytes = 0;
            mBusy = false;
        }, "photos-copy").start();
    }
}
