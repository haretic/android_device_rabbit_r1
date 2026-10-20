package cat.kenny.r1.perfoverlay;

import android.os.Bundle;

import com.android.settingslib.widget.MainSwitchPreference;
import com.android.settingslib.widget.SettingsBasePreferenceFragment;

public class OverlayFragment extends SettingsBasePreferenceFragment {

    private MainSwitchPreference mEnabled;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.overlay_settings, rootKey);
        mEnabled = findPreference(Hud.KEY_ENABLED);
        mEnabled.setOnPreferenceChangeListener((preference, value) -> {
            // Hud saves the choice and starts or stops the overlay
            Hud.setEnabled(requireContext(), (Boolean) value);
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile or the notification may have switched it in the meantime.
        mEnabled.setChecked(Hud.isEnabled(requireContext()));
    }
}
