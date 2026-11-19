// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.os.Bundle;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Battery details: the wheel and side button work here and in its dialogs. */
public class BatteryDetailsActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        // The title on one line: the tall header took a third of the screen, and only a
        // finger's scroll folds it, never the wheel's
        if (getAppBarLayout() != null) {
            getAppBarLayout().setExpanded(false, false);
        }
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new BatteryDetailsFragment())
                    .commit();
        }
    }
}
