// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.List;

/** The top of What drained me: the loss, its time, the time as a bar and the sentence. */
public class DrainHeaderPreference extends Preference {

    private CharSequence mLost;
    private CharSequence mWhen;
    private CharSequence mStory;
    private List<DrainModel.Span> mSpans;
    private long mFrom;
    private long mTo;
    private CharSequence mBarStart;
    private CharSequence mBarEnd;

    public DrainHeaderPreference(Context context) {
        super(context);
        setLayoutResource(R.layout.drain_header);
        setSelectable(false);
    }

    void setText(CharSequence lost, CharSequence when, CharSequence story) {
        mLost = lost;
        mWhen = when;
        mStory = story;
        notifyChanged();
    }

    /** Null spans: no bar, as on the page of one state. */
    void setBar(List<DrainModel.Span> spans, long from, long to, CharSequence start,
            CharSequence end) {
        mSpans = spans;
        mFrom = from;
        mTo = to;
        mBarStart = start;
        mBarEnd = end;
        notifyChanged();
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        text(holder, R.id.lost, mLost);
        text(holder, R.id.when, mWhen);
        text(holder, R.id.story, mStory);
        final DrainBarView bar = (DrainBarView) holder.findViewById(R.id.bar);
        final int visible = mSpans != null ? View.VISIBLE : View.GONE;
        bar.setVisibility(visible);
        holder.findViewById(R.id.bar_labels).setVisibility(visible);
        if (mSpans != null) {
            bar.setSpans(mSpans, mFrom, mTo);
            text(holder, R.id.bar_start, mBarStart);
            text(holder, R.id.bar_end, mBarEnd);
        }
    }

    private static void text(PreferenceViewHolder holder, int id, CharSequence text) {
        final TextView view = (TextView) holder.findViewById(id);
        view.setText(text);
        view.setVisibility(text == null || text.length() == 0 ? View.GONE : View.VISIBLE);
    }
}
