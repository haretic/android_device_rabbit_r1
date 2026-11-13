// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Shows a row's summary in full. The settings theme stops summaries at ten lines, which cut the
 * tweaks' explanations off mid-sentence; these pages' rows explain themselves at length.
 */
final class LongSummaries implements RecyclerView.OnChildAttachStateChangeListener {

    private LongSummaries() {
    }

    /** For a page's list, once its view exists (onViewCreated). */
    static void apply(@NonNull RecyclerView list) {
        list.addOnChildAttachStateChangeListener(new LongSummaries());
    }

    @Override
    public void onChildViewAttachedToWindow(@NonNull View row) {
        final View summary = row.findViewById(android.R.id.summary);
        if (summary instanceof TextView text && text.getMaxLines() != Integer.MAX_VALUE) {
            text.setMaxLines(Integer.MAX_VALUE);
        }
    }

    @Override
    public void onChildViewDetachedFromWindow(@NonNull View row) {
    }
}
