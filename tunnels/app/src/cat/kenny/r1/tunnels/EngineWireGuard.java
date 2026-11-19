// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/** The existing helper protocol, key encoding, routes, MTU and stall policy behind Engine. */
public final class EngineWireGuard implements Engine {
    private static final long STALE_S=180;
    private BuilderFactory factory;
    private Net net;
    private ParcelFileDescriptor tun;
    private Tunneld.Session session;
    private Thread watcher;
    private volatile boolean stopping;
    private volatile Snapshot current=new Update().build();
    private long upSince, lastTx, lastRx;
    private String place="";

    @Override public void start(Net net, BuilderFactory factory) throws Failure {
        this.net=net; this.factory=factory; stopping=false;
        try {
            if (net.isMullvad()) {
                Relays relays;
                try { relays=Relays.load(factory.context()); }
                catch(IOException e) { throw new Failure(Error.RELAYS); }
                Relays.Relay relay=relays.pick(net.location,net.lastRelay);
                if (relay==null) throw new Failure(Error.RELAYS);
                net.host=relay.ip; net.port=51820; net.peerKey=relay.publicKeyHex;
                net.lastRelay=relay.hostname; place=relays.placeOf(relay);
                Store.save(factory.context(),net);
            }
            InetAddress[] all=InetAddress.getAllByName(net.host);
            if (all.length==0) throw new Failure(Error.DNS);
            tun=builder(net).establish();
            if (tun==null) throw new Failure(Error.NO_CONSENT);
            session=Tunneld.Session.start(tun.getFileDescriptor());
            session.set(uapi(net,all[0])); session.command("up");
            upSince=SystemClock.elapsedRealtime();
            Update u=new Update(); u.state=State.RUNNING; u.online=true;
            u.addresses=new ArrayList<>(net.addresses); u.place=place; current=u.build();
            Tunneld.Session watched=session;
            watcher=new Thread(() -> {
                try { watched.waitFor(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                if (!stopping) {
                    Update fail=current.copy(); fail.state=State.FAILED; fail.error=Error.HELPER_EXITED;
                    factory.publish(fail.build());
                }
            },"wg-exit");
            watcher.start();
        } catch (UnknownHostException e) { throw new Failure(Error.DNS); }
        catch (Tunneld.Failure e) { throw new Failure(helperError(e)); }
        catch (IOException e) { throw new Failure(Error.IO); }
        catch (IllegalArgumentException e) { throw new Failure(Error.SETTINGS); }
    }
    private static Error helperError(Tunneld.Failure e) {
        switch(e.why) {
            case Tunneld.Failure.EXITED:return Error.HELPER_EXITED;
            case Tunneld.Failure.STRANGER:return Error.HELPER_STRANGER;
            case Tunneld.Failure.REFUSED:return Error.HELPER_REFUSED;
            default:return Error.HELPER_NO_START;
        }
    }
    @Override public void onUnderlyingNetwork(Network network) throws Failure {
        if(network!=null) { command("rebind"); command("wake"); }
    }
    @Override public void onLinkPropertiesChanged(Network network) throws Failure {
        onUnderlyingNetwork(network);
    }
    @Override public void wake() throws Failure { command("wake"); }
    private void command(String command) throws Failure {
        if (session==null) return;
        try { session.command(command); } catch(IOException e) { throw new Failure(Error.CONTROL); }
    }
    @Override public Snapshot snapshot() throws Failure {
        if (session==null) return current;
        String text;
        try { text=session.get(); } catch(IOException e) { throw new Failure(Error.CONTROL); }
        long handshake=0,rx=0,tx=0;
        for (String line:text.split("\n")) {
            int eq=line.indexOf('='); if(eq<=0) continue;
            try {
                String key=line.substring(0,eq); long value;
                if (key.equals("last_handshake_time_sec")) {
                    value=Long.parseLong(line.substring(eq+1)); handshake=Math.max(handshake,value);
                } else if(key.equals("rx_bytes")) rx+=Long.parseLong(line.substring(eq+1));
                else if(key.equals("tx_bytes")) tx+=Long.parseLong(line.substring(eq+1));
            } catch(NumberFormatException ignored) { }
        }
        Update u=current.copy(); u.handshake=handshake; u.rx=rx; u.tx=tx;
        boolean old=handshake==0 ? SystemClock.elapsedRealtime()-upSince>STALE_S*1000
            : System.currentTimeMillis()/1000-handshake>STALE_S;
        u.stalled=old && (net.keepalive>0 || handshake==0 || tx>lastTx && rx==lastRx);
        lastTx=tx; lastRx=rx; current=u.build(); return current;
    }
    @Override public synchronized void stop() throws Failure {
        stopping=true;
        if(session!=null) {
            session.close();
            if(!session.hasStopped()) throw new Failure(Error.STOP_TIMEOUT);
            session=null;
        }
        if(tun!=null) { try { tun.close(); } catch(IOException ignored) {} tun=null; }
        if(watcher!=null) {
            try { watcher.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(Error.STOP_TIMEOUT); }
            if(watcher.isAlive()) throw new Failure(Error.STOP_TIMEOUT);
            watcher=null;
        }
        current=new Update().build();
    }

    private VpnService.Builder builder(Net net) throws Engine.Failure {
        final VpnService.Builder builder = factory.create();
        builder.setSession(net.name);
        try {
            for (String cidr : net.addresses) {
                final int slash = cidr.indexOf('/');
                builder.addAddress(cidr.substring(0, slash),
                        Integer.parseInt(cidr.substring(slash + 1)));
            }
            for (String dns : net.dns) {
                if (WgConf.parseIp(dns) != null) {
                    builder.addDnsServer(dns);
                } else {
                    builder.addSearchDomain(dns);
                }
            }
            for (String route : net.routes()) {
                // The system takes a network only without its host's bits
                final String network = WgConf.networkOf(route);
                final String cidr = network != null ? network : route;
                final int slash = cidr.indexOf('/');
                builder.addRoute(cidr.substring(0, slash),
                        Integer.parseInt(cidr.substring(slash + 1)));
            }
        } catch (IllegalArgumentException | StringIndexOutOfBoundsException e) {
            throw new Engine.Failure(Error.SETTINGS);
        }
        builder.setMtu(net.mtu);
        builder.setBlocking(true);
        return builder;
    }

    private static List<String> uapi(Net net, InetAddress server) {
        final List<String> lines = new ArrayList<>();
        lines.add("private_key=" + net.privateKey);
        lines.add("replace_peers=true");
        lines.add("public_key=" + net.peerKey);
        if (!net.presharedKey.isEmpty()) {
            lines.add("preshared_key=" + net.presharedKey);
        }
        String ip = server.getHostAddress();
        final int zone = ip.indexOf('%');
        if (zone >= 0) {
            ip = ip.substring(0, zone);
        }
        lines.add("endpoint=" + (ip.indexOf(':') >= 0 ? "[" + ip + "]" : ip) + ":" + net.port);
        lines.add("persistent_keepalive_interval=" + net.keepalive);
        lines.add("replace_allowed_ips=true");
        for (String cidr : net.peerAllowedIps()) {
            lines.add("allowed_ip=" + cidr);
        }
        return lines;
    }
}
