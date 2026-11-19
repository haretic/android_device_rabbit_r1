// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels.zt;

import android.content.Context;
import android.net.Network;
import android.net.VpnService;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import cat.kenny.r1.tunnels.Engine;
import cat.kenny.r1.tunnels.Net;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.FileDescriptor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CompletableFuture;

/** All JNI calls, callbacks, state I/O, ARP/NDP and UDP/tun I/O belong to one loop thread. */
public final class ZeroTierEngine implements Engine, ZtCallbacks {
    private BuilderFactory factory;
    private Net net;
    private volatile Snapshot current=new Update().build();
    private Update status=new Update();
    private volatile boolean running;
    private Thread loop;
    private volatile FileDescriptor wakeWrite;
    private FileDescriptor wakeRead,v4,v6;
    private ParcelFileDescriptor tun;
    private long node,nwid;
    private long[] deadline=new long[1];
    private ZtStateStore store;
    private ZtPlan plan;
    private EthernetBridge bridge;
    private final ConcurrentLinkedQueue<Runnable> commands=new ConcurrentLinkedQueue<>();
    private final ArrayDeque<EthernetBridge.Frame> frames=new ArrayDeque<>();
    private final ArrayDeque<byte[]> toTun=new ArrayDeque<>();
    private final ArrayDeque<String> configs=new ArrayDeque<>();
    private final Set<String> subscriptions=new HashSet<>();
    private boolean portsChanged;
    private final Object commandLock=new Object();
    private volatile CompletableFuture<Void> leaving;
    private boolean left;

    /** This node's public address while off, or null before an identity has been created. */
    public static String nodeAddress(Context context) {
        return ZtStateStore.publicAddress(context.getFilesDir());
    }
    /** Offline removal never loads JNI and never reads or removes either identity file. */
    public static void forgetNetwork(Context context,String networkId) throws Failure {
        final long id;
        try { id=ZtAddress.networkId(networkId); }
        catch(IllegalArgumentException e) { throw new Failure(Error.INVALID_CONFIG); }
        try { ZtStateStore.removeNetwork(context.getFilesDir(),id); }
        catch(ZtStateStore.StorageFailure e) { throw new Failure(Error.STORAGE); }
    }

