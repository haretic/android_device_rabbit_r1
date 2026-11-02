// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.window.OnBackInvokedDispatcher;

/**
 * The wheel UI's first-run tour: one control at a time, with practice on the gadget itself.
 * Home opens it once ({@link #KEY_DONE}); Settings > System > Taskbar and wheel replays it.
 */
public class TourActivity extends Activity {

    /** Settings.Secure: 1 once the tour was shown, so home opens it only the first time. */
    public static final String KEY_DONE = "r1_wheel_tour_done";

    private static final int WELCOME = 0, TURN = 1, PRESS = 2, HOLD = 3, TASKBAR = 4,
            SLIDER = 5, BACK = 6, POWER = 7, HELP = 8, DONE = 9;
    private static final int PAGES = DONE + 1;
    private static final int TURNS_NEEDED = 4;
    private static final int REQUEST_HELP = 1;

    private int mPage;
    private int mTurns;
    private int mRow;
    private boolean mTaskbarSeen;
    private boolean mLongPress;
    private boolean mSliderGrabbed;
    private boolean mSliderChanged;
    private boolean mCompleted;

    private Gadget mGadget;
    private TextView mStep, mTitle, mBody, mHint, mNext;
    private LinearLayout mRows;
    private SeekBar mSlider;
    private final TextView[] mRowViews = new TextView[3];
    private int mAccent, mOnAccent, mSurface, mText, mTextDim;
    private final Runnable mAdvance = this::next;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Shown once, even if it is left half way
        Settings.Secure.putInt(getContentResolver(), KEY_DONE, 1);
        // The side button comes here as the select key, its hold as a long press
        final WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        getWindow().setAttributes(lp);
        getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::onBackPressed);

        mAccent = getColor(android.R.color.system_accent1_200);
        mOnAccent = getColor(android.R.color.system_accent1_900);
        mSurface = getColor(android.R.color.system_neutral1_800);
        mText = getColor(android.R.color.system_neutral1_50);
        mTextDim = getColor(android.R.color.system_neutral2_300);
        if (savedInstanceState != null) {
            mPage = savedInstanceState.getInt("page");
        }
        setContentView(build());
        show(mPage);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt("page", mPage);
    }

    private View build() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.BLACK);
        // Clear of the status and navigation bars, the margins on top of them
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            final android.graphics.Insets bars =
                    insets.getInsets(android.view.WindowInsets.Type.systemBars());
            v.setPadding(dp(20) + bars.left, dp(12) + bars.top, dp(20) + bars.right,
                    dp(16) + bars.bottom);
            return insets;
        });

        final FrameLayout top = new FrameLayout(this);
        mStep = text(13, mTextDim, false);
        top.addView(mStep, new FrameLayout.LayoutParams(-2, -2, Gravity.START | Gravity.CENTER_VERTICAL));
        final TextView skip = text(14, mAccent, true);
        skip.setText(R.string.tour_skip);
        skip.setPadding(dp(12), dp(8), 0, dp(8));
        skip.setFocusable(false);
        skip.setOnClickListener(v -> finish());
        top.addView(skip, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.CENTER_VERTICAL));
        root.addView(top, new LinearLayout.LayoutParams(-1, -2));

        // The gadget takes the room the page's text leaves, up to its full size, centred in it:
        // smaller on a page with the exercise rows, no hole under a short page's text
        mGadget = new Gadget(this, dp(150));
        final LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-1, 0, 1);
        glp.topMargin = dp(8);
        root.addView(mGadget, glp);

        mTitle = text(22, mText, true);
        final LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-1, -2);
        tlp.topMargin = dp(16);
        root.addView(mTitle, tlp);
        mBody = text(15, mTextDim, false);
        mBody.setLineSpacing(0, 1.15f);
        final LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(-1, -2);
        blp.topMargin = dp(6);
        root.addView(mBody, blp);

        mRows = new LinearLayout(this);
        mRows.setOrientation(LinearLayout.VERTICAL);
        final int[] names = {R.string.tour_row_1, R.string.tour_row_2, R.string.tour_row_3};
        for (int i = 0; i < 3; i++) {
            final TextView row = text(15, mText, false);
            row.setText(names[i]);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(16), 0, dp(16), 0);
            final LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(-1, dp(40));
            rlp.topMargin = dp(i == 0 ? 14 : 6);
            mRows.addView(row, rlp);
            mRowViews[i] = row;
        }
        root.addView(mRows, new LinearLayout.LayoutParams(-1, -2));

        mSlider = new SeekBar(this);
        mSlider.setMax(10);
        mSlider.setFocusable(false);
        mSlider.setContentDescription(getString(R.string.tour_slider_title));
        mSlider.setPadding(dp(16), dp(6), dp(16), dp(6));
        final GradientDrawable sliderBg = new GradientDrawable();
        sliderBg.setCornerRadius(dp(12));
        sliderBg.setColor(mSurface);
        sliderBg.setStroke(dp(3), mAccent);
        mSlider.setBackground(sliderBg);
        final LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(-1, dp(48));
        slp.topMargin = dp(14);
        root.addView(mSlider, slp);

        final LinearLayout bottom = new LinearLayout(this);
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        mHint = text(14, mAccent, true);
        mHint.setGravity(Gravity.CENTER_VERTICAL);
        bottom.addView(mHint, new LinearLayout.LayoutParams(0, -2, 1));
        // For touch: the original pages go on without the gadget's controls too
        mNext = text(15, mOnAccent, true);
        mNext.setPadding(dp(18), dp(8), dp(18), dp(8));
        mNext.setBackground(pill(mAccent));
        mNext.setFocusable(false);
        mNext.setOnClickListener(v -> {
            if (mPage == HELP) {
                onPress();
            } else {
                next();
            }
        });
        final LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(-2, -2);
        nlp.setMarginStart(dp(8));
        bottom.addView(mNext, nlp);
        final LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(-1, -2);
        flp.topMargin = dp(16);
        root.addView(bottom, flp);
        return root;
    }

    private void show(int page) {
        mHint.removeCallbacks(mAdvance);
        mPage = page;
        mStep.setText(getString(R.string.tour_step, page + 1, PAGES));
        mTurns = 0;
        mLongPress = false;
        mTaskbarSeen = false;
        mSliderGrabbed = false;
        mSliderChanged = false;
        mCompleted = false;
        final int[][] texts = {
                {R.string.tour_welcome_title, R.string.tour_welcome_body, R.string.tour_welcome_hint},
                {R.string.tour_turn_title, R.string.tour_turn_body, R.string.tour_turn_hint},
                {R.string.tour_press_title, R.string.tour_press_body, R.string.tour_press_hint},
                {R.string.tour_hold_title, R.string.tour_hold_body, R.string.tour_hold_hint},
                {R.string.tour_taskbar_title, R.string.tour_taskbar_body, R.string.tour_taskbar_hint},
                {R.string.tour_slider_title, R.string.tour_slider_body, R.string.tour_slider_hint},
                {R.string.tour_back_title, R.string.tour_back_body, R.string.tour_back_hint},
                {R.string.tour_power_title, R.string.tour_power_body, R.string.tour_power_hint},
                {R.string.tour_help_title, R.string.tour_help_body, R.string.tour_help_hint},
                {R.string.tour_done_title, R.string.tour_done_body, R.string.tour_done_hint},
        };
        mTitle.setText(texts[page][0]);
        mBody.setText(texts[page][1]);
        mHint.setText(texts[page][2]);
        mNext.setText(page == DONE ? R.string.tour_finish
                : page == HELP ? R.string.tour_help_open : R.string.tour_next);
        mRows.setVisibility(page == TURN || page == PRESS ? View.VISIBLE : View.GONE);
        mSlider.setVisibility(page == SLIDER ? View.VISIBLE : View.GONE);
        mSlider.setProgress(5);
        updateRows(-1);
        mGadget.show(page == TURN ? Gadget.WHEEL
                : page == WELCOME || page == POWER || page == DONE
                ? Gadget.NONE : page == HOLD ? Gadget.HOLD
                : page == TASKBAR || page == BACK ? Gadget.DOUBLE : Gadget.PRESS);
        mTitle.setAlpha(0f);
        mTitle.setTranslationY(dp(8));
        mTitle.animate().alpha(1f).translationY(0).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
    }

    private void updateRows(int picked) {
        for (int i = 0; i < 3; i++) {
            final boolean on = i == mRow && (mPage == TURN || mPage == PRESS);
            final GradientDrawable bg = new GradientDrawable();
            bg.setCornerRadius(dp(12));
            bg.setColor(i == picked ? mAccent : mSurface);
            if (on && i != picked) {
                // As wide as the wheel's own ring (ViewRootImpl WHEEL_FOCUS_WIDTH_DP), the one it teaches
                bg.setStroke(dp(3), mAccent);
            }
            mRowViews[i].setBackground(bg);
            mRowViews[i].setTextColor(i == picked ? mOnAccent : mText);
        }
    }

    private void next() {
        if (mPage == DONE) {
            finish();
            return;
        }
        getWindow().getDecorView().performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        show(mPage + 1);
    }

    private void completePage() {
        if (mCompleted) {
            return;
        }
        mCompleted = true;
        mHint.setText(R.string.tour_well_done);
        mHint.postDelayed(mAdvance, 700);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        final int code = event.getKeyCode();
        final boolean down = event.getAction() == KeyEvent.ACTION_DOWN;
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    onTurn(code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                if (down && (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                    mLongPress = true;
                } else if (!down) {
                    if (mLongPress || (event.getFlags() & KeyEvent.FLAG_LONG_PRESS) != 0) {
                        mLongPress = false;
                        onHold();
                    } else if (!event.isCanceled()) {
                        onPress();
                    }
                }
                return true;
            case KeyEvent.KEYCODE_POWER:
                // The side button as such, where the policy hands it to the window
                if (down && event.isLongPress()) {
                    mLongPress = true;
                    onHold();
                } else if (!down) {
                    if (!mLongPress && !event.isCanceled()) {
                        onPress();
                    }
                    mLongPress = false;
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (mPage == BACK) {
                    if (!down && !event.isCanceled()) {
                        onBackPressed();
                    }
                    return true;
                }
                // A hold, where it is set to go back; elsewhere back leaves the tour
                if (!down && mPage == HOLD) {
                    onHold();
                    return true;
                }
                break;
        }
        return super.dispatchKeyEvent(event);
    }

    private void onTurn(int step) {
        if (mCompleted) {
            return;
        }
        if (mPage == SLIDER) {
            mGadget.nudge(step);
            if (mSliderGrabbed) {
                final int before = mSlider.getProgress();
                mSlider.setProgress(Math.max(0, Math.min(mSlider.getMax(), before + step)));
                if (before != mSlider.getProgress()) {
                    mSliderChanged = true;
                    mHint.setText(R.string.tour_slider_release);
                    mSlider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                }
            }
            return;
        }
        if (mPage != TURN && mPage != PRESS) {
            mGadget.nudge(step);
            return;
        }
        mRow = Math.floorMod(mRow + step, 3);
        mGadget.nudge(step);
        updateRows(-1);
        mRowViews[mRow].performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        if (mPage == TURN && ++mTurns == TURNS_NEEDED) {
            completePage();
        }
    }

    private void onPress() {
        if (mCompleted) {
            return;
        }
        switch (mPage) {
            case PRESS:
                updateRows(mRow);
                completePage();
                break;
            case HOLD:
                mHint.setText(R.string.tour_hold_again);
                break;
            case TURN:
            case BACK:
                break;
            case SLIDER:
                if (!mSliderGrabbed) {
                    mSliderGrabbed = true;
                    mSliderChanged = false;
                    mHint.setText(R.string.tour_slider_turn);
                    mGadget.show(Gadget.WHEEL);
                } else {
                    mSliderGrabbed = false;
                    mGadget.show(Gadget.PRESS);
                    if (mSliderChanged) {
                        completePage();
                    } else {
                        mHint.setText(R.string.tour_slider_hint);
                    }
                }
                break;
            case HELP:
                startActivityForResult(new Intent(this, WheelActivity.class)
                        .putExtra("page", "help"), REQUEST_HELP);
                break;
            default:
                next();
        }
    }

    private void onHold() {
        if (mPage == HOLD) {
            completePage();
        } else if (mPage > WELCOME) {
            show(mPage - 1);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // The Back exercise also needs a Back event; closing the bar alone does not pass it.
        if (mPage == TASKBAR || mPage == BACK) {
            if (!hasFocus) {
                mTaskbarSeen = true;
            } else if (mPage == TASKBAR && mTaskbarSeen) {
                completePage();
            }
        }
    }

    @Override
    public void onBackPressed() {
        if (mPage == BACK) {
            if (mTaskbarSeen) {
                completePage();
            }
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_HELP && mPage == HELP) {
            completePage();
        }
    }

    @Override
    protected void onDestroy() {
        mHint.removeCallbacks(mAdvance);
        super.onDestroy();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
    }

    private TextView text(float sp, int color, boolean bold) {
        final TextView view = new TextView(this);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        if (bold) {
            view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return view;
    }

    private GradientDrawable pill(int color) {
        final GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(40));
        d.setColor(color);
        return d;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    /** Opens the tour, as from the settings page. */
    static void start(Context context) {
        context.startActivity(new Intent(context, TourActivity.class));
    }

    /**
     * The gadget from the front, its screen to the left, the wheel and the side button to the
     * right, with the control in question lit: the wheel's grooves run as it turns, the button
     * pulses once for a press, twice for a double press, and fills for a hold.
     */
    private static final class Gadget extends View {
        static final int NONE = 0, WHEEL = 1, PRESS = 2, DOUBLE = 3, HOLD = 4;

        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF mRect = new RectF();
        private final int mAccent, mBody, mScreen, mEdge, mMaxHeight;
        private int mMode;
        private float mGroove, mPulse;
        private ValueAnimator mAnim;

        Gadget(Context context, int maxHeight) {
            super(context);
            mMaxHeight = maxHeight;
            mAccent = context.getColor(android.R.color.system_accent1_200);
            mBody = 0xFFE8572A;
            mScreen = 0xFF101010;
            mEdge = 0xFFB8441F;
            setFocusable(false);
        }

        void show(int mode) {
            mMode = mode;
            if (mAnim != null) {
                mAnim.cancel();
            }
            mAnim = ValueAnimator.ofFloat(0f, 1f);
            mAnim.setDuration(mode == HOLD ? 1600 : mode == DOUBLE ? 1300 : 1100);
            mAnim.setRepeatCount(ValueAnimator.INFINITE);
            mAnim.addUpdateListener(a -> {
                mPulse = (float) a.getAnimatedValue();
                if (mMode == WHEEL) {
                    mGroove = mPulse;
                }
                invalidate();
            });
            mAnim.start();
        }

        void nudge(int step) {
            mGroove += step * 0.25f;
            invalidate();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            if (mAnim != null) {
                mAnim.cancel();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            final float d = getResources().getDisplayMetrics().density;
            // Its button's cue reaches 32dp right of the body
            final float h = Math.min(Math.min(getHeight(), mMaxHeight),
                    (getWidth() - 64 * d) / 1.08f), w = h * 1.08f;
            if (h < 48 * d) {
                return;
            }
            final float left = (getWidth() - w) / 2f, top = (getHeight() - h) / 2f;
            // The body
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setColor(mBody);
            mRect.set(left, top, left + w, top + h);
            canvas.drawRoundRect(mRect, h * 0.12f, h * 0.12f, mPaint);
            // The screen
            mPaint.setColor(mScreen);
            mRect.set(left + h * 0.08f, top + h * 0.1f, left + w * 0.66f, top + h * 0.9f);
            canvas.drawRoundRect(mRect, h * 0.05f, h * 0.05f, mPaint);
            // The wheel, its grooves moving as it turns
            final float wx = left + w * 0.76f, wy = top + h * 0.2f;
            final float ww = w * 0.13f, wh = h * 0.6f;
            mPaint.setColor(mMode == WHEEL ? mAccent : mEdge);
            mRect.set(wx, wy, wx + ww, wy + wh);
            canvas.drawRoundRect(mRect, ww / 2f, ww / 2f, mPaint);
            mPaint.setColor(mMode == WHEEL ? 0x66000000 : 0x40000000);
            mPaint.setStrokeWidth(2 * d);
            final float gap = wh / 7f;
            final float shift = ((mGroove % 1f) + 1f) % 1f * gap;
            canvas.save();
            canvas.clipRect(wx, wy + ww / 3f, wx + ww, wy + wh - ww / 3f);
            for (float y = wy - gap + shift; y < wy + wh; y += gap) {
                canvas.drawLine(wx + ww * 0.2f, y, wx + ww * 0.8f, y, mPaint);
            }
            canvas.restore();
            // The side button, on the right edge near the top
            final boolean lit = mMode == PRESS || mMode == DOUBLE || mMode == HOLD;
            final float bx = left + w, by = top + h * 0.12f, bh = h * 0.16f;
            mPaint.setColor(lit ? mAccent : mEdge);
            mRect.set(bx - 2 * d, by, bx + 5 * d, by + bh);
            canvas.drawRoundRect(mRect, 3 * d, 3 * d, mPaint);
            if (!lit) {
                return;
            }
            // Its cue: one ring, two rings, or a ring filling
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3 * d);
            final float cx = bx + 18 * d, cy = by + bh / 2f, r = 9 * d;
            if (mMode == HOLD) {
                mPaint.setColor(0x40FFFFFF);
                canvas.drawCircle(cx, cy, r, mPaint);
                mPaint.setColor(mAccent);
                mRect.set(cx - r, cy - r, cx + r, cy + r);
                canvas.drawArc(mRect, -90, 360 * Math.min(1f, mPulse * 1.4f), false, mPaint);
            } else {
                final int pulses = mMode == DOUBLE ? 2 : 1;
                for (int i = 0; i < pulses; i++) {
                    final float t = mPulse * (mMode == DOUBLE ? 2.2f : 1.4f) - i * 0.9f;
                    if (t < 0 || t > 1) {
                        continue;
                    }
                    mPaint.setColor(mAccent);
                    mPaint.setAlpha((int) (255 * (1 - t)));
                    canvas.drawCircle(cx, cy, r * (0.6f + t), mPaint);
                }
                mPaint.setAlpha(255);
            }
        }
    }
}
