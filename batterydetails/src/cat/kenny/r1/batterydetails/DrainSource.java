// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.os.BatteryStats.HistoryItem;
import android.os.BatteryStatsManager;
import android.os.BatteryUsageStats;
import android.os.BatteryUsageStatsQuery;
import android.util.Log;

import com.android.internal.os.BatteryStatsHistoryIterator;

/**
 * Reads Android's own battery history into a {@link DrainModel}. Nothing is recorded for this
 * page: the system keeps the history anyway, a week of it and more in its 4 MB, with the
 * screen, sleep, the charger, the app in front, wake locks and jobs. Reading it takes a
 * second or two, so off the main thread.
 */
final class DrainSource {

    private static final String TAG = "R1BatteryDetails";
    /** The longest period the page shows, and a day for the run that began before it. */
    static final long HISTORY_MS = 8 * 24 * 3600 * 1000L;

    private DrainSource() {
    }

    /** Null when the history can't be read. */
    static DrainModel load(Context context) {
        final DrainModel model = new DrainModel(System.currentTimeMillis());
        final BatteryUsageStatsQuery query = new BatteryUsageStatsQuery.Builder()
                .includeBatteryHistory()
                .setPreferredHistoryDurationMs(HISTORY_MS)
                .build();
        try (BatteryUsageStats stats = context.getSystemService(BatteryStatsManager.class)
                .getBatteryUsageStats(query);
                BatteryStatsHistoryIterator history = stats.iterateBatteryStatsHistory()) {
            // The history counts milliseconds of its own and says the date now and then
            boolean dated = false;
            long wall = 0;
            long base = 0;
            boolean first = true;
            int states = 0;
            while (history.hasNext()) {
                final HistoryItem item = history.next();
                if (item.cmd == HistoryItem.CMD_CURRENT_TIME
                        || item.cmd == HistoryItem.CMD_RESET) {
                    wall = item.currentTime;
                    base = item.time;
                    dated = true;
                }
                if (!dated) {
                    continue;
                }
                model.at(wall + item.time - base);
                if (item.cmd == HistoryItem.CMD_RESET || item.cmd == HistoryItem.CMD_START) {
                    model.reset();
                }
                if (item.cmd != HistoryItem.CMD_UPDATE) {
                    continue;
                }
                model.level(item.batteryLevel);
                final int changed = first ? ~0 : states ^ item.states;
                first = false;
                states = item.states;
                if ((changed & HistoryItem.STATE_BATTERY_PLUGGED_FLAG) != 0) {
                    model.plugged((states & HistoryItem.STATE_BATTERY_PLUGGED_FLAG) != 0);
                }
                if ((changed & HistoryItem.STATE_SCREEN_ON_FLAG) != 0) {
                    model.screen((states & HistoryItem.STATE_SCREEN_ON_FLAG) != 0);
                }
                if ((changed & HistoryItem.STATE_CPU_RUNNING_FLAG) != 0) {
                    model.running((states & HistoryItem.STATE_CPU_RUNNING_FLAG) != 0);
                }
                if ((changed & HistoryItem.STATE_WAKE_LOCK_FLAG) != 0
                        || item.wakelockTag != null) {
                    final boolean held = (states & HistoryItem.STATE_WAKE_LOCK_FLAG) != 0;
                    model.wakeLock(held,
                            item.wakelockTag != null ? item.wakelockTag.uid : DrainModel.UID_NONE,
                            item.wakelockTag != null ? item.wakelockTag.string : null);
                }
                if (item.eventCode != HistoryItem.EVENT_NONE && item.eventTag != null) {
                    final int event = item.eventCode & HistoryItem.EVENT_TYPE_MASK;
                    final boolean start = (item.eventCode & HistoryItem.EVENT_FLAG_START) != 0;
                    final boolean finish = (item.eventCode & HistoryItem.EVENT_FLAG_FINISH) != 0;
                    if (start != finish && item.eventTag.string != null) {
                        if (event == HistoryItem.EVENT_TOP) {
                            model.top(start, item.eventTag.uid, item.eventTag.string);
                        } else if (event == HistoryItem.EVENT_JOB) {
                            model.job(start, item.eventTag.uid, item.eventTag.string);
                        }
                    }
                }
            }
            // The history's last entry can be minutes old while nothing changes
            model.at(System.currentTimeMillis());
            return model;
        } catch (Exception e) {
            Log.w(TAG, "Couldn't read the battery history", e);
            return null;
        }
    }
}
