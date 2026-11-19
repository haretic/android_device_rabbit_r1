// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import cat.kenny.r1.tunnels.qr.Qr;

/** A code a phone's camera reads, dark on a white card with the quiet edge it needs. */
public final class QrView extends View {

    private final Paint mPaint = new Paint();
    private final float mDensity;
    private boolean[][] mModules;
    private String mText;

    public QrView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mDensity = getResources().getDisplayMetrics().density;
    }

    void set(String text) {
        if (text.equals(mText)) {
            return;
        }
        mText = text;
        mModules = Qr.encode(text);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        mPaint.setColor(0xffffffff);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), 10 * mDensity, 10 * mDensity,
                mPaint);
        if (mModules == null) {
            return;
        }
        final int count = mModules.length;
        // Whole pixels a module, or the code shimmers; what is left over goes around it
        final int module = Math.max(1, (int) ((getWidth() - 12 * mDensity) / count));
        final float left = (getWidth() - module * count) / 2f;
        final float top = (getHeight() - module * count) / 2f;
        mPaint.setColor(0xff000000);
        for (int y = 0; y < count; y++) {
            for (int x = 0; x < count; x++) {
                if (mModules[y][x]) {
                    canvas.drawRect(left + x * module, top + y * module,
                            left + (x + 1) * module, top + (y + 1) * module, mPaint);
                }
            }
        }
    }
}
