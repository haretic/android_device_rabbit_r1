// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import androidx.fragment.app.Fragment;

public class AppProfilesActivity extends BaseActivity {

    @Override
    Fragment createFragment() {
        return new AppProfilesFragment();
    }
}
