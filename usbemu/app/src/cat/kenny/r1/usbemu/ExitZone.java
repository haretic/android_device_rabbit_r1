// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * One of the touchpad's two exit circles: it takes the fingers that land on it, so they don't
 * move the PC's pointer, tells whether it is held, and fills its ring as the hold goes on.
 */
final class ExitZone extends View {
    interface Listener { void held(); }

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint done = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF arc = new RectF();
    private final Listener listener;
    private boolean held;
    private float progress;

    ExitZone(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        float density = context.getResources().getDisplayMetrics().density;
        fill.setColor(0x33ffffff);
        ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2 * density); ring.setColor(0x80ffffff);
        done.setStyle(Paint.Style.STROKE); done.setStrokeWidth(4 * density);
        done.setStrokeCap(Paint.Cap.ROUND); done.setColor(0xffb4e7a5);
        setFocusable(false);
    }

    boolean held() { return held; }

    void progress(float p) { progress = p; invalidate(); }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                set(true);
                return true;
            case MotionEvent.ACTION_UP: case MotionEvent.ACTION_CANCEL:
                set(false);
                performClick();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void set(boolean down) {
        if (held == down) return;
        held = down;
        fill.setColor(down ? 0x66ffffff : 0x33ffffff);
        invalidate();
        listener.held();
    }

    @Override protected void onDraw(Canvas c) {
        float inset = done.getStrokeWidth();
        arc.set(inset, inset, getWidth() - inset, getHeight() - inset);
        c.drawOval(arc, fill);
        c.drawOval(arc, ring);
        if (progress > 0) c.drawArc(arc, -90, 360 * progress, false, done);
    }
}
