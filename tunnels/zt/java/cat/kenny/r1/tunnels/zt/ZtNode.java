// SPDX-License-Identifier: MIT
package cat.kenny.r1.tunnels.zt;

/** Minimal node-core JNI surface; supplies no sockets, tun, routing or service.
 * Serialize calls per node (including deletion), and never call from callbacks.
 * All now/deadline values are milliseconds in ONE increasing clock domain:
 * Android SystemClock.elapsedRealtime() includes suspend. nextDeadline has >=1
 * element, receives an ABSOLUTE deadline, and is valid even on ordinary errors.
 * Unsigned network IDs/MACs/ADI use long bit patterns. JSON IDs are fixed hex
 * strings. processFrame data is Ethernet payload without its 14-byte header.
 * Callbacks that throw propagate the original exception; if mutation had begun,
 * the caller should stop/recreate the node rather than assume transactionality.
 */
public final class ZtNode {
    static { System.loadLibrary("r1zt"); }
    private ZtNode() {}
    public static native long nodeNew(ZtCallbacks cb, long now);
    public static native void nodeDelete(long node);
    public static native int processWirePacket(long node, long now, long localSocket,
        byte[] remoteAddr, int remotePort, byte[] data, long[] nextDeadline);
    public static native int processFrame(long node, long now, long nwid,
        long srcMac, long dstMac, int etherType, int vlan, byte[] frame, long[] nextDeadline);
    public static native int processBackgroundTasks(long node, long now, long[] nextDeadline);
    public static native int join(long node, long nwid);
    public static native int leave(long node, long nwid);
    public static native long address(long node);
    /** Boolean acceptance, not ZT_ResultCode. Never advertise a VPN/ZeroTier interface. */
    public static native int addLocalInterfaceAddress(long node,byte[] address,int port);
    public static native void clearLocalInterfaceAddresses(long node);
    /** Never includes identity.secret; public identity is included. */
    public static native String statusJson(long node);
    public static native String networksJson(long node);
    public static native String peersJson(long node);
    public static native int multicastSubscribe(long node, long nwid, long mac, long adi);
    public static native int multicastUnsubscribe(long node, long nwid, long mac, long adi);
}
