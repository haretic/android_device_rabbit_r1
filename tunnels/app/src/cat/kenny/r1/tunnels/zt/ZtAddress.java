// SPDX-License-Identifier: MPL-2.0
// Algorithms derived from ZeroTierOne 1.16.2 node/MAC.hpp and node/InetAddress.cpp.
// Copyright (c) ZeroTier, Inc.; Java adaptation copyright (c) 2026 haretic contributors.
package cat.kenny.r1.tunnels.zt;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;

/** Pure numeric addressing; never performs a hostname lookup. */
public final class ZtAddress {
    private ZtAddress() {}
    public static byte[] ip(String text) {
        if (text==null || !text.matches("[0-9a-fA-F:.]+") || text.indexOf(':')<0
                && !text.matches("[0-9]+(\\.[0-9]+){3}")) throw new IllegalArgumentException();
        try {
            byte[] result=InetAddress.getByName(text).getAddress();
            if(text.indexOf(':')>=0 && result.length!=16) throw new IllegalArgumentException();
            return result;
        } catch(UnknownHostException e) { throw new IllegalArgumentException(); }
    }
    public static String text(byte[] ip) {
        try { return InetAddress.getByAddress(ip).getHostAddress(); }
        catch(UnknownHostException e) { throw new IllegalArgumentException(); }
    }
    public static long networkId(String id) {
        if(id==null || !id.matches("[0-9a-fA-F]{16}")) throw new IllegalArgumentException();
        long n=Long.parseUnsignedLong(id,16); if(n==0) throw new IllegalArgumentException(); return n;
    }
    public static long mac(long node,long nwid) {
        int first=((int)nwid & 0xfe)|2; if(first==0x52) first=0x32;
        long value=((long)first<<40)|(node&0xffffffffffL);
        for(int i=0;i<5;i++) value^=((nwid>>>(8*(i+1)))&255L)<<(32-8*i);
        return value;
    }
    public static byte[] rfc4193(long node,long nwid) {
        byte[] a=new byte[16]; a[0]=(byte)0xfd;
        for(int i=0;i<8;i++) a[1+i]=(byte)(nwid>>>(56-8*i));
        a[9]=(byte)0x99; a[10]=(byte)0x93;
        for(int i=0;i<5;i++) a[11+i]=(byte)(node>>>(32-8*i)); return a;
    }
    public static byte[] sixPlane(long node,long nwid) {
        byte[] a=new byte[16]; long n=nwid^(nwid>>>32); a[0]=(byte)0xfc;
        for(int i=0;i<4;i++) a[1+i]=(byte)(n>>>(24-8*i));
        for(int i=0;i<5;i++) a[5+i]=(byte)(node>>>(32-8*i)); a[15]=1; return a;
    }
    public static Long computedMac(byte[] ip,long nwid) {
        if(ip.length!=16) return null;
        byte[] six=sixPlane(0,nwid),rfc=rfc4193(0,nwid); long node=0;
        if(Arrays.equals(Arrays.copyOf(ip,5),Arrays.copyOf(six,5))
                && ip[10]==0 && ip[11]==0 && ip[12]==0 && ip[13]==0 && ip[14]==0 && ip[15]==1) {
            for(int i=5;i<10;i++) node=(node<<8)|(ip[i]&255L);
        } else if(Arrays.equals(Arrays.copyOf(ip,11),Arrays.copyOf(rfc,11))) {
            for(int i=11;i<16;i++) node=(node<<8)|(ip[i]&255L);
        } else return null;
        return node==0 ? null : mac(node,nwid);
    }
    public static long multicastMac(byte[] ip) {
        if(ip.length==4 && (ip[0]&0xf0)==0xe0)
            return 0x01005e000000L|((ip[1]&127L)<<16)|((ip[2]&255L)<<8)|(ip[3]&255L);
        if(ip.length==16 && (ip[0]&255)==255) return 0x333300000000L|v4(Arrays.copyOfRange(ip,12,16));
        return 0;
    }
    public static byte[] solicitedNode(byte[] target) {
        if(target.length!=16) throw new IllegalArgumentException();
        byte[] ip=new byte[16]; ip[0]=(byte)255; ip[1]=2; ip[11]=1; ip[12]=(byte)255;
        System.arraycopy(target,13,ip,13,3); return ip;
    }
    public static long v4(byte[] ip) {
        if(ip.length!=4) throw new IllegalArgumentException(); long n=0;
        for(byte b:ip) n=(n<<8)|(b&255L); return n;
    }
    public static boolean zero(byte[] bytes) { for(byte b:bytes) if(b!=0) return false; return true; }
}
