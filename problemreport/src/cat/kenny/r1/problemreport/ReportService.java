// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;

/** Makes the report (Report.run) in its own thread, so it keeps going when the page is left. */
public class ReportService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        new Thread(() -> {
            Report.run(getApplicationContext());
            stopSelf(startId);
        }, "ProblemReport").start();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
