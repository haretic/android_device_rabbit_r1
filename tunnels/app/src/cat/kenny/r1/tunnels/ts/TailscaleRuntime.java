// Copyright (c) Tailscale Inc & AUTHORS
// Copyright (c) 2026 haretic contributors
// SPDX-License-Identifier: BSD-3-Clause
package cat.kenny.r1.tunnels.ts;
import android.content.Context;
import cat.kenny.r1.tunnels.Engine;
import java.io.File;

/** Upstream specifies exactly one Application per process; the binding has no backend Close. */
final class TailscaleRuntime {
    private static TailscaleRuntime instance;
    final libtailscale.Application app;
    final TailscaleContext context;
    private TailscaleRuntime(Context context) throws Engine.Failure {
        this.context=new TailscaleContext(context);
        File dir=new File(context.getFilesDir(),"tailscale");
        if(!dir.isDirectory() && !dir.mkdirs()) throw new Engine.Failure(Engine.Error.STORAGE);
        try {
            android.system.Os.chmod(dir.getPath(),0700);
            go.Seq.setContext(context.getApplicationContext());
            this.context.updateDns(null);
            // Supply the upstream file-ops hook without enabling the optional Taildrop UI.
            libtailscale.Libtailscale.setShareFileHelper(new NoFileSharing());
            android.util.Log.i("R1Tailscale","Starting Go application");
            app=libtailscale.Libtailscale.start(dir.getPath(),"",false,this.context);
            if(app==null) throw new Engine.Failure(Engine.Error.CORE);
            android.util.Log.i("R1Tailscale","Go Start returned application");
        } catch(UnsatisfiedLinkError|NoClassDefFoundError e) { throw new Engine.Failure(Engine.Error.LIBRARY_UNAVAILABLE); }
        catch(Exception e) { throw new Engine.Failure(Engine.Error.STORAGE); }
    }
    private static final class NoFileSharing implements libtailscale.ShareFileHelper {
        private Engine.Failure unsupported(){return new Engine.Failure(Engine.Error.UNSUPPORTED);}
        @Override public void deleteFile(String uri) throws Exception {throw unsupported();}
        @Override public String getFileInfo(String uri) throws Exception {throw unsupported();}
        @Override public String getFileURI(String name) throws Exception {throw unsupported();}
        @Override public String listFilesJSON(String uri) {return "[]";}
        @Override public libtailscale.InputStream openFileReader(String name) throws Exception {throw unsupported();}
        @Override public libtailscale.OutputStream openFileWriter(String name,long offset) throws Exception {throw unsupported();}
        @Override public String renameFile(String from,String to) throws Exception {throw unsupported();}
    }
    private static Lease owner;
    private static TailscaleClient cleanupClient;
    private static Lease cleanupLease;

    /** Keep an offline client reachable until its worker and backend really stop. */
    static synchronized void retain(TailscaleClient client, Lease lease) {
        if (owner != lease || lease.released) throw new IllegalStateException("Not the backend owner");
        cleanupClient = client;
        cleanupLease = lease;
    }
    /** Serializes use of the one process-wide backend, including control-only logout. */
    static final class Lease implements AutoCloseable {
        final TailscaleRuntime runtime;
        private boolean released;
        Lease(TailscaleRuntime runtime) { this.runtime=runtime; }
        @Override public void close() {
            synchronized(TailscaleRuntime.class) {
                if(!released) { released=true; if(owner==this) owner=null; }
            }
        }
    }
    static synchronized Lease acquire(Context context) throws Engine.Failure {
        if(cleanupClient!=null) {
            // The previous offline request may have timed out stopping its worker. Retry
            // before refusing a new owner, without ever running two backend users together.
            cleanupClient.stop();
            Lease lease=cleanupLease;
            cleanupClient=null; cleanupLease=null;
            lease.close();
        }
        if(owner!=null) throw new Engine.Failure(Engine.Error.CONTROL);
        owner=new Lease(get(context));
        return owner;
    }
    static synchronized TailscaleRuntime get(Context context) throws Engine.Failure {
        if(instance==null) instance=new TailscaleRuntime(context.getApplicationContext());return instance;
    }
}
