// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;
import cat.kenny.r1.tunnels.ts.TailscaleClient;
import cat.kenny.r1.tunnels.ts.TailscaleEngine;
import cat.kenny.r1.tunnels.zt.ZeroTierEngine;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** UI access to the service-owned engine. Start/stop via TunnelService, after VPN consent. */
public final class EngineAccess {
    private static volatile Engine active;
    private static final CopyOnWriteArrayList<Subscription> observers=new CopyOnWriteArrayList<>();
    private EngineAccess() {}
    static Engine current() {return active;}
    static synchronized void claim(Engine engine) throws Engine.Failure {
        if(active!=null && active!=engine) throw new Engine.Failure(Engine.Error.STOP_TIMEOUT);
        active=engine;
    }
    static synchronized void release(Engine engine) {if(active==engine) active=null;}
    public static Engine.Snapshot snapshot() {
        Engine.Snapshot s=Status.get().engine;return s==null?new Engine.Update().build():s;
    }
    /** Receive initial/future immutable snapshots on the supplied executor; close on page exit. */
    public static AutoCloseable observe(Executor executor,Consumer<Engine.Snapshot> observer) {
        if(executor==null || observer==null) throw new IllegalArgumentException();
        Subscription s=new Subscription(executor,observer);observers.add(s);Status.watch(true);
        s.deliver(snapshot());return s;
    }
    static void publish(Engine.Snapshot snapshot) {
        if(snapshot==null) snapshot=new Engine.Update().build();
        for(Subscription s:observers) s.deliver(snapshot);
    }
    private static final class Subscription implements AutoCloseable {
        final Executor executor;final Consumer<Engine.Snapshot> observer;final AtomicBoolean closed=new AtomicBoolean();
        Subscription(Executor executor,Consumer<Engine.Snapshot> observer){this.executor=executor;this.observer=observer;}
        void deliver(Engine.Snapshot snapshot) {
            try {executor.execute(()->{if(!closed.get()) observer.accept(snapshot);});}
            catch(RuntimeException e){close();}
        }
        @Override public void close(){if(closed.compareAndSet(false,true)){observers.remove(this);Status.watch(false);}}
    }
    /** null while no Tailscale profile is active or start is still preparing the library. */
    public static TailscaleClient tailscale() {
        Engine engine=active;return engine instanceof TailscaleEngine?((TailscaleEngine)engine).client():null;
    }
    /** Rejoin/leave is queued; selecting another profile remains TunnelService's job. */
    public static ZeroTierEngine zerotier(){Engine engine=active;return engine instanceof ZeroTierEngine?(ZeroTierEngine)engine:null;}
}
