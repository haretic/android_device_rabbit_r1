// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import cat.kenny.r1.synth.engine.SynthEngine;

import java.util.Locale;

/**
 * The loop: a card with its state, how long it is and where it plays, the pads it strikes as
 * marks along it, and beside that its one button and Clear. A row under the pads upright; a
 * column beside them where the view is taller than wide, as turned.
 */
public final class LoopView extends View {

    interface Listener {
        void onLoopAction();

        void onLoopClear();
    }

    /** What the wheel is on here, as Play goes past the pads */
    static final int NONE = 0, ACTION = 1, CLEAR = 2;
    private static final int INK = 0xff2a1206;
    private static final int OFF = 0xff555555;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final RectF mCard = new RectF();
    private final RectF mAction = new RectF();
    private final RectF mClear = new RectF();
    private final float mDensity;
    private final int mAccent;
    private final int mSurface;
    private final int mLine;
    private final int mDim;
    private final Typeface mText = Typeface.create("haretic-text", Typeface.NORMAL);
    private final Typeface mBold = Typeface.create("haretic-text-bold", Typeface.NORMAL);
    private final String[] mStates;
    private final String[] mActions;
    private final String mClearName;
    private final String mReady;
    private final int[] mMarks = new int[SynthEngine.LOOP_MARKS];
    private Listener mListener;
    private int mState = -1;
    private float mProgress;
    /** Tenths of a second, what the card shows */
    private int mTenths = -1;
    private String mDetail = "";
    private int mFocus = NONE;
    private int mPressed = NONE;
    /** Laid out in a row, see layout() */
    private boolean mWide;

    public LoopView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mSurface = context.getColor(R.color.surface);
        mLine = context.getColor(R.color.surface_line);
        mDim = context.getColor(R.color.text_dim);
        mStates = getResources().getStringArray(R.array.loop_states);
        mActions = getResources().getStringArray(R.array.loop_actions);
        mClearName = getResources().getString(R.string.loop_clear);
        mReady = getResources().getString(R.string.loop_ready_detail);
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    static boolean clearable(int state) {
        return state == SynthEngine.LOOP_RECORDING || state == SynthEngine.LOOP_PLAYING
                || state == SynthEngine.LOOP_ADDING || state == SynthEngine.LOOP_STOPPED;
    }

    /** Outlines the part the wheel is on, NONE where it is not on the loop. */
    void focus(int part) {
        if (mFocus != part) {
            mFocus = part;
            invalidate();
        }
    }

    /** From the engine, every frame. */
    void show(SynthEngine engine) {
        boolean changed = false;
        final int state = engine.loopState();
        if (state != mState) {
            mState = state;
            changed = true;
            setContentDescription(getResources().getString(R.string.loop_description,
                    mStates[state], mActions[state]));
        }
        final float progress = engine.loopProgress();
        if (progress != mProgress) {
            mProgress = progress;
            changed = true;
        }
        final int tenths = Math.round(engine.loopSeconds() * 10);
        if (tenths != mTenths) {
            mTenths = tenths;
            mDetail = getResources().getString(R.string.loop_seconds,
                    String.format(Locale.getDefault(), "%.1f", tenths / 10f));
            changed = true;
        }
        for (int i = 0; i < mMarks.length; i++) {
            final int marks = engine.loopMarks(i);
            if (marks != mMarks[i]) {
                mMarks[i] = marks;
                changed = true;
            }
        }
        // Recording pulses
        if (changed || state == SynthEngine.LOOP_RECORDING) {
            invalidate();
        }
    }

