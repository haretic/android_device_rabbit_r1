// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel;

import android.app.AlertDialog;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;

/**
 * The r1's wheel and side button in an app's window: the wheel (D-pad up and down)
 * moves the focus with a tick, a press of the side button works as the D-pad centre on what
 * has the focus, and holding it goes back, as it does everywhere with the wheel UI.
 * The window asks the system for the side button with INPUT_FEATURE_POWER_KEY_SELECTS,
 * which only system windows get.
 */
public final class WheelSelect implements InvocationHandler {

    private final Window mWindow;
    private final Window.Callback mCallback;
    /** A long press already went back, ignore the rest of it. */
    private boolean mSlept;

    private WheelSelect(Window window, Window.Callback callback) {
        mWindow = window;
        mCallback = callback;
    }

    /** The activity's window and the dialogs of its fragments, ListPreference's among them. */
    public static void attach(FragmentActivity activity) {
        attach(activity.getWindow());
        activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                new FragmentManager.FragmentLifecycleCallbacks() {
                    @Override
                    public void onFragmentStarted(@NonNull FragmentManager fm,
                            @NonNull Fragment f) {
                        if (f instanceof DialogFragment) {
                            attach(((DialogFragment) f).getDialog());
                        }
                    }
                }, true);
    }

    public static void attach(Dialog dialog) {
        if (dialog != null && dialog.getWindow() != null) {
            attach(dialog.getWindow());
        }
        // A list of choices draws into its padding (clipToPadding false), and the wheel scrolls
        // its rows up there, over the dialog's title: keep them below it
        if (dialog instanceof AlertDialog && ((AlertDialog) dialog).getListView() != null) {
            ((AlertDialog) dialog).getListView().setClipToPadding(true);
        }
    }

    public static void attach(Window window) {
        final Window.Callback callback = window.getCallback();
        if (callback == null || Proxy.isProxyClass(callback.getClass())) {
            return;
        }
        final WindowManager.LayoutParams lp = window.getAttributes();
        lp.inputFeatures |= WindowManager.LayoutParams.INPUT_FEATURE_POWER_KEY_SELECTS;
        window.setAttributes(lp);
        window.setCallback((Window.Callback) Proxy.newProxyInstance(
                Window.Callback.class.getClassLoader(), new Class<?>[] {Window.Callback.class},
                new WheelSelect(window, callback)));
        window.getDecorView().getViewTreeObserver().addOnGlobalFocusChangeListener(
                (oldFocus, newFocus) -> {
                    // Only the wheel, not a tap that also focuses
                    if (newFocus != null && !newFocus.isInTouchMode()) {
                        newFocus.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                    }
                });
    }

    @Override
    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args)
            throws Throwable {
        if ("dispatchKeyEvent".equals(method.getName())
                && ((KeyEvent) args[0]).getKeyCode() == KeyEvent.KEYCODE_POWER) {
            onPowerKey((KeyEvent) args[0]);
            return true;
        }
        try {
            return method.invoke(mCallback, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private void onPowerKey(KeyEvent event) {
        // A hold is told by how long the button is down: the side button's events reach the
        // window without FLAG_LONG_PRESS, and maybe without repeats
        final boolean held = event.getEventTime() - event.getDownTime()
                >= ViewConfiguration.getLongPressTimeout();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) {
                mSlept = false;
            } else if (held && !mSlept) {
                goBack();
            }
            return;
        }
        if (mSlept || event.isCanceled()) {
            return;
        }
        if (held) {
            goBack();
            return;
        }
        final View decor = mWindow.getDecorView();
        final View focused = decor.findFocus();
        if (focused == null || focused.isInTouchMode()) {
            // Nothing picked yet: show where the wheel starts, the first item
            decor.requestFocusFromTouch();
            return;
        }
        focused.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
        // As the D-pad centre: views click, lists pick their selected row, switches toggle
        final long now = SystemClock.uptimeMillis();
        mCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_DPAD_CENTER, 0));
        mCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_DPAD_CENTER, 0));
    }

    /** A hold goes back, as everywhere with the wheel UI */
    private void goBack() {
        mSlept = true;
        final long now = SystemClock.uptimeMillis();
        mCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_BACK, 0));
        mCallback.dispatchKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_BACK, 0));
    }
}
