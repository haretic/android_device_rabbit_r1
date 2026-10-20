package cat.kenny.r1.perfoverlay;

import android.os.Bundle;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Settings > Performance overlay, a Settings page like the others: the wheel works here. */
public class OverlayActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame,
                            new OverlayFragment())
                    .commit();
        }
    }
}
