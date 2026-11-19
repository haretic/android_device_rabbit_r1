// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.SystemProperties;
import android.util.AttributeSet;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * What the wheel turns, against the edge the wheel is on: the channels as a drum with the one
 * tuned in at the middle line, or, while someone speaks, the volume as a ladder of marks.
 * Upright down the right edge; turned, with the wheel along the top or the bottom
 * (wheelEdge), across it, the next channel to the right and loud on the left, the way a step
 * of the wheel goes there (SystemUI turns the wheel's way with the screen).
 */
public final class DrumView extends View {

    /** A finger on the drum: it turns under the finger, and a tap picks what was tapped. */
    interface Listener {
        /** As a wheel click: +1 is the next item, or quieter, as the wheel turned down. */
        void onDrumTurn(int way);

        /** The item tapped, while it shows the items. */
        void onDrumPick(int item);

        /** The volume tapped, while it shows the volume. */
        void onVolumePick(int volume);

        void onDrumTalk(boolean down);
    }

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
    private Listener mListener;
    private final int mSlop;
    private float mDownX, mDownY, mAtY;
    private boolean mDragging, mHeld;
    /** The wheel's edge, see wheelEdge: the drum runs across the top (1) or bottom (3). */
    private int mEdge;
    private final Runnable mHold = () -> {
        mHeld = true;
        mListener.onDrumTalk(true);
    };

    public DrumView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mText.setTextAlign(Paint.Align.RIGHT);
        mSlop = ViewConfiguration.get(context).getScaledTouchSlop();
    }

    void setListener(Listener listener) {
        mListener = listener;
    }

    /**
     * The screen edge the wheel is on now: 0 right, as upright, 1 top, 2 left, 3 bottom. As
     * SystemUI's WheelTaskbarSettings.physicalRotation, with the boot-selected natural
     * orientation (ro.vendor.display.primary_orientation) counted in.
     */
    static int wheelEdge(Context context) {
        final Display display = context.getDisplay();
        final int rotation = display != null ? display.getRotation() : 0;
        final int offset = SystemProperties.getInt("ro.vendor.display.primary_orientation", 0);
        return (rotation + (offset == 90 || offset == 180 || offset == 270 ? offset / 90 : 0))
                % 4;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mEdge = wheelEdge(getContext());
    }

    /** The wheel's edge again, after a turn of 180 degrees that kept the page. */
    void setEdge(int edge) {
        if (edge != mEdge) {
            mEdge = edge;
            invalidate();
        }
    }

    private boolean across() {
        return (mEdge == 1 || mEdge == 3) && getWidth() > getHeight();
    }

    /** Where a finger is along the drum. */
    private float along(MotionEvent event) {
        return across() ? event.getX() : event.getY();
    }

    /** The screen's middle line in this view, where the wheel is: across, its middle. */
    private float mid() {
        final int[] at = new int[2];
        getLocationOnScreen(at);
        return across() ? getRootView().getWidth() / 2f - at[0]
                : getRootView().getHeight() / 2f - at[1];
    }

    private float itemPitch() {
        if (across()) {
            return (mVolume >= 0 ? 30 : 100) * mDensity;
        }
        return (mVolume >= 0 ? 22 : 46) * mDensity;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (mListener == null) {
            return super.onTouchEvent(event);
        }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancelTouch();
                mDownX = event.getX();
                mDownY = mAtY = along(event);
                mDragging = false;
                postDelayed(mHold, ViewConfiguration.getLongPressTimeout());
                getParent().requestDisallowInterceptTouchEvent(true);
                return true;
            case MotionEvent.ACTION_MOVE: {
                if (!mDragging && Math.hypot(event.getX() - mDownX,
                        event.getY() - mDownY) >= mSlop) {
                    cancelTouch();
                    mDragging = true;
                }
                final float pitch = itemPitch();
                while (mDragging && Math.abs(along(event) - mAtY) >= pitch) {
                    // The drum follows the finger: drawn up (left, across), the next item comes
                    // to the middle. The volume's ladder is loud at the top (left), so up there
                    // is louder
                    final boolean up = along(event) < mAtY;
                    mAtY += up ? -pitch : pitch;
                    mListener.onDrumTurn(mVolume >= 0 ? (up ? -1 : 1) : (up ? 1 : -1));
                }
                return true;
            }
            case MotionEvent.ACTION_UP:
                removeCallbacks(mHold);
                if (!mDragging && !mHeld) {
                    performClick();
                    final float off = (along(event) - mid()) / itemPitch();
                    if (mVolume >= 0) {
                        // The marks are loud at the top, nine of them around the middle line
                        final int mark = Math.max(0, Math.min(8, Math.round(4 - off)));
                        mListener.onVolumePick(Math.round(mark * mVolumeMax / 8f));
                    } else {
                        final int item = Math.round(mShown + off);
                        if (item >= 0 && item < mItems.length) {
                            mListener.onDrumPick(item);
                        }
                    }
                }
                cancelTouch();
                return true;
            case MotionEvent.ACTION_CANCEL:
                cancelTouch();
                return true;
            default:
                return true;
        }
    }

    void cancelTouch() {
        removeCallbacks(mHold);
        if (mHeld) {
            mHeld = false;
            mListener.onDrumTalk(false);
        }
    }

    boolean holding() {
        return mHeld;
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelTouch();
        super.onDetachedFromWindow();
    }

    @Override
    public boolean performClick() {
        return super.performClick();
    }

    void channels(String[] items, int at) {
        mItems = items;
        if (at >= 0 && at < items.length) {
            setContentDescription(items[at]);
        }
        if (mVolume >= 0 || mAt != at) {
            mVolume = -1;
            mAt = at;
            invalidate();
        }
    }

    void volume(int volume, int max) {
        setContentDescription(getResources().getString(R.string.drum_volume, volume));
        mVolume = volume;
        mVolumeMax = Math.max(1, max);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (across()) {
            drawAcross(canvas);
            return;
        }
        mText.setTextAlign(Paint.Align.RIGHT);
        final float w = getWidth();
        // The wheel is at the screen's middle line, not this view's
        final float mid = mid();
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

    /**
     * The drum across the top, the wheel's edge, or the bottom: its marks against that edge,
     * the names beside them away from it, the tuned-in one at the screen's middle.
     */
    private void drawAcross(Canvas canvas) {
        final float h = getHeight();
        final float d = mDensity;
        final boolean flip = mEdge == 3;
        final float mid = mid();
        if (mVolume >= 0) {
            final int marks = 9;
            final float pitch = itemPitch();
            final int lit = Math.round(mVolume * (marks - 1) / (float) mVolumeMax);
            for (int i = 0; i < marks; i++) {
                // Loud on the left
                final float x = mid + (marks / 2 - i) * pitch;
                final boolean here = i == lit;
                mMark.setColor(here ? 0xffffffff : i < lit ? mAccent : 0xff5c5c5c);
                final float len = (here ? 40 : 14) * d;
                final float half = (here ? 3 : 1) * d;
                canvas.drawRoundRect(x - half, flip ? h - len : 0, x + half, flip ? h : len,
                        half, half, mMark);
            }
            return;
        }
        if (Math.abs(mShown - mAt) > 0.01f) {
            mShown += (mAt - mShown) * 0.35f;
            postInvalidateOnAnimation();
        } else {
            mShown = mAt;
        }
        final float pitch = itemPitch();
        mText.setTextAlign(Paint.Align.CENTER);
        for (int i = 0; i < mItems.length; i++) {
            final float off = i - mShown;
            if (Math.abs(off) > 3.4f) {
                continue;
            }
            final float x = mid + off * pitch;
            final boolean here = i == mAt;
            final float fade = Math.max(0.25f, 1 - Math.abs(off) * 0.3f);
            mText.setTypeface(Typeface.create(here ? "haretic-text-bold" : "haretic-text",
                    Typeface.NORMAL));
            mText.setTextSize((here ? 18 : 16) * d);
            mText.setColor(here ? 0xffffffff : 0xffb8b8b8);
            mText.setAlpha(Math.round(255 * (here ? 1 : fade * 0.8f)));
            final float textMid = flip ? h * 0.38f : h * 0.62f;
            canvas.drawText(mItems[i], x, textMid - (mText.ascent() + mText.descent()) / 2,
                    mText);
            mMark.setColor(here ? mAccent : 0xff5c5c5c);
            final float len = (here ? 16 : 8) * d;
            final float half = (here ? 3 : 0.75f) * d;
            canvas.drawRoundRect(x - half, flip ? h - len : -half, x + half,
                    flip ? h + half : len, half, half, mMark);
        }
    }
}
