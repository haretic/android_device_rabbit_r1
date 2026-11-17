// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.knob;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

/**
 * The dial against the screen's right edge, centred where the wheel is: ticks on an arc, and a
 * lit stretch that moves a tick with each step, so the turn is seen where it is made. It shows
 * motion, not a level: the r1 does not know the other side's volume.
 */
final class DialView extends View {
    private static final int TICKS = 24;
    /** The arc's radius, and how far left of the screen's right edge it reaches. */
    private static final int RADIUS_DP = 140;
    static final int REACH_DP = 90;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int mAccent;
    private float mAt = TICKS / 2f, mShown = TICKS / 2f;
    private float mCentre = 0.4f;

    DialView(Context context, int accent, float centre) {
        super(context);
        mAccent = accent;
        mCentre = centre;
        mPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    private int mTop;

    /** The arc stays below this height of the screen, where the page's head ends. */
    void setTop(int top) {
        mTop = top;
        invalidate();
    }

    void step(boolean up) {
        // Running off an end comes back in at the other: the dial has no ends
        mAt = (mAt + (up ? -1 : 1) + TICKS + 1) % (TICKS + 1);
        if (Math.abs(mAt - mShown) > 2) {
            mShown = mAt;
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float d = getResources().getDisplayMetrics().density;
        final int[] at = new int[2];
        getLocationOnScreen(at);
        // Against the right edge, where the wheel is: a short arc that leaves the page to
        // the words beside it
        final float w = getWidth(), r = RADIUS_DP * d;
        final float cx = w + (RADIUS_DP - REACH_DP) * d;
        // Level with the wheel, or as near to it as the head of the page leaves room for:
        // the arc's ends are half its radius above and below its middle
        final float cy = Math.max(getRootView().getHeight() * mCentre, mTop + r / 2 + 6 * d)
                - at[1];
        mShown += (mAt - mShown) * 0.35f;
        if (Math.abs(mAt - mShown) > 0.01f) {
            postInvalidateOnAnimation();
        } else {
            mShown = mAt;
        }
        for (int i = 0; i <= TICKS; i++) {
            final double a = Math.toRadians(150 + 60.0 * i / TICKS);
            final boolean big = i % 6 == 0;
            final float near = Math.abs(i - mShown);
            mPaint.setColor(near < 2.6f ? mAccent : 0xFF4A4039);
            mPaint.setStrokeWidth((big ? 5 : 3.5f) * d);
            final float r1 = r - (big ? 26 : 16) * d;
            canvas.drawLine(cx + r * (float) Math.cos(a), cy + r * (float) Math.sin(a),
                    cx + r1 * (float) Math.cos(a), cy + r1 * (float) Math.sin(a), mPaint);
        }
        final double a = Math.toRadians(150 + 60.0 * mShown / TICKS);
        mPaint.setColor(0xFFF1F1F1);
        canvas.drawCircle(cx + (r - 44 * d) * (float) Math.cos(a),
                cy + (r - 44 * d) * (float) Math.sin(a), 9 * d, mPaint);
    }
}
