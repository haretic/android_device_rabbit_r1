// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceViewHolder;

import java.util.List;
import java.util.Objects;

/** The top of the page, as in the mockup: "78%", the status and the 24 hour graph. */
public class HeaderPreference extends Preference {

    static final long SPAN_MS = 24 * 3600 * 1000L;

    private CharSequence mPercent;
    private CharSequence mStatus;
    private List<History.Point> mPoints;
    private long mEnd;
    private boolean mGraphShown = true;

    public HeaderPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
        setLayoutResource(R.layout.battery_header);
        setSelectable(false);
    }

    void setText(CharSequence percent, CharSequence status) {
        if (!Objects.equals(percent, mPercent) || !Objects.equals(status, mStatus)) {
            mPercent = percent;
            mStatus = status;
            notifyChanged();
        }
    }

    /** The last 24 hours up to {@code end}, as History.load read them off the main thread. */
    void setPoints(List<History.Point> points, long end) {
        mPoints = points;
        mEnd = end;
        notifyChanged();
    }

    /** The graph and its labels, or the level and the status alone. */
    void setGraphShown(boolean shown) {
        if (shown != mGraphShown) {
            mGraphShown = shown;
            notifyChanged();
        }
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        ((TextView) holder.findViewById(R.id.percent)).setText(mPercent);
        ((TextView) holder.findViewById(R.id.status)).setText(mStatus);
        final int graph = mGraphShown ? View.VISIBLE : View.GONE;
        holder.findViewById(R.id.graph).setVisibility(graph);
        holder.findViewById(R.id.graph_labels).setVisibility(graph);
        holder.findViewById(R.id.graph_empty).setVisibility(View.GONE);
        if (mGraphShown && mPoints != null) {
            ((GraphView) holder.findViewById(R.id.graph))
                    .setPoints(mPoints, mEnd - SPAN_MS, mEnd);
            // One point is just a dot, say why.
            holder.findViewById(R.id.graph_empty).setVisibility(
                    mPoints.size() < 2 ? View.VISIBLE : View.GONE);
        }
    }
}
