// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

/** A tool that is one big reading (tool.xml), redrawn while it is on screen. */
abstract class OneReading extends ToolActivity {

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    protected TextView mNumber;
    protected TextView mUnit;
    protected TextView mLine;
    protected TextView mMore;

    private final Runnable mShow = new Runnable() {
        @Override
        public void run() {
            show();
            mHandler.postDelayed(this, period());
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.tool);
        mNumber = findViewById(R.id.number);
        mUnit = findViewById(R.id.unit);
        mLine = findViewById(R.id.line);
        mMore = findViewById(R.id.more);
        // The wheel's two ends, tapped, are a click each way
        turnKey(R.id.end_up, -1, repeats());
        turnKey(R.id.end_down, 1, repeats());
    }

    /** Whether a finger kept on an end goes on clicking, as for a value to run up. */
    protected boolean repeats() {
        return true;
    }

    protected final void title(int title, String up, String down) {
        ((TextView) findViewById(R.id.title)).setText(title);
        ((TextView) findViewById(R.id.end_up)).setText(up);
        ((TextView) findViewById(R.id.end_down)).setText(down);
    }

    protected final void stage(View view) {
        ((ViewGroup) findViewById(R.id.stage)).addView(view);
    }

    /** Milliseconds between two show() while on screen. */
    protected long period() {
        return 50;
    }

    protected abstract void show();

    @Override
    protected void onResume() {
        super.onResume();
        mHandler.post(mShow);
    }

    @Override
    protected void onPause() {
        mHandler.removeCallbacks(mShow);
        super.onPause();
    }

    @Override
    protected boolean usesWheel() {
        return true;
    }
}
