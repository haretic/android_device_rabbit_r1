// Copyright (c) Tailscale Inc & AUTHORS
// Copyright (c) 2026 haretic contributors
// SPDX-License-Identifier: BSD-3-Clause
// LocalAPI and watch order adapted from upstream Client.kt / Notifier.kt. See LICENSE-Tailscale.
package cat.kenny.r1.tunnels.ts;

import android.content.Context;
import android.net.Network;
import cat.kenny.r1.tunnels.Engine;
import cat.kenny.r1.tunnels.Net;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Async UI API. Use thenAcceptAsync/whenCompleteAsync with the UI executor for UI work. */
public final class TailscaleClient {
    public interface Listener { void changed(Engine.Snapshot snapshot); }
    private final TailscaleRuntime runtime;
    private final Engine.BuilderFactory factory;
    private final Listener listener;
    private final ThreadPoolExecutor work=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
        new LinkedBlockingQueue<>(128),r->new Thread(r,"ts-api"));
    private volatile boolean closed;
    private volatile Engine.Snapshot current=new Engine.Update().build();
    private volatile libtailscale.NotificationManager watcher;
    private volatile CompletableFuture<String> browse;
    private String control,exit,exitName;
    private boolean routes,dns;
    TailscaleClient(TailscaleRuntime runtime,Engine.BuilderFactory factory,Net net,Listener listener) {
        this.runtime=runtime;this.factory=factory;this.listener=listener;
        control=net.controlUrl;exit=net.exitNodeId;exitName=net.exitNodeName;routes=net.acceptRoutes;dns=net.acceptDns;
        Engine.Update initial=new Engine.Update();initial.state=Engine.State.STARTING;current=initial.build();
    }
    private final class Task<T> implements Runnable {
        final Callable<T> action;final CompletableFuture<T> future=new CompletableFuture<>();final Runnable cleanup;
        Task(Callable<T> action,Runnable cleanup) {this.action=action;this.cleanup=cleanup;}
        @Override public void run() {
            try {
                if(closed) throw new Engine.Failure(Engine.Error.CONTROL);
                future.complete(action.call());
            } catch(Exception e) { future.completeExceptionally(e instanceof Engine.Failure?e:new Engine.Failure(Engine.Error.API)); }
            finally {cleanup.run();}
        }
        void cancel() {cleanup.run();future.completeExceptionally(new Engine.Failure(Engine.Error.CONTROL));}
    }
    private <T> CompletableFuture<T> submit(Callable<T> action) {return submit(action,()->{});}
    private <T> CompletableFuture<T> submit(Callable<T> action,Runnable cleanup) {
        Task<T> task=new Task<>(action,cleanup);
        try {if(closed) throw new RejectedExecutionException();work.execute(task);}catch(RejectedExecutionException e){task.cancel();}
        return task.future;
    }
    CompletableFuture<Void> initialize(libtailscale.IPNService service) {
        return submit(()->{
            // Initial state + prefs + netmap + engine + initial health + rate limit + no private keys.
            watcher=runtime.app.watchNotifications(415,bytes->{
                if(closed || bytes==null) return;
                if(bytes.length>8*1024*1024) {error(Engine.Error.CALLBACK);return;}
                byte[] owned=bytes.clone();
                submit(()->{
                    JSONObject notification=new JSONObject(new String(owned,StandardCharsets.UTF_8));
                    update(TsJson.notification(notification,current));
                    if(!notification.isNull("NetMap") || !notification.isNull("State")) refresh();
                    return null;
                });
            });
            if(closed) {watcher.stop();watcher=null;throw new Engine.Failure(Engine.Error.CONTROL);}
            raw("PATCH","prefs",TsJson.maskedPrefs(control,exit,routes,dns,true));
            if(closed) {
                raw("PATCH","prefs",new JSONObject().put("WantRunning",false).put("WantRunningSet",true));
                throw new Engine.Failure(Engine.Error.CONTROL);
            }
            libtailscale.Libtailscale.requestVPN(service);
            refresh();return null;
        });
    }
    public Engine.Snapshot snapshot() {return current;}
    public CompletableFuture<Engine.Snapshot> status() {return submit(this::refresh);}
    public CompletableFuture<List<Engine.Peer>> exitNodes() {
        return submit(()->{List<Engine.Peer> nodes=new ArrayList<>();for(Engine.Peer p:refresh().peers) if(p.exitNode) nodes.add(p);return java.util.Collections.unmodifiableList(nodes);});
    }
    public CompletableFuture<Void> setExitNode(String id) { return setExitNode(id,null); }
    /** Preserve the displayed choice even after it disappears from the current peer list. */
    public CompletableFuture<Void> setExitNode(String id,String name) {
        return submit(()->{
            if(id==null || id.length()>256 || id.indexOf('\n')>=0 || name!=null
                    && (name.length()>256 || name.indexOf('\n')>=0)) throw new Engine.Failure(Engine.Error.INVALID_CONFIG);
            String chosen=id.isEmpty()?"":name!=null?name:id.equals(exit)?exitName:"";
            edit(control,id,chosen,routes,dns,true);return null;
        });
    }
    public CompletableFuture<Void> setAcceptRoutes(boolean enabled) {return submit(()->{edit(control,exit,enabled,dns);return null;});}
    public CompletableFuture<Void> setAcceptDns(boolean enabled) {return submit(()->{edit(control,exit,routes,enabled);return null;});}
    public CompletableFuture<Void> setControlUrl(String url) {return submit(()->{edit(url,exit,routes,dns);return null;});}
    private void edit(String server,String node,boolean acceptRoutes,boolean acceptDns) throws Exception {
        edit(server,node,acceptRoutes,acceptDns,true);
    }
    private void edit(String server,String node,boolean acceptRoutes,boolean acceptDns,boolean refresh) throws Exception {
        edit(server,node,node.equals(exit)?exitName:"",acceptRoutes,acceptDns,refresh);
    }
    private void edit(String server,String node,String name,boolean acceptRoutes,boolean acceptDns,boolean refresh) throws Exception {
        try {server=TsJson.controlUrl(server);}catch(IllegalArgumentException e){throw new Engine.Failure(Engine.Error.INVALID_CONFIG);}
        raw("PATCH","prefs",TsJson.maskedPrefs(server,node,acceptRoutes,acceptDns,true));
        factory.saveOptions(server,node,name,acceptRoutes,acceptDns);
        control=server;exit=node;exitName=name;routes=acceptRoutes;dns=acceptDns;if(refresh) refresh();
    }
    /** Future completes with BrowseToURL. Completion/login state continues in snapshot(). */
    public CompletableFuture<String> loginInteractive(String controlUrl) {
        CompletableFuture<String> result=new CompletableFuture<>();
        submit(()->{
            if(browse!=null && !browse.isDone()) browse.completeExceptionally(new Engine.Failure(Engine.Error.CONTROL));
            browse=result;
            Engine.Update u=current.copy();u.loginUrl="";u.loginComplete=false;u.state=Engine.State.AUTHENTICATING;update(u.build());
            edit(controlUrl,exit,routes,dns,false);
            raw("POST","start",new JSONObject());raw("POST","login-interactive",null);
            Engine.Snapshot now=refresh();if(!now.loginUrl.isEmpty()) result.complete(now.loginUrl);
            return null;
        }).whenComplete((ignored,error)->{if(error!=null) result.completeExceptionally(error);});
        return result;
    }
    /** Consumes/wipes the caller's auth-key array immediately and its queued copy on completion. */
    public CompletableFuture<Void> loginWithAuthKey(char[] authKey,String controlUrl) {
        if(authKey==null) {CompletableFuture<Void> f=new CompletableFuture<>();f.completeExceptionally(new Engine.Failure(Engine.Error.INVALID_CONFIG));return f;}
        char[] owned=authKey.clone();Arrays.fill(authKey,'\0');
        return submit(()->{
            if(owned.length==0 || owned.length>4096) throw new Engine.Failure(Engine.Error.INVALID_CONFIG);
            edit(controlUrl,exit,routes,dns,false);
            raw("POST","start",new JSONObject().put("AuthKey",new String(owned)));
            refresh();return null;
        },()->Arrays.fill(owned,'\0'));
    }
    /** Sign out while off. No initialize/requestVPN/IPNService/builder/tun is used.
     * Go's Application has no Close in the pinned binding: stop its transport with
     * WantRunning=false and stop this API worker, retaining only that idle singleton. */
    public static CompletableFuture<Void> logoutOffline(Context context,Net net) {
        final Context app=context.getApplicationContext();
        return CompletableFuture.runAsync(()->{
            final TailscaleRuntime.Lease lease;
            try { lease=TailscaleRuntime.acquire(app); }
            catch(Engine.Failure e) { throw new CompletionException(e); }
            TailscaleClient client=null;
            Engine.Failure failure=null;
            try {
                // No option edits are issued; a factory is unnecessary for logout-only calls.
                client=new TailscaleClient(lease.runtime,null,net,ignored->{});
                final TailscaleClient api=client;
                // LocalAPI has its own request deadline. Do not impose a second wait timeout:
                // an accepted logout can finish late, and its actual result owns deletion.
                api.submit(()->{
                    api.raw("PATCH","prefs",new JSONObject().put("WantRunning",false).put("WantRunningSet",true));
                    api.raw("POST","logout",null);
                    api.update(new Engine.Update().build());
                    return null;
                }).join();
            } catch(CompletionException e) {
                Throwable cause=e.getCause();
                failure=cause instanceof Engine.Failure?(Engine.Failure)cause:new Engine.Failure(Engine.Error.API);
            } catch(RuntimeException e) { failure=new Engine.Failure(Engine.Error.API); }
            finally {
                boolean stopped=true;
                if(client!=null) try { client.stop(); }
                catch(Engine.Failure e) {
                    stopped=false;
                    TailscaleRuntime.retain(client,lease);
                    // Logout's result remains authoritative. Cleanup failure is retried by
                    // the next acquire, not reported as a failed sign-out after it succeeded.
                    android.util.Log.w("R1Tailscale","Offline cleanup retained: "+e.error.name());
                }
                if(stopped) lease.close();
            }
            if(failure!=null) throw new CompletionException(failure);
        });
    }
    public CompletableFuture<Void> logout() {
        return submit(()->{raw("POST","logout",null);Engine.Update u=current.copy();u.loginComplete=false;u.loginUrl="";update(u.build());refresh();return null;});
    }
    void networkChanged(Network network) {
        submit(()->{String iface=runtime.context.updateDns(network);libtailscale.Libtailscale.onDNSConfigChanged(iface);return null;});
    }
    private Engine.Snapshot refresh() throws Exception {
        JSONObject status=raw("GET","status",null);update(TsJson.status(status,current));return current;
    }
    private void update(Engine.Snapshot next) {
        if(closed) return;current=next;listener.changed(next);
        if(browse!=null) {
            if(next.error!=Engine.Error.NONE) browse.completeExceptionally(new Engine.Failure(next.error));
            else if(!next.loginUrl.isEmpty() || next.loginComplete) browse.complete(next.loginUrl);
        }
    }
    void error(Engine.Error error) {
        Engine.Update u=current.copy();u.error=error;u.state=Engine.State.FAILED;current=u.build();
        if(!closed) listener.changed(current);
    }
    private JSONObject raw(String method,String endpoint,JSONObject json) throws Exception {
        byte[] body=json==null?null:json.toString().getBytes(StandardCharsets.UTF_8);
        ByteInput input=body==null?null:new ByteInput(body);
        try {
            libtailscale.LocalAPIResponse response=runtime.app.callLocalAPI(5000,method,"/localapi/v0/"+endpoint,input);
            // In the pinned Android binding BodyInputStream is an unimplemented nil stub.
            // BodyBytes is the supported API (also used by the upstream Kotlin client).
            // Always drain it, including HTTP errors, so Go's net.Pipe writer can finish.
            byte[] bytes=response.bodyBytes();
            if(bytes==null) bytes=new byte[0];
            if(bytes.length>8*1024*1024) throw new Engine.Failure(Engine.Error.API);
            if(response.statusCode()<200 || response.statusCode()>=300) {
                // Never log response bodies, prefs, auth keys, URLs, or backend error text.
                android.util.Log.w("R1Tailscale","LocalAPI " + method + " " + endpoint
                    + " returned HTTP " + response.statusCode());
                throw new Engine.Failure(Engine.Error.API);
            }
            return bytes.length==0 || bytes[0]!='{'?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
        } finally {
            if(input!=null) input.close();if(body!=null) Arrays.fill(body,(byte)0);
        }
    }

    private static final class ByteInput implements libtailscale.InputStream {
        private byte[] bytes;
        ByteInput(byte[] bytes){this.bytes=bytes;}
        @Override public synchronized byte[] read(){byte[] value=bytes;bytes=null;return value;}
        @Override public synchronized void close(){if(bytes!=null) Arrays.fill(bytes,(byte)0);bytes=null;}
    }
    void stop() throws Engine.Failure {
        closed=true;
        if(watcher!=null){watcher.stop();watcher=null;}
        for(Runnable r:work.shutdownNow()) if(r instanceof TailscaleClient.Task) ((Task<?>)r).cancel();
        try {
            if(!work.awaitTermination(7000,TimeUnit.MILLISECONDS)) throw new Engine.Failure(Engine.Error.STOP_TIMEOUT);
            raw("PATCH","prefs",new JSONObject().put("WantRunning",false).put("WantRunningSet",true));
        } catch(Engine.Failure e){throw e;}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Engine.Failure(Engine.Error.STOP_TIMEOUT);}
        catch(Exception e){throw new Engine.Failure(Engine.Error.API);}
        finally {if(browse!=null) browse.completeExceptionally(new Engine.Failure(Engine.Error.CONTROL));}
    }
}
