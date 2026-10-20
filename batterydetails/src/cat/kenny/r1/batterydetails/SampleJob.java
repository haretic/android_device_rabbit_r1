// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Adds a point to the graph about every 15 minutes while the r1 is awake. It never wakes
 * the r1: a sleeping r1 just leaves a gap the graph bridges. That is why this is a one-shot
 * job with only a minimum delay, scheduled again after each run. A periodic job gets a
 * deadline, and JobScheduler wakes the device from sleep for every deadline
 * (ELAPSED_REALTIME_WAKEUP in TimeController); a delay alone is a non-wakeup alarm.
 */
public class SampleJob extends JobService {

    // Job IDs are per UID, and every android.uid.system app shares one (Settings,
    // LineageParts, the other r1 apps), so this one is "R1BD" in ASCII.
    private static final int JOB_ID = 0x52314244;
    private static final long PERIOD_MS = 15 * 60 * 1000L;

    /** Makes sure a sample is pending. */
    static void schedule(Context context) {
        final JobInfo pending = context.getSystemService(JobScheduler.class)
                .getPendingJob(JOB_ID);
        if (pending == null || pending.isPeriodic()) {
            scheduleNext(context);
        }
    }

    private static void scheduleNext(Context context) {
        context.getSystemService(JobScheduler.class).schedule(
                new JobInfo.Builder(JOB_ID, new ComponentName(context, SampleJob.class))
                        .setMinimumLatency(PERIOD_MS)
                        .setPersisted(true)
                        .build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // A sticky broadcast, one HAL property and an appended line: fine on the main thread
        History.record(this, Reading.battery(this));
        // Scheduling the same ID replaces this job, so do it once this run has returned.
        final Context app = getApplicationContext();
        new Handler(Looper.getMainLooper()).post(() -> scheduleNext(app));
        return false;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        return false;
    }
}
