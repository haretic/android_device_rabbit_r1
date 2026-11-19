// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.synth;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

import cat.kenny.r1.synth.engine.SynthEngine;

import java.util.Random;

/**
 * The six pads, two across, three across where the view is wide, as turned. A pad under a finger turns to the accent and, held, breaks into
 * slices that shift with every stutter. Each pad carries a small drawing of its sound.
 */
public final class PadsView extends View {

    interface Listener {
        void onPad(int pad, boolean down);
    }

    /** Each sound's drawing is about this tall, in dp (mark()). */
    private static final float MARK_HEIGHT = 44;
    private static final int SLICES = 5;
    private static final int INK = 0xff2a1206;

    private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path mPath = new Path();
    private final RectF mRect = new RectF();
    private final float mDensity;
    private final int mAccent;
    private final int mCard;
    /** The pointer on each pad, -1 none */
    private final int[] mPointer = new int[SynthEngine.PADS];
    private final float[] mLevel = new float[SynthEngine.PADS];
    private final int[] mSlice = new int[SynthEngine.PADS];
    /** Held down by the side button, where the wheel plays the pads */
    private final boolean[] mKeyDown = new boolean[SynthEngine.PADS];
    /** The pad the wheel is on, -1 where the wheel is a knob */
    private int mSelected = -1;
    /** Where each drawing's scattered parts are: the same every time the app is opened */
    private final float[] mScatter = new float[64];
    private String[] mNames = new String[SynthEngine.PADS];
    private Listener mListener;

