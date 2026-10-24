// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.wheel.settings;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.UserHandle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import lineageos.providers.LineageSettings;

import java.util.Map;

/**
 * Places an app's touch zones (the wheel's touch mode, "touch:X1:Y1:X2:Y2" in WHEEL_APP_MODES):
 * over the app, a tap where a step up should tap, then one where a step down should, then Save.
 * The points are in thousandths of the screen, as r1-wheeld's touchscreen covers all of it.
 * Started over the app, by the taskbar's Wheel mode menu or the per-app page.
 */
public class TouchZonesActivity extends Activity {

    /** The app whose zones these are. */
    public static final String EXTRA_PACKAGE = "package";

    private String mPackage;
    private ZonesView mZones;
    private TextView mHint;
    private Button mSave;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mPackage = getIntent().getStringExtra(EXTRA_PACKAGE);
        if (TextUtils.isEmpty(mPackage)) {
            finish();
            return;
        }
        getWindow().getAttributes().layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        getWindow().setDecorFitsSystemWindows(false);

        final FrameLayout root = new FrameLayout(this);
        mZones = new ZonesView(this);
        root.addView(mZones);

        mHint = new TextView(this);
        mHint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        mHint.setTextColor(Color.WHITE);
        mHint.setGravity(Gravity.CENTER);
        mHint.setShadowLayer(dp(4), 0, 0, Color.BLACK);
        final FrameLayout.LayoutParams hint = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        hint.leftMargin = hint.rightMargin = dp(24);
        root.addView(mHint, hint);

        final LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        final Button cancel = button(android.R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        final Button again = button(R.string.touch_zones_again);
        again.setOnClickListener(v -> {
            mZones.clear();
            update();
        });
        mSave = button(R.string.touch_zones_save);
        mSave.setOnClickListener(v -> save());
        buttons.addView(cancel, weight());
        buttons.addView(again, weight());
        buttons.addView(mSave, weight());
        final FrameLayout.LayoutParams bar = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM);
        bar.leftMargin = bar.rightMargin = dp(16);
        bar.bottomMargin = dp(28);
        root.addView(buttons, bar);
        setContentView(root);

        // Zones already set start where they are
        final String mode = AppModesFragment.read(this).get(mPackage);
        final int[] points = parse(mode);
        if (points != null) {
            mZones.set(points);
        }
        update();
    }

    private Button button(int text) {
        final Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        return button;
    }

    private LinearLayout.LayoutParams weight() {
        final LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.leftMargin = lp.rightMargin = dp(4);
        return lp;
    }

    private void update() {
        final int placed = mZones.placed();
        mHint.setText(placed == 0 ? R.string.touch_zones_hint_up
                : placed == 1 ? R.string.touch_zones_hint_down : R.string.touch_zones_hint_done);
        mHint.setVisibility(placed == 2 && mZones.dragging() ? View.INVISIBLE : View.VISIBLE);
        mSave.setEnabled(placed == 2);
    }

    private void save() {
        final int[] p = mZones.points();
        final Map<String, String> modes = AppModesFragment.read(this);
        modes.put(mPackage, "touch:" + p[0] + ":" + p[1] + ":" + p[2] + ":" + p[3]);
        AppModesFragment.write(this, modes);
        finish();
    }

    /** A touch mode's points, X1 Y1 X2 Y2, or null for another mode. */
    static int[] parse(String mode) {
        if (mode == null || !mode.startsWith("touch:")) {
            return null;
        }
        final String[] parts = mode.split(":");
        if (parts.length != 5) {
            return null;
        }
        final int[] points = new int[4];
        try {
            for (int i = 0; i < 4; i++) {
                points[i] = Math.max(0, Math.min(1000, Integer.parseInt(parts[i + 1])));
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return points;
    }

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    /** The app dimmed, the zones as rings marked up and down; a tap places the next. */
    private final class ZonesView extends View {

        private final Paint mDim = new Paint();
        private final Paint mRing = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mFill = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mText = new Paint(Paint.ANTI_ALIAS_FLAG);
        /** The zones in thousandths of the screen, up then down. */
        private final int[] mPoints = new int[4];
        private int mPlaced;
        /** The zone a finger moves, -1 for none. */
        private int mDragging = -1;

        ZonesView(Context context) {
            super(context);
            mDim.setColor(0x99000000);
            final int accent = context.getColor(android.R.color.system_accent1_200);
            mRing.setStyle(Paint.Style.STROKE);
            mRing.setStrokeWidth(dp(3));
            mRing.setColor(accent);
            mFill.setColor((accent & 0x00ffffff) | 0x55000000);
            mText.setColor(Color.WHITE);
            mText.setTextAlign(Paint.Align.CENTER);
            mText.setTextSize(dp(15));
            mText.setFakeBoldText(true);
        }

        int placed() {
            return mPlaced;
        }

        boolean dragging() {
            return mDragging >= 0;
        }

        int[] points() {
            return mPoints.clone();
        }

        void set(int[] points) {
            System.arraycopy(points, 0, mPoints, 0, 4);
            mPlaced = 2;
            invalidate();
        }

        void clear() {
            mPlaced = 0;
            invalidate();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            // In the screen's own terms: the window covers it all, but not the cutout's offset
            final Rect screen = getWindowManager().getCurrentWindowMetrics().getBounds();
            final int x = Math.round(event.getRawX() * 1000 / Math.max(1, screen.width()));
            final int y = Math.round(event.getRawY() * 1000 / Math.max(1, screen.height()));
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> {
                    mDragging = near(event.getX(), event.getY());
                    if (mDragging < 0 && mPlaced < 2) {
                        mDragging = mPlaced++;
                    }
                    if (mDragging >= 0) {
                        place(mDragging, x, y);
                    }
                }
                case MotionEvent.ACTION_MOVE -> {
                    if (mDragging >= 0) {
                        place(mDragging, x, y);
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> mDragging = -1;
            }
            update();
            return true;
        }

        private void place(int zone, int x, int y) {
            mPoints[zone * 2] = Math.max(0, Math.min(1000, x));
            mPoints[zone * 2 + 1] = Math.max(0, Math.min(1000, y));
            invalidate();
        }

        /** The placed zone under a finger, so it can be moved, -1 for none. */
        private int near(float x, float y) {
            for (int zone = 0; zone < mPlaced; zone++) {
                if (Math.hypot(x - viewX(zone), y - viewY(zone)) < dp(36)) {
                    return zone;
                }
            }
            return -1;
        }

        private float viewX(int zone) {
            final int[] at = new int[2];
            getLocationOnScreen(at);
            final Rect screen = getWindowManager().getCurrentWindowMetrics().getBounds();
            return mPoints[zone * 2] * screen.width() / 1000f - at[0];
        }

        private float viewY(int zone) {
            final int[] at = new int[2];
            getLocationOnScreen(at);
            final Rect screen = getWindowManager().getCurrentWindowMetrics().getBounds();
            return mPoints[zone * 2 + 1] * screen.height() / 1000f - at[1];
        }

        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawRect(0, 0, getWidth(), getHeight(), mDim);
            final float radius = dp(28);
            for (int zone = 0; zone < mPlaced; zone++) {
                final float x = viewX(zone);
                final float y = viewY(zone);
                canvas.drawCircle(x, y, radius, mFill);
                canvas.drawCircle(x, y, radius, mRing);
                canvas.drawText(getString(zone == 0 ? R.string.touch_zones_up
                                : R.string.touch_zones_down),
                        x, y - (mText.descent() + mText.ascent()) / 2, mText);
            }
        }
    }
}
