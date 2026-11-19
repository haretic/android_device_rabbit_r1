// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.net.Network;
import android.net.VpnService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** One engine owns the VPN at a time. Service calls run on its worker, never the UI thread. */
public interface Engine {
    enum State { STOPPED, STARTING, AUTHENTICATING, WAITING_APPROVAL, RUNNING, FAILED }
    enum Error { NONE, MISSING_PROFILE, REVOKED, INVALID_CONFIG, LIBRARY_UNAVAILABLE, NO_CONSENT, SETTINGS, DNS, RELAYS,
        ACCOUNT_INVALID, ACCOUNT_EXPIRED, DEVICE_LIMIT, RATE_LIMITED, IO, STORAGE, CORE, CONTROL, CALLBACK, TIMEOUT, STOP_TIMEOUT, API, UNSUPPORTED,
        HELPER_NO_START, HELPER_EXITED, HELPER_STRANGER, HELPER_REFUSED }
    enum NetworkState { UNKNOWN, REQUESTING_CONFIGURATION, OK, ACCESS_DENIED, NOT_FOUND,
        PORT_ERROR, CLIENT_TOO_OLD, AUTHENTICATION_REQUIRED }
    enum BackendState { UNKNOWN, NO_STATE, IN_USE_OTHER_USER, NEEDS_LOGIN, NEEDS_MACHINE_AUTH,
        STOPPED, STARTING, RUNNING }
    enum Path { DIRECT, RELAY, UNREACHABLE }

    final class Failure extends Exception {
        public final Error error;
        public Failure(Error error) { super(error.name()); this.error = error; }
    }
    /** Metadata only; never an identity secret, auth key, private key or unfiltered backend error. */
    final class Peer {
        public final String id, name;
        public final List<String> addresses;
        public final Path path;
        public final long latency;
        public final boolean exitNode;
        public Peer(String id, String name, List<String> addresses, Path path,
                long latency, boolean exitNode) {
            this.id = id; this.name = name; this.addresses = immutable(addresses);
            this.path = path; this.latency = latency; this.exitNode = exitNode;
        }
    }
    final class Snapshot {
        public final State state;
        public final Error error;
        public final NetworkState networkState;
        public final BackendState backendState;
        public final String nodeAddress, networkName, place, loginUrl, loginName, hostName;
        public final List<String> addresses;
        public final List<Peer> peers;
        public final boolean online, relayed, loginComplete, stalled;
        public final long handshake, rx, tx;
        private Snapshot(Update u) {
            state=u.state; error=u.error; networkState=u.networkState; backendState=u.backendState;
            nodeAddress=u.nodeAddress; networkName=u.networkName; place=u.place; loginUrl=u.loginUrl;
            loginName=u.loginName; hostName=u.hostName;
            addresses=immutable(u.addresses); peers=immutable(u.peers); online=u.online;
            relayed=u.relayed; loginComplete=u.loginComplete; stalled=u.stalled;
            handshake=u.handshake; rx=u.rx; tx=u.tx;
        }
        public Update copy() { return new Update(this); }
    }
    /** Worker-local snapshot builder. Published snapshots are deeply immutable metadata. */
    final class Update {
        public State state=State.STOPPED;
        public Error error=Error.NONE;
        public NetworkState networkState=NetworkState.UNKNOWN;
        public BackendState backendState=BackendState.UNKNOWN;
        public String nodeAddress="", networkName="", place="", loginUrl="", loginName="", hostName="";
        public List<String> addresses=new ArrayList<>();
        public List<Peer> peers=new ArrayList<>();
        public boolean online, relayed, loginComplete, stalled;
        public long handshake, rx, tx;
        public Update() {}
        private Update(Snapshot s) {
            state=s.state; error=s.error; networkState=s.networkState; backendState=s.backendState;
            nodeAddress=s.nodeAddress; networkName=s.networkName; place=s.place; loginUrl=s.loginUrl;
            loginName=s.loginName; hostName=s.hostName;
            addresses=new ArrayList<>(s.addresses); peers=new ArrayList<>(s.peers); online=s.online;
            relayed=s.relayed; loginComplete=s.loginComplete; stalled=s.stalled;
            handshake=s.handshake; rx=s.rx; tx=s.tx;
        }
        public Snapshot build() { return new Snapshot(this); }
    }
    static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }
    /** Sampled by the service's existing poll, before its visibility throttle.
     * elapsed includes suspend; uptime does not. Sampling elapsed first can only
     * underestimate the offset. Keep its maximum; allow 1ms clock quantization. */
    final class SuspendClock {
        private long offset;
        public SuspendClock(long elapsed,long uptime) { offset=elapsed-uptime; }
        public boolean resumed(long elapsed,long uptime) {
            long next=elapsed-uptime;
            boolean resumed=next>offset+1;
            offset=Math.max(offset,next);
            return resumed;
        }
    }
    interface BuilderFactory {
        /** Fresh common builder: package excluded, configure intent, underlying network, metering. */
        VpnService.Builder create() throws Failure;
        Context context();
        Network underlyingNetwork();
        boolean protect(int fd);
        /** Persist only these options onto the latest saved profile, without overwriting UI edits. */
        void saveOptions(String controlUrl,String exitNodeId,boolean acceptRoutes,boolean acceptDns) throws Failure;
        /** Name and id are saved together; compatibility for factories without named choices. */
        default void saveOptions(String controlUrl,String exitNodeId,String exitNodeName,
                boolean acceptRoutes,boolean acceptDns) throws Failure {
            saveOptions(controlUrl,exitNodeId,acceptRoutes,acceptDns);
        }
        /** May be called from an engine/native callback. Service queues it; never blocks here. */
        void publish(Snapshot snapshot);
    }
    void start(Net net, BuilderFactory factory) throws Failure;
    void stop() throws Failure;
    void onUnderlyingNetwork(Network network) throws Failure;
    /** Same underlying Network can acquire new DNS/link addresses without changing its ID. */
    default void onLinkPropertiesChanged(Network network) throws Failure {}
    void wake() throws Failure;
    /** May perform bounded control I/O (WireGuard); service polls on its worker. */
    Snapshot snapshot() throws Failure;
}
