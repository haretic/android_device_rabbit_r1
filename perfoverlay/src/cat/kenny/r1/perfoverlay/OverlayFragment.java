package cat.kenny.r1.perfoverlay;

import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceFragment;
import android.preference.SwitchPreference;

public class OverlayFragment extends PreferenceFragment
        implements Preference.OnPreferenceChangeListener {

    private SwitchPreference mEnabled;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        addPreferencesFromResource(R.xml.overlay_settings);
        mEnabled = (SwitchPreference) findPreference(Hud.KEY_ENABLED);
        mEnabled.setOnPreferenceChangeListener(this);
    }

    @Override
    public void onResume() {
        super.onResume();
        // The tile or the notification may have switched it in the meantime.
        mEnabled.setChecked(Hud.isEnabled(getActivity()));
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object newValue) {
        // The preference saves the value itself, this only starts or stops the overlay.
        Hud.apply(getActivity(), (Boolean) newValue);
        return true;
    }
}
