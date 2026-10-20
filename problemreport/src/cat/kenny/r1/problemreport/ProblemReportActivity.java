// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.os.Bundle;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Settings > System > Problem report: the wheel and side button work here and in its dialog. */
public class ProblemReportActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new ProblemReportFragment())
                    .commit();
        }
    }
}
