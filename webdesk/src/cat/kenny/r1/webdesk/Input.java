// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk;

import android.content.Context;
import android.graphics.Point;
import android.hardware.input.InputManager;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * What the computer does with its mouse and keyboard, done on the r1: a finger on the screen,
 * the wheel, the side button, and typing into whatever text field is open.
 */
final class Input {

    private final Context mContext;
    private final InputManager mInput;
    private final KeyCharacterMap mKeys = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD);
    /** The wheel's steps take their time in the wheel's daemon: one after the other, off here */
    private final ThreadPoolExecutor mWheel = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
            new ThreadPoolExecutor.DiscardPolicy());
    private volatile long mGeneration;
    private LocalSocket mSocket;
    private long mDown;
    private boolean mTouching;

    Input(Context context) {
        mContext = context;
        mInput = context.getSystemService(InputManager.class);
    }

    /** Does the event; returns what it was for the r1's "what the computer is doing" list. */
    synchronized String handle(Map<String, Object> event, BooleanSupplier permission) {
        final long generation = mGeneration;
        final BooleanSupplier allowed = () -> generation == mGeneration
                && permission.getAsBoolean();
        if (!allowed.getAsBoolean()) {
            return null;
        }
        final Object type = event.get("t");
        if (!(type instanceof String t)) {
            return null;
        }
        wake(allowed);
        switch (t) {
            case "down":
            case "move":
            case "up":
                touch(t, number(event.get("x")), number(event.get("y")), allowed);
                return "screen";
            case "wheel": {
                final int steps = (int) Math.max(-8, Math.min(8, number(event.get("d"))));
                if (steps != 0) {
                    mWheel.execute(() -> wheel(steps > 0 ? "down" : "up",
                            Math.abs(steps), allowed));
                }
                return "screen";
            }
            case "press":
                mWheel.execute(() -> side("press", allowed));
                return "screen";
            case "hold":
                mWheel.execute(() -> side("hold", allowed));
                return "screen";
            case "text":
                if (event.get("s") instanceof String s && s.length() <= 4096) {
                    type(s, allowed);
                }
                return "typing";
            case "key":
                if (event.get("k") instanceof String k) {
                    final int code = keyCode(k);
                    if (code != 0) {
                        key(code, allowed);
                    }
                }
                return "typing";
            default:
                return null;
        }
    }

    private static float number(Object value) {
        final float number = value instanceof Number n ? n.floatValue() : 0;
        return Float.isFinite(number) ? number : 0;
    }

    private void wake(BooleanSupplier allowed) {
        if (!mContext.getSystemService(PowerManager.class).isInteractive()) {
            key(KeyEvent.KEYCODE_WAKEUP, allowed);
        }
    }

    /** x and y as fractions of the picture, which is the screen the way it is turned now. */
    private void touch(String what, float x, float y, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean()) {
            return;
        }
        final Point size = new Point();
        mContext.getDisplay().getRealSize(size);
        final float px = Math.max(0, Math.min(1, x)) * (size.x - 1);
        final float py = Math.max(0, Math.min(1, y)) * (size.y - 1);
        final long now = SystemClock.uptimeMillis();
        final int action;
        if (what.equals("down")) {
            if (mTouching) {
                return;
            }
            mTouching = true;
            mDown = now;
            action = MotionEvent.ACTION_DOWN;
        } else if (!mTouching) {
            return;
        } else if (what.equals("up")) {
            mTouching = false;
            action = MotionEvent.ACTION_UP;
        } else {
            action = MotionEvent.ACTION_MOVE;
        }
        final MotionEvent event = MotionEvent.obtain(mDown, now, action, px, py, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        mInput.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        event.recycle();
    }

    private void key(int code, BooleanSupplier allowed) {
        if (!allowed.getAsBoolean()) {
            return;
        }
        final long now = SystemClock.uptimeMillis();
        for (int action : new int[] {KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP}) {
            mInput.injectInputEvent(new KeyEvent(now, now, action, code, 0, 0,
                    KeyCharacterMap.VIRTUAL_KEYBOARD, 0, KeyEvent.FLAG_FROM_SYSTEM,
                    InputDevice.SOURCE_KEYBOARD), InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
        }
    }

    private void type(String text, BooleanSupplier allowed) {
        final KeyEvent[] events = mKeys.getEvents(text.toCharArray());
        if (events != null) {
            for (KeyEvent event : events) {
                if (!allowed.getAsBoolean()) {
                    return;
                }
                mInput.injectInputEvent(KeyEvent.changeFlags(event,
                        event.getFlags() | KeyEvent.FLAG_FROM_SYSTEM),
                        InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            }
            return;
        }
        // Letters no keyboard layout has keys for go in as the text they are
        if (!allowed.getAsBoolean()) {
            return;
        }
        mInput.injectInputEvent(new KeyEvent(SystemClock.uptimeMillis(), text,
                KeyCharacterMap.VIRTUAL_KEYBOARD, 0), InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
    }

    private static int keyCode(String key) {
        switch (key) {
            case "Enter": return KeyEvent.KEYCODE_ENTER;
            case "Backspace": return KeyEvent.KEYCODE_DEL;
            case "Delete": return KeyEvent.KEYCODE_FORWARD_DEL;
            case "Tab": return KeyEvent.KEYCODE_TAB;
            case "Escape": return KeyEvent.KEYCODE_BACK;
            case "ArrowLeft": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "ArrowRight": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ArrowUp": return KeyEvent.KEYCODE_DPAD_UP;
            case "ArrowDown": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "Home": return KeyEvent.KEYCODE_MOVE_HOME;
            case "End": return KeyEvent.KEYCODE_MOVE_END;
            case "GoHome": return KeyEvent.KEYCODE_HOME;
            default: return 0;
        }
    }

    /** A turn of the wheel the way the wheel's own goes: through its daemon, in the app's mode. */
    private void wheel(String way, int steps, BooleanSupplier allowed) {
        if (!daemon("inject " + way + " " + steps, allowed)) {
            // No daemon to ask: the steps as the keys an app in the wheel's "app" mode gets
            for (int i = 0; i < steps; i++) {
                key(way.equals("down") ? KeyEvent.KEYCODE_DPAD_DOWN : KeyEvent.KEYCODE_DPAD_UP,
                        allowed);
            }
        }
    }

    private void side(String how, BooleanSupplier allowed) {
        if (!daemon("inject side " + how, allowed)) {
            key(KeyEvent.KEYCODE_DPAD_CENTER, allowed);
        }
    }

    private boolean daemon(String line, BooleanSupplier allowed) {
        try (LocalSocket socket = new LocalSocket()) {
            synchronized (this) {
                if (!allowed.getAsBoolean()) {
                    return false;
                }
                mSocket = socket;
            }
            socket.connect(new LocalSocketAddress("r1-wheel",
                    LocalSocketAddress.Namespace.RESERVED));
            socket.setSoTimeout(5000);
            if (!allowed.getAsBoolean()) {
                return false;
            }
            socket.getOutputStream().write((line + "\n").getBytes(StandardCharsets.US_ASCII));
            // The daemon also tells its clients of the wheel's turns: the answer is one line
            // among those
            final java.io.BufferedReader lines = new java.io.BufferedReader(
                    new java.io.InputStreamReader(socket.getInputStream(),
                            StandardCharsets.US_ASCII));
            for (String answer; (answer = lines.readLine()) != null; ) {
                if (answer.equals("injected")) {
                    return true;
                }
            }
            return false;
        } catch (IOException e) {
            return false;
        } finally {
            synchronized (this) {
                mSocket = null;
            }
        }
    }

    synchronized void cancel() {
        mGeneration++;
        mWheel.getQueue().clear();
        if (mSocket != null) {
            try {
                mSocket.close();
            } catch (IOException e) {
                // Closing also cancels a daemon command waiting for its reply.
            }
        }
        if (mTouching) {
            mTouching = false;
            final MotionEvent event = MotionEvent.obtain(mDown, SystemClock.uptimeMillis(),
                    MotionEvent.ACTION_CANCEL, 0, 0, 0);
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            mInput.injectInputEvent(event, InputManager.INJECT_INPUT_EVENT_MODE_ASYNC);
            event.recycle();
        }
    }

    void close() {
        cancel();
        mWheel.shutdownNow();
    }
}
