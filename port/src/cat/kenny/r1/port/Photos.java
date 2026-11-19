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
    /** Port.stick of the stick, for a cut-short copy's note */
    private final String mStickId;
    private final Consumer<String> mSay;
    private final List<File> mNew = new ArrayList<>();
    private long mBytes;
    private volatile boolean mStopped;
    private volatile boolean mBusy;
    /** Copying now, not only counting */
    private volatile boolean mCopying;
    /** Stopped from the sheet: the copy ends, the sheet stays and can copy the rest later */
    private volatile boolean mCancel;
    private boolean mCounted;
    /** Run, on the copying thread, once every new photo is on the stick. */
    private volatile Runnable mCopied;

    Photos(Context context, VolumeInfo stick, Consumer<String> say) {
        mContext = context.getApplicationContext();
        mFrom = new File("/data/media/" + UserHandle.myUserId() + "/DCIM");
        mStick = stick.getInternalPath();
        mStickId = Port.stick(stick);
        mTo = new File(mStick, "DCIM/r1");
        mSay = say;
    }

    boolean busy() {
        return mBusy;
    }

    void stop() {
        mStopped = true;
    }

    boolean copying() {
        return mCopying;
    }

    /** Ends the copy under way, keeping what is whole on the stick; the rest is counted again. */
    void cancel() {
        if (mCopying) {
            mCancel = true;
        }
    }

    void setOnCopied(Runnable copied) {
        mCopied = copied;
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
        Port.copyStarted(mContext);
        mBusy = true;
        mCopying = true;
        mCancel = false;
        new Thread(() -> {
            final int all = mNew.size();
            int done = 0;
            final byte[] buffer = new byte[1 << 16];
            final int root = mFrom.getPath().length() + 1;
            boolean whole = false;
            boolean recounted = false;
            String said = null;
            try {
                for (File file : mNew) {
                    if (mStopped || mCancel) {
                        break;
                    }
                    mSay.accept(mContext.getString(R.string.copy_progress, done + 1, all));
                    final File to = new File(mTo, file.getPath().substring(root));
                    to.getParentFile().mkdirs();
                    // Under another name until it is whole: a stick pulled out leaves no half
                    // photo that looks like one
                    final File part = new File(to.getPath() + ".part");
                    // Read to its end: a stop from the sheet after that still keeps the photo
                    boolean read = false;
                    try (FileInputStream in = new FileInputStream(file);
                            FileOutputStream out = new FileOutputStream(part)) {
                        while (!mStopped && !mCancel) {
                            final int n = in.read(buffer);
                            if (n < 0) {
                                read = true;
                                break;
                            }
                            out.write(buffer, 0, n);
                        }
                        out.getFD().sync();
                    }
                    if (mStopped || !read) {
                        part.delete();
                        break;
                    }
                    if (!part.renameTo(to)) {
                        throw new IOException("rename");
                    }
                    to.setLastModified(file.lastModified());
                    done++;
                }
                if (mCancel && !mStopped && done < all) {
                    // Stopped from the sheet, which says so: no note kept for the next one.
                    // Still copying until the rest is counted, so a press meanwhile is no copy
                    mSay.accept(mContext.getString(R.string.copy_stopping));
                    mNew.clear();
                    mBytes = 0;
                    find(mFrom, "");
                    recounted = true;
                    said = mContext.getResources().getQuantityString(R.plurals.copy_stopped,
                            all, done, all);
                } else if (!mStopped) {
                    // Every photo on it, a stop pressed after the last one too: a finished copy
                    said = mContext.getResources().getQuantityString(R.plurals.copy_done,
                            done, done);
                    whole = true;
                }
            } catch (IOException | RuntimeException e) {
                said = mContext.getString(R.string.copy_failed, done, all);
            }
            if (mStopped && !whole) {
                // The sheet may already be gone. Keep the outcome for its next opening.
                Port.copyStopped(mContext, mStickId, done, all);
            }
            if (!recounted) {
                mNew.clear();
                mBytes = 0;
            }
            mCancel = false;
            // Both at once and before the outcome is said: the row's title then offers what a
            // press really does
            mCopying = false;
            mBusy = false;
            if (said != null) {
                mSay.accept(said);
            }
            final Runnable copied = mCopied;
            if (whole && copied != null) {
                copied.run();
            }
        }, "photos-copy").start();
    }
}
