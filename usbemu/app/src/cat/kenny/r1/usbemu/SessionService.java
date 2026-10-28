// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.service.quicksettings.TileService;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Port of UsbMode's foreground session lifecycle and image operations. */
public final class SessionService extends Service {
    private static final String CHANNEL = "usb_devices";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private volatile boolean cancel;
    private volatile String activeToken = "";
    private int queued;
    private volatile boolean stopping;
    private String lastSession = "";
    private final Runnable monitor = new Runnable() {
        @Override public void run() {
            String session = Control.prop("st.session");
            if (!session.equals(lastSession)) {
                lastSession = session;
                notifySession();
                TileService.requestListeningState(SessionService.this,
                        new ComponentName(SessionService.this, UsbTileService.class));
            }
            if (queued == 0 && !Control.running()) {
                stopForeground(STOP_FOREGROUND_REMOVE); stopSelf();
            } else handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate() {
        super.onCreate();
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW));
        startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        // Resume observation after process death; root operations are journaled
        // independently of this process. Do not replay an image mutation.
        if (Control.busy(this)) {
            String pending = Control.prefs(this).getString("activeToken", "");
            queued++;
            worker.execute(() -> {
                String error = "";
                try {
                    if (!pending.isEmpty()) await(pending, 150000, false);
                    else error = "interrupted";
                    recoverImport();
                } catch (Exception e) { error = "interrupted"; }
                complete(error);
            });
        } else worker.execute(this::recoverImport);
        handler.postDelayed(monitor, 500);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (android.os.UserHandle.myUserId() != 0) { stopSelf(); return START_NOT_STICKY; }
        if (intent == null) { notifySession(); return START_STICKY; }
        final String action = intent.getAction();
        if ("observe".equals(action)) return START_STICKY;
        if ("cancel".equals(action) || "stop".equals(action)) {
            cancel = true;
            if (!activeToken.isEmpty()) SystemProperties.set(Control.PREFIX + "req.cancel", activeToken);
            if ("cancel".equals(action)) return START_STICKY;
            stopping = true;
            Control.prefs(this).edit().putBoolean("busy", true).putString("job", "stop").apply();
            final String token = token();
            SystemProperties.set(Control.PREFIX + "req.stop", token);
            queued++;
            worker.execute(() -> {
                String error = "";
                try { await(token, 30000, false); } catch (IOException e) { error = e.getMessage(); }
                complete(error);
                handler.post(() -> stopping = false);
            });
            return START_STICKY;
        }
        if (Control.busy(this) || stopping) return START_STICKY;
        queued++;
        cancel = false;
        Control.prefs(this).edit().putBoolean("busy", true).putString("job", action)
                .putString("error", "").putInt("importPercent", 0)
                .putString("jobImage", intent.getStringExtra("image"))
                .putString("jobFs", intent.getStringExtra("fs"))
                .putInt("jobSize", intent.getIntExtra("size", 4)).apply();
        worker.execute(() -> {
            String error = "";
            try {
                if (!Control.hasFolder(this)) throw new IOException("folder");
                switch (action) {
                    case "import": importImage(intent.getData()); call("scan", null, true, 0, "", false); break;
                    case "create":
                        call("create", intent.getStringExtra("image"), false,
                                intent.getIntExtra("size", 4), intent.getStringExtra("fs"), false);
                        call("scan", null, true, 0, "", false);
                        if (intent.getBooleanExtra("use", false) && !cancel) {
                            Images image = Images.inventory().stream().filter(i -> i.name.equals(intent.getStringExtra("image")))
                                    .findFirst().orElseThrow(() -> new IOException("changed"));
                            callImage("start", image, false);
                        }
                        break;
                    case "start": case "swap":
                        Images image = new Images(intent.getStringExtra("image"), intent.getStringExtra("stamp"),
                                intent.getBooleanExtra("ro", true));
                        callImage(action, image, image.readOnly); break;
                    case "scan": call("scan", null, true, 0, "", false); break;
                    default: throw new IOException("request");
                }
            } catch (Exception e) { error = cancel ? "cancelled" : (e.getMessage() == null ? "io" : e.getMessage()); }
            complete(error);
        });
        return START_STICKY;
    }

