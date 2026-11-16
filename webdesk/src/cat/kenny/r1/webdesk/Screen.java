// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.webdesk;

import static android.view.Display.DEFAULT_DISPLAY;

import android.graphics.Bitmap;
import android.os.RemoteException;
import android.view.WindowManagerGlobal;
import android.window.ScreenCaptureInternal;
import android.window.ScreenCaptureInternal.ScreenshotHardwareBuffer;
import android.window.ScreenCaptureInternal.SynchronousScreenCaptureListener;

import cat.kenny.r1.webdesk.http.Response;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.zip.CRC32;

/** The r1's screen as pictures: a stream of them for the page, and one as a screenshot. */
final class Screen {

    private static final String BOUNDARY = "r1frame";
    /** Between two looks at the screen: about ten pictures a second while it changes */
    private static final long LOOK_MS = 90;
    /** A picture at least this often, so a quiet connection is not taken for a dead one */
    private static final long STILL_MS = 1500;

    /** The screen as it is composed, in a bitmap that can be read; null when it can't be had. */
    private static Bitmap capture() {
        final SynchronousScreenCaptureListener listener =
                ScreenCaptureInternal.createSyncCaptureListener();
        try {
            WindowManagerGlobal.getWindowManagerService().captureDisplay(DEFAULT_DISPLAY, null,
                    listener);
        } catch (RemoteException e) {
            return null;
        }
        final ScreenshotHardwareBuffer buffer = listener.getBuffer();
        final Bitmap hardware = buffer == null ? null : buffer.asBitmap();
        if (hardware == null) {
            return null;
        }
        final Bitmap bitmap = hardware.copy(Bitmap.Config.ARGB_8888, false);
        hardware.recycle();
        buffer.getHardwareBuffer().close();
        if (bitmap != null) {
            bitmap.setHasAlpha(false);
        }
        return bitmap;
    }

    byte[] png() {
        final Bitmap bitmap = capture();
        if (bitmap == null) {
            return null;
        }
        final ByteArrayOutputStream out = new ByteArrayOutputStream(256 * 1024);
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        bitmap.recycle();
        return out.toByteArray();
    }

    /** Pictures for as long as the computer may have them and takes them. */
    void stream(Response response, BooleanSupplier allowed) throws IOException {
        final OutputStream out = response.stream("multipart/x-mixed-replace; boundary="
                + BOUNDARY);
        final ByteArrayOutputStream jpeg = new ByteArrayOutputStream(128 * 1024);
        final CRC32 crc = new CRC32();
        long last = -1;
        long sent = 0;
        while (allowed.getAsBoolean()) {
            final long began = System.currentTimeMillis();
            final Bitmap bitmap = capture();
            if (bitmap != null) {
                jpeg.reset();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, jpeg);
                bitmap.recycle();
                final byte[] bytes = jpeg.toByteArray();
                crc.reset();
                crc.update(bytes);
                if (crc.getValue() != last || began - sent >= STILL_MS) {
                    last = crc.getValue();
                    sent = began;
                    out.write(("--" + BOUNDARY + "\r\nContent-Type: image/jpeg\r\n"
                            + "Content-Length: " + bytes.length + "\r\n\r\n")
                            .getBytes(StandardCharsets.US_ASCII));
                    out.write(bytes);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            }
            final long left = LOOK_MS - (System.currentTimeMillis() - began);
            if (left > 0) {
                try {
                    Thread.sleep(left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
