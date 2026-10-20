// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** The level over the last 24 hours: a line with the area under it filled, 0 to 100 %. */
public class GraphView extends View {

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();

    private List<History.Point> mPoints;
    private long mStart;
    private long mEnd;

    public GraphView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mLine.setColor(context.getColor(R.color.graph_line));
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(dp(1.8f));
        mLine.setStrokeCap(Paint.Cap.ROUND);
        mLine.setStrokeJoin(Paint.Join.ROUND);
        mFill.setColor(context.getColor(R.color.graph_fill));
    }

    void setPoints(List<History.Point> points, long start, long end) {
        mPoints = points;
        mStart = start;
        mEnd = end;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mPoints == null || mPoints.isEmpty() || mEnd <= mStart) {
            return;
        }
        // Room for half the line at the top and bottom
        final float inset = mLine.getStrokeWidth() / 2;
        final float left = inset;
        final float right = getWidth() - inset;
        final float top = inset;
        final float bottom = getHeight() - inset;

        if (mPoints.size() == 1) {
            final History.Point p = mPoints.get(0);
            canvas.drawPoint(x(p.time, left, right), y(p.percent, top, bottom), mLine);
            return;
        }
        // The line
        mPath.reset();
        for (int i = 0; i < mPoints.size(); i++) {
            final History.Point p = mPoints.get(i);
            final float x = x(p.time, left, right);
            final float y = y(p.percent, top, bottom);
            if (i == 0) {
                mPath.moveTo(x, y);
            } else {
                mPath.lineTo(x, y);
            }
        }
        // The area under it, from the same path closed along the bottom
        final Path area = new Path(mPath);
        area.lineTo(x(mPoints.get(mPoints.size() - 1).time, left, right), getHeight());
        area.lineTo(x(mPoints.get(0).time, left, right), getHeight());
        area.close();
        canvas.drawPath(area, mFill);
        canvas.drawPath(mPath, mLine);
    }

    private float x(long time, float left, float right) {
        // Clamped: the clock may have been set back since
        final float x = left + (right - left) * (time - mStart) / (float) (mEnd - mStart);
        return Math.max(left, Math.min(right, x));
    }

    private static float y(int percent, float top, float bottom) {
        return bottom - (bottom - top) * Math.max(0, Math.min(100, percent)) / 100f;
    }

    private float dp(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
