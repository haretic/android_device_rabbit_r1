// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;

import android.system.Os;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Locale;

/** Node-loop only; credential-encrypted app-private files, identity.secret explicitly 0600. */
final class ZtStateStore {
    static final class StorageFailure extends RuntimeException {}
    private final File dir;
    ZtStateStore(File parent) {
        dir=new File(parent,"zerotier");
        try { if(!dir.isDirectory() && !dir.mkdirs()) throw new IOException(); Os.chmod(dir.getPath(),0700); }
        catch(Exception e) { throw new StorageFailure(); }
    }
    private File file(int type,long[] id) {
        if(id==null || id.length!=2 || type<1 || type>6) throw new StorageFailure();
        if(type==1) return new File(dir,"identity.public");
        if(type==2) return new File(dir,"identity.secret");
        return new File(dir,String.format(Locale.ROOT,"%d-%016x-%016x",type,id[0],id[1]));
    }
    /** Public identity format: 10 hex address, identity type 0, then the public key.
     * Never inspect identity.secret, and do not create a directory or load the native core. */
    static String publicAddress(File parent) {
        File identity=new File(new File(parent,"zerotier"),"identity.public");
        if(!identity.isFile() || identity.length()>4096) return null;
        try(FileInputStream in=new FileInputStream(identity)) {
            byte[] bytes=new byte[4097]; int length=0,n;
            while(length<bytes.length && (n=in.read(bytes,length,bytes.length-length))>0) length+=n;
            if(length==bytes.length) return null;
            String text=new String(bytes,0,length,java.nio.charset.StandardCharsets.US_ASCII).trim();
            if(!text.matches("[0-9a-fA-F]{10}:0:[0-9a-fA-F]{128}")) return null;
            String address=text.substring(0,10).toLowerCase(Locale.ROOT);
            return address.equals("0000000000")?null:address;
        } catch(IOException|SecurityException e) { return null; }
    }
    /** Only this network's cached membership/configuration, including interrupted writes. */
    static void removeNetwork(File parent,long networkId) {
        File directory=new File(parent,"zerotier");
        if(!directory.exists()) return;
        String prefix=String.format(Locale.ROOT,"6-%016x-",networkId);
        File[] files=directory.listFiles();
        if(files==null) throw new StorageFailure();
        for(File entry:files) {
            String name=entry.getName();
            if(name.startsWith(prefix) && name.substring(prefix.length()).matches("[0-9a-f]{16}(\\.tmp)?")
                    && !entry.delete()) throw new StorageFailure();
        }
    }
    byte[] get(int type,long[] id) {
        if(type==0) return null;
        File f=file(type,id); if(!f.exists()) return null;
        try {
            long length=f.length(); if(length<=0 || length>2*1024*1024) throw new IOException();
            Os.chmod(f.getPath(),0600);
            byte[] bytes=new byte[(int)length];
            try(FileInputStream in=new FileInputStream(f)) {
                int off=0,n; while(off<bytes.length && (n=in.read(bytes,off,bytes.length-off))>0) off+=n;
                if(off!=bytes.length || in.read()!=-1) throw new IOException();
            }
            return bytes;
        } catch(Exception e) { throw new StorageFailure(); }
    }
    void put(int type,long[] id,byte[] bytes) {
        if(type==0) return;
        File f=file(type,id),tmp=new File(f.getPath()+".tmp");
        try {
            if(bytes==null) { if(f.exists() && !f.delete()) throw new IOException(); return; }
            if(bytes.length==0 || bytes.length>2*1024*1024) throw new IOException();
            try(FileOutputStream out=new FileOutputStream(tmp)) {
                Os.chmod(tmp.getPath(),0600); out.write(bytes); out.getFD().sync();
            }
            if(!tmp.renameTo(f)) throw new IOException();
        } catch(Exception e) { tmp.delete(); throw new StorageFailure(); }
    }
}
