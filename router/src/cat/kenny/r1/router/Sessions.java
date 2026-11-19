// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.router;

import android.content.Context;
import android.net.TetheredClient;
import android.net.TetheringManager;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * When the r1's own network came on and when each device joined it, for as long as this app
 * runs. Only what was seen happening: a network that was on, or a device that was on it, before
 * the app first looked has no time, rather than a wrong one. Main thread only.
 */
final class Sessions {

    private static Sessions sOne;

    /** A device seen joining, by its hardware address: when */
    private final Map<String, Long> mJoined = new HashMap<>();
    /** Who is on it now, by hardware address */
    private final Set<String> mHere = new HashSet<>();
    /** The first report is how things already were, not a change */
    private boolean mSeenClients, mSeenInterfaces;
    private boolean mOn;
    /** When the network was seen coming on, 0 when not seen */
    private long mOnSince;

    /** Watching from the first time the page opens, for the life of the app. */
    static Sessions get(Context context) {
        if (sOne == null) {
            sOne = new Sessions();
            final Context app = context.getApplicationContext();
            app.getSystemService(TetheringManager.class).registerTetheringEventCallback(
                    app.getMainExecutor(), sOne.mCallback);
        }
        return sOne;
    }

    private final TetheringManager.TetheringEventCallback mCallback =
            new TetheringManager.TetheringEventCallback() {
        @Override
        public void onTetheredInterfacesChanged(List<String> interfaces) {
            boolean on = false;
            for (String name : interfaces) {
                // The hotspot's, not a cable's: as the page tells them
                on |= name.startsWith("ap") || name.startsWith("wlan")
                        || name.startsWith("swlan");
            }
            if (on && !mOn && mSeenInterfaces) {
                mOnSince = System.currentTimeMillis();
            }
            if (!on) {
                // Off: the next time on is a new session, with everyone joining anew
                mOnSince = 0;
                mJoined.clear();
                mHere.clear();
            }
            mOn = on;
            mSeenInterfaces = true;
        }

        @Override
        public void onClientsChanged(Collection<TetheredClient> clients) {
            final Set<String> now = new HashSet<>();
            for (TetheredClient client : clients) {
                if (client.getTetheringType() != TetheringManager.TETHERING_WIFI
                        || client.getMacAddress() == null) {
                    continue;
                }
                final String mac = client.getMacAddress().toString();
                now.add(mac);
                if (mSeenClients && !mHere.contains(mac)) {
                    mJoined.put(mac, System.currentTimeMillis());
                }
            }
            // One that left and comes back has joined again
            mJoined.keySet().retainAll(now);
            mHere.clear();
            mHere.addAll(now);
            mSeenClients = true;
        }
    };

    /** When the device joined, 0 when that was not seen. */
    long joined(TetheredClient client) {
        if (client.getMacAddress() == null) {
            return 0;
        }
        final Long at = mJoined.get(client.getMacAddress().toString());
        return at == null ? 0 : at;
    }

    /** When the r1's own network came on, 0 when that was not seen. */
    long onSince() {
        return mOn ? mOnSince : 0;
    }
}
