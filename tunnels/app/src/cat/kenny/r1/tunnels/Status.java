// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

/**
 * How the tunnel is, as TunnelService last saw it, for the pages to show: they read it every
 * two seconds while they are on screen, and while one is, the service asks the helper as
 * often. The app is one process, so a holder is all it takes.
 */
final class Status {
    static final int OFF = 0;
    static final int CONNECTING = 1;
    static final int ON = 2;
    static final int FAILED = 3;

    /** A copy, so what a page shows is all from one moment. */
    static final class Snapshot {
        int state = OFF;
        String netId;
        Engine.Error error = Engine.Error.NONE;
        /** This r1's first address in the tunnel */
        String address = "";
        /** Where Mullvad's traffic comes out, a city and country, or "" */
        String place = "";
        long upSince;
        /** Seconds since 1970 of the last handshake, 0 for none yet */
        long handshake;
        long rx;
        long tx;
        /** Sent for over three minutes with nothing back, or no handshake in that time */
        boolean stalled;
        /** Typed engine metadata for new pages; legacy fields above remain compatible. */
        Engine.Snapshot engine;
    }

    private static Snapshot sNow = new Snapshot();
    private static int sWatching;

    private Status() {
    }

    static synchronized Snapshot get() {
        final Snapshot copy = new Snapshot();
        copy.state = sNow.state;
        copy.netId = sNow.netId;
        copy.error = sNow.error;
        copy.address = sNow.address;
        copy.place = sNow.place;
        copy.upSince = sNow.upSince;
        copy.handshake = sNow.handshake;
        copy.rx = sNow.rx;
        copy.tx = sNow.tx;
        copy.stalled = sNow.stalled;
        copy.engine = sNow.engine;
        return copy;
    }

    static void set(Snapshot now) {
        synchronized (Status.class) { sNow = now; }
        EngineAccess.publish(now.engine);
    }

    /** Whether the network is the one that is on or on its way, or failed last. */
    static boolean isCurrent(Snapshot now, String id) {
        return now.state != OFF && id != null && id.equals(now.netId);
    }

    /** A page showing the status comes and goes */
    static synchronized void watch(boolean on) {
        sWatching = Math.max(0, sWatching + (on ? 1 : -1));
    }

    static synchronized boolean watched() {
        return sWatching > 0;
    }
}
