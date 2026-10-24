package cat.kenny.r1.perfoverlay;

import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.content.Context;
import android.os.RemoteException;
import android.util.Log;
import android.view.WindowManager;
import android.window.TaskFpsCallback;

/**
 * The frame rate of the app in front as SurfaceFlinger counts it for the app's task, every frame
 * of each of its layers (WindowManager.registerTaskFpsCallback, ACCESS_FPS_COUNTER). A game's
 * frames are counted wherever it draws them, a SurfaceView included, and an app that draws
 * nothing reads 0.
 */
final class TaskFps {

    private static final String TAG = "PerfOverlay";

    private final WindowManager mWindowManager;
    private final TaskFpsCallback mCallback = new TaskFpsCallback() {
        @Override
        public void onFpsReported(float fps) {
            mFps = fps;
        }
    };
    /** The root task measured, -1 for none. */
    private int mTaskId = -1;
    private volatile float mFps = -1;
    /** The window manager refused the callback, as without the permission. */
    private boolean mUnavailable;

    TaskFps(Context context) {
        mWindowManager = context.getSystemService(WindowManager.class);
    }

    /** The front app's frame rate, -1 before its first report or where it can't be had. */
    int sample() {
        if (mUnavailable) {
            return -1;
        }
        final int task = frontRootTask();
        if (task != mTaskId) {
            stop();
            if (task >= 0) {
                try {
                    mWindowManager.registerTaskFpsCallback(task, Runnable::run, mCallback);
                    mTaskId = task;
                } catch (RuntimeException e) {
                    Log.w(TAG, "No task frame rates", e);
                    mUnavailable = true;
                }
            }
        }
        final float fps = mFps;
        return mTaskId < 0 || fps < 0 ? -1 : Math.round(fps);
    }

    /** Stop counting, as while the screen is off; the next sample starts again. */
    void stop() {
        if (mTaskId >= 0) {
            mWindowManager.unregisterTaskFpsCallback(mCallback);
        }
        mTaskId = -1;
        mFps = -1;
    }

    private static int frontRootTask() {
        try {
            final RootTaskInfo info = ActivityTaskManager.getService().getFocusedRootTaskInfo();
            return info == null ? -1 : info.taskId;
        } catch (RemoteException e) {
            return -1;
        }
    }
}