    public PadsView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mCard = context.getColor(R.color.surface);
        java.util.Arrays.fill(mPointer, -1);
        final Random random = new Random(1337);
        for (int i = 0; i < mScatter.length; i++) {
            mScatter[i] = random.nextFloat();
        }
    }

    void set(String[] names, Listener listener) {
        mNames = names;
        setContentDescription(android.text.TextUtils.join(", ", names));
        mListener = listener;
    }

    /** Outlines the pad the wheel is on; -1 for none. */
    void select(int pad) {
        if (mSelected != pad) {
            mSelected = pad;
            invalidate();
        }
    }

    /** A pad held down by the side button rather than a finger. */
    void keyDown(int pad, boolean down) {
        if (mKeyDown[pad] != down) {
            mKeyDown[pad] = down;
            invalidate();
        }
    }

    /** From the engine, every frame while anything sounds. */
    void show(int pad, float level, int slice) {
        if (mLevel[pad] != level || mSlice[pad] != slice) {
            mLevel[pad] = level;
            mSlice[pad] = slice;
            invalidate();
        }
    }

    /** Two across upright; three in a view twice as wide as tall, as with the screen turned. */
    private int columns() {
        return getWidth() >= 2 * getHeight() ? 3 : 2;
    }

    private void bounds(int pad, RectF out) {
        final int columns = columns();
        final int rows = (SynthEngine.PADS + columns - 1) / columns;
        final float gap = 8 * mDensity;
        final float w = (getWidth() - gap * (columns - 1)) / columns;
        final float h = (getHeight() - gap * (rows - 1)) / rows;
        final float x = (pad % columns) * (w + gap);
        final float y = (pad / columns) * (h + gap);
        out.set(x, y, x + w, y + h);
    }

    private int padAt(float x, float y) {
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            bounds(pad, mRect);
            if (mRect.contains(x, y)) {
                return pad;
            }
        }
        return -1;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        final int action = event.getActionMasked();
        final int index = event.getActionIndex();
        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            final int pad = padAt(event.getX(index), event.getY(index));
            if (pad >= 0 && mPointer[pad] < 0) {
                mPointer[pad] = event.getPointerId(index);
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
                mListener.onPad(pad, true);
                invalidate();
            }
        } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP
                || action == MotionEvent.ACTION_CANCEL) {
            for (int pad = 0; pad < SynthEngine.PADS; pad++) {
                if (mPointer[pad] >= 0 && (action == MotionEvent.ACTION_CANCEL
                        || mPointer[pad] == event.getPointerId(index))) {
                    mPointer[pad] = -1;
                    mListener.onPad(pad, false);
                    invalidate();
                }
            }
        }
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        final float radius = 22 * mDensity;
        for (int pad = 0; pad < SynthEngine.PADS; pad++) {
            bounds(pad, mRect);
            final boolean held = mPointer[pad] >= 0 || mKeyDown[pad];
            if (held) {
                // In slices that shift sideways, anew with every stutter
                final float sliceH = mRect.height() / SLICES;
                final Random shift = new Random(pad * 31L + mSlice[pad]);
                mPaint.setColor(mAccent);
                for (int i = 0; i < SLICES; i++) {
                    final float dx = mSlice[pad] == 0 ? 0
                            : (shift.nextFloat() - 0.5f) * 26 * mDensity;
                    final float top = mRect.top + i * sliceH;
                    canvas.save();
                    canvas.clipRect(mRect.left - 20 * mDensity, top,
                            mRect.right + 20 * mDensity, top + sliceH + 0.5f);
                    canvas.drawRoundRect(mRect.left + dx, mRect.top, mRect.right + dx,
                            mRect.bottom, radius, radius, mPaint);
                    canvas.restore();
                }
            } else {
                // Still ringing: the card glows towards the accent with the sound's level
                mPaint.setColor(blend(mCard, mAccent, Math.min(0.5f, mLevel[pad] * 0.6f)));
                canvas.drawRoundRect(mRect, radius, radius, mPaint);
            }
            if (pad == mSelected) {
                // The pad the wheel is on, outlined as the focus is everywhere
                final float inset = 1.5f * mDensity;
                mPaint.setColor(held ? 0xffffffff : mAccent);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(3 * mDensity);
                canvas.drawRoundRect(mRect.left + inset, mRect.top + inset,
                        mRect.right - inset, mRect.bottom - inset, radius, radius, mPaint);
                mPaint.setStyle(Paint.Style.FILL);
            }
            final int ink = held ? INK : 0xff8d8d8d;
            mPaint.setTypeface(Typeface.create("haretic-text-bold", Typeface.NORMAL));
            mPaint.setTextSize(18 * mDensity);
            // The drawing above the name, never under it: shrunk to the room a short pad has
            final float markTop = mRect.top + Math.min(18 * mDensity, mRect.height() * 0.12f);
            final float nameTop = mRect.bottom - 18 * mDensity + mPaint.ascent() - 4 * mDensity;
            final float scale = Math.max(0.3f,
                    Math.min(1, (nameTop - markTop) / (MARK_HEIGHT * mDensity)));
            canvas.save();
            canvas.scale(scale, scale, mRect.left + 18 * mDensity, markTop);
            mark(canvas, pad, mRect.left + 18 * mDensity, markTop, ink);
            canvas.restore();
            mPaint.setStyle(Paint.Style.FILL);
            mPaint.setTypeface(Typeface.create("haretic-text-bold", Typeface.NORMAL));
            mPaint.setTextSize(18 * mDensity);
            mPaint.setColor(held ? INK : 0xffffffff);
            if (mNames[pad] != null) {
                canvas.drawText(mNames[pad], mRect.left + 18 * mDensity,
                        mRect.bottom - 18 * mDensity, mPaint);
            }
        }
    }

    private static int blend(int a, int b, float t) {
        final int r = Math.round(((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        final int g = Math.round(((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        final int bl = Math.round((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }

    /** Each sound's small drawing, in a box about 112 by 44 dp. */
    private void mark(Canvas canvas, int pad, float x, float y, int color) {
        final float d = mDensity;
        mPaint.setColor(color);
        mPaint.setStyle(Paint.Style.FILL);
        mPaint.setStrokeCap(Paint.Cap.ROUND);
        switch (pad) {
            case SynthEngine.GRAIN:
                for (int i = 0; i < 14; i++) {
                    final float top = y + (8 + mScatter[i] * 22) * d;
                    canvas.drawRect(x + (4 + i * 7) * d, top, x + (7 + i * 7) * d,
                            top + (3 + mScatter[i + 14] * 9) * d, mPaint);
                }
                break;
            case SynthEngine.CHIRP:
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(3 * d);
                mPath.rewind();
                mPath.moveTo(x + 4 * d, y + 34 * d);
                mPath.quadTo(x + 40 * d, y + 34 * d, x + 60 * d, y + 20 * d);
                mPath.quadTo(x + 80 * d, y + 6 * d, x + 100 * d, y + 4 * d);
                canvas.drawPath(mPath, mPaint);
                break;
            case SynthEngine.CRUSH: {
                final int[] steps = {26, 26, 14, 14, 30, 8, 8, 22, 22};
                for (int i = 0; i < steps.length; i++) {
                    canvas.drawRect(x + (4 + i * 11) * d, y + steps[i] * d,
                            x + (15 + i * 11) * d, y + (steps[i] + 4) * d, mPaint);
                }
                break;
            }
            case SynthEngine.BELL:
                canvas.drawCircle(x + 22 * d, y + 22 * d, 6 * d, mPaint);
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(2 * d);
                mPaint.setAlpha(150);
                canvas.drawCircle(x + 22 * d, y + 22 * d, 14 * d, mPaint);
                mPaint.setStrokeWidth(1.5f * d);
                mPaint.setAlpha(76);
                canvas.drawCircle(x + 22 * d, y + 22 * d, 22 * d, mPaint);
                mPaint.setAlpha(255);
                break;
            case SynthEngine.SUB:
                mPaint.setStyle(Paint.Style.STROKE);
                mPaint.setStrokeWidth(4 * d);
                mPath.rewind();
                for (int i = 0; i <= 54; i++) {
                    final float px = x + (4 + i * 2) * d;
                    final float py = y + (20 - 13 * (float) Math.sin(i / 54.0 * Math.PI * 3)) * d;
                    if (i == 0) {
                        mPath.moveTo(px, py);
                    } else {
                        mPath.lineTo(px, py);
                    }
                }
                canvas.drawPath(mPath, mPaint);
                break;
            default:
                for (int i = 0; i < 26; i++) {
                    canvas.drawCircle(x + (6 + mScatter[i + 28] * 96) * d,
                            y + (4 + mScatter[i + 2] * 32) * d, 1.6f * d, mPaint);
                }
        }
        mPaint.setStyle(Paint.Style.FILL);
    }
}
