// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.radiosleep;

import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_ACTION;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_CLASS;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_KEY;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_KEYWORDS;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_SUMMARY_ON;
import static android.provider.SearchIndexablesContract.COLUMN_INDEX_RAW_TITLE;
import static android.provider.SearchIndexablesContract.INDEXABLES_RAW_COLUMNS;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.provider.SearchIndexablesProvider;

/**
 * Radios in sleep in Settings search. Settings indexes its injected entries by title and
 * summary only, and the summary spells Wi‑Fi with a non-breaking hyphen, so a search for
 * wifi, airplane or bluetooth never found it: the keywords here do.
 */
public class SearchProvider extends SearchIndexablesProvider {

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryXmlResources(String[] projection) {
        return null;
    }

    @Override
    public Cursor queryRawData(String[] projection) {
        final MatrixCursor cursor = new MatrixCursor(INDEXABLES_RAW_COLUMNS);
        final Context context = getContext();
        final Object[] row = new Object[INDEXABLES_RAW_COLUMNS.length];
        row[COLUMN_INDEX_RAW_KEY] = "r1_radio_sleep_search";
        row[COLUMN_INDEX_RAW_TITLE] = context.getString(R.string.app_name);
        row[COLUMN_INDEX_RAW_SUMMARY_ON] = context.getString(R.string.tile_summary);
        row[COLUMN_INDEX_RAW_KEYWORDS] = context.getString(R.string.search_keywords);
        row[COLUMN_INDEX_RAW_INTENT_ACTION] = Intent.ACTION_MAIN;
        row[COLUMN_INDEX_RAW_INTENT_TARGET_PACKAGE] = context.getPackageName();
        row[COLUMN_INDEX_RAW_INTENT_TARGET_CLASS] = RadioSleepActivity.class.getName();
        cursor.addRow(row);
        return cursor;
    }

    @Override
    public Cursor queryNonIndexableKeys(String[] projection) {
        return null;
    }
}
