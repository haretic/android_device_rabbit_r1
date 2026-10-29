// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.problemreport;

import android.content.Intent;
import android.os.Bundle;

import androidx.fragment.app.FragmentManager;

import cat.kenny.r1.wheel.WheelSelect;

import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity;

/** Developer options and the shell runner both open this page. */
public class SelfTestActivity extends CollapsingToolbarBaseActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WheelSelect.attach(this);
        if (savedInstanceState == null) showRun(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        getSupportFragmentManager().popBackStackImmediate(null,
                FragmentManager.POP_BACK_STACK_INCLUSIVE);
        showRun(intent);
    }

    private void showRun(Intent intent) {
        String token = SelfTest.token(intent.getStringExtra(SelfTest.EXTRA_TOKEN));
        if (token.isEmpty()) token = SelfTest.current(this);
        final Bundle args = new Bundle();
        args.putString(SelfTest.EXTRA_TOKEN, token);
        final SelfTestFragment fragment = new SelfTestFragment();
        fragment.setArguments(args);
        getSupportFragmentManager().beginTransaction()
                .replace(com.android.settingslib.collapsingtoolbar.R.id.content_frame, fragment)
                .commit();
    }
}
