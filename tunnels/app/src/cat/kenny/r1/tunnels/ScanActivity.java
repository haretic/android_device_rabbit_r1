// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.tunnels;

import android.Manifest;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.LuminanceSource;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Reading a QR code (N3): the camera's picture with corners to hold the code in, read here on
 * the r1 from the camera's frames. The r1's camera turns to face the user once it is opened,
 * by itself. What was read goes back to the page that asked, through Draft, not an intent.
 */
public final class ScanActivity extends Page {
    static final String EXTRA_MODE = "mode";
    /** A tunnel's settings */
    static final int MODE_CONF = 0;
    /** A Mullvad account number */
    static final int MODE_ACCOUNT = 1;
    /** A ZeroTier network ID, 16 hex characters, alone or in a longer text such as a link */
    static final int MODE_ZT_ID = 2;
    private static final String CAMERA_ID = "0";
    private static final int REQ_CAMERA = 3;
    private static final long DECODE_EVERY_MS = 150;

    private int mMode;
    private TextureView mPreview;
    private View mFrame;
    private View mProblem;
    private View mAllow;
    private CameraDevice mCamera;
    private CameraCaptureSession mSession;
    private ImageReader mReader;
    private Surface mSurface;
    private HandlerThread mThread;
    private Size mSize;
    private int mSensor = 90;
    private boolean mOpening;
    private String mLastWrong;
    private volatile boolean mDone;
    private long mLastDecode;
    private final QRCodeReader mQr = new QRCodeReader();
    private final Map<DecodeHintType, Object> mHints = new EnumMap<>(DecodeHintType.class);

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        mMode = getIntent().getIntExtra(EXTRA_MODE, MODE_CONF);
        if (mMode == MODE_ACCOUNT) {
            title(R.string.kind_mullvad);
            steps(1, 2);
        } else if (mMode == MODE_ZT_ID) {
            title(R.string.kind_zt);
            steps(1, 2);
        } else {
            title(R.string.kind_wg);
            steps(1, 3);
        }
        mHints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        getLayoutInflater().inflate(R.layout.scan, mBody, true);
        mFrame = mBody.findViewById(R.id.frame);
        mPreview = mBody.findViewById(R.id.preview);
        size();
        mPreview.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
                open();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
                fit();
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                close();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture texture) {
            }
        });
        note(mBody, R.drawable.ic_scan, getText(R.string.scan_note));
        mProblem = note(mBody, R.drawable.ic_info, "");
        noteText(mProblem, "", true);
        mAllow = row(mBody, R.drawable.ic_scan, getText(R.string.camera_allow),
                getText(R.string.camera_allow_sum));
        pressHint(mAllow, getText(R.string.hint_allow));
        mAllow.setOnClickListener(v -> requestPermissions(
                new String[] {Manifest.permission.CAMERA}, REQ_CAMERA));
        hint(R.string.hint_back);
        if (checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            noteText(mProblem, getText(R.string.scan_camera_why), false);
            mAllow.requestFocus();
            requestPermissions(new String[] {Manifest.permission.CAMERA}, REQ_CAMERA);
        } else {
            mAllow.setVisibility(View.GONE);
        }
    }

    /** The picture as tall as fits: shorter where the r1 lies on its side */
    private void size() {
        final float density = getResources().getDisplayMetrics().density;
        final int screen = getResources().getConfiguration().screenHeightDp;
        final int dp = Math.max(160, Math.min(380, screen - 190));
        final ViewGroup.LayoutParams params = mFrame.getLayoutParams();
        params.height = (int) (dp * density);
        mFrame.setLayoutParams(params);
    }

    @Override
    public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        size();
        mPreview.post(this::fit);
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] results) {
        if (request != REQ_CAMERA) {
            return;
        }
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            noteText(mProblem, "", true);
            mAllow.setVisibility(View.GONE);
            open();
        } else {
            noteText(mProblem, getText(R.string.scan_camera_why), false);
            mAllow.setVisibility(View.VISIBLE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        open();
    }

    @Override
    protected void onPause() {
        close();
        super.onPause();
    }

    // The camera

    private void open() {
        if (mCamera != null || mOpening || mDone || !isResumedNow() || !mPreview.isAvailable()
                || checkSelfPermission(Manifest.permission.CAMERA)
                        != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        final CameraManager manager = getSystemService(CameraManager.class);
        try {
            final CameraCharacteristics chars = manager.getCameraCharacteristics(CAMERA_ID);
            final StreamConfigurationMap map =
                    chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            final Integer sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensor = sensor != null ? sensor : 90;
            if (map == null) {
                throw new IllegalStateException("no stream configurations");
            }
            mSize = pick(map.getOutputSizes(SurfaceTexture.class));
            final Size yuv = pick(map.getOutputSizes(ImageFormat.YUV_420_888));
            if (mSize == null || yuv == null) {
                throw new IllegalStateException("no sizes");
            }
            mThread = new HandlerThread("scan");
            mThread.start();
            mReader = ImageReader.newInstance(yuv.getWidth(), yuv.getHeight(),
                    ImageFormat.YUV_420_888, 2);
            mReader.setOnImageAvailableListener(this::frame, new Handler(mThread.getLooper()));
            mOpening = true;
            manager.openCamera(CAMERA_ID, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    mOpening = false;
                    if (!isResumedNow() || mReader == null) {
                        camera.close();
                        return;
                    }
                    mCamera = camera;
                    session();
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    mOpening = false;
                    camera.close();
                    if (mCamera == camera) {
                        mCamera = null;
                    }
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    mOpening = false;
                    camera.close();
                    if (mCamera == camera) {
                        mCamera = null;
                    }
                    Log.w(Store.TAG, "Camera error " + error);
                    noteText(mProblem, getText(R.string.scan_no_camera), true);
                }
            }, mMain);
        } catch (CameraAccessException | RuntimeException e) {
            mOpening = false;
            Log.w(Store.TAG, "Camera not opened: " + e.getClass().getSimpleName());
            noteText(mProblem, getText(R.string.scan_no_camera), true);
            close();
        }
    }

    /** The largest size up to 1280 x 960: enough to read a code, not too much to read often */
    private static Size pick(Size[] sizes) {
        Size best = null;
        if (sizes == null) {
            return null;
        }
        for (Size size : sizes) {
            if (size.getWidth() <= 1280 && size.getHeight() <= 960 && (best == null
                    || size.getWidth() * size.getHeight() > best.getWidth() * best.getHeight())) {
                best = size;
            }
        }
        if (best == null && sizes.length > 0) {
            best = sizes[sizes.length - 1];
        }
        return best;
    }

    private void session() {
        final SurfaceTexture texture = mPreview.getSurfaceTexture();
        if (texture == null || mCamera == null || mReader == null) {
            return;
        }
        texture.setDefaultBufferSize(mSize.getWidth(), mSize.getHeight());
        mSurface = new Surface(texture);
        final List<OutputConfiguration> outputs = new ArrayList<>();
        outputs.add(new OutputConfiguration(mSurface));
        outputs.add(new OutputConfiguration(mReader.getSurface()));
        final CameraDevice camera = mCamera;
        final SessionConfiguration config = new SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR, outputs, getMainExecutor(),
                new CameraCaptureSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraCaptureSession session) {
                        if (mCamera != camera || mReader == null || mSurface == null) {
                            session.close();
                            return;
                        }
                        mSession = session;
                        try {
                            final CaptureRequest.Builder request =
                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                            request.addTarget(mSurface);
                            request.addTarget(mReader.getSurface());
                            session.setRepeatingRequest(request.build(), null, null);
                        } catch (CameraAccessException | RuntimeException e) {
                            Log.w(Store.TAG, "No preview: " + e.getClass().getSimpleName());
                            noteText(mProblem, getText(R.string.scan_no_camera), true);
                        }
                    }

                    @Override
                    public void onConfigureFailed(CameraCaptureSession session) {
                        noteText(mProblem, getText(R.string.scan_no_camera), true);
                    }
                });
        try {
            camera.createCaptureSession(config);
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(Store.TAG, "No session: " + e.getClass().getSimpleName());
            noteText(mProblem, getText(R.string.scan_no_camera), true);
        }
        fit();
    }

    /**
     * The picture filling the frame without being stretched. The camera's frames come turned
     * upright for the r1 held as it is made to be; turned on its side, they are turned again.
     */
    private void fit() {
        if (mSize == null) {
            return;
        }
        final int vw = mPreview.getWidth();
        final int vh = mPreview.getHeight();
        if (vw == 0 || vh == 0) {
            return;
        }
        final int rotation = getDisplay() != null ? getDisplay().getRotation()
                : Surface.ROTATION_0;
        final float cx = vw / 2f;
        final float cy = vh / 2f;
        final Matrix matrix = new Matrix();
        if (rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) {
            final RectF view = new RectF(0, 0, vw, vh);
            final RectF buffer = new RectF(0, 0, mSize.getHeight(), mSize.getWidth());
            buffer.offset(cx - buffer.centerX(), cy - buffer.centerY());
            matrix.setRectToRect(view, buffer, Matrix.ScaleToFit.FILL);
            final float scale = Math.max((float) vh / mSize.getHeight(),
                    (float) vw / mSize.getWidth());
            matrix.postScale(scale, scale, cx, cy);
            matrix.postRotate(90 * (rotation - 2), cx, cy);
        } else {
            // Upright: the frame's own shape, sideways for a sensor mounted sideways
            final boolean sideways = mSensor % 180 != 0;
            final float cw = sideways ? mSize.getHeight() : mSize.getWidth();
            final float ch = sideways ? mSize.getWidth() : mSize.getHeight();
            final float scale = Math.max(vw / cw, vh / ch);
            matrix.setScale(scale * cw / vw, scale * ch / vh, cx, cy);
            if (rotation == Surface.ROTATION_180) {
                matrix.postRotate(180, cx, cy);
            }
        }
        mPreview.setTransform(matrix);
    }

    private void close() {
        if (mSession != null) {
            try {
                mSession.close();
            } catch (RuntimeException e) {
                // closing anyway
            }
            mSession = null;
        }
        if (mCamera != null) {
            mCamera.close();
            mCamera = null;
        }
        if (mReader != null) {
            mReader.close();
            mReader = null;
        }
        if (mThread != null) {
            mThread.quitSafely();
            mThread = null;
        }
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
        }
    }

    // Reading

    /** A frame, on the reading thread: its brightness alone is what a code reader needs. */
    private void frame(ImageReader reader) {
        final Image image;
        try {
            image = reader.acquireLatestImage();
        } catch (RuntimeException e) {
            return;
        }
        if (image == null) {
            return;
        }
        try {
            final long now = SystemClock.elapsedRealtime();
            if (mDone || now - mLastDecode < DECODE_EVERY_MS) {
                return;
            }
            mLastDecode = now;
            final Image.Plane plane = image.getPlanes()[0];
            final ByteBuffer buffer = plane.getBuffer();
            final int stride = plane.getRowStride();
            final int width = image.getWidth();
            final int height = image.getHeight();
            final byte[] data = new byte[stride * height];
            buffer.get(data, 0, Math.min(buffer.remaining(), data.length));
            final LuminanceSource source = new PlanarYUVLuminanceSource(data, stride, height,
                    0, 0, width, height, false);
            final Result result = mQr.decode(new BinaryBitmap(new HybridBinarizer(source)),
                    mHints);
            final String text = result.getText();
            if (text != null) {
                runOnUiThread(() -> found(text));
            }
        } catch (ReaderException e) {
            // no code in this frame
        } catch (RuntimeException e) {
            Log.w(Store.TAG, "Frame not read: " + e.getClass().getSimpleName());
        } finally {
            mQr.reset();
            image.close();
        }
    }

    private void found(String text) {
        if (mDone) {
            return;
        }
        if (mMode == MODE_ACCOUNT) {
            final String digits = text.replaceAll("[^0-9]", "");
            if (digits.length() != 16) {
                wrong(text, R.string.scan_not_account);
                return;
            }
            mDone = true;
            Draft.scanned = digits;
        } else if (mMode == MODE_ZT_ID) {
            final java.util.regex.Matcher id = java.util.regex.Pattern
                    .compile("(?<![0-9A-Fa-f])[0-9A-Fa-f]{16}(?![0-9A-Fa-f])").matcher(text);
            if (!id.find()) {
                wrong(text, R.string.scan_not_zt);
                return;
            }
            mDone = true;
            Draft.scanned = id.group().toLowerCase(java.util.Locale.ROOT);
        } else {
            if (!java.util.regex.Pattern.compile("(?im)^\\s*\\[\\s*interface\\s*\\]")
                    .matcher(text).find()) {
                wrong(text, R.string.scan_not_conf);
                return;
            }
            mDone = true;
            Draft.scanned = text;
        }
        getWindow().getDecorView().performHapticFeedback(
                android.view.HapticFeedbackConstants.CONFIRM);
        setResult(RESULT_OK);
        finish();
    }

    /** Said once for each code that is not what is wanted, while the camera reads on */
    private void wrong(String text, int what) {
        if (!text.equals(mLastWrong)) {
            mLastWrong = text;
            noteText(mProblem, getText(what), true);
        }
    }
}
