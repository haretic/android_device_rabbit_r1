// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.batterydetails;

import android.os.Bundle;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/**
 * What drained me, and with {@link DrainFragment#EXTRA_STATE} the page of one of its three
 * states: who was running in it.
 */
public class DrainActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        final int state = getIntent().getIntExtra(DrainFragment.EXTRA_STATE, -1);
        if (state >= 0) {
            setTitle(DrainFragment.STATE_TITLES[state]);
        }
        if (savedInstanceState == null) {
            final DrainFragment fragment = new DrainFragment();
            fragment.setArguments(getIntent().getExtras());
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            fragment)
                    .commit();
        }
    }
}
