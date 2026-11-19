// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.UnderlineSpan;
import android.view.KeyEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import cat.kenny.r1.tunnels.zt.ZtAddress;

/**
 * ZeroTier, step 1 (N6): the network's ID, 16 characters 0-9 and a-f, on a key pad the wheel
 * goes through in reading order, a press or a tap typing the key; a keyboard's keys type too.
 * Or scanned from a QR code. Then the name it has on this r1, and on to the review.
 */
public final class ZtJoinActivity extends Page {
    private static final int REQ_SCAN = 1;
    private static final int CHARS = 16;
    private static final int[] KEYS = {R.id.hex_0, R.id.hex_1, R.id.hex_2, R.id.hex_3,
        R.id.hex_4, R.id.hex_5, R.id.hex_6, R.id.hex_7, R.id.hex_8, R.id.hex_9, R.id.hex_a,
        R.id.hex_b, R.id.hex_c, R.id.hex_d, R.id.hex_e, R.id.hex_f};
    private static final String HEX = "0123456789abcdef";

    private final StringBuilder mId = new StringBuilder();
    private TextView mNumber;
    private TextView mNext;
    private EditText mName;
    private View mProblem;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        title(R.string.kind_zt);
        steps(1, 2);
        lead(getText(R.string.zt_lead));
        getLayoutInflater().inflate(R.layout.pad_hex, mBody, true);
        mNumber = mBody.findViewById(R.id.number);
        mNext = mBody.findViewById(R.id.key_next);
        for (int i = 0; i < KEYS.length; i++) {
            final char c = HEX.charAt(i);
            final View key = mBody.findViewById(KEYS[i]);
            pressHint(key, getText(R.string.hint_pad_hex));
            key.setOnClickListener(v -> type(c));
        }
        final View erase = mBody.findViewById(R.id.key_back);
        pressHint(erase, getText(R.string.hint_pad_hex));
        erase.setOnClickListener(v -> erase());
        pressHint(mNext, getText(R.string.hint_zt_next));
        mNext.setOnClickListener(v -> next());
        mProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mProblem, "", true);

        final View field = getLayoutInflater().inflate(R.layout.field, mBody, false);
        ((TextView) field.findViewById(R.id.label)).setText(R.string.zt_name);
        mName = field.findViewById(R.id.edit);
        mName.setId(R.id.field_zt_name);
        mName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        mName.setText(R.string.zt_default_name);
        pressHint(mName, getText(R.string.hint_type_page));
        mBody.addView(field);

        final View scan = row(mBody, R.drawable.ic_scan, getText(R.string.zt_scan),
                getText(R.string.zt_scan_sum));
        pressHint(scan, getText(R.string.hint_scan));
        scan.setOnClickListener(v -> startActivityForResult(new Intent(this, ScanActivity.class)
                .putExtra(ScanActivity.EXTRA_MODE, ScanActivity.MODE_ZT_ID), REQ_SCAN));
        EngineUi.footer(this, mBody, R.string.zt_trademark);
        hint(R.string.hint_pad_hex);
        if (Draft.net != null && EngineUi.isZeroTier(Draft.net)) {
            // Back from the review: what was typed stays
            mId.append(Draft.net.networkId);
            mName.setText(Draft.net.name);
        }
        show();
        mBody.findViewById(R.id.hex_0).requestFocus();
    }

    private void type(char c) {
        if (mId.length() >= CHARS) {
            return;
        }
        mId.append(c);
        noteText(mProblem, "", true);
        show();
        if (mId.length() == CHARS) {
            mNext.requestFocus();
        }
    }

    private void erase() {
        if (mId.length() == 0) {
            return;
        }
        mId.setLength(mId.length() - 1);
        noteText(mProblem, "", true);
        show();
    }

    /** Four groups of four, as typed so far, the place of the next one, the rest to come */
    private void show() {
        final SpannableStringBuilder text = new SpannableStringBuilder();
        final int faint = getColor(R.color.surface_line);
        for (int i = 0; i < CHARS; i++) {
            if (i > 0 && i % 4 == 0) {
                text.append(' ');
            }
            final int start = text.length();
            if (i < mId.length()) {
                text.append(mId.charAt(i));
            } else {
                text.append('_');
                text.setSpan(new ForegroundColorSpan(i == mId.length() ? accent() : faint),
                        start, start + 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (i == mId.length()) {
                    text.setSpan(new UnderlineSpan(), start, start + 1,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            }
        }
        mNumber.setText(text);
        mNumber.setContentDescription(getString(R.string.zt_typed, mId.length()));
        mNext.setAlpha(mId.length() == CHARS ? 1f : 0.45f);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // A keyboard's 0-9 and a-f type the ID, unless the name is being typed
        if (!(getCurrentFocus() instanceof EditText)) {
            final int code = event.getKeyCode();
            char c = 0;
            if (code >= KeyEvent.KEYCODE_0 && code <= KeyEvent.KEYCODE_9) {
                c = (char) ('0' + code - KeyEvent.KEYCODE_0);
            } else if (code >= KeyEvent.KEYCODE_A && code <= KeyEvent.KEYCODE_F) {
                c = (char) ('a' + code - KeyEvent.KEYCODE_A);
            }
            if (c != 0) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    type(c);
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_DEL) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    erase();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        if (request == REQ_SCAN) {
            if (result == RESULT_OK && Draft.scanned != null
                    && Draft.scanned.length() == CHARS) {
                mId.setLength(0);
                mId.append(Draft.scanned);
                Draft.scanned = null;
                noteText(mProblem, "", true);
                show();
                mNext.requestFocus();
            }
            return;
        }
        super.onActivityResult(request, result, data);
    }

    private void next() {
        if (mId.length() != CHARS) {
            return;
        }
        final String id = mId.toString();
        try {
            ZtAddress.networkId(id);
        } catch (IllegalArgumentException e) {
            noteText(mProblem, getText(R.string.zt_bad_id), true);
            return;
        }
        final String name = mName.getText().toString().trim();
        if (name.isEmpty()) {
            noteText(mProblem, getText(R.string.name_needed), true);
            mName.requestFocus();
            return;
        }
        noteText(mProblem, "", true);
        final Net net = new Net();
        net.kind = Net.ZEROTIER;
        net.name = name;
        net.networkId = id;
        Draft.net = net;
        startActivity(new Intent(this, ZtReviewActivity.class));
    }

    @Override
    protected void back() {
        Draft.clear();
        super.back();
    }
}
