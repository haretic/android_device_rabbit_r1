// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.os.Bundle;

import androidx.fragment.app.Fragment;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/**
 * The taskbar's settings: its last button, and Settings > System > Taskbar and wheel. The
 * pages under it (the buttons, the wheel in each app) open in place, back returns.
 */
public class WheelActivity extends CollapsingToolbarBaseActivity
        implements PreferenceFragmentCompat.OnPreferenceStartFragmentCallback {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        getSupportFragmentManager().addOnBackStackChangedListener(() -> {
            if (getSupportFragmentManager().getBackStackEntryCount() == 0) {
                setTitle(R.string.wheel_title);
            }
        });
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new WheelFragment())
                    .commit();
        }
    }

    @Override
    public boolean onPreferenceStartFragment(PreferenceFragmentCompat caller, Preference pref) {
        final Fragment page = getSupportFragmentManager().getFragmentFactory()
                .instantiate(getClassLoader(), pref.getFragment());
        page.setArguments(pref.getExtras());
        getSupportFragmentManager().beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, page)
                .addToBackStack(null)
                .commit();
        setTitle(pref.getTitle());
        return true;
    }
}