    /** Wide: card, button and Clear in a row. Tall: the three stacked. */
    private void layout() {
        final float w = getWidth();
        final float h = getHeight();
        final float gap = 6 * mDensity;
        mWide = w >= 2.5f * h;
        if (mWide) {
            mPaint.setTypeface(mBold);
            mPaint.setTextSize(14 * mDensity);
            float widest = 0;
            for (String action : mActions) {
                widest = Math.max(widest, mPaint.measureText(action));
            }
            final float action = Math.max(64 * mDensity, widest + 28 * mDensity);
            final float clear = Math.max(56 * mDensity,
                    mPaint.measureText(mClearName) + 28 * mDensity);
            mClear.set(w - clear, 0, w, h);
            mAction.set(mClear.left - gap - action, 0, mClear.left - gap, h);
            mCard.set(0, 0, mAction.left - gap, h);
        } else {
            final float pill = Math.min(48 * mDensity, (h - 2 * gap) / 3);
            mClear.set(0, h - pill, w, h);
            mAction.set(0, mClear.top - gap - pill, w, mClear.top - gap);
            mCard.set(0, 0, w, mAction.top - gap);
        }
    }

    private int partAt(float x, float y) {
        layout();
        if (mAction.contains(x, y)) {
            return ACTION;
        }
        if (mClear.contains(x, y) && clearable(mState)) {
            return CLEAR;
        }
        return NONE;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mListener == null || mState < 0) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressed = partAt(event.getX(), event.getY());
                if (mPressed == ACTION) {
                    // On the touch, as a pad is: closing a take is a matter of timing. The
                    // activity gives the tick
                    performClick();
                    mListener.onLoopAction();
                }
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                // Clear on letting go, and only on the button it started on
                if (mPressed == CLEAR && partAt(event.getX(), event.getY()) == CLEAR) {
                    mListener.onLoopClear();
                }
                mPressed = NONE;
                invalidate();
                return true;
            case MotionEvent.ACTION_CANCEL:
                mPressed = NONE;
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mState < 0) {
            return;
        }
        layout();
        final float d = mDensity;
        final boolean wide = mWide;
        final float radius = Math.min(mCard.height() / 2, 16 * d);
        // The card, filled with where the loop plays
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mSurface);
        canvas.drawRoundRect(mCard, radius, radius, mPaint);
        canvas.save();
        mClip.rewind();
        mClip.addRoundRect(mCard, radius, radius, Path.Direction.CW);
        canvas.clipPath(mClip);
        final boolean going = mState == SynthEngine.LOOP_PLAYING
                || mState == SynthEngine.LOOP_ADDING;
        if (mState == SynthEngine.LOOP_RECORDING) {
            // A slow pulse of the accent while the take runs
            final float pulse = 0.5f + 0.5f * (float) Math.sin(
                    SystemClock.uptimeMillis() % 1000 / 1000.0 * 2 * Math.PI);
            mPaint.setColor(mAccent);
            mPaint.setAlpha(Math.round(30 + 40 * pulse));
            canvas.drawRect(mCard, mPaint);
            mPaint.setAlpha(255);
        } else if (going) {
            mPaint.setColor(mAccent);
            mPaint.setAlpha(mState == SynthEngine.LOOP_ADDING ? 80 : 48);
            canvas.drawRect(mCard.left, mCard.top,
                    mCard.left + mProgress * mCard.width(), mCard.bottom, mPaint);
            mPaint.setAlpha(255);
        }
        // The pads the loop strikes, as marks along its foot
        if (clearable(mState) && mState != SynthEngine.LOOP_RECORDING) {
            final float inset = radius * 0.6f + 6 * d;
            final float span = mCard.width() - 2 * inset;
            final float foot = mCard.bottom - (wide ? 5 : 8) * d;
            final float tall = (wide ? 5 : 8) * d;
            for (int i = 0; i < mMarks.length; i++) {
                if (mMarks[i] == 0) {
                    continue;
                }
                final float x = mCard.left + inset + (i + 0.5f) * span / mMarks.length;
                final float at = (i + 0.5f) / mMarks.length;
                mPaint.setColor(going && at <= mProgress ? 0xffffffff : mAccent);
                // Taller for more pads struck together
                final float t = tall * Math.min(1f, 0.5f + 0.25f * Integer.bitCount(mMarks[i]));
                canvas.drawRoundRect(x - d, foot - t, x + d, foot, d, d, mPaint);
            }
            if (going) {
                final float x = mCard.left + inset + mProgress * span;
                mPaint.setColor(0xffffffff);
                canvas.drawRect(x - d, mCard.top + 4 * d, x + d, mCard.bottom - 4 * d, mPaint);
            }
        }
        canvas.restore();
        // What it is doing, and how long or what next
        final String name = mStates[mState];
        final String detail = mState == SynthEngine.LOOP_EMPTY ? ""
                : mState == SynthEngine.LOOP_ARMED ? mReady : mDetail;
        final float pad = 14 * d;
        mPaint.setTypeface(mBold);
        mPaint.setTextSize(14 * d);
        mPaint.setTextAlign(Paint.Align.LEFT);
        mPaint.setColor(mState == SynthEngine.LOOP_RECORDING ? mAccent : 0xffffffff);
        final float nameWidth = mPaint.measureText(name);
        if (wide) {
            final float base = mCard.centerY() - (mPaint.ascent() + mPaint.descent()) / 2
                    - (clearable(mState) && mState != SynthEngine.LOOP_RECORDING ? 2 * d : 0);
            canvas.drawText(name, mCard.left + pad, base, mPaint);
            mPaint.setTypeface(mText);
            mPaint.setTextSize(13 * d);
            mPaint.setColor(mDim);
            // Only where it fits whole
            if (!detail.isEmpty() && mCard.left + pad + nameWidth + 6 * d
                    + mPaint.measureText(detail) <= mCard.right - pad) {
                canvas.drawText(detail, mCard.left + pad + nameWidth + 6 * d, base, mPaint);
            }
        } else {
            final float left = mCard.left + 12 * d;
            canvas.drawText(name, left, mCard.top + 10 * d - mPaint.ascent(), mPaint);
            mPaint.setTypeface(mText);
            mPaint.setTextSize(13 * d);
            mPaint.setColor(mDim);
            // Under the name where the card has room for it above the marks
            if (!detail.isEmpty() && mCard.height() >= 64 * d
                    && left + mPaint.measureText(detail) <= mCard.right - 8 * d) {
                canvas.drawText(detail, left, mCard.top + 30 * d - mPaint.ascent(), mPaint);
            }
        }
        // The one button: in the accent, the way out of a waiting loop plainer
        final boolean primary = mState != SynthEngine.LOOP_ARMED;
        pill(canvas, mAction, mActions[mState], primary ? mAccent : mLine,
                primary ? INK : 0xffffffff, mPressed == ACTION, mFocus == ACTION);
        final boolean clear = clearable(mState);
        pill(canvas, mClear, mClearName, clear ? mLine : mSurface, clear ? 0xffffffff : OFF,
                mPressed == CLEAR, mFocus == CLEAR);
    }

    private void pill(Canvas canvas, RectF r, String text, int fill, int ink, boolean pressed,
            boolean focused) {
        final float d = mDensity;
        final float radius = Math.min(r.height() / 2, 16 * d);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(pressed ? blend(fill, 0xffffffff, 0.25f) : fill);
        canvas.drawRoundRect(r, radius, radius, mPaint);
        if (focused) {
            // The part the wheel is on, outlined as a pad is
            final float inset = 1.5f * d;
            mPaint.setColor(fill == mAccent ? 0xffffffff : mAccent);
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(3 * d);
            canvas.drawRoundRect(r.left + inset, r.top + inset, r.right - inset,
                    r.bottom - inset, radius - inset, radius - inset, mPaint);
            mPaint.setStyle(Paint.Style.FILL);
        }
        mPaint.setTypeface(mBold);
        mPaint.setTextSize(14 * d);
        mPaint.setTextAlign(Paint.Align.CENTER);
        mPaint.setColor(ink);
        canvas.drawText(text, r.centerX(), r.centerY() - (mPaint.ascent() + mPaint.descent()) / 2,
                mPaint);
        mPaint.setTextAlign(Paint.Align.LEFT);
    }

    private static int blend(int a, int b, float t) {
        final int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        final int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        final int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
