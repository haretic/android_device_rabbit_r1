// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;
import java.util.Arrays;

/** RFC 4861 solicitations/advertisements, including IPv6 header and checksum. No extension headers. */
public final class Ndp {
    private Ndp() {}
    public static final class Packet {
        public final int type;
        public final long mac;
        public final boolean solicited;
        private final byte[] source,target;
        private Packet(int type,long mac,boolean solicited,byte[] source,byte[] target) {
            this.type=type; this.mac=mac; this.solicited=solicited;
            this.source=source; this.target=target;
        }
        public byte[] source() { return source.clone(); }
        public byte[] target() { return target.clone(); }
    }
    public static byte[] solicitation(long mac,byte[] source,byte[] target) {
        if(source.length!=16 || target.length!=16 || (target[0]&255)==255) throw new IllegalArgumentException();
        boolean dad=ZtAddress.zero(source);
        byte[] p=base(source,ZtAddress.solicitedNode(target),dad?24:32);
        p[40]=(byte)135; System.arraycopy(target,0,p,48,16);
        if(!dad) { p[64]=1; p[65]=1; Arp.putMac(p,66,mac); }
        checksum(p); return p;
    }
    public static byte[] advertisement(long mac,byte[] target,byte[] destination,boolean solicited) {
        if(target.length!=16 || destination.length!=16 || ZtAddress.zero(target)
                || (target[0]&255)==255 || solicited && (destination[0]&255)==255) throw new IllegalArgumentException();
        byte[] p=base(target,destination,32); p[40]=(byte)136; p[44]=(byte)(solicited?0x60:0x20);
        System.arraycopy(target,0,p,48,16); p[64]=2; p[65]=1; Arp.putMac(p,66,mac); checksum(p); return p;
    }
    public static Packet parse(byte[] p) {
        if(p.length<64 || (p[0]&0xf0)!=0x60 || (p[6]&255)!=58 || (p[7]&255)!=255
                || Arp.u16(p,4)+40!=p.length || p[41]!=0 || (p[40]&255)!=135 && (p[40]&255)!=136
                || sum(p)!=0xffff) return null;
        int type=p[40]&255; byte[] source=Arrays.copyOfRange(p,8,24),target=Arrays.copyOfRange(p,48,64);
        if((target[0]&255)==255 || ZtAddress.zero(target) || (source[0]&255)==255) return null;
        boolean dad=ZtAddress.zero(source),solicited=type==136 && (p[44]&0x40)!=0;
        if(type==136 && (dad || solicited && (p[24]&255)==255)) return null;
        if(type==135 && dad && !Arrays.equals(Arrays.copyOfRange(p,24,40),ZtAddress.solicitedNode(target))) return null;
        long mac=0;
        for(int off=64;off<p.length;) {
            if(off+2>p.length) return null; int length=(p[off+1]&255)*8;
            if(length==0 || off+length>p.length) return null;
            if((p[off]&255)==(type==135?1:2)) {
                if(length!=8 || mac!=0 || dad) return null;
                mac=Arp.mac(p,off+2); if(mac==0 || (mac&0x010000000000L)!=0) return null;
            }
            off+=length;
        }
        return new Packet(type,mac,solicited,source,target);
    }
    private static byte[] base(byte[] source,byte[] dest,int length) {
        byte[] p=new byte[40+length]; p[0]=0x60; Arp.put16(p,4,length); p[6]=58; p[7]=(byte)255;
        System.arraycopy(source,0,p,8,16); System.arraycopy(dest,0,p,24,16); return p;
    }
    private static int sum(byte[] p) {
        long sum=p.length-40+58;
        for(int i=8;i<40;i+=2) sum+=Arp.u16(p,i);
        for(int i=40;i<p.length;i+=2) sum+=(p[i]&255)<<8 | (i+1<p.length?p[i+1]&255:0);
        while((sum>>>16)!=0) sum=(sum&65535)+(sum>>>16); return (int)sum;
    }
    private static void checksum(byte[] p) { Arp.put16(p,42,(~sum(p))&65535); }
}
