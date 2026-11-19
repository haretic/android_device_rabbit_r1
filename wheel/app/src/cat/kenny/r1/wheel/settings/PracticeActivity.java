// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import cat.kenny.r1.wheel.WheelSelect;

/**
 * Try it, from How the wheel works: the wheel and the side button on real controls, a list, a
 * switch, a slider and a text field, each card saying what to do. The controls are the
 * framework's own, so they answer as everywhere else: the slider grabs on a press, the field
 * brings the keyboard. A line at the top says what just happened and the r1 drawn beside it
 * lights the part in use. Every control is a tap away too; a hold of the side button goes back,
 * out of the page.
 */
public class PracticeActivity extends Activity {

    /** How long the drawing keeps the part lit after its use. */
    private static final long LIT_MS = 1600;

    private TextView mStatus;
    private TourActivity.Gadget mGadget;
    private int mLit = TourActivity.Gadget.NONE;
    private final TextView[] mRows = new TextView[3];
    private int mPicked = -1;
    private int mAccent, mOnAccent, mCard, mRow, mText, mTextDim;
    private final Runnable mUnlight = () -> light(TourActivity.Gadget.NONE);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // The side button presses what the ring is on, a hold goes back
        WheelSelect.attach(getWindow());
        mAccent = getColor(android.R.color.system_accent1_200);
        mOnAccent = getColor(android.R.color.system_accent1_900);
        mCard = getColor(android.R.color.system_neutral1_900);
        mRow = getColor(android.R.color.system_neutral1_800);
        mText = getColor(android.R.color.system_neutral1_50);
        mTextDim = getColor(android.R.color.system_neutral2_300);
        setContentView(build());
    }

    private View build() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final android.graphics.Insets bars = insets.getInsets(
                    android.view.WindowInsets.Type.systemBars()
                            | android.view.WindowInsets.Type.displayCutout());
            v.setPadding(dp(16) + bars.left, dp(8) + bars.top, dp(16) + bars.right,
                    bars.bottom);
            return insets;
        });

        // The title and what just happened, the r1 beside them
        final LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        final LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        final TextView title = text(22, mText, true);
        title.setText(R.string.practice_title);
        words.addView(title);
        mStatus = text(14, mAccent, true);
        mStatus.setMaxLines(2);
        mStatus.setText(R.string.practice_start);
        words.addView(mStatus);
        header.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        mGadget = new TourActivity.Gadget(this, dp(72));
        header.addView(mGadget, new LinearLayout.LayoutParams(dp(136), dp(72)));
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        final ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        // Room under the last control for the wheel's ring round it
        scroll.setClipToPadding(false);
        scroll.setPadding(0, dp(4), 0, dp(16));
        final LinearLayout cards = new LinearLayout(this);
        cards.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(cards, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // A list: the first stop, which a stray press only picks
        final LinearLayout list = card(cards, R.string.practice_list, R.string.practice_list_how);
        final int[] names = {R.string.practice_row_1, R.string.practice_row_2,
                R.string.practice_row_3};
        for (int i = 0; i < names.length; i++) {
            final TextView row = text(15, mText, false);
            row.setText(names[i]);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setMinHeight(dp(48));
            row.setPadding(dp(16), 0, dp(16), 0);
            row.setFocusable(true);
            row.setClickable(true);
            final int index = i;
            row.setOnClickListener(v -> pick(index));
            final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = dp(i == 0 ? 8 : 4);
            list.addView(row, lp);
            mRows[i] = row;
        }
        updateRows();

        final LinearLayout toggle = card(cards, R.string.practice_switch,
                R.string.practice_switch_how);
        final Switch flip = new Switch(this);
        flip.setId(R.id.practice_switch);
        flip.setText(R.string.practice_switch_off);
        flip.setTextColor(mText);
        flip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        flip.setMinHeight(dp(48));
        flip.setPadding(dp(16), 0, dp(12), 0);
        flip.setBackground(rounded(mRow));
        flip.setOnCheckedChangeListener((button, on) -> {
            flip.setText(on ? R.string.practice_switch_on : R.string.practice_switch_off);
            status(on ? R.string.practice_switch_on : R.string.practice_switch_off);
        });
        toggle.addView(flip, below(8));

        final LinearLayout slide = card(cards, R.string.practice_slider,
                R.string.practice_slider_how);
        final SeekBar slider = new SeekBar(this);
        slider.setId(R.id.practice_slider);
        slider.setMax(10);
        slider.setProgress(5);
        slider.setContentDescription(getString(R.string.practice_slider));
        slider.setPadding(dp(16), 0, dp(16), 0);
        slider.setBackground(rounded(mRow));
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                mStatus.setText(getString(R.string.practice_slider_at, progress));
            }

            @Override
            public void onStartTrackingTouch(SeekBar bar) {
                // A finger on it, or the side button's grab
                if (!bar.isInTouchMode()) {
                    status(R.string.practice_slider_grabbed);
                }
            }

            @Override
            public void onStopTrackingTouch(SeekBar bar) {
                mStatus.setText(getString(R.string.practice_slider_at, bar.getProgress()));
            }
        });
        final LinearLayout.LayoutParams slp = below(8);
        slp.height = dp(48);
        slide.addView(slider, slp);

        final LinearLayout type = card(cards, R.string.practice_text, R.string.practice_text_how);
        final EditText field = new EditText(this);
        field.setId(R.id.practice_text);
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHint(R.string.practice_text_hint);
        field.setTextColor(mText);
        field.setHintTextColor(mTextDim);
        field.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        field.setMinHeight(dp(48));
        field.setPadding(dp(16), 0, dp(16), 0);
        field.setBackground(rounded(mRow));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                mStatus.setText(getString(R.string.practice_text_typed, s.length()));
            }
        });
        type.addView(field, below(8));

        // Told, not tried here: the taskbar opens over the page, a hold leaves it
        card(cards, R.string.practice_more, R.string.practice_more_how);

        final TextView done = text(15, mOnAccent, true);
        done.setText(R.string.practice_done);
        done.setGravity(Gravity.CENTER);
        done.setMinHeight(dp(48));
        done.setBackground(pill(mAccent));
        done.setFocusable(true);
        done.setClickable(true);
        done.setOnClickListener(v -> finish());
        final LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(-1, -2);
        dlp.topMargin = dp(12);
        cards.addView(done, dlp);
        return root;
    }

    /** A rounded card with a heading and a line on how to work what follows in it. */
    private LinearLayout card(LinearLayout cards, int heading, int how) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(12), dp(10), dp(12), dp(12));
        card.setBackground(rounded(mCard));
        final TextView name = text(15, mText, true);
        name.setText(heading);
        card.addView(name);
        final TextView line = text(13, mTextDim, false);
        line.setText(how);
        line.setLineSpacing(0, 1.08f);
        card.addView(line, below(2));
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(8);
        cards.addView(card, lp);
        return card;
    }

    private void pick(int index) {
        mPicked = index;
        updateRows();
        mStatus.setText(getString(R.string.practice_picked, mRows[index].getText()));
    }

    private void updateRows() {
        for (int i = 0; i < mRows.length; i++) {
            mRows[i].setBackground(rounded(i == mPicked ? mAccent : mRow));
            mRows[i].setTextColor(i == mPicked ? mOnAccent : mText);
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN) {
            if (down) {
                light(TourActivity.Gadget.WHEEL);
                mGadget.nudge(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                // Said before the control has the step: a grabbed slider says its value after
                status(R.string.practice_turned);
            }
        } else if (KeyEvent.isConfirmKey(code) && !down) {
            light(TourActivity.Gadget.PRESS);
        }
        return super.dispatchKeyEvent(event);
    }

    /** Lights the drawing's part in use for a moment. */
    private void light(int part) {
        mGadget.removeCallbacks(mUnlight);
        if (part != mLit) {
            mLit = part;
            mGadget.show(part);
        }
        if (part != TourActivity.Gadget.NONE) {
            mGadget.postDelayed(mUnlight, LIT_MS);
        }
    }

    private void status(int text) {
        mStatus.setText(text);
    }

    @Override
    protected void onDestroy() {
        mGadget.removeCallbacks(mUnlight);
        super.onDestroy();
    }

    private LinearLayout.LayoutParams below(int margin) {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(margin);
        return lp;
    }

    private TextView text(float sp, int color, boolean bold) {
        final TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("haretic-text-medium", Typeface.NORMAL));
        }
        return view;
    }

    private GradientDrawable rounded(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(12));
        d.setColor(color);
        return d;
    }

    private GradientDrawable pill(int color) {
        final GradientDrawable d = rounded(color);
        d.setCornerRadius(dp(40));
        return d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
