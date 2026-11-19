// Copyright (c) Tailscale Inc & AUTHORS
// Copyright (c) 2026 haretic contributors
// SPDX-License-Identifier: BSD-3-Clause
// Plain Java adaptation of upstream App.kt / NetworkChangeCallback.kt. See LICENSE-Tailscale.
package cat.kenny.r1.tunnels.ts;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import cat.kenny.r1.tunnels.Engine;
import org.json.JSONArray;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Process-scoped AppContext, never retains a Service or Activity. Secrets use Keystore AES-GCM. */
final class TailscaleContext implements libtailscale.AppContext {
    private static final String ALIAS="r1_tailscale_state_v1";
    private final Context context;
    private final SharedPreferences prefs;
    private volatile String dns="";
    TailscaleContext(Context context) {
        this.context=context.getApplicationContext();
        prefs=this.context.getSharedPreferences("tailscale-state-v1",Context.MODE_PRIVATE);
    }
    private SecretKey key() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(ALIAS)) return (SecretKey)store.getKey(ALIAS,null);
        // Never silently replace an unavailable key if encrypted state already exists.
        if(!prefs.getAll().isEmpty()) throw new Engine.Failure(Engine.Error.STORAGE);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
    @Override public synchronized void encryptToPref(String name,String value) throws Exception {
        try {
            SharedPreferences.Editor edit=prefs.edit();
            if(value==null || value.isEmpty()) edit.remove(name);
            else {
                Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");cipher.init(Cipher.ENCRYPT_MODE,key());
                cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                byte[] plain=value.getBytes(java.nio.charset.StandardCharsets.UTF_8),encrypted;
                try { encrypted=cipher.doFinal(plain); } finally { Arrays.fill(plain,(byte)0); }
                byte[] iv=cipher.getIV(),blob=new byte[1+iv.length+encrypted.length];
                if(iv.length!=12) throw new Engine.Failure(Engine.Error.STORAGE);
                blob[0]=1;System.arraycopy(iv,0,blob,1,12);System.arraycopy(encrypted,0,blob,13,encrypted.length);
                edit.putString(name,Base64.encodeToString(blob,Base64.NO_WRAP));
            }
            if(!edit.commit()) throw new Engine.Failure(Engine.Error.STORAGE);
        } catch(Exception e) { throw new Engine.Failure(Engine.Error.STORAGE); }
    }
    @Override public synchronized String decryptFromPref(String name) throws Exception {
        String encoded=prefs.getString(name,null);if(encoded==null) return "";
        try {
            byte[] blob=Base64.decode(encoded,Base64.NO_WRAP);
            if(blob.length<29 || blob[0]!=1) throw new Engine.Failure(Engine.Error.STORAGE);
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,blob,1,12));
            cipher.updateAAD(name.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            byte[] plain=cipher.doFinal(blob,13,blob.length-13);
            try { return new String(plain,java.nio.charset.StandardCharsets.UTF_8); }
            finally { Arrays.fill(plain,(byte)0); }
        } catch(Exception e) { throw new Engine.Failure(Engine.Error.STORAGE); }
    }
    @Override public synchronized String getStateStoreKeysJSON() {
        JSONArray keys=new JSONArray();
        for(String key:prefs.getAll().keySet()) if(key.startsWith("statestore-")) keys.put(key.substring(11));
        return keys.toString();
    }
    @Override public String getInstallSource() { return "r1-system"; }
    @Override public String getModelName() { return Build.MODEL; }
    @Override public String getOSVersion() { return Build.VERSION.RELEASE; }
    @Override public boolean isChromeOS() { return false; }
    @Override public boolean shouldUseGoogleDNSFallback() { return false; }
    @Override public String getPlatformDNSConfig() { return dns; }
    /** Returns selected non-VPN interface name; called on an engine worker. */
    String updateDns(Network preferred) {
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        Network picked=null,fallback=preferred!=null && usable(cm,preferred)?preferred:null;
        // Upstream prefers a non-metered non-VPN network with DNS, then any with DNS,
        // then any usable physical network. Never feed the VPN's own DNS back into Go.
        for(Network n:cm.getAllNetworks()) if(usable(cm,n)) {
            if(fallback==null) fallback=n;
            NetworkCapabilities caps=cm.getNetworkCapabilities(n);LinkProperties links=cm.getLinkProperties(n);
            if(links!=null && !links.getDnsServers().isEmpty()) {
                if(picked==null) picked=n;
                if(caps!=null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)) {picked=n;break;}
            }
        }
        if(picked==null) picked=fallback;
        LinkProperties lp=picked==null?null:cm.getLinkProperties(picked);
        StringBuilder text=new StringBuilder();
        if(lp!=null) {
            for(java.net.InetAddress ip:lp.getDnsServers()) text.append(ip.getHostAddress()).append(' ');
            if(lp.getDomains()!=null) text.append('\n').append(lp.getDomains());
        }
        dns=text.toString();return lp==null || lp.getInterfaceName()==null?"":lp.getInterfaceName();
    }
    private static boolean usable(ConnectivityManager cm,Network n) {
        NetworkCapabilities c=cm.getNetworkCapabilities(n);
        return c!=null && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
            && c.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }
    @Override public String getInterfacesAsString() throws Exception {
        StringBuilder out=new StringBuilder();
        java.util.Enumeration<NetworkInterface> all=NetworkInterface.getNetworkInterfaces();
        if(all==null) return "";
        for(NetworkInterface n:Collections.list(all)) {
            try {
                out.append(String.format(Locale.ROOT,"%s %d %d %b %b %b %b %b |",n.getName(),n.getIndex(),n.getMTU(),n.isUp(),n.supportsMulticast(),n.isLoopback(),n.isPointToPoint(),n.supportsMulticast()));
                for(InterfaceAddress address:n.getInterfaceAddresses()) {
                    String ip=address.getAddress().getHostAddress();int zone=ip.indexOf('%');if(zone>=0) ip=ip.substring(0,zone);
                    out.append(ip).append('/').append(address.getNetworkPrefixLength()).append(' ');
                }
                out.append('\n');
            } catch(java.net.SocketException ignored) {}
        }
        return out.toString();
    }
    @Override public void log(String tag,String line) { /* Deliberately discard unfiltered Go log text. */ }
    @Override public boolean getSyspolicyBooleanValue(String name) throws Exception { throw new NoPolicy(); }
    @Override public String getSyspolicyStringValue(String name) throws Exception { throw new NoPolicy(); }
    @Override public String getSyspolicyStringArrayJSONValue(String name) throws Exception { throw new NoPolicy(); }
    // The Android bridge matches syspolicy.ErrNoSuchKey by exact error text. A different
    // message means a broken policy provider and makes normal prefs edits fail closed.
    private static final class NoPolicy extends Exception { NoPolicy() {super("no such key");} }
    @Override public boolean hardwareAttestationKeySupported() { return false; }
    @Override public String hardwareAttestationKeyCreate() throws Exception { throw new NoPolicy(); }
    @Override public void hardwareAttestationKeyLoad(String id) throws Exception { throw new NoPolicy(); }
    @Override public byte[] hardwareAttestationKeyPublic(String id) throws Exception { throw new NoPolicy(); }
    @Override public void hardwareAttestationKeyRelease(String id) throws Exception { throw new NoPolicy(); }
    @Override public byte[] hardwareAttestationKeySign(String id,byte[] data) throws Exception { throw new NoPolicy(); }
}
