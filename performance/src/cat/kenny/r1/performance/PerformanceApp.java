// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.app.Application;
import android.os.Process;
import android.os.UserHandle;

/** Persistent: starts following the app in front as soon as the system is up. */
public class PerformanceApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Only the system user's process holds the hints
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM
                && Process.myUid() == Process.SYSTEM_UID) {
            Controller.get(this).start();
        }
    }
}
