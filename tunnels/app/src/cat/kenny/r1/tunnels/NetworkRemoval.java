// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.content.Context;
import android.util.Log;
import cat.kenny.r1.tunnels.ts.TailscaleClient;
import cat.kenny.r1.tunnels.zt.ZeroTierEngine;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicLong;

/** Process-owned removal operations. Pages observe results; they never own completion. */
final class NetworkRemoval {
    private static final Map<String, CompletableFuture<Void>> pending = new HashMap<>();
    private static final AtomicLong revision = new AtomicLong();
    private NetworkRemoval() {}

    /** Lets an already-visible Home rebuild once after a background removal completes. */
    static long revision() { return revision.get(); }

    static synchronized CompletableFuture<Void> pending(String id) {
        return pending.get(id);
    }

    static synchronized CompletableFuture<Void> remove(Context context, Net original) {
        CompletableFuture<Void> existing = pending.get(original.id);
        if (existing != null) return existing;
        final Context app = context.getApplicationContext();
        // Retain only the values the operation needs, not a mutable page-owned profile.
        final Net net = new Net();
        net.id = original.id; net.kind = original.kind; net.networkId = original.networkId;
        net.account = original.account; net.deviceId = original.deviceId;
        final CompletableFuture<Void> result = new CompletableFuture<>();
        pending.put(net.id, result);
        final CompletableFuture<Void> leaving;
        try {
            if (EngineUi.isTailscale(net)) {
                TailscaleClient client = Status.isCurrent(Status.get(), net.id)
                        ? EngineAccess.tailscale() : null;
                leaving = client != null ? client.logout() : TailscaleClient.logoutOffline(app, net);
            } else if (EngineUi.isZeroTier(net)) {
                ZeroTierEngine engine = Status.isCurrent(Status.get(), net.id)
                        ? EngineAccess.zerotier() : null;
                leaving = engine != null ? engine.leave() : CompletableFuture.runAsync(() -> {
                    try { ZeroTierEngine.forgetNetwork(app, net.networkId); }
                    catch (Engine.Failure failure) { throw new CompletionException(failure); }
                });
            } else {
                leaving = CompletableFuture.completedFuture(null);
            }
        } catch (RuntimeException failure) {
            pending.remove(net.id);
            result.completeExceptionally(failure);
            return result;
        }
        leaving.whenCompleteAsync((ignored, failure) -> {
            try {
                if (failure != null) throw new CompletionException(failure);
                boolean current = Status.isCurrent(Status.get(), net.id)
                        || net.id.equals(Store.active(app));
                // The service checks the id again on its worker, protecting a newer selection.
                if (current) TunnelService.stop(app, net.id);
                Store.delete(app, net.id);
                if (Store.load(app, net.id) != null) throw new Engine.Failure(Engine.Error.STORAGE);
                revision.incrementAndGet();
                if (net.isMullvad() && !net.deviceId.isEmpty()) {
                    CompletableFuture.runAsync(() -> {
                        try { new Mullvad(net.account).removeDevice(net.deviceId); }
                        catch (IOException e) {
                            Log.w(Store.TAG, "Mullvad's device not removed: " + e.getClass().getSimpleName());
                        }
                    });
                }
                result.complete(null);
            } catch (Exception error) {
                result.completeExceptionally(error);
            } finally {
                synchronized (NetworkRemoval.class) { pending.remove(net.id, result); }
            }
        });
        return result;
    }
}
