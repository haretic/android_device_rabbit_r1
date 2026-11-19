// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.view.View;

/** The picture under a tool's reading: the bar's lamps, the tuner's line or the tone's wave. */
final class StageView extends View {

    static final int LAMPS = 0;
    static final int CENTS = 1;
    static final int WAVE = 2;

    private final int mKind;
    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final float mDensity;
    private final int mAccent;
    private final int mLine;
    private final int mDim;
    /** Lamps: beats in the bar and the one lit, -1 none. Cents: -50..50, NaN none. Wave: on. */
    private int mBeats = 4;
    private int mBeat = -1;
    private float mCents = Float.NaN;
    private boolean mOn;
    /** Cents: the large needle, the in-tune band and the ends named, read from further away */
    private boolean mLarge;
    private final String mFlat;
    private final String mSharp;

    StageView(Context context, int kind) {
        super(context);
        mKind = kind;
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mLine = context.getColor(R.color.surface_line);
        mDim = context.getColor(R.color.text_faint);
        mFlat = context.getString(R.string.tuner_flat);
        mSharp = context.getString(R.string.tuner_sharp);
    }

    void large(boolean large) {
        if (large != mLarge) {
            mLarge = large;
            invalidate();
        }
    }

    void lamps(int beats, int beat) {
        if (beats != mBeats || beat != mBeat) {
            mBeats = beats;
            mBeat = beat;
            invalidate();
        }
    }

    void cents(float cents) {
        mCents = cents;
        invalidate();
    }

    void wave(boolean on) {
        if (on != mOn) {
            mOn = on;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        final float h = getHeight();
        final float cy = h / 2;
        if (mKind == LAMPS) {
            // The bar's first beat is the big lamp; beats played are filled, the rest rings
            final float small = Math.min(15 * mDensity, h * 0.3f);
            final float big = small * 1.4f;
            final float gap = Math.min(22 * mDensity, (w - 2 * big - 2 * small * (mBeats - 1))
                    / Math.max(1, mBeats - 1));
            float x = 0;
            for (int i = 0; i < mBeats; i++) {
                final float r = i == 0 ? big : small;
                mPaint.setStyle(i == mBeat || i < mBeat ? Paint.Style.FILL : Paint.Style.STROKE);
                mPaint.setStrokeWidth(2 * mDensity);
                mPaint.setColor(i == mBeat ? mAccent : 0xff4a4a4a);
                canvas.drawCircle(x + r, cy, r - mDensity, mPaint);
                x += 2 * r + gap;
            }
        } else if (mKind == CENTS && mLarge) {
            drawLargeCents(canvas, w, h);
        } else if (mKind == CENTS) {
            final float base = h * 0.62f;
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeCap(Paint.Cap.BUTT);
            mPaint.setStrokeWidth(1.5f * mDensity);
            mPaint.setColor(mLine);
            canvas.drawLine(0, base, w, base, mPaint);
            for (int c = -50; c <= 50; c += 10) {
                final float x = w / 2 + c * (w - 2 * mDensity) / 100;
                mPaint.setColor(c == 0 ? mDim : 0xff4a4a4a);
                mPaint.setStrokeWidth((c == 0 ? 2.5f : 1.5f) * mDensity);
                canvas.drawLine(x, base - (c % 50 == 0 ? 22 : 14) * mDensity, x, base, mPaint);
            }
            if (!Float.isNaN(mCents)) {
                final float x = w / 2 + Math.max(-50, Math.min(50, mCents)) * (w - 8 * mDensity)
                        / 100;
                mPaint.setStyle(Paint.Style.FILL);
                // In tune within 3 cents: the marker goes white on the middle mark
                mPaint.setColor(Math.abs(mCents) <= 3 ? 0xffffffff : mAccent);
                canvas.drawRoundRect(x - 4 * mDensity, base - 36 * mDensity, x + 4 * mDensity,
                        base + 8 * mDensity, 4 * mDensity, 4 * mDensity, mPaint);
            }
        } else {
            mPaint.setStyle(Paint.Style.STROKE);
            mPaint.setStrokeWidth(1.5f * mDensity);
            mPaint.setColor(mLine);
            canvas.drawLine(0, cy, w, cy, mPaint);
            mPath.rewind();
            final float amp = h / 2 - 3 * mDensity;
            for (float x = 0; x <= w; x += 3) {
                final float y = cy - (float) Math.sin(x / w * Math.PI * 6) * amp;
                if (x == 0) {
                    mPath.moveTo(x, y);
                } else {
                    mPath.lineTo(x, y);
                }
            }
            mPaint.setStrokeWidth(3 * mDensity);
            mPaint.setStrokeCap(Paint.Cap.ROUND);
            mPaint.setStrokeJoin(Paint.Join.ROUND);
            mPaint.setColor(mOn ? mAccent : 0xff5c5c5c);
            canvas.drawPath(mPath, mPaint);
        }
    }

    /**
     * The large needle: the stage's full height and a finger wide, over a band that is in tune
     * (within 3 cents), with the two ends named. White in the band, the accent outside it.
     */
    private void drawLargeCents(Canvas canvas, float w, float h) {
        final float d = mDensity;
        final float label = 13 * d * getResources().getConfiguration().fontScale;
        final float base = h - label - 8 * d;
        final float span = w - 16 * d;
        final float mid = w / 2;
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mLine);
        final float band = Math.max(3 * span / 100, 6 * d);
        canvas.drawRoundRect(mid - band, 0, mid + band, base, 4 * d, 4 * d, mPaint);
        mPaint.setStyle(Paint.Style.STROKE);
        mPaint.setStrokeCap(Paint.Cap.BUTT);
        mPaint.setStrokeWidth(2 * d);
        canvas.drawLine(0, base, w, base, mPaint);
        for (int c = -50; c <= 50; c += 10) {
            final float x = mid + c * span / 100;
            mPaint.setColor(c == 0 ? mDim : 0xff4a4a4a);
            mPaint.setStrokeWidth((c == 0 ? 3f : 2f) * d);
            canvas.drawLine(x, base - (c % 50 == 0 ? base * 0.6f : base * 0.35f), x, base, mPaint);
        }
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setColor(mDim);
        mPaint.setTextSize(label);
        mPaint.setTextAlign(Paint.Align.LEFT);
        canvas.drawText(mFlat, 0, h - 2 * d, mPaint);
        mPaint.setTextAlign(Paint.Align.RIGHT);
        canvas.drawText(mSharp, w, h - 2 * d, mPaint);
        mPaint.setTextAlign(Paint.Align.LEFT);
        if (!Float.isNaN(mCents)) {
            final float x = mid + Math.max(-50, Math.min(50, mCents)) * span / 100;
            final float half = 7 * d;
            mPaint.setColor(Math.abs(mCents) <= 3 ? 0xffffffff : mAccent);
            canvas.drawRoundRect(x - half, 2 * d, x + half, base + 6 * d, half, half, mPaint);
        }
    }
}
