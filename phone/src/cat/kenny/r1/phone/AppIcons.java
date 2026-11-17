// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The icons of the phone's apps. The phone tells an app's identifier and name, never its icon:
 * that is looked up once in the App Store's public catalogue, by the identifier alone, and kept.
 * Without a network, or for an app the catalogue does not have, there is none.
 */
final class AppIcons {
    private static final int SIZE = 96;
    private static final long RETRY_MS = 24 * 60 * 60 * 1000L;
    private static final int MAX_BYTES = 512 * 1024;

    private final File mDir;
    private final Runnable mArrived;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mFetcher = Executors.newSingleThreadExecutor();
    private final Map<String, Bitmap> mIcons = new HashMap<>();
    private final Set<String> mAsked = new HashSet<>();

    AppIcons(Context context, Runnable arrived) {
        mDir = new File(context.getFilesDir(), "icons");
        mDir.mkdirs();
        mArrived = arrived;
    }

    private static boolean valid(String appId) {
        return appId != null && appId.matches("[A-Za-z0-9.-]{1,155}");
    }

    /** The app's icon, or null while it is not known; then it is asked for, once. */
    Bitmap get(String appId) {
        if (!valid(appId)) {
            return null;
        }
        Bitmap icon = mIcons.get(appId);
        if (icon != null) {
            return icon;
        }
        final File file = new File(mDir, appId + ".png");
        if (file.length() > 0) {
            icon = BitmapFactory.decodeFile(file.getPath());
            if (icon != null) {
                mIcons.put(appId, icon);
                return icon;
            }
        }
        final File none = new File(mDir, appId + ".none");
        if (mAsked.add(appId)
                && System.currentTimeMillis() - none.lastModified() > RETRY_MS) {
            mFetcher.execute(() -> fetch(appId, file, none));
        }
        return null;
    }

    void close() {
        mFetcher.shutdownNow();
    }

    private void fetch(String appId, File file, File none) {
        try {
            final byte[] found = read("https://itunes.apple.com/lookup?bundleId="
                    + URLEncoder.encode(appId, "UTF-8"));
            final JSONObject answer = new JSONObject(new String(found, "UTF-8"));
            if (answer.optInt("resultCount") < 1) {
                // Apple's own apps mostly are not in the catalogue
                none.createNewFile();
                none.setLastModified(System.currentTimeMillis());
                return;
            }
            final String art = answer.getJSONArray("results").getJSONObject(0)
                    .getString("artworkUrl100");
            if (!art.startsWith("https://")) {
                return;
            }
            final byte[] image = read(art);
            final Bitmap raw = BitmapFactory.decodeByteArray(image, 0, image.length);
            if (raw == null) {
                return;
            }
            final Bitmap icon = round(raw);
            try (FileOutputStream out = new FileOutputStream(file)) {
                icon.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
            mMain.post(() -> {
                mIcons.put(appId, icon);
                mArrived.run();
            });
        } catch (Exception e) {
            // No network now: asked again when the service next starts
        }
    }

    private static byte[] read(String url) throws Exception {
        final HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        try (InputStream in = connection.getInputStream()) {
            final ByteArrayOutputStream out = new ByteArrayOutputStream();
            final byte[] buffer = new byte[8192];
            int n;
            while ((n = in.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > MAX_BYTES) {
                    throw new java.io.IOException("too large");
                }
            }
            return out.toByteArray();
        } finally {
            connection.disconnect();
        }
    }

    /** The phone's icon shape: a square with well-rounded corners. */
    private static Bitmap round(Bitmap raw) {
        final Bitmap icon = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(icon);
        final Path shape = new Path();
        shape.addRoundRect(new RectF(0, 0, SIZE, SIZE), SIZE * 0.225f, SIZE * 0.225f,
                Path.Direction.CW);
        canvas.clipPath(shape);
        canvas.drawBitmap(raw, null, new RectF(0, 0, SIZE, SIZE),
                new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG));
        return icon;
    }
}
