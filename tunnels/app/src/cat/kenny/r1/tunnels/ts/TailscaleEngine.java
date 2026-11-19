// Copyright (c) Tailscale Inc & AUTHORS
// Copyright (c) 2026 haretic contributors
// SPDX-License-Identifier: BSD-3-Clause
// IPNService/VPNServiceBuilder adapters from upstream Kotlin. See LICENSE-Tailscale.
package cat.kenny.r1.tunnels.ts;

import android.net.IpPrefix;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.system.OsConstants;
import cat.kenny.r1.tunnels.Engine;
import cat.kenny.r1.tunnels.Net;
import cat.kenny.r1.tunnels.zt.ZtAddress;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Service-owned VPN adapter; Go owns every successfully detached tun descriptor. */
public final class TailscaleEngine implements Engine,libtailscale.IPNService {
    private final String serviceId=UUID.randomUUID().toString();
    private BuilderFactory factory;
    private TailscaleRuntime.Lease lease;
    private volatile TailscaleClient client;
    private volatile boolean stopping,vpnAttached,requested;
    private volatile Snapshot current=new Update().build();
    private final Set<ParcelFileDescriptor> pending=new HashSet<>();
    @Override public void start(Net net,BuilderFactory factory) throws Failure {
        if(client!=null) throw new Failure(Error.INVALID_CONFIG);
        try {TsJson.controlUrl(net.controlUrl);}catch(IllegalArgumentException e){throw new Failure(Error.INVALID_CONFIG);}
        this.factory=factory;stopping=false;
        Update initial=new Update();initial.state=State.STARTING;current=initial.build();
        lease=TailscaleRuntime.acquire(factory.context());
        TailscaleRuntime runtime=lease.runtime;
        client=new TailscaleClient(runtime,factory,net,this::changed);
        requested=true;
        client.initialize(this).whenComplete((ignored,error)->{if(error!=null && !stopping) fail(Error.API);});
    }
    /** Non-null after start; page calls are async and never invoke Go on the main thread. */
    public TailscaleClient client() {return client;}
    private void changed(Snapshot status) {
        if(stopping) return;
        Update u=status.copy();if(u.state==State.RUNNING && !vpnAttached) u.state=State.STARTING;
        current=u.build();factory.publish(current);
    }
    private void fail(Error error) {
        Update u=current.copy();u.state=State.FAILED;u.error=error;current=u.build();factory.publish(current);
    }
    @Override public Snapshot snapshot() {return current;}
    @Override public void onUnderlyingNetwork(Network network) {if(client!=null && !stopping) client.networkChanged(network);}
    @Override public void onLinkPropertiesChanged(Network network) {onUnderlyingNetwork(network);}
    @Override public void wake() {if(client!=null && !stopping) client.networkChanged(factory.underlyingNetwork());}
    @Override public synchronized void stop() throws Failure {
        stopping=true;
        Failure failure=null;
        if(client!=null) try {client.stop();}catch(Failure e){failure=e;}
        if(failure!=null && failure.error==Error.STOP_TIMEOUT) throw failure;
        // WantRunning=false stops transport; serviceDisconnect makes Go close its TUNs.
        // This binding intentionally retains the process-wide Go Application after disconnect.
        if(requested) {libtailscale.Libtailscale.serviceDisconnect(this);requested=false;}
        synchronized(pending) {
            for(ParcelFileDescriptor fd:pending) try {fd.close();}catch(Exception ignored){}
            pending.clear();
        }
        vpnAttached=false;
        if(failure!=null) throw failure;
        if(lease!=null) { lease.close(); lease=null; }
        client=null;current=new Update().build();
    }
    @Override public String id() {return serviceId;}
    @Override public boolean protect(int fd) {return factory.protect(fd);}
    @Override public void close() {if(!stopping) fail(Error.IO);}
    @Override public void disconnectVPN() {if(!stopping) fail(Error.IO);}
    @Override public void updateVpnStatus(boolean on) {vpnAttached=on;if(client!=null && !stopping) changed(client.snapshot());}
    @Override public libtailscale.VPNServiceBuilder newBuilder() {
        try {
            VpnService.Builder b=factory.create().allowFamily(OsConstants.AF_INET).allowFamily(OsConstants.AF_INET6)
                .setUnderlyingNetworks(null).setBlocking(false);
            return new Builder(b);
        } catch(Failure e) {return new Builder(null);}
    }
    private final class Builder implements libtailscale.VPNServiceBuilder {
        private final VpnService.Builder builder;
        Builder(VpnService.Builder builder) {this.builder=builder;}
        private void check() throws Failure {if(builder==null || stopping) throw new Failure(Error.CONTROL);}
        @Override public void addAddress(String address,int prefix) throws Exception {
            check();try{builder.addAddress(ZtAddress.text(ZtAddress.ip(address)),prefix);}catch(IllegalArgumentException e){throw new Failure(Error.SETTINGS);}
        }
        @Override public void addDNSServer(String address) throws Exception {
            check();try{builder.addDnsServer(ZtAddress.text(ZtAddress.ip(address)));}catch(IllegalArgumentException e){throw new Failure(Error.SETTINGS);}
        }
        @Override public void addRoute(String address,int prefix) throws Exception {
            check();try{builder.addRoute(ZtAddress.text(ZtAddress.ip(address)),prefix);}catch(IllegalArgumentException e){throw new Failure(Error.SETTINGS);}
        }
        @Override public void excludeRoute(String address,int prefix) throws Exception {
            check();try{builder.excludeRoute(new IpPrefix(InetAddress.getByAddress(ZtAddress.ip(address)),prefix));}catch(IllegalArgumentException e){throw new Failure(Error.SETTINGS);}
        }
        @Override public void addSearchDomain(String domain) throws Exception {check();builder.addSearchDomain(domain);}
        @Override public void setMTU(int mtu) throws Exception {check();try{builder.setMtu(mtu);}catch(IllegalArgumentException e){throw new Failure(Error.SETTINGS);}}
        @Override public libtailscale.ParcelFileDescriptor establish() throws Exception {
            check();ParcelFileDescriptor fd=builder.establish();if(fd==null) throw new Failure(Error.NO_CONSENT);
            synchronized(pending) {
                if(stopping){fd.close();throw new Failure(Error.CONTROL);}pending.add(fd);
            }
            return new libtailscale.ParcelFileDescriptor() {
                private boolean detached;
                @Override public int detach() throws Exception {
                    synchronized(pending) {
                        if(detached || stopping) {fd.close();pending.remove(fd);throw new Failure(Error.CONTROL);}
                        int raw=fd.detachFd();pending.remove(fd);detached=true;vpnAttached=true;
                        if(client!=null) changed(client.snapshot());return raw;
                    }
                }
            };
        }
    }
}
