// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.View;

import java.io.IOException;
import java.io.InputStream;

/**
 * Setting up a tunnel, step 1 (N2): where its settings are. Scanned, from a file, from the
 * clipboard, or typed in; each read the same way, and on to the review with what was read,
 * or a line saying what in them is wrong.
 */
public final class WgStartActivity extends Page {
    private static final int REQ_SCAN = 1;
    private static final int REQ_FILE = 2;

    private View mProblem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.kind_wg);
        steps(1, 3);
        lead(getText(R.string.wg_lead));
        final View scan = row(mBody, R.drawable.ic_scan, getText(R.string.from_scan),
                getText(R.string.from_scan_sum));
        pressHint(scan, getText(R.string.hint_scan));
        scan.setOnClickListener(v -> startActivityForResult(new Intent(this, ScanActivity.class)
                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_CONF), REQ_SCAN));
        final View file = row(mBody, R.drawable.ic_file, getText(R.string.from_file),
                getText(R.string.from_file_sum));
        pressHint(file, getText(R.string.hint_file));
        file.setOnClickListener(v -> openFile());
        final View paste = row(mBody, R.drawable.ic_paste, getText(R.string.from_paste),
                getText(R.string.from_paste_sum));
        pressHint(paste, getText(R.string.hint_paste));
        paste.setOnClickListener(v -> paste());
        final View type = row(mBody, R.drawable.ic_edit, getText(R.string.from_type),
                getText(R.string.from_type_sum));
        pressHint(type, getText(R.string.hint_type));
        type.setOnClickListener(v -> startActivity(new Intent(this, TypeActivity.class)));
        mProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mProblem, "", true);
        hint(R.string.hint_scan);
        scan.requestFocus();
    }

    private void openFile() {
        final Intent open = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try {
            startActivityForResult(open, REQ_FILE);
        } catch (ActivityNotFoundException e) {
            noteText(mProblem, getText(R.string.file_unreadable), true);
        }
    }

    private void paste() {
        final ClipboardManager clipboard = getSystemService(ClipboardManager.class);
        final ClipData clip = clipboard != null ? clipboard.getPrimaryClip() : null;
        final CharSequence text = clip != null && clip.getItemCount() > 0
                ? clip.getItemAt(0).coerceToText(this) : null;
        if (text == null || text.length() == 0) {
            noteText(mProblem, getText(R.string.clip_empty), true);
            return;
        }
        take(text.toString(), Draft.FROM_CLIP, null);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_SCAN) {
            if (result == RESULT_OK && Draft.scanned != null) {
                final String text = Draft.scanned;
                Draft.scanned = null;
                take(text, Draft.FROM_CODE, null);
            }
            return;
        }
        if (request == REQ_FILE) {
            if (result == RESULT_OK && data != null && data.getData() != null) {
                readFile(data.getData());
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    private void readFile(Uri uri) {
        new Thread(() -> readFileOffMain(uri), "tunnel import").start();
    }

    private void readFileOffMain(Uri uri) {
        final String text;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                throw new IOException("no stream");
            }
            text = Store.readAll(in, 64 * 1024);
        } catch (IOException | RuntimeException e) {
            runOnUiThread(() -> {
                if (!isFinishing() && !isDestroyed()) {
                    noteText(mProblem, getText(R.string.file_unreadable), true);
                }
            });
            return;
        }
        String name = null;
        try (Cursor cursor = getContentResolver().query(uri,
                new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                name = cursor.getString(0);
            }
        } catch (RuntimeException e) {
            // no name, then
        }
        if (name != null && name.toLowerCase(java.util.Locale.ROOT).endsWith(".conf")) {
            name = name.substring(0, name.length() - 5);
        }
        final String importedName = name;
        runOnUiThread(() -> {
            if (!isFinishing() && !isDestroyed()) {
                take(text, Draft.FROM_FILE, importedName);
            }
        });
    }

    /** The settings read, on to the review; or what is wrong in them, said here. */
    private void take(String text, int from, String name) {
        final Net net;
        try {
            net = WgConf.parse(text);
        } catch (WgConf.BadConf e) {
            noteText(mProblem, e.getMessage(), true);
            return;
        }
        net.name = name != null && !name.trim().isEmpty() ? name.trim() : net.host;
        noteText(mProblem, "", true);
        Draft.net = net;
        Draft.from = from;
        startActivity(new Intent(this, ReviewActivity.class));
    }
}
