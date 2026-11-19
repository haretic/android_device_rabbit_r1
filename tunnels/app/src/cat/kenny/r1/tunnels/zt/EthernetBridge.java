// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure bounded tun/IP <-> Ethernet bridge. Confined to the node loop, no callback reentry. */
public final class EthernetBridge {
    public static final long BROADCAST=0xffffffffffffL;
    public static final class Frame {
        public final long source,destination;
        public final int etherType;
        private final byte[] data;
        public Frame(long source,long destination,int etherType,byte[] data) {
            this.source=source; this.destination=destination; this.etherType=etherType; this.data=data.clone();
        }
        public byte[] data() { return data.clone(); }
    }
    public static final class Result {
        public final List<Frame> frames=new ArrayList<>();
        public final List<byte[]> packets=new ArrayList<>();
    }
    private static final class Neighbor { long mac,seen; Neighbor(long m,long n) {mac=m;seen=n;} }
    private static final class Pending { byte[] ip,hop; long since; Pending(byte[] i,byte[] h,long n) {ip=i.clone();hop=h;since=n;} }
    private final ZtPlan plan;
    private final long nwid;
    private final Map<String,Neighbor> cache=new LinkedHashMap<>();
    private final Map<String,Long> probes=new LinkedHashMap<>();
    private final List<Pending> pending=new ArrayList<>();
    public EthernetBridge(long nwid,ZtPlan plan) { this.nwid=nwid; this.plan=plan; }
    private static String key(byte[] ip) { return Arrays.toString(ip); }
    private void expire(long now) {
        cache.entrySet().removeIf(e->now-e.getValue().seen>90000);
        probes.entrySet().removeIf(e->now-e.getValue()>5000);
        pending.removeIf(p->now-p.since>5000);
    }
    private void learn(byte[] ip,long mac,long now,Result result) {
        if(ZtAddress.zero(ip) || ZtAddress.multicastMac(ip)!=0 || mac==0 || (mac&0x010000000000L)!=0) return;
        if(cache.size()>=256 && !cache.containsKey(key(ip))) cache.remove(cache.keySet().iterator().next());
        cache.put(key(ip),new Neighbor(mac,now)); probes.remove(key(ip));
        for(Iterator<Pending> i=pending.iterator();i.hasNext();) {
            Pending p=i.next(); if(Arrays.equals(p.hop,ip)) { result.frames.add(frame(mac,p.ip)); i.remove(); }
        }
    }
    private Frame frame(long dest,byte[] ip) { return new Frame(plan.mac,dest,(ip[0]&0xf0)==0x40?0x800:0x86dd,ip); }
    private static byte[] source(byte[] p) { return Arrays.copyOfRange(p,(p[0]&0xf0)==0x40?12:8,(p[0]&0xf0)==0x40?16:24); }
    private static byte[] destination(byte[] p) { return Arrays.copyOfRange(p,(p[0]&0xf0)==0x40?16:24,(p[0]&0xf0)==0x40?20:40); }
    public static boolean validIp(byte[] p) {
        if(p.length==0) return false;
        if((p[0]&0xf0)==0x40) {
            int header=(p[0]&15)*4;
            return header>=20 && p.length>=header && Arp.u16(p,2)==p.length;
        }
        return (p[0]&0xf0)==0x60 && p.length>=40 && Arp.u16(p,4)+40==p.length;
    }
    public Result fromTun(byte[] ip,long now) {
        Result result=new Result(); expire(now);
        if(!validIp(ip) || ip.length>plan.mtu) return result;
        byte[] dest=destination(ip),hop=plan.nextHop(dest);
        if(plan.broadcast(dest)) {
            if(plan.broadcast) result.frames.add(frame(BROADCAST,ip)); return result;
        }
        long multi=ZtAddress.multicastMac(dest);
        if(multi!=0) { result.frames.add(frame(multi,ip)); return result; }
        Long computed=ZtAddress.computedMac(hop,nwid);
        Neighbor neighbor=cache.get(key(hop));
        if(computed!=null || neighbor!=null) {
            result.frames.add(frame(computed!=null?computed:neighbor.mac,ip)); return result;
        }
        if(pending.size()>=128) pending.remove(0);
        pending.add(new Pending(ip,hop,now));
        Long last=probes.get(key(hop)); if(last!=null && now-last<1000) return result;
        if(probes.size()>=256) probes.remove(probes.keySet().iterator().next()); probes.put(key(hop),now);
        byte[] src=plan.owns(source(ip))?source(ip):plan.source(hop.length);
        if(src==null) return result;
        if(hop.length==4) result.frames.add(new Frame(plan.mac,BROADCAST,0x806,Arp.request(plan.mac,src,hop)));
        else result.frames.add(new Frame(plan.mac,ZtAddress.multicastMac(ZtAddress.solicitedNode(hop)),0x86dd,Ndp.solicitation(plan.mac,src,hop)));
        return result;
    }
    public Result fromEthernet(Frame frame,long now) {
        Result result=new Result(); expire(now);
        if(frame.destination!=plan.mac && frame.destination!=BROADCAST && (frame.destination&0x010000000000L)==0) return result;
        byte[] p=frame.data;
        if(frame.etherType==0x806) {
            Arp.Packet arp=Arp.parse(p); if(arp==null || arp.senderMac!=frame.source) return result;
            learn(arp.sender(),arp.senderMac,now,result);
            if(arp.operation==1 && plan.owns(arp.target())) result.frames.add(new Frame(plan.mac,arp.senderMac,0x806,Arp.reply(plan.mac,arp.target(),arp.senderMac,arp.sender())));
            return result;
        }
        if(frame.etherType!=0x800 && frame.etherType!=0x86dd || !validIp(p) || p.length>plan.mtu
                || ((p[0]&0xf0)==0x40)!=(frame.etherType==0x800)) return result;
        if(frame.etherType==0x86dd && p[6]==58 && p.length>=41 && ((p[40]&255)==135 || (p[40]&255)==136)) {
            Ndp.Packet nd=Ndp.parse(p); if(nd==null || nd.mac!=0 && nd.mac!=frame.source) return result;
            if(nd.type==136) { if(nd.mac!=0) learn(nd.target(),nd.mac,now,result); }
            else {
                if(nd.mac!=0) learn(nd.source(),nd.mac,now,result);
                if(plan.owns(nd.target())) {
                    boolean dad=ZtAddress.zero(nd.source());
                    byte[] dest=dad?ZtAddress.ip("ff02::1"):nd.source();
                    long mac=dad?ZtAddress.multicastMac(dest):frame.source;
                    result.frames.add(new Frame(plan.mac,mac,0x86dd,Ndp.advertisement(plan.mac,nd.target(),dest,!dad)));
                }
            }
            return result;
        }
        learn(source(p),frame.source,now,result);
        byte[] dest=destination(p);
        if(plan.owns(dest) || ZtAddress.multicastMac(dest)!=0 || plan.broadcast && plan.broadcast(dest)) result.packets.add(p.clone());
        return result;
    }
}
