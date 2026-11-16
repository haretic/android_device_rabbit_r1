// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import cat.kenny.r1.toolbox.engine.LevelMath;

/**
 * The level's dial. Flat: a ball on a plate, rolling to the low side, home in the ring at the
 * centre. On edge and plumb: a line that stays with the horizon over a fixed mark.
 */
public final class BubbleView extends View {

    /** The tilt that puts the ball at the rim. */
    private static final float RIM_DEGREES = 10f;

    private final Paint mLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlate = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mBall = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mShine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private int mMode;
    private float mX;
    private float mY;
    private float mAngle;

    public BubbleView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        mLine.setStyle(Paint.Style.STROKE);
        mLine.setStrokeWidth(1.5f * mDensity);
        mLine.setColor(context.getColor(R.color.surface_line));
        mPlate.setColor(context.getColor(R.color.surface));
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mBall.setColor(a.getColor(0, 0xffec6a37));
        a.recycle();
        mShine.setColor(0x59ffffff);
    }

    void set(int mode, float tiltX, float tiltY, float angle) {
        mMode = mode;
        mX = tiltX;
        mY = tiltY;
        mAngle = angle;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float cx = getWidth() / 2f;
        final float cy = getHeight() / 2f;
        final float r = Math.min(cx, cy) - 2 * mDensity;
        if (r <= 0) {
            return;
        }
        canvas.drawCircle(cx, cy, r, mPlate);
        mLine.setColor(getContext().getColor(R.color.surface_line));
        mLine.setStrokeWidth(1.5f * mDensity);
        canvas.drawCircle(cx, cy, r, mLine);
        if (mMode == LevelMath.FLAT) {
            canvas.drawCircle(cx, cy, r * 0.62f, mLine);
            canvas.drawLine(cx - r, cy, cx + r, cy, mLine);
            canvas.drawLine(cx, cy - r, cx, cy + r, mLine);
            final float ball = r * 0.2f;
            // Home: a ring the ball fits, brighter than the plate's lines
            mLine.setColor(getContext().getColor(R.color.text_dim));
            mLine.setStrokeWidth(2 * mDensity);
            canvas.drawCircle(cx, cy, ball + 3 * mDensity, mLine);
            float dx = mX / RIM_DEGREES;
            float dy = -mY / RIM_DEGREES;
            final float d = (float) Math.hypot(dx, dy);
            if (d > 1) {
                dx /= d;
                dy /= d;
            }
            final float bx = cx + dx * (r - ball);
            final float by = cy + dy * (r - ball);
            canvas.drawCircle(bx, by, ball, mBall);
            canvas.drawCircle(bx - ball * 0.3f, by - ball * 0.35f, ball * 0.22f, mShine);
            return;
        }
        // The mark the line should lie on
        canvas.drawLine(cx - r, cy, cx - r * 0.72f, cy, mLine);
        canvas.drawLine(cx + r * 0.72f, cy, cx + r, cy, mLine);
        canvas.drawLine(cx, cy - r, cx, cy - r * 0.86f, mLine);
        canvas.drawLine(cx, cy + r * 0.86f, cx, cy + r, mLine);
        canvas.save();
        // The line stays level while the r1 turns under it
        canvas.rotate(-mAngle, cx, cy);
        mBall.setStrokeWidth(4 * mDensity);
        mBall.setStrokeCap(Paint.Cap.ROUND);
        if (mMode == LevelMath.ON_EDGE) {
            canvas.drawLine(cx - r * 0.66f, cy, cx + r * 0.66f, cy, mBall);
        } else {
            canvas.drawLine(cx, cy - r * 0.8f, cx, cy + r * 0.8f, mBall);
        }
        canvas.drawCircle(cx, cy, 7 * mDensity, mBall);
        canvas.restore();
    }
}
