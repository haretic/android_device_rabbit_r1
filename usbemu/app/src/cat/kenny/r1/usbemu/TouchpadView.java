// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/**
 * The whole screen as the PC's touchpad: one finger moves the pointer, a tap clicks, a tap and
 * a drag holds the button down, two fingers scroll, a two-finger tap right-clicks and a
 * three-finger tap middle-clicks.
 */
final class TouchpadView extends View {
    interface Pointer { void mouse(int buttons, int x, int y, int wheel); }

    private static final int TAP_MS = 220;
    // Room for a second tap that starts a drag
    private static final int DRAG_WAIT_MS = 200;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Pointer pointer;
    private final float density, slop;
    private float lastX, lastY, startX, startY, scrollRest;
    private long downAt;
    private int fingers, buttons;
    private boolean moved, dragging, tapPending;
    private final Runnable click = () -> { tapPending = false; tap(1); };

    TouchpadView(Context context, Pointer pointer) {
        super(context);
        this.pointer = pointer;
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setFocusable(false);
    }

    private void tap(int button) { pointer.mouse(button, 0, 0, 0); pointer.mouse(0, 0, 0, 0); }

    // Slow strokes move finely; fast ones cross the PC's screen without lifting off
    private int accelerated(float d) {
        float speed = Math.abs(d) / density;
        return Math.round(d * Math.min(1.3f + speed * 0.22f, 4f) / density * 1.6f);
    }

    private float centroid(MotionEvent e, boolean y) {
        float sum = 0;
        for (int i = 0; i < e.getPointerCount(); i++) sum += y ? e.getY(i) : e.getX(i);
        return sum / e.getPointerCount();
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                getParent().requestDisallowInterceptTouchEvent(true);
                downAt = e.getEventTime(); fingers = 1; moved = false; scrollRest = 0;
                startX = lastX = e.getX(); startY = lastY = e.getY();
                // A tap waiting for its click turns into a held button
                if (tapPending) { handler.removeCallbacks(click); tapPending = false; dragging = true; buttons = 1; pointer.mouse(buttons, 0, 0, 0); }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
                fingers = Math.max(fingers, e.getPointerCount());
                lastX = centroid(e, false); lastY = centroid(e, true);
                return true;
            case MotionEvent.ACTION_POINTER_UP: {
                // The fingers left keep going on from where they are
                int gone = e.getActionIndex();
                float x = 0, y = 0;
                for (int i = 0; i < e.getPointerCount(); i++) if (i != gone) { x += e.getX(i); y += e.getY(i); }
                lastX = x / (e.getPointerCount() - 1); lastY = y / (e.getPointerCount() - 1);
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                float x = centroid(e, false), y = centroid(e, true);
                if (Math.hypot(e.getX() - startX, e.getY() - startY) > slop) moved = true;
                if (e.getPointerCount() >= 2) {
                    // Natural scrolling: the page follows the fingers, a notch per 14 dp
                    scrollRest += (y - lastY) / (14 * density);
                    int notches = (int) scrollRest;
                    if (notches != 0) { pointer.mouse(buttons, 0, 0, notches); scrollRest -= notches; moved = true; }
                    lastX = x; lastY = y;
                } else if (moved) {
                    int dx = accelerated(x - lastX), dy = accelerated(y - lastY);
                    if (dx != 0 || dy != 0) {
                        pointer.mouse(buttons, dx, dy, 0);
                        // Keep what a step rounded off for the next one
                        lastX += dx == 0 ? 0 : x - lastX; lastY += dy == 0 ? 0 : y - lastY;
                    }
                } else { lastX = x; lastY = y; }
                return true;
            }
            case MotionEvent.ACTION_UP: {
                boolean quick = e.getEventTime() - downAt < TAP_MS;
                if (dragging) { dragging = false; buttons = 0; pointer.mouse(0, 0, 0, 0); }
                else if (!moved && quick && fingers == 1) { tapPending = true; handler.postDelayed(click, DRAG_WAIT_MS); }
                else if (!moved && quick && fingers == 2) tap(2);
                else if (!moved && quick && fingers >= 3) tap(4);
                performClick();
                return true;
            }
            case MotionEvent.ACTION_CANCEL:
                cancel();
                return true;
            default: return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    /** Lets go of a held button and drops a click still waiting. */
    void cancel() {
        handler.removeCallbacks(click); tapPending = false;
        if (dragging || buttons != 0) { dragging = false; buttons = 0; pointer.mouse(0, 0, 0, 0); }
    }

    @Override protected void onDetachedFromWindow() { cancel(); super.onDetachedFromWindow(); }
}
