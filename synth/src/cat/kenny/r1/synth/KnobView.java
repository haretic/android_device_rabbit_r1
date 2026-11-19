// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The knob's lane along the edge the wheel is on: what it works on, its value as a ladder of
 * marks with a bar at the value, and a dot for each of the things it can be. Upright the lane
 * runs down the right edge, its top the high end; turned, with the wheel along the top or the
 * bottom (wheelEdge), it runs across, its high end on the left, where a step up of the wheel
 * goes there (SystemUI turns the wheel's way with the screen).
 */
public final class KnobView extends View {

    /** A finger on the lane: dragged it sets the value where it is, a tap moves on. */
    interface Listener {
        /** 0 at the bottom of the ladder to 1 at its top. */
        void onKnobTouch(float value);

        void onKnobTap();
    }

    private static final int MARKS = 16;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mAccent;
    private String mName = "";
    private int mWhich;
    private int mCount = 3;
    private float mValue;
    private Listener mListener;
    private float mDownY;
    private boolean mDragging;
    private final int mSlop;
    /** The wheel's edge, see wheelEdge: the lane runs across the top (1) or bottom (3). */
    private int mEdge;

    /**
     * The screen edge the wheel is on now: 0 right, as upright, 1 top, 2 left, 3 bottom. As
     * SystemUI's WheelTaskbarSettings.physicalRotation, with the boot-selected natural
     * orientation (ro.vendor.display.primary_orientation) counted in.
     */
    static int wheelEdge(Context context) {
        final Display display = context.getDisplay();
        final int rotation = display != null ? display.getRotation() : 0;
        final int offset = SystemProperties.getInt("ro.vendor.display.primary_orientation", 0);
        return (rotation + (offset == 90 || offset == 180 || offset == 270 ? offset / 90 : 0))
                % 4;
    }

    private boolean across() {
        return (mEdge == 1 || mEdge == 3) && getWidth() > getHeight();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEdge = wheelEdge(getContext());
    }

    /** The wheel's edge again, after a turn of 180 degrees that kept the page. */
    void setEdge(int edge) {
        if (edge != mEdge) {
            mEdge = edge;
            invalidate();
        }
    }

    public KnobView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    /** The ladder's high end, along the lane: its top, or its left end across. */
    private float top() {
        return (across() ? 20 : 44) * mDensity;
    }

    private float bottom() {
        return across() ? getWidth() - 20 * mDensity : getHeight() - 34 * mDensity;
    }

    /** Where a finger is along the lane. */
    private float along(MotionEvent event) {
        return across() ? event.getX() : event.getY();
    }

    /** The value at a place along the ladder, the ends held past the ladder's ends. */
    private float valueAt(float y) {
        final float span = Math.max(1, bottom() - top());
        return Math.max(0, Math.min(1, 1 - (y - top()) / span));
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mListener == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mDownY = along(event);
                mDragging = false;
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE:
                if (!mDragging && Math.abs(along(event) - mDownY) >= mSlop) {
                    mDragging = true;
                }
                if (mDragging) {
                    mListener.onKnobTouch(valueAt(along(event)));
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (mDragging) {
                    mListener.onKnobTouch(valueAt(along(event)));
                } else {
                    performClick();
                    mListener.onKnobTap();
                }
                mDragging = false;
                return true;
            case MotionEvent.ACTION_CANCEL:
                mDragging = false;
                return true;
            default:
                return true;
        }
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    void set(String name, int which, int count, float value) {
        mName = name;
        mWhich = which;
        mCount = count;
        mValue = value;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (across()) {
            drawAcross(canvas);
            return;
        }
        final float w = getWidth();
        final float h = getHeight();
        final float top = top();
        final float bottom = bottom();
        mPaint.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        mPaint.setTextSize(13 * mDensity);
        mPaint.setTextAlign(Paint.Align.RIGHT);
        mPaint.setColor(0xffffffff);
        canvas.drawText(mName, w - 20 * mDensity, 22 * mDensity, mPaint);
        for (int i = 0; i <= MARKS; i++) {
            final float y = top + i * (bottom - top) / MARKS;
            mPaint.setColor(1 - (float) i / MARKS <= mValue ? mAccent : 0xff555555);
            canvas.drawRect(w - (i % 4 == 0 ? 15 : 8) * mDensity, y - mDensity, w, y + mDensity,
                    mPaint);
        }
        final float at = top + (1 - mValue) * (bottom - top);
        mPaint.setColor(0xffffffff);
        canvas.drawRoundRect(w - 54 * mDensity, at - 3.5f * mDensity, w + 4 * mDensity,
                at + 3.5f * mDensity, 3.5f * mDensity, 3.5f * mDensity, mPaint);
        // As many dots as fit the lane: closer and smaller the more things the knob can be
        final float gap = Math.min(14 * mDensity, (w - 20 * mDensity) / Math.max(1, mCount));
        final float dot = Math.min(4 * mDensity, gap * 0.36f);
        for (int i = 0; i < mCount; i++) {
            mPaint.setColor(i == mWhich ? mAccent : 0xff555555);
            canvas.drawCircle(w - 12 * mDensity - (mCount - 1 - i) * gap,
                    h - 12 * mDensity, i == mWhich ? dot * 1.25f : dot, mPaint);
        }
    }

    /**
     * The lane across the top, the wheel's edge, or the bottom: the marks hang from that edge,
     * the high end on the left, the name and the dots on the side away from the edge.
     */
    private void drawAcross(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float d = mDensity;
        final float left = top();
        final float right = bottom();
        // Distances from the wheel's edge, mirrored for the bottom one
        final boolean flip = mEdge == 3;
        mPaint.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        mPaint.setTextSize(13 * d);
        mPaint.setTextAlign(Paint.Align.LEFT);
        mPaint.setColor(0xffffffff);
        final float nameY = flip ? 6 * d - mPaint.ascent() : h - 6 * d - mPaint.descent();
        canvas.drawText(mName, left, nameY, mPaint);
        for (int i = 0; i <= MARKS; i++) {
            final float x = left + i * (right - left) / MARKS;
            final float len = (i % 4 == 0 ? 15 : 8) * d;
            mPaint.setColor(1 - (float) i / MARKS <= mValue ? mAccent : 0xff555555);
            canvas.drawRect(x - d, flip ? h - len : 0, x + d, flip ? h : len, mPaint);
        }
        final float at = left + (1 - mValue) * (right - left);
        final float bar = Math.min(30 * d, h * 0.55f);
        mPaint.setColor(0xffffffff);
        canvas.drawRoundRect(at - 3.5f * d, flip ? h - bar : -4 * d, at + 3.5f * d,
                flip ? h + 4 * d : bar, 3.5f * d, 3.5f * d, mPaint);
        final float gap = Math.min(14 * d, (w * 0.3f) / Math.max(1, mCount));
        final float dot = Math.min(4 * d, gap * 0.36f);
        final float dotY = flip ? 12 * d : h - 12 * d;
        for (int i = 0; i < mCount; i++) {
            mPaint.setColor(i == mWhich ? mAccent : 0xff555555);
            canvas.drawCircle(right - (mCount - 1 - i) * gap, dotY,
                    i == mWhich ? dot * 1.25f : dot, mPaint);
        }
    }
}
