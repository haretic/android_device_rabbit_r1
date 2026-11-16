// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * The knob's lane along the edge the wheel is on: what it works on, its value as a ladder of
 * marks with a bar at the value, and a dot for each of the three things it can be.
 */
public final class KnobView extends View {

    private static final int MARKS = 16;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mAccent;
    private String mName = "";
    private int mWhich;
    private int mCount = 3;
    private float mValue;

    public KnobView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
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
        final float w = getWidth();
        final float h = getHeight();
        final float top = 44 * mDensity;
        final float bottom = h - 34 * mDensity;
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
        for (int i = 0; i < mCount; i++) {
            mPaint.setColor(i == mWhich ? mAccent : 0xff555555);
            canvas.drawCircle(w - 26 * mDensity - (mCount - 1 - i) * 14 * mDensity,
                    h - 12 * mDensity, 4 * mDensity, mPaint);
        }
    }
}
