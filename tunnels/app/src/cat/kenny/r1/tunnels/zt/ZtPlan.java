// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Pure config -> Builder plan, independent of Android/JNI/JSON. */
public final class ZtPlan {
    public static final class Prefix {
        public final String address;
        public final int bits;
        public Prefix(String address,int bits) {
            byte[] bytes=ZtAddress.ip(address);
            if(bits<0 || bits>bytes.length*8) throw new IllegalArgumentException();
            this.address=ZtAddress.text(bytes); this.bits=bits;
        }
        public Prefix network() {
            byte[] bytes=ZtAddress.ip(address);
            for(int i=bits;i<bytes.length*8;i++) bytes[i/8]&=~(1<<(7-i%8));
            return new Prefix(ZtAddress.text(bytes),bits);
        }
        public boolean contains(byte[] ip) {
            byte[] own=ZtAddress.ip(address); if(ip.length!=own.length) return false;
            for(int i=0;i<bits;i++) if(((own[i/8]^ip[i/8])&(1<<(7-i%8)))!=0) return false;
            return true;
        }
        @Override public String toString() { return address+"/"+bits; }
    }
    public static final class Route {
        public final Prefix target;
        public final String via;
        public Route(Prefix target,String via) {
            this.target=target.network(); this.via=via==null || via.isEmpty()?"":ZtAddress.text(ZtAddress.ip(via));
            if(!this.via.isEmpty() && ZtAddress.ip(this.via).length!=ZtAddress.ip(target.address).length) throw new IllegalArgumentException();
        }
    }
    public final List<Prefix> addresses;
    public final List<Route> routes;
    public final List<String> dns;
    public final String domain;
    public final int mtu;
    public final long mac;
    public final boolean broadcast;
    public ZtPlan(List<Prefix> addresses,List<Route> managed,List<String> dns,String domain,
            int mtu,long mac,boolean broadcast,boolean acceptRoutes,boolean acceptDns) {
        if(mtu<576 || mtu>10000 || mac<=0 || (mac>>>48)!=0 || (mac&0x010000000000L)!=0) throw new IllegalArgumentException();
        List<Prefix> add=new ArrayList<>(); List<Route> routes=new ArrayList<>();
        for(Prefix p:addresses) {
            byte[] a=ZtAddress.ip(p.address);
            if(ZtAddress.zero(a) || ZtAddress.multicastMac(a)!=0 || a.length==16 && mtu<1280) throw new IllegalArgumentException();
            add.add(p); routes.add(new Route(p,""));
        }
        if(acceptRoutes) for(Route r:managed) {
            // Treat an all-zero "via" as on-link, as in the native sockaddr representation.
            String via=r.via;
            if(!via.isEmpty() && ZtAddress.zero(ZtAddress.ip(via))) via="";
            Route route=new Route(r.target,via);
            if(!routes.stream().anyMatch(x -> x.target.toString().equals(route.target.toString()) && x.via.equals(route.via))) routes.add(route);
        }
        List<String> servers=new ArrayList<>();
        if(acceptDns) for(String server:dns) {
            byte[] ip=ZtAddress.ip(server);
            if(ZtAddress.zero(ip) || ZtAddress.multicastMac(ip)!=0) throw new IllegalArgumentException();
            servers.add(ZtAddress.text(ip));
        }
        if(domain==null || !domain.isEmpty() && !domain.matches("[A-Za-z0-9_.-]{1,253}")) throw new IllegalArgumentException();
        this.addresses=Collections.unmodifiableList(add); this.routes=Collections.unmodifiableList(routes);
        this.dns=Collections.unmodifiableList(servers); this.domain=acceptDns?domain:"";
        this.mtu=mtu; this.mac=mac; this.broadcast=broadcast;
    }
    public byte[] nextHop(byte[] destination) {
        Route best=null;
        for(Route r:routes) if(r.target.contains(destination) && (best==null || r.target.bits>best.target.bits
                || r.target.bits==best.target.bits && !r.via.isEmpty())) best=r;
        return best!=null && !best.via.isEmpty()?ZtAddress.ip(best.via):destination.clone();
    }
    public boolean owns(byte[] ip) {
        for(Prefix p:addresses) if(Arrays.equals(ZtAddress.ip(p.address),ip)) return true; return false;
    }
    public byte[] source(int length) {
        for(Prefix p:addresses) { byte[] ip=ZtAddress.ip(p.address); if(ip.length==length) return ip; } return null;
    }
    public boolean broadcast(byte[] ip) {
        if(ip.length!=4) return false;
        if(ZtAddress.v4(ip)==0xffffffffL) return true;
        for(Prefix p:addresses) if(p.bits<31 && p.contains(ip)) {
            long mask=(1L<<(32-p.bits))-1;
            if((ZtAddress.v4(ip)&mask)==mask) return true;
        }
        return false;
    }
    public List<String> addressStrings() {
        List<String> result=new ArrayList<>(); for(Prefix p:addresses) result.add(p.toString()); return result;
    }
    /** Stable signature used to avoid replacing tun for status-only network config callbacks. */
    public String signature() {
        StringBuilder s=new StringBuilder().append(mtu).append(':').append(mac).append(':').append(broadcast);
        for(Prefix p:addresses) s.append('|').append(p);
        for(Route r:routes) s.append('|').append(r.target).append('>').append(r.via);
        return s.append('|').append(dns).append('|').append(domain).toString();
    }
}
