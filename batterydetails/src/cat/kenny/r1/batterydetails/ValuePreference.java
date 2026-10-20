// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.Objects;

/** A reading in Right now: its name, and the value at the end of the row. */
public class ValuePreference extends Preference {

    private CharSequence mValue;

    public ValuePreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setWidgetLayoutResource(R.layout.value_widget);
        setSelectable(false);
    }

    void setValue(CharSequence value) {
        if (!Objects.equals(value, mValue)) {
            mValue = value;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ((TextView) holder.findViewById(R.id.value)).setText(mValue);
    }
}
