// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.radiosleep;

import android.os.Bundle;
import android.os.UserManager;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Settings > Battery > Radios in sleep, a Settings page like the others: the wheel works here. */
public final class RadioSleepActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (!getSystemService(UserManager.class).isSystemUser()) {
            finish();
            return;
        }
        WheelSelect.attach(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new RadioSleepFragment())
                    .commit();
        }
    }
}
