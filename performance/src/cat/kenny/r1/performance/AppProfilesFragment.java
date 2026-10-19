// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Bundle;
import android.widget.TextView;

import androidx.preference.Preference;
import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceViewHolder;

import com.android.settingslib.widget.SettingsBasePreferenceFragment;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Settings > Performance > Per-app profiles: the apps with their own profile. The side button
 * on an app picks its profile or removes it.
 */
public class AppProfilesFragment extends SettingsBasePreferenceFragment {

    private PreferenceCategory mApps;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.app_profiles, rootKey);
        mApps = findPreference("apps");
        findPreference("add").setOnPreferenceClickListener(p -> {
            pickApp();
            return true;
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        update();
    }

    static String label(Context context, String packageName) {
        final PackageManager pm = context.getPackageManager();
        try {
            return pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /** The entries sorted by app name. */
    static List<Map.Entry<String, String>> sorted(Context context, Map<String, String> apps) {
        final Collator collator = Collator.getInstance();
        final List<Map.Entry<String, String>> entries = new ArrayList<>(apps.entrySet());
        entries.sort((a, b) -> collator.compare(label(context, a.getKey()),
                label(context, b.getKey())));
        return entries;
    }

    private void update() {
        final Context context = requireContext();
        final PackageManager pm = context.getPackageManager();
        for (int i = mApps.getPreferenceCount() - 1; i >= 0; i--) {
            final Preference p = mApps.getPreference(i);
            if (!"add".equals(p.getKey())) {
                mApps.removePreference(p);
            }
        }
        int order = 0;
        for (Map.Entry<String, String> entry : sorted(context, Profiles.getAppProfiles(context))) {
            final String packageName = entry.getKey();
            final ProfilePreference p = new ProfilePreference(context);
            p.setKey(packageName);
            p.setOrder(order++);
            p.setTitle(label(context, packageName));
            p.mProfile = getString(Profiles.label(entry.getValue()));
            try {
                final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
                p.setIcon(info.loadIcon(pm));
                final CharSequence category = ApplicationInfo.getCategoryTitle(context,
                        info.category);
                if (category != null) {
                    p.setSummary(category);
                }
            } catch (PackageManager.NameNotFoundException e) {
                // Uninstalled since; still listed so it can be removed
            }
            p.setOnPreferenceClickListener(pref -> {
                pickProfile(packageName, entry.getValue());
                return true;
            });
            mApps.addPreference(p);
        }
    }

    private void pickProfile(String packageName, String current) {
        final Context context = requireContext();
        final String[] items = new String[Profiles.ALL.length + 1];
        int checked = -1;
        for (int i = 0; i < Profiles.ALL.length; i++) {
            items[i] = getString(Profiles.label(Profiles.ALL[i]));
            if (Profiles.ALL[i].equals(current)) {
                checked = i;
            }
        }
        items[Profiles.ALL.length] = getString(R.string.app_profiles_remove);
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(label(context, packageName))
                .setSingleChoiceItems(items, checked, (d, which) -> {
                    Profiles.setAppProfile(context, packageName,
                            which < Profiles.ALL.length ? Profiles.ALL[which] : null);
                    d.dismiss();
                    update();
                })
                .create();
        dialog.show();
        WheelSelect.attach(dialog);
    }

    private void pickApp() {
        final Context context = requireContext();
        final PackageManager pm = context.getPackageManager();
        final Map<String, String> profiles = Profiles.getAppProfiles(context);
        final List<ResolveInfo> apps = new ArrayList<>();
        for (ResolveInfo info : pm.queryIntentActivities(
                new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
            final String packageName = info.activityInfo.packageName;
            if (!profiles.containsKey(packageName) && apps.stream().noneMatch(
                    a -> a.activityInfo.packageName.equals(packageName))) {
                apps.add(info);
            }
        }
        final Collator collator = Collator.getInstance();
        apps.sort(Comparator.comparing(info -> info.loadLabel(pm).toString(), collator));
        final String[] labels = new String[apps.size()];
        for (int i = 0; i < labels.length; i++) {
            labels[i] = apps.get(i).loadLabel(pm).toString();
        }
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.app_profiles_pick_app)
                .setItems(labels, (d, which) ->
                        pickProfile(apps.get(which).activityInfo.packageName, null))
                .create();
        dialog.show();
        WheelSelect.attach(dialog);
    }

    /** An app with its profile on the right, like the mockup. */
    static class ProfilePreference extends Preference {
        String mProfile;

        ProfilePreference(Context context) {
            super(context);
            setWidgetLayoutResource(R.layout.profile_widget);
        }

        @Override
        public void onBindViewHolder(PreferenceViewHolder holder) {
            super.onBindViewHolder(holder);
            ((TextView) holder.findViewById(R.id.profile)).setText(mProfile);
        }
    }
}
