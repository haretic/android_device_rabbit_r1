// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.widget.ScrollView;

/**
 * Who is on the channel, scrolling only once they no longer fit. Until then it leaves the
 * finger alone, so a slide, hold or tap over the list reaches the page's TouchWheel as it does
 * everywhere else on the page.
 */
public final class PeopleScroll extends ScrollView {

    public PeopleScroll(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    private boolean scrolls() {
        return canScrollVertically(1) || canScrollVertically(-1);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return scrolls() && super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        // Not taking the down is what passes the whole gesture on to the page
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN && !scrolls()) {
            return false;
        }
        return super.onTouchEvent(event);
    }
}
