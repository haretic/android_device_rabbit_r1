// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;

/**
 * What the wheel turns, against the edge the wheel is on: the channels as a drum with the one
 * tuned in at the middle line, or, while someone speaks, the volume as a ladder of marks.
 */
public final class DrumView extends View {

    private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mMark = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mAccent;
    private String[] mItems = new String[0];
    private int mAt;
    /** Where the drum is drawn, in items: it runs after mAt for a few frames. */
    private float mShown;
    private int mVolume = -1;
    private int mVolumeMax = 1;

    public DrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mText.setTextAlign(Paint.Align.RIGHT);
    }

    void channels(String[] items, int at) {
        mItems = items;
        if (mVolume >= 0 || mAt != at) {
            mVolume = -1;
            mAt = at;
            invalidate();
        }
    }

    void volume(int volume, int max) {
        mVolume = volume;
        mVolumeMax = Math.max(1, max);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float w = getWidth();
        // The wheel is at the screen's middle line, not this view's
        final int[] at = new int[2];
        getLocationOnScreen(at);
        final float mid = getRootView().getHeight() / 2f - at[1];
        if (mVolume >= 0) {
            final int marks = 9;
            final float pitch = 22 * mDensity;
            final int lit = Math.round(mVolume * (marks - 1) / (float) mVolumeMax);
            for (int i = 0; i < marks; i++) {
                // Loud at the top
                final float y = mid + (marks / 2 - i) * pitch;
                final boolean here = i == lit;
                mMark.setColor(here ? 0xffffffff : i < lit ? mAccent : 0xff5c5c5c);
                final float len = (here ? 46 : 14) * mDensity;
                final float half = (here ? 3 : 1) * mDensity;
                canvas.drawRoundRect(w - len, y - half, w, y + half, half, half, mMark);
            }
            return;
        }
        if (Math.abs(mShown - mAt) > 0.01f) {
            mShown += (mAt - mShown) * 0.35f;
            postInvalidateOnAnimation();
        } else {
            mShown = mAt;
        }
        final float pitch = 46 * mDensity;
        for (int i = 0; i < mItems.length; i++) {
            final float off = i - mShown;
            if (Math.abs(off) > 3.4f) {
                continue;
            }
            final float y = mid + off * pitch;
            final boolean here = i == mAt;
            final float fade = Math.max(0.25f, 1 - Math.abs(off) * 0.24f);
            mText.setTypeface(Typeface.create(here ? "haretic-text-bold" : "haretic-text",
                    Typeface.NORMAL));
            mText.setTextSize((here ? 18 : 16) * mDensity);
            mText.setColor(here ? 0xffffffff : 0xffb8b8b8);
            mText.setAlpha(Math.round(255 * (here ? 1 : fade * 0.8f)));
            canvas.drawText(mItems[i], w - 34 * mDensity,
                    y - (mText.ascent() + mText.descent()) / 2, mText);
            mMark.setColor(here ? mAccent : 0xff5c5c5c);
            final float len = (here ? 20 : 8) * mDensity;
            final float half = (here ? 3 : 0.75f) * mDensity;
            canvas.drawRoundRect(w - len, y - half, w + half, y + half, half, half, mMark);
        }
    }
}
