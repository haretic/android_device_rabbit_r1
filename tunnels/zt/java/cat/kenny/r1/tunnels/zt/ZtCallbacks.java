// SPDX-License-Identifier: MIT
package cat.kenny.r1.tunnels.zt;

/** Callbacks run synchronously on the calling thread. Do not reenter ZtNode.
 * Queue work and return quickly. Caller must serialize every call per node.
 * State IDs always have two unsigned 64-bit components represented by Java long.
 * stateGet returns null if absent; statePut data==null means delete.
 * Addresses are raw 4/16-byte IPs, never sockaddr memory. Scoped IPv6 is not
 * supported by this minimal surface. All callback byte arrays are owned copies.
 */
public interface ZtCallbacks {
    byte[] stateGet(int type, long[] id);
    void statePut(int type, long[] id, byte[] data);
    int wireSend(long localSocket, byte[] addr, int port, byte[] data, int ttl);
    void frame(long nwid, long srcMac, long dstMac, int etherType, int vlan, byte[] data);
    int networkConfig(long nwid, int op, String configJson);
    /** UP=0, OFFLINE=1, ONLINE=2, DOWN=3, COLLISION=4. Null for no payload.
     * TRACE(5): UTF-8 bytes. USER_MESSAGE(6): BE origin:u64,type:u64,body.
     * REMOTE_TRACE(7): BE origin:u64 followed by untrusted dictionary bytes.
     */
    void event(int code, byte[] payload);
}
