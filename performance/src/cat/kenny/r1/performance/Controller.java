// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.app.ActivityManager;
import android.app.ActivityTaskManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.SystemProperties;
import android.service.quicksettings.TileService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Decides which power HAL hints to hold and keeps sys.powerhal.held_hints up to date: the
 * profile of the app in front (its own, or the one for all apps), the memory speed hold
 * while a game is in front, and the parked big cores while the screen is off; or only the
 * lowest of everything with the extreme battery saver (ExtremeSaver). Lives in the
 * app's persistent process; everything runs on the main thread.
 */
final class Controller {

    private static final String TAG = "R1Performance";
    private static final String HELD_HINTS = "sys.powerhal.held_hints";
    /** Task changes come in bursts while an app starts, look once it has settled. */
    private static final long SETTLE_MS = 250;

    private static Controller sInstance;

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Runnable mCheckFront = this::checkFront;
    private String mFront;
    private boolean mFrontIsGame;
    private boolean mScreenOn = true;
    private String mHeld;

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            // On a binder thread
            mHandler.removeCallbacks(mCheckFront);
            mHandler.postDelayed(mCheckFront, SETTLE_MS);
        }

        @Override
        public void onTaskMovedToFront(ActivityManager.RunningTaskInfo taskInfo) {
            onTaskStackChanged();
        }
    };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            update();
        }
    };

    private final BroadcastReceiver mSaverReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_POWER_DISCONNECTED.equals(intent.getAction())) {
                ExtremeSaver.onUnplugged(mContext);
            } else {
                ExtremeSaver.onPowerSaveModeChanged(mContext);
            }
        }
    };

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefsListener =
            (prefs, key) -> update();

    private Controller(Context context) {
        mContext = context.getApplicationContext();
    }

    static synchronized Controller get(Context context) {
        if (sInstance == null) {
            sInstance = new Controller(context);
        }
        return sInstance;
    }

    void start() {
        final IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        final IntentFilter saver = new IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED);
        saver.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mContext.registerReceiver(mSaverReceiver, saver, Context.RECEIVER_NOT_EXPORTED);
        Profiles.prefs(mContext).registerOnSharedPreferenceChangeListener(mPrefsListener);
        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (RemoteException e) {
            Log.e(TAG, "Can't follow the app in front", e);
        }
        checkFront();
        ExtremeSaver.restore(mContext);
    }

    /** Recomputes the held hints, e.g. after a Kernel Tweaks change. */
    void update() {
        final List<String> hints = new ArrayList<>();
        if (ExtremeSaver.isOn(mContext)) {
            // Lowest everything, whatever the profile or the app
            hints.add("EXTREME_SAVER");
        } else {
            addProfileHints(hints);
        }
        final String held = String.join(",", hints);
        if (!held.equals(mHeld)) {
            mHeld = held;
            SystemProperties.set(HELD_HINTS, held);
            Log.i(TAG, "Holding [" + held + "] for " + mFront);
            TileService.requestListeningState(mContext,
                    new ComponentName(mContext, ProfileTileService.class));
        }
    }

    private void addProfileHints(List<String> hints) {
        final String profile = activeProfile();
        final String profileHint = Profiles.hint(profile);
        if (profileHint != null) {
            hints.add(profileHint);
        }
        if (mFrontIsGame && Profiles.isGameDramHeld(mContext)
                && !Profiles.POWERSAVE.equals(profile)) {
            hints.add("GAME_DRAM");
        }
        if (!mScreenOn && KernelTweaks.isActive()
                && "1".equals(KernelTweaks.get(KernelTweaks.PARK))) {
            hints.add("PARK_BIG");
        }
        // The overclock: boost is on for the cluster (r1-ktweaks.sh), the HAL holds the speed,
        // below any saver's cap
        final String big = Overclock.step(Overclock.BIG);
        if (big != null) {
            hints.add("OC_BIG_" + big);
        }
        final String little = Overclock.step(Overclock.LITTLE);
        if (little != null) {
            hints.add("OC_LITTLE_" + little);
        }
    }

    /** The profile in use now: the app in front's own, or the one for all apps. */
    String activeProfile() {
        final String own = mFront == null ? null : Profiles.getAppProfiles(mContext).get(mFront);
        return own != null ? own : Profiles.getProfile(mContext);
    }

    /** The app in front, null if it isn't known. */
    String front() {
        return mFront;
    }

    private void checkFront() {
        // The system user sees every task, not just its own
        final List<ActivityManager.RunningTaskInfo> tasks =
                mContext.getSystemService(ActivityManager.class).getRunningTasks(1);
        final ComponentName top = tasks == null || tasks.isEmpty() ? null : tasks.get(0).topActivity;
        final String front = top != null ? top.getPackageName() : null;
        if (!Objects.equals(front, mFront)) {
            Log.d(TAG, "In front: " + front);
            mFront = front;
            mFrontIsGame = isGame(front);
        }
        update();
    }

    private boolean isGame(String packageName) {
        if (packageName == null) {
            return false;
        }
        try {
            final ApplicationInfo info =
                    mContext.getPackageManager().getApplicationInfo(packageName, 0);
            return info.category == ApplicationInfo.CATEGORY_GAME
                    || (info.flags & ApplicationInfo.FLAG_IS_GAME) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
