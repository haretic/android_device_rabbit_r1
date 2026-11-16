// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** The last minute of sound, a bar a second, the loud part of it in yellow. */
public final class BarsView extends View {

    private static final float FLOOR_DB = 20f;
    private static final float TOP_DB = 100f;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float[] mBars = new float[0];
    private int mCount;
    private int mSlots = 60;
    private float mLoudFrom = Float.MAX_VALUE;

    public BarsView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    /** The newest bar last. Bars at or above loudFrom are the loud part. */
    void set(float[] bars, int count, int slots, float loudFrom) {
        mBars = bars;
        mCount = count;
        mSlots = slots;
        mLoudFrom = loudFrom;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float pitch = (float) getWidth() / mSlots;
        final float width = Math.max(1f, pitch * 0.62f);
        final int h = getHeight();
        final int grey = getContext().getColor(R.color.bar);
        final int loud = getContext().getColor(R.color.loud);
        // Right aligned: the newest second is at "now"
        for (int i = 0; i < mCount; i++) {
            final float db = mBars[i];
            final float t = Math.max(0.03f, Math.min(1f, (db - FLOOR_DB) / (TOP_DB - FLOOR_DB)));
            final float x = (mSlots - mCount + i) * pitch;
            mPaint.setColor(db >= mLoudFrom ? loud : grey);
            canvas.drawRoundRect(x, h - t * h, x + width, h, width / 2, width / 2, mPaint);
        }
    }
}
