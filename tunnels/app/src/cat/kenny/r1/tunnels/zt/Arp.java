// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;
import java.util.Arrays;

/** Ethernet/IPv4 ARP payloads (the 14-byte Ethernet header is supplied separately to JNI). */
public final class Arp {
    private Arp() {}
    public static final class Packet {
        public final int operation;
        public final long senderMac,targetMac;
        private final byte[] sender,target;
        private Packet(byte[] p) {
            operation=u16(p,6); senderMac=mac(p,8); targetMac=mac(p,18);
            sender=Arrays.copyOfRange(p,14,18); target=Arrays.copyOfRange(p,24,28);
        }
        public byte[] sender() { return sender.clone(); }
        public byte[] target() { return target.clone(); }
    }
    public static Packet parse(byte[] p) {
        if(p.length<28 || u16(p,0)!=1 || u16(p,2)!=0x0800 || p[4]!=6 || p[5]!=4
                || u16(p,6)!=1 && u16(p,6)!=2) return null;
        long mac=mac(p,8);
        if(mac==0 || (mac&0x010000000000L)!=0) return null;
        return new Packet(p);
    }
    public static byte[] request(long mac,byte[] source,byte[] target) { return build(1,mac,source,0,target); }
    public static byte[] reply(long mac,byte[] source,long remoteMac,byte[] remote) { return build(2,mac,source,remoteMac,remote); }
    private static byte[] build(int operation,long mac,byte[] source,long targetMac,byte[] target) {
        if(source.length!=4 || target.length!=4 || mac<=0 || mac>>>48!=0) throw new IllegalArgumentException();
        byte[] p=new byte[28]; put16(p,0,1); put16(p,2,0x0800); p[4]=6; p[5]=4; put16(p,6,operation);
        putMac(p,8,mac); System.arraycopy(source,0,p,14,4); putMac(p,18,targetMac); System.arraycopy(target,0,p,24,4); return p;
    }
    static int u16(byte[] p,int off) { return ((p[off]&255)<<8)|(p[off+1]&255); }
    static void put16(byte[] p,int off,int value) { p[off]=(byte)(value>>>8); p[off+1]=(byte)value; }
    static long mac(byte[] p,int off) { long n=0; for(int i=0;i<6;i++) n=(n<<8)|(p[off+i]&255L); return n; }
    static void putMac(byte[] p,int off,long mac) { for(int i=0;i<6;i++) p[off+i]=(byte)(mac>>>(40-8*i)); }
}
