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
            final Bundle args = new Bundle();
            args.putString(SelfTest.EXTRA_TOKEN,
                    SelfTest.token(getIntent().getStringExtra(SelfTest.EXTRA_TOKEN)));
            final ProblemReportFragment fragment = new ProblemReportFragment();
            fragment.setArguments(args);
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            fragment)
                    .commit();
        }
    }
}
