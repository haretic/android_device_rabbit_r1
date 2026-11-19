// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

/** Over the camera's picture: the square to hold the code in, its corners in the accent. */
public final class CornersView extends View {

    private final Paint mDim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float mDensity;

    public CornersView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mDim.setColor(0x73000000);
        final TypedValue accent = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.colorAccent, accent, true);
        mLine.setColor(accent.data);
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(3.5f * mDensity);
        mLine.setStrokeCap(Paint.Cap.ROUND);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float side = Math.min(w, h) - 2 * 40 * mDensity;
        if (side <= 0) {
            return;
        }
        final float left = (w - side) / 2;
        final float top = (h - side) / 2;
        final RectF square = new RectF(left, top, left + side, top + side);
        final float round = 22 * mDensity;
        // Dim around the square
        mPath.reset();
        mPath.setFillType(Path.FillType.EVEN_ODD);
        mPath.addRect(0, 0, w, h, Path.Direction.CW);
        mPath.addRoundRect(square, round, round, Path.Direction.CW);
        canvas.drawPath(mPath, mDim);
        // The four corners
        final float arm = 38 * mDensity;
        final float r = round;
        mPath.reset();
        mPath.setFillType(Path.FillType.WINDING);
        // top left
        mPath.moveTo(square.left, square.top + arm);
        mPath.lineTo(square.left, square.top + r);
        mPath.quadTo(square.left, square.top, square.left + r, square.top);
        mPath.lineTo(square.left + arm, square.top);
        // top right
        mPath.moveTo(square.right - arm, square.top);
        mPath.lineTo(square.right - r, square.top);
        mPath.quadTo(square.right, square.top, square.right, square.top + r);
        mPath.lineTo(square.right, square.top + arm);
        // bottom right
        mPath.moveTo(square.right, square.bottom - arm);
        mPath.lineTo(square.right, square.bottom - r);
        mPath.quadTo(square.right, square.bottom, square.right - r, square.bottom);
        mPath.lineTo(square.right - arm, square.bottom);
        // bottom left
        mPath.moveTo(square.left + arm, square.bottom);
        mPath.lineTo(square.left + r, square.bottom);
        mPath.quadTo(square.left, square.bottom, square.left, square.bottom - r);
        mPath.lineTo(square.left, square.bottom - arm);
        canvas.drawPath(mPath, mLine);
    }
}
