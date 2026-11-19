// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

/**
 * A network being set up, between the set-up's pages. Held here in the app's own memory
 * rather than in intents, as those pass through the system and this holds keys.
 */
final class Draft {
    /** Where the settings came from: the review's first line */
    static final int FROM_CODE = 0;
    static final int FROM_FILE = 1;
    static final int FROM_CLIP = 2;
    static final int FROM_TYPED = 3;

    static Net net;
    static int from;
    /** What the last scan read */
    static String scanned;
    /** The key made for typing them in: private and public, hex */
    static String[] typedKey;
    /** Mullvad's set-up: the session, the account's end, and this r1 as a device */
    static Mullvad mullvad;
    static long paidUntil;
    static int deviceNumber;

    private Draft() {
    }

    static void clear() {
        net = null;
        scanned = null;
        typedKey = null;
        mullvad = null;
        paidUntil = 0;
        deviceNumber = 0;
    }
}
