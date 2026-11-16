// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/** The voice coming in, as bars about the middle, the newest at the right. */
public final class WaveView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float[] mLevels = new float[52];

    public WaveView(Context context, AttributeSet attrs) {
        super(context, attrs);
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mPaint.setColor(a.getColor(0, 0xffec6a37));
        a.recycle();
    }

    float[] levels() {
        return mLevels;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float pitch = (float) getWidth() / mLevels.length;
        final float width = Math.max(1, pitch * 0.5f);
        final float mid = getHeight() / 2f;
        for (int i = 0; i < mLevels.length; i++) {
            final float half = Math.max(width / 2, mLevels[i] * mid);
            final float x = i * pitch;
            canvas.drawRoundRect(x, mid - half, x + width, mid + half, width / 2, width / 2,
                    mPaint);
        }
    }
}