    private void complete(String error) {
        handler.post(() -> {
            queued--;
            Control.prefs(this).edit().putBoolean("busy", queued > 0).putString("error", error)
                    .putLong("completion", Control.prefs(this).getLong("completion", 0) + 1).apply();
            notifySession();
            TileService.requestListeningState(this, new ComponentName(this, UsbTileService.class));
        });
    }
    private void callImage(String op, Images image, boolean ro) throws IOException {
        call(op, image.name, ro, 0, image.stamp, "swap".equals(op));
    }
    private synchronized String token() {
        long next = Math.max(System.currentTimeMillis() * 1000L,
                Control.prefs(this).getLong("lastToken", 0) + 1);
        if (!Control.prefs(this).edit().putLong("lastToken", next).commit()) throw new IllegalStateException("io");
        return Long.toString(next);
    }
    private void call(String op, String name, boolean ro, int size, String fsOrStamp, boolean force) throws IOException {
        if (cancel) throw new IOException("cancelled");
        String token = token();
        activeToken = token;
        Control.prefs(this).edit().putString("rootOp", op).putString("activeToken", token).apply();
        String payload = Control.folder(this) + "\n" + (name == null ? "" : name) + "\n"
                + (ro ? "1" : "0") + "\n" + ("create".equals(op) ? "" : fsOrStamp) + "\n"
                + size + "\n" + ("create".equals(op) ? fsOrStamp : "") + "\n" + (force ? "1" : "0") + "\n1\n";
        if (payload.chars().filter(c -> c == '\n').count() != 8) throw new IOException("request");
        File request = new File("/data/system/r1-usbemu/requests", token);
        try (FileOutputStream out = new FileOutputStream(request)) {
            out.write(payload.getBytes(StandardCharsets.UTF_8)); out.getFD().sync();
        }
        try { android.system.Os.chmod(request.getPath(), 0640); }
        catch (android.system.ErrnoException e) { throw new IOException("request", e); }
        SystemProperties.set(Control.PREFIX + "req.run", token + ":" + op);
        try { await(token, "create".equals(op) ? 150000 : 30000, true); }
        finally { activeToken = ""; Control.prefs(this).edit().remove("activeToken").apply(); Files.deleteIfExists(request.toPath()); }
    }
    private void await(String token, long timeout, boolean cancellable) throws IOException {
        long deadline = SystemClock.elapsedRealtime() + timeout;
        boolean accepted = false;
        long retryAt = SystemClock.elapsedRealtime() + 1000;
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cancellable && cancel) {
                SystemProperties.set(Control.PREFIX + "req.cancel", token);
                // Wait for root to finish cancelling before another operation.
            }
            String result = Control.prop("st.result");
            String stop = Control.prop("req.stop");
            if (!stop.equals(token) && result.startsWith(stop + ":")
                    && (result.endsWith(":done") || result.endsWith(":error"))) {
                try {
                    if (Long.parseLong(stop) > Long.parseLong(token)) throw new IOException("cancelled");
                } catch (NumberFormatException ignored) { }
            }
            if (result.startsWith(token + ":")) {
                accepted = true;
                if (result.equals(token + ":done")) return;
                if (result.equals(token + ":error")) throw new IOException(Control.prop("st.error"));
                if (result.equals(token + ":cancelled")) throw new IOException("cancelled");
            }
            if (cancellable && stopping && !Control.prop("req.stop").isEmpty()
                    && !result.startsWith(token + ":")) throw new IOException("cancelled");
            // init may still be reaping the previous oneshot when its next
            // property arrives. Retry only until this token is acknowledged.
            if (cancellable && !accepted && SystemClock.elapsedRealtime() >= retryAt) {
                SystemProperties.set(Control.PREFIX + "req.run", token + ":" + Control.prefs(this).getString("rootOp", "scan"));
                retryAt += 1000;
            }
            SystemClock.sleep(100);
        }
        if (cancellable) SystemProperties.set(Control.PREFIX + "req.cancel", token);
        throw new IOException("hal_timeout");
    }

    private void importImage(Uri source) throws IOException {
        if (source == null) throw new IOException("image");
        String name = "";
        long total = -1;
        try (Cursor c = getContentResolver().query(source,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}, null, null, null)) {
            if (c != null && c.moveToFirst()) { name = c.getString(0); total = c.isNull(1) ? -1 : c.getLong(1); }
        }
        if (!Images.imageName(name)) throw new IOException("name");
        final String targetName = name;
        if (Images.inventory().stream().anyMatch(i -> i.name.equalsIgnoreCase(targetName))) throw new IOException("exists");
        long free = Images.free();
        if (total > free - Images.GB) throw new IOException("space");
        Uri tree = Control.tree(this);
        Uri parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
        Uri destination = null;
        String temporaryName = ".r1-usbemu-" + token() + ".importing";
        // Journal the planned SAF name before creating it, and the returned URI
        // before copying. A restart removes only this unfinished temporary file.
        if (!Control.prefs(this).edit().putString("importName", temporaryName)
                .putString("importTree", tree.toString()).commit()) throw new IOException("io");
        boolean saved = false;
        try {
            destination = DocumentsContract.createDocument(getContentResolver(), parent,
                    "application/octet-stream", temporaryName);
            if (destination == null) throw new IOException("io");
            if (!Control.prefs(this).edit().putString("importUri", destination.toString()).commit()) throw new IOException("io");
            long copied = 0;
            try (InputStream in = getContentResolver().openInputStream(source);
                    android.os.ParcelFileDescriptor fd = getContentResolver().openFileDescriptor(destination, "w")) {
                if (in == null || fd == null) throw new IOException("io");
                try (OutputStream out = new android.os.ParcelFileDescriptor.AutoCloseOutputStream(fd)) {
                    byte[] buffer = new byte[1024 * 1024]; int count;
                    while ((count = in.read(buffer)) != -1) {
                        if (cancel) throw new IOException("cancelled");
                        if (copied + count > free - Images.GB) throw new IOException("space");
                        out.write(buffer, 0, count); copied += count;
                        Control.prefs(this).edit().putInt("importPercent", total > 0
                                ? (int) Math.min(99, copied * 100 / total) : -1).apply();
                    }
                    out.flush(); fd.getFileDescriptor().sync();
                }
            }
            if (total >= 0 && copied != total) throw new IOException("changed");
            if (copied <= 0 || copied % 512 != 0 || (name.toLowerCase(java.util.Locale.ROOT).endsWith(".iso")
                    && !Images.cdSize(copied))) throw new IOException("size");
            if (cancel) throw new IOException("cancelled");
            Uri renamed = DocumentsContract.renameDocument(getContentResolver(), destination, name);
            if (renamed == null) throw new IOException("io");
            destination = renamed;
            try (Cursor c = getContentResolver().query(destination,
                    new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                if (c == null || !c.moveToFirst() || !name.equals(c.getString(0))) throw new IOException("exists");
            }
            saved = true;
        } finally {
            if (!saved && destination != null) {
                try { DocumentsContract.deleteDocument(getContentResolver(), destination); }
                catch (Exception ignored) { }
            }
            Control.prefs(this).edit().remove("importName").remove("importTree").remove("importUri").commit();
        }
    }
    private void recoverImport() {
        String name = Control.prefs(this).getString("importName", "");
        String treeText = Control.prefs(this).getString("importTree", "");
        if (!name.matches("\\.r1-usbemu-[0-9]+\\.importing") || treeText.isEmpty()) return;
        try {
            Uri tree = Uri.parse(treeText);
            Control.primaryPath(tree);
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree));
            try (Cursor c = getContentResolver().query(children, new String[] {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID, OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (name.equals(c.getString(1))) DocumentsContract.deleteDocument(getContentResolver(),
                            DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)));
                }
            }
            Control.prefs(this).edit().remove("importName").remove("importTree").remove("importUri").commit();
        } catch (Exception ignored) { /* Keep the journal until access is restored. */ }
    }
    private Notification notification() {
        PendingIntent open = PendingIntent.getActivity(this, 0, new Intent(this, UsbActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1, new Intent(this, SessionService.class).setAction("stop"),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String[] session = Images.session();
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_usb)
                .setContentTitle(getString(R.string.app_name)).setContentText(Control.running()
                        ? getString(R.string.tile_running, session[1]) : getString(R.string.preparing))
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build();
    }
    private void notifySession() { getSystemService(NotificationManager.class).notify(1, notification()); }
    @Override public void onDestroy() { handler.removeCallbacks(monitor); worker.shutdown(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
