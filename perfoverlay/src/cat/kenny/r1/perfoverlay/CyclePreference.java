// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.perfoverlay;

import android.content.Context;
import android.util.AttributeSet;

import androidx.preference.ListPreference;

/**
 * A choice that moves on to its next value on a press of the side button or a tap, instead of
 * opening a list: one stop for the wheel, as on the role apps' settings pages. Short lists
 * only. Kept, summarised and listened to as a ListPreference.
 */
public class CyclePreference extends ListPreference {

    public CyclePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onClick() {
        final CharSequence[] values = getEntryValues();
        if (values == null || values.length == 0) {
            return;
        }
        final String next = values[(findIndexOfValue(getValue()) + 1) % values.length].toString();
        if (callChangeListener(next)) {
            setValue(next);
        }
    }
}
