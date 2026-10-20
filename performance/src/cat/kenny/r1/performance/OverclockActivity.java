// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import androidx.fragment.app.Fragment;

public class OverclockActivity extends BaseActivity {

    @Override
    Fragment createFragment() {
        return new OverclockFragment();
    }
}
