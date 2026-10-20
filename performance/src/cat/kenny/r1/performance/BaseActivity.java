// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** A Settings page: the wheel and side button work here and in its dialogs. */
abstract class BaseActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            createFragment())
                    .commit();
        }
    }

    abstract Fragment createFragment();
}
