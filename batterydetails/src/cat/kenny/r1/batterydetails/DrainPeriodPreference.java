// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.content.res.TypedArray;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

/**
 * Since the charger, 24 hours or 7 days. One row, so the wheel has one stop for it: a press
 * moves on to the next period. A finger picks one directly.
 */
public class DrainPeriodPreference extends Preference {

    interface Listener {
        void onPeriod(int period);
    }

    static final int PERIODS = 3;
    private static final int[] IDS = {R.id.period_0, R.id.period_1, R.id.period_2};

    private int mPeriod;
    private Listener mListener;

    public DrainPeriodPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.drain_period);
    }

    void setPeriod(int period, Listener listener) {
        mPeriod = period;
        mListener = listener;
        notifyChanged();
    }

    @Override
    protected void onClick() {
        pick((mPeriod + 1) % PERIODS);
    }

    private void pick(int period) {
        if (period != mPeriod && mListener != null) {
            mPeriod = period;
            notifyChanged();
            mListener.onPeriod(period);
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        for (int i = 0; i < PERIODS; i++) {
            final TextView view = (TextView) holder.findViewById(IDS[i]);
            final int period = i;
            if (i == mPeriod) {
                view.setBackgroundResource(R.drawable.drain_period_selected);
                view.setTextColor(getContext().getColor(R.color.drain_on_accent));
            } else {
                view.setBackground(null);
                final TypedArray a = getContext().obtainStyledAttributes(
                        new int[] {android.R.attr.textColorPrimary});
                view.setTextColor(a.getColorStateList(0));
                a.recycle();
            }
            view.setOnClickListener(v -> pick(period));
        }
    }
}
