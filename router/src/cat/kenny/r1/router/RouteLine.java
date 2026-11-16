// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.router;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The line through the stops of the route, behind their icons: each stretch in the colour of
 * how things are between the two stops it joins.
 */
public final class RouteLine extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    /** The middle of each stop's icon, top to bottom, and the colour of the stretch after it */
    private float[] mStops = new float[0];
    private int[] mColors = new int[0];

    public RouteLine(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mPaint.setStrokeWidth(2 * mDensity);
    }

    void set(float[] stops, int[] colors) {
        mStops = stops;
        mColors = colors;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        // The icons are 44 dp wide, 26 dp in from the edge
        final float x = getLayoutDirection() == LAYOUT_DIRECTION_RTL
                ? getWidth() - 48 * mDensity : 48 * mDensity;
        for (int i = 0; i + 1 < mStops.length; i++) {
            mPaint.setColor(mColors[i]);
            canvas.drawLine(x, mStops[i], x, mStops[i + 1], mPaint);
        }
    }
}