    @Override public void start(Net net,BuilderFactory factory) throws Failure {
        if(loop!=null) throw new Failure(Error.INVALID_CONFIG);
        try { nwid=ZtAddress.networkId(net.networkId); }
        catch(IllegalArgumentException e) { throw new Failure(Error.INVALID_CONFIG); }
        this.factory=factory; this.net=net;
        status=new Update(); leaving=null; left=false; portsChanged=false;
        subscriptions.clear(); deadline[0]=0;
        try {
            FileDescriptor[] pipes=Os.pipe();
            try {
                for(FileDescriptor fd:pipes) {
                    Os.fcntlInt(fd,OsConstants.F_SETFL,OsConstants.O_NONBLOCK);
                    Os.fcntlInt(fd,OsConstants.F_SETFD,OsConstants.FD_CLOEXEC);
                }
            } catch(ErrnoException e) {close(pipes[0]);close(pipes[1]);throw e;}
            wakeRead=pipes[0]; wakeWrite=pipes[1];
        } catch(ErrnoException e) { throw new Failure(Error.IO); }
        status.state=State.STARTING; status.networkState=NetworkState.REQUESTING_CONFIGURATION;
        current=status.build(); running=true;
        loop=new Thread(this::run,"zt-loop"); loop.start();
    }
    /** Rejoin the configured network only; selecting a different saved network is the service's job. */
    public void join(String networkId) throws Failure {
        try { if(ZtAddress.networkId(networkId)!=nwid) throw new IllegalArgumentException(); }
        catch(IllegalArgumentException e) { throw new Failure(Error.INVALID_CONFIG); }
        enqueue(()->check(ZtNode.join(node,nwid)));
    }
    /** Leave on the node loop, then stop it and erase membership. Completion includes cleanup.
     * A concurrent stop drains this accepted leave before deleting the node. */
    public CompletableFuture<Void> leave() {
        synchronized(commandLock) {
            if(leaving!=null) return leaving;
            CompletableFuture<Void> result=new CompletableFuture<>();
            if(!running) { result.completeExceptionally(new Failure(Error.CONTROL)); return result; }
            leaving=result;
            commands.add(()->{ check(ZtNode.leave(node,nwid)); left=true; running=false; });
            signal();
            return result;
        }
    }
    @Override public void onUnderlyingNetwork(Network network) { enqueue(()->portsChanged=true); }
    @Override public void onLinkPropertiesChanged(Network network) {onUnderlyingNetwork(network);}
    @Override public void wake() { enqueue(()->deadline[0]=0); }
    @Override public Snapshot snapshot() { return current; }
    private void enqueue(Runnable r) {
        synchronized(commandLock) { if(running && leaving==null) { commands.add(r); signal(); } }
    }
    private void signal() {
        FileDescriptor fd=wakeWrite;
        if(fd!=null) try { Os.write(fd,new byte[]{1},0,1); } catch(Exception ignored) {}
    }
    @Override public synchronized void stop() throws Failure {
        synchronized(commandLock) { running=false; signal(); }
        if(loop!=null) {
            try { loop.join(5000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); throw new Failure(Error.STOP_TIMEOUT); }
            if(loop.isAlive()) throw new Failure(Error.STOP_TIMEOUT);
            loop=null;
        }
    }
    private static final class CoreFailure extends RuntimeException {}
    private static void check(int result) { if(result!=0) throw new CoreFailure(); }
    private void run() {
        try {
            store=new ZtStateStore(factory.context().getFilesDir());
            openPorts(); node=ZtNode.nodeNew(this,SystemClock.elapsedRealtime());
            if(node==0) throw new CoreFailure();
            advertiseLocalAddresses();
            status.nodeAddress=String.format(Locale.ROOT,"%010x",ZtNode.address(node));
            check(ZtNode.join(node,nwid)); deadline[0]=0;
            long queryAt=0;
            byte[] packet=new byte[65535];
            while(running || !commands.isEmpty()) {
                Runnable command; while((command=commands.poll())!=null) command.run();
                if(!running) break;
                if(portsChanged) { openPorts(); portsChanged=false; deadline[0]=0; }
                long now=SystemClock.elapsedRealtime();
                if(deadline[0]<=now) check(ZtNode.processBackgroundTasks(node,now,deadline));
                while(!configs.isEmpty()) configure(configs.removeFirst());
                flushFrames(); flushTun();
                if(now>=queryAt) { queryStatus(); queryAt=now+2000; }
                List<StructPollfd> polls=new ArrayList<>();
                addPoll(polls,wakeRead,false); addPoll(polls,v4,false); addPoll(polls,v6,false);
                if(tun!=null) addPoll(polls,tun.getFileDescriptor(),!toTun.isEmpty());
                int wait=(int)Math.max(1,Math.min(250,deadline[0]-SystemClock.elapsedRealtime()));
                try { Os.poll(polls.toArray(new StructPollfd[0]),wait); }
                catch(ErrnoException e) { if(e.errno==OsConstants.EINTR) continue; throw e; }
                for(StructPollfd p:polls) {
                    if((p.revents&(OsConstants.POLLERR|OsConstants.POLLHUP|OsConstants.POLLNVAL))!=0) throw new CoreFailure();
                    if((p.revents&OsConstants.POLLIN)==0) continue;
                    if(p.fd==wakeRead) { try { Os.read(wakeRead,packet,0,packet.length); } catch(ErrnoException e) { if(e.errno!=OsConstants.EAGAIN) throw e; } }
                    else if(p.fd==v4 || p.fd==v6) receive(p.fd,packet);
                    else if(tun!=null && p.fd==tun.getFileDescriptor()) {
                        try {
                            int n=Os.read(p.fd,packet,0,packet.length);
                            if(n<=0) throw new CoreFailure();
                            if(bridge!=null) apply(bridge.fromTun(Arrays.copyOf(packet,n),SystemClock.elapsedRealtime()));
                        } catch(ErrnoException e) { if(e.errno!=OsConstants.EAGAIN) throw e; }
                    }
                }
            }
        } catch(ZtStateStore.StorageFailure e) { fail(Error.STORAGE); }
        catch(UnsatisfiedLinkError|NoClassDefFoundError e) { fail(Error.LIBRARY_UNAVAILABLE); }
        catch(Engine.Failure e) { fail(e.error); }
        catch(IllegalArgumentException e) { fail(Error.INVALID_CONFIG); }
        catch(org.json.JSONException e) { fail(Error.CORE); }
        catch(Exception e) { fail(e instanceof CoreFailure?Error.CORE:Error.IO); }
        finally {
            synchronized(commandLock) { running=false; }
            if(node!=0) { try { ZtNode.nodeDelete(node); } catch(RuntimeException ignored) {} node=0; }
            closeTun(); close(v4); close(v6); close(wakeRead); close(wakeWrite);
            v4=null;v6=null;wakeRead=null;wakeWrite=null;
            commands.clear(); frames.clear(); configs.clear(); toTun.clear();
            if(left) {
                try { ZtStateStore.removeNetwork(factory.context().getFilesDir(),nwid); }
                catch(ZtStateStore.StorageFailure e) { fail(Error.STORAGE); }
            }
            if(status.state!=State.FAILED) { status.state=State.STOPPED; publish(); }
            CompletableFuture<Void> result=leaving;
            if(result!=null) {
                if(left && status.state!=State.FAILED) result.complete(null);
                else result.completeExceptionally(new Failure(status.error==Error.NONE?Error.CONTROL:status.error));
            }
        }
    }
    private void receive(FileDescriptor fd,byte[] bytes) throws Exception {
        for(int count=0;count<32;count++) {
            InetSocketAddress remote=new InetSocketAddress(0);
            int n;
            try { n=Os.recvfrom(fd,bytes,0,bytes.length,0,remote); }
            catch(ErrnoException e) { if(e.errno==OsConstants.EAGAIN) return; throw e; }
            if(n>0) check(ZtNode.processWirePacket(node,SystemClock.elapsedRealtime(),fd==v4?4:6,
                remote.getAddress().getAddress(),remote.getPort(),Arrays.copyOf(bytes,n),deadline));
        }
    }
    private static void addPoll(List<StructPollfd> out,FileDescriptor fd,boolean write) {
        if(fd==null) return; StructPollfd p=new StructPollfd(); p.fd=fd;
        p.events=(short)(OsConstants.POLLIN|(write?OsConstants.POLLOUT:0)); out.add(p);
    }
    private void openPorts() throws Exception {
        close(v4);close(v6); v4=null;v6=null;
        try { v4=port(OsConstants.AF_INET,new byte[4]); } catch(ErrnoException ignored) {}
        try { v6=port(OsConstants.AF_INET6,new byte[16]); } catch(ErrnoException ignored) {}
        if(v4==null && v6==null) throw new CoreFailure();
        if(node!=0) advertiseLocalAddresses();
    }
    private void advertiseLocalAddresses() throws Exception {
        ZtNode.clearLocalInterfaceAddresses(node);
        android.net.ConnectivityManager cm=factory.context().getSystemService(android.net.ConnectivityManager.class);
        Set<String> excluded=new HashSet<>();
        for(Network n:cm.getAllNetworks()) {
            android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(n);
            android.net.LinkProperties links=cm.getLinkProperties(n);
            if(caps!=null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) && links!=null)
                excluded.add(links.getInterfaceName());
        }
        java.util.Enumeration<java.net.NetworkInterface> interfaces=java.net.NetworkInterface.getNetworkInterfaces();
        if(interfaces==null) return;
        for(java.net.NetworkInterface iface:java.util.Collections.list(interfaces)) {
            if(!iface.isUp() || iface.isLoopback() || excluded.contains(iface.getName())) continue;
            for(InetAddress ip:java.util.Collections.list(iface.getInetAddresses())) {
                if(ip.isAnyLocalAddress() || ip.isLoopbackAddress() || ip.isLinkLocalAddress()
                        || ip.isMulticastAddress() || plan!=null && plan.owns(ip.getAddress())) continue;
                FileDescriptor fd=ip.getAddress().length==4?v4:v6;if(fd==null) continue;
                int port=((InetSocketAddress)Os.getsockname(fd)).getPort();
                ZtNode.addLocalInterfaceAddress(node,ip.getAddress(),port);
            }
        }
    }
    private static FileDescriptor port(int family,byte[] any) throws Exception {
        FileDescriptor fd=Os.socket(family,OsConstants.SOCK_DGRAM|OsConstants.SOCK_NONBLOCK|OsConstants.SOCK_CLOEXEC,OsConstants.IPPROTO_UDP);
        try {
            if(family==OsConstants.AF_INET6) Os.setsockoptInt(fd,OsConstants.IPPROTO_IPV6,OsConstants.IPV6_V6ONLY,1);
            Os.bind(fd,InetAddress.getByAddress(any),0); return fd;
        } catch(Exception e) { close(fd); throw e; }
    }
    private static void close(FileDescriptor fd) { if(fd!=null) try { Os.close(fd); } catch(ErrnoException ignored) {} }
    @Override public int wireSend(long local,byte[] addr,int port,byte[] data,int ttl) {
        FileDescriptor fd=addr.length==4?v4:v6;
        if(fd==null || local>=0 && local!=(addr.length==4?4:6)) return -1;
        try {
            // The entire app UID is excluded by the common Builder, including these sockets.
            // protect() is not needed for ZeroTier's transport. C API permits ignoring TTL hints.
            return Os.sendto(fd,data,0,data.length,0,InetAddress.getByAddress(addr),port)==data.length?0:-1;
        } catch(Exception e) { return -1; }
    }
    @Override public byte[] stateGet(int type,long[] id) { return store.get(type,id); }
    @Override public void statePut(int type,long[] id,byte[] data) { store.put(type,id,data); }
    @Override public void frame(long network,long src,long dst,int type,int vlan,byte[] data) {
        if(network==nwid && vlan==0 && bridge!=null) apply(bridge.fromEthernet(new EthernetBridge.Frame(src,dst,type,data),SystemClock.elapsedRealtime()));
    }
    private void apply(EthernetBridge.Result r) {
        for(EthernetBridge.Frame f:r.frames) { if(frames.size()<256) frames.add(f); }
        for(byte[] p:r.packets) { if(toTun.size()<256) toTun.add(p); }
    }
    private void flushFrames() {
        // Never reenter node from a callback: process the queued replies only after JNI returns.
        for(int i=0;i<256 && !frames.isEmpty();i++) {
            EthernetBridge.Frame f=frames.removeFirst();
            check(ZtNode.processFrame(node,SystemClock.elapsedRealtime(),nwid,f.source,f.destination,f.etherType,0,f.data(),deadline));
            status.tx+=f.data().length;
        }
    }
    private void flushTun() throws Exception {
        while(tun!=null && !toTun.isEmpty()) {
            byte[] p=toTun.peekFirst();
            try {
                if(Os.write(tun.getFileDescriptor(),p,0,p.length)!=p.length) throw new CoreFailure();
                toTun.removeFirst(); status.rx+=p.length;
            } catch(ErrnoException e) { if(e.errno==OsConstants.EAGAIN) return; throw e; }
        }
    }
    @Override public int networkConfig(long network,int op,String json) {
        if(network!=nwid) return 0;
        if(configs.size()>=32) configs.removeFirst();
        configs.add(op==3 || op==4?"null":json); return 0;
    }
    @Override public void event(int code,byte[] payload) {
        if(code==1 || code==3) {
            status.online=false;
            android.util.Log.i("R1ZeroTier",code==1?"Node up":"Node offline");
        }
        if(code==2) { status.online=true;android.util.Log.i("R1ZeroTier","Node online"); }
        if(code==4) throw new CoreFailure();
        // TRACE/remote/user messages can contain secrets or untrusted content: discard them.
    }
    private void configure(String json) throws Exception {
        if(json==null || json.equals("null")) { closeTun(); status.state=State.STARTING; return; }
        JSONObject c=new JSONObject(json);
        status.networkName=c.optString("name","");
        NetworkState previous=status.networkState;
        try { status.networkState=NetworkState.valueOf(c.optString("statusName","UNKNOWN")); }
        catch(IllegalArgumentException e) { status.networkState=NetworkState.UNKNOWN; }
        if(status.networkState!=previous) android.util.Log.i("R1ZeroTier","Network state="+status.networkState.name());
        if(status.networkState!=NetworkState.OK) {
            closeTun(); status.addresses.clear();
            status.state=status.networkState==NetworkState.ACCESS_DENIED?State.WAITING_APPROVAL
                :status.networkState==NetworkState.AUTHENTICATION_REQUIRED?State.AUTHENTICATING:State.STARTING;
            publish(); return;
        }
        List<ZtPlan.Prefix> addresses=new ArrayList<>(); List<ZtPlan.Route> routes=new ArrayList<>();
        JSONArray a=c.optJSONArray("assignedAddresses"),r=c.optJSONArray("routes");
        if(a!=null) for(int i=0;i<a.length();i++) { JSONObject p=a.getJSONObject(i); addresses.add(new ZtPlan.Prefix(p.getString("address"),p.getInt("prefix"))); }
        if(r!=null) for(int i=0;i<r.length();i++) { JSONObject p=r.getJSONObject(i); routes.add(new ZtPlan.Route(new ZtPlan.Prefix(p.getString("target"),p.getInt("prefix")),p.optString("via",""))); }
        List<String> dns=new ArrayList<>(); JSONObject d=c.optJSONObject("dns");
        if(d!=null && d.optJSONArray("servers")!=null) for(int i=0;i<d.getJSONArray("servers").length();i++) dns.add(d.getJSONArray("servers").getString(i));
        ZtPlan next=new ZtPlan(addresses,routes,dns,d==null?"":d.optString("domain",""),c.getInt("mtu"),Long.parseUnsignedLong(c.getString("mac"),16),c.optBoolean("broadcastEnabled",true),net.acceptRoutes,net.acceptDns);
        status.addresses=next.addressStrings();
        if(next.addresses.isEmpty()) { closeTun(); status.state=State.STARTING; publish(); return; }
        if(plan==null || !plan.signature().equals(next.signature())) {
            VpnService.Builder b=factory.create().setMtu(next.mtu).setBlocking(false);
            for(ZtPlan.Prefix p:next.addresses) b.addAddress(p.address,p.bits);
            for(ZtPlan.Route route:next.routes) b.addRoute(route.target.address,route.target.bits);
            for(String server:next.dns) b.addDnsServer(server);
            if(!next.domain.isEmpty()) b.addSearchDomain(next.domain);
            ParcelFileDescriptor replacement=b.establish();
            if(replacement==null) throw new Failure(Error.NO_CONSENT);
            closeTun(); tun=replacement; plan=next; bridge=new EthernetBridge(nwid,next);
            updateSubscriptions(next);
            advertiseLocalAddresses();
        }
        status.state=State.RUNNING; publish();
    }
    private void updateSubscriptions(ZtPlan next) {
        Set<String> wanted=new HashSet<>();
        if(next.broadcast) wanted.add("ffffffffffff:0");
        wanted.add("333300000001:0");
        for(ZtPlan.Prefix p:next.addresses) {
            byte[] ip=ZtAddress.ip(p.address);
            wanted.add(ip.length==4?"ffffffffffff:"+ZtAddress.v4(ip)
                :String.format(Locale.ROOT,"%012x:0",ZtAddress.multicastMac(ZtAddress.solicitedNode(ip))));
        }
        for(String s:subscriptions) if(!wanted.contains(s)) subscription(s,false);
        for(String s:wanted) if(!subscriptions.contains(s)) subscription(s,true);
        subscriptions.clear();subscriptions.addAll(wanted);
    }
    private void subscription(String value,boolean add) {
        String[] s=value.split(":"); long mac=Long.parseUnsignedLong(s[0],16),adi=Long.parseLong(s[1]);
        check(add?ZtNode.multicastSubscribe(node,nwid,mac,adi):ZtNode.multicastUnsubscribe(node,nwid,mac,adi));
    }
    private void queryStatus() throws Exception {
        JSONObject n=new JSONObject(ZtNode.statusJson(node)); status.online=n.optBoolean("online");
        JSONArray networks=new JSONArray(ZtNode.networksJson(node));
        for(int i=0;i<networks.length();i++) { JSONObject c=networks.getJSONObject(i); if(Long.parseUnsignedLong(c.getString("nwid"),16)==nwid) configure(c.toString()); }
        JSONArray peers=new JSONArray(ZtNode.peersJson(node)); status.peers=new ArrayList<>(); status.relayed=false;
        for(int i=0;i<peers.length();i++) {
            JSONObject p=peers.getJSONObject(i); JSONArray paths=p.optJSONArray("paths"); List<String> addresses=new ArrayList<>();
            if(paths!=null) for(int j=0;j<paths.length();j++) { JSONObject path=paths.getJSONObject(j); if(!path.optBoolean("expired")) addresses.add(path.optString("address")); }
            Path path=!addresses.isEmpty()?Path.DIRECT:status.online && p.optInt("role")==0?Path.RELAY:Path.UNREACHABLE;
            status.relayed|=path==Path.RELAY;
            status.peers.add(new Peer(p.getString("address"),"",addresses,path,p.optLong("latency",-1),false));
        }
        publish();
    }
    private void closeTun() {
        if(tun!=null) try { tun.close(); } catch(Exception ignored) {}
        tun=null; plan=null; bridge=null; frames.clear();toTun.clear();
    }
    private void fail(Error error) { status.state=State.FAILED;status.error=error;publish(); }
    private void publish() { current=status.build(); factory.publish(current); }
}
