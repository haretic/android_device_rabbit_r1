// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/**
 * The time of a period as one bar: screen on, awake with the screen off, asleep, and on the
 * charger. A week holds thousands of stretches and the bar a few hundred pixels, so each
 * pixel column shows what mattered in its time: the screen and awake time show from a small
 * share on, or a night's wake-ups would vanish in the sleep around them.
 */
public class DrainBarView extends View {

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mClip = new Path();
    private final RectF mBounds = new RectF();
    private final int[] mColors = new int[4];
    private List<DrainModel.Span> mSpans;
    private long mFrom;
    private long mTo;

    public DrainBarView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mColors[DrainModel.SCREEN] = stateColor(context, DrainModel.SCREEN);
        mColors[DrainModel.AWAKE] = stateColor(context, DrainModel.AWAKE);
        mColors[DrainModel.ASLEEP] = stateColor(context, DrainModel.ASLEEP);
        mColors[DrainModel.CHARGING] = context.getColor(R.color.drain_charging);
    }

    /** The colour a state has in the bar and on its row. */
    static int stateColor(Context context, int state) {
        if (state == DrainModel.SCREEN) {
            final TypedArray a = context.obtainStyledAttributes(
                    new int[] {android.R.attr.colorAccent});
            final int accent = a.getColor(0, 0xFFEC6A37);
            a.recycle();
            return accent;
        }
        return context.getColor(state == DrainModel.AWAKE ? R.color.drain_awake
                : R.color.drain_asleep);
    }

    void setSpans(List<DrainModel.Span> spans, long from, long to) {
        mSpans = spans;
        mFrom = from;
        mTo = to;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final int width = getWidth();
        final int height = getHeight();
        if (mSpans == null || mTo <= mFrom || width <= 0) {
            return;
        }
        mBounds.set(0, 0, width, height);
        mClip.rewind();
        mClip.addRoundRect(mBounds, height / 2f, height / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(mClip);
        final double perPixel = (double) (mTo - mFrom) / width;
        final double[] ms = new double[4];
        int span = 0;
        int runStart = 0;
        int runState = -1;
        for (int x = 0; x <= width; x++) {
            int state = -1;
            if (x < width) {
                final double a = mFrom + x * perPixel;
                final double b = a + perPixel;
                java.util.Arrays.fill(ms, 0);
                while (span < mSpans.size() && mSpans.get(span).end <= a) {
                    span++;
                }
                for (int i = span; i < mSpans.size() && mSpans.get(i).start < b; i++) {
                    final DrainModel.Span s = mSpans.get(i);
                    ms[s.state] += Math.min(b, s.end) - Math.max(a, s.start);
                }
                state = ms[DrainModel.CHARGING] > perPixel / 2 ? DrainModel.CHARGING
                        : ms[DrainModel.SCREEN] >= perPixel * 0.3 ? DrainModel.SCREEN
                        : ms[DrainModel.AWAKE] >= perPixel * 0.15 ? DrainModel.AWAKE
                        : ms[DrainModel.ASLEEP] > 0 ? DrainModel.ASLEEP
                        : ms[DrainModel.SCREEN] > 0 ? DrainModel.SCREEN
                        : ms[DrainModel.AWAKE] > 0 ? DrainModel.AWAKE
                        : ms[DrainModel.CHARGING] > 0 ? DrainModel.CHARGING : -1;
            }
            if (state != runState) {
                if (runState >= 0) {
                    mPaint.setColor(mColors[runState]);
                    canvas.drawRect(runStart, 0, x, height, mPaint);
                }
                runStart = x;
                runState = state;
            }
        }
        canvas.restore();
    }
}
