// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

/**
 * The face while the button is held: the whole screen in the accent, rings spreading from where
 * the finger is, and who hears it.
 */
public final class TalkView extends View {

    private static final int INK = 0xff2a1206;

    private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint mText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final float mDensity;
    private final int mAccent;
    private String mTo = "";
    private String mWho = "";
    private String mLetGo = "";
    private String mTitle = "";
    private long mSince;

    public TalkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
        final TypedArray a = context.obtainStyledAttributes(
                new int[] {android.R.attr.colorAccent});
        mAccent = a.getColor(0, 0xffec6a37);
        a.recycle();
        mRing.setStyle(Paint.Style.STROKE);
        mRing.setColor(INK);
        mText.setColor(INK);
    }

    void set(String title, String to, String who, String letGo, long since) {
        mTitle = title;
        setContentDescription(title + "\n" + to + "\n" + who + "\n" + letGo);
        mTo = to;
        mWho = who;
        mLetGo = letGo;
        mSince = since;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(mAccent);
        final float w = getWidth();
        final float h = getHeight();
        // The side button is on the right edge at the middle line: the rings start there and
        // move outwards while the button is held
        final float phase = (System.currentTimeMillis() % 1400) / 1400f;
        final float pitch = 58 * mDensity;
        for (int i = 0; i < 8; i++) {
            final float r = (i + phase) * pitch + 12 * mDensity;
            final float far = Math.min(1, r / (w * 0.95f));
            mRing.setStrokeWidth(Math.max(3, 9 - 7 * far) * mDensity);
            mRing.setAlpha(Math.round(255 * Math.max(0.08f, 1 - far * 1.05f)));
            canvas.drawCircle(w + 12 * mDensity, h / 2, r, mRing);
        }
        final float left = 24 * mDensity;
        final int room = Math.round(w * 0.56f);
        float y = h * 0.34f;
        mText.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        mText.setTextSize(31 * mDensity);
        canvas.drawText(mTitle, left, y, mText);
        y += 36 * mDensity;
        mText.setTypeface(Typeface.create("haretic-text-bold", Typeface.NORMAL));
        mText.setTextSize(19 * mDensity);
        canvas.drawText(mTo, left, y, mText);
        y += 10 * mDensity;
        mText.setTypeface(Typeface.create("haretic-text", Typeface.NORMAL));
        mText.setTextSize(17 * mDensity);
        final StaticLayout who = StaticLayout.Builder.obtain(mWho, 0, mWho.length(), mText, room)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(3).build();
        canvas.save();
        canvas.translate(left, y);
        who.draw(canvas);
        canvas.restore();
        y += who.getHeight() + 44 * mDensity;
        final long seconds = Math.max(0, (System.currentTimeMillis() - mSince) / 1000);
        mText.setTypeface(Typeface.create("haretic-display", Typeface.NORMAL));
        mText.setTextSize(22 * mDensity);
        canvas.drawText(seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60,
                left, y, mText);
        mText.setTypeface(Typeface.create("haretic-text-medium", Typeface.NORMAL));
        mText.setTextSize(16.5f * mDensity);
        canvas.drawText(mLetGo, left, h - 28 * mDensity, mText);
        if (getVisibility() == VISIBLE) {
            postInvalidateOnAnimation();
        }
    }
}
