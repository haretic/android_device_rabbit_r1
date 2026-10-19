// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.performance;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The CPU profiles and what was picked: the profile for all apps, the apps with their own,
 * and whether games hold the memory speed. Kept in device protected storage, the app runs
 * before the user unlocks.
 */
final class Profiles {

    static final String POWERSAVE = "powersave";
    static final String BALANCED = "balanced";
    static final String PERFORMANCE = "performance";
    /** In the order the page and the tile show them. */
    static final String[] ALL = {POWERSAVE, BALANCED, PERFORMANCE};

    private static final String PREFS = "performance";
    static final String KEY_PROFILE = "profile";
    static final String KEY_APP_PROFILES = "app_profiles";
    static final String KEY_GAME_DRAM = "game_dram";

    private Profiles() {
    }

    static SharedPreferences prefs(Context context) {
        return context.createDeviceProtectedStorageContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getProfile(Context context) {
        return valid(prefs(context).getString(KEY_PROFILE, BALANCED));
    }

    static void setProfile(Context context, String profile) {
        prefs(context).edit().putString(KEY_PROFILE, valid(profile)).apply();
    }

    /** The profile after {@code profile}, for the tile. */
    static String next(String profile) {
        for (int i = 0; i < ALL.length; i++) {
            if (ALL[i].equals(profile)) {
                return ALL[(i + 1) % ALL.length];
            }
        }
        return BALANCED;
    }

    static boolean isGameDramHeld(Context context) {
        return prefs(context).getBoolean(KEY_GAME_DRAM, true);
    }

    /** Package name to profile, stored as "package=profile" entries. */
    static Map<String, String> getAppProfiles(Context context) {
        final Map<String, String> profiles = new HashMap<>();
        for (String entry : prefs(context).getStringSet(KEY_APP_PROFILES, new HashSet<>())) {
            final int split = entry.lastIndexOf('=');
            if (split > 0) {
                profiles.put(entry.substring(0, split), valid(entry.substring(split + 1)));
            }
        }
        return profiles;
    }

    /** Gives {@code packageName} its own profile, or takes it away with null. */
    static void setAppProfile(Context context, String packageName, String profile) {
        final Map<String, String> profiles = getAppProfiles(context);
        if (profile == null) {
            profiles.remove(packageName);
        } else {
            profiles.put(packageName, valid(profile));
        }
        final Set<String> entries = new HashSet<>();
        for (Map.Entry<String, String> e : profiles.entrySet()) {
            entries.add(e.getKey() + "=" + e.getValue());
        }
        prefs(context).edit().putStringSet(KEY_APP_PROFILES, entries).apply();
    }

    static int label(String profile) {
        switch (profile) {
            case POWERSAVE:
                return R.string.profile_powersave;
            case PERFORMANCE:
                return R.string.profile_performance;
            default:
                return R.string.profile_balanced;
        }
    }

    /** The power HAL hint that makes the profile, null for the stock behaviour. */
    static String hint(String profile) {
        switch (profile) {
            case POWERSAVE:
                return "PROFILE_POWERSAVE";
            case PERFORMANCE:
                return "PROFILE_PERFORMANCE";
            default:
                return null;
        }
    }

    private static String valid(String profile) {
        for (String p : ALL) {
            if (p.equals(profile)) {
                return p;
            }
        }
        return BALANCED;
    }
}
