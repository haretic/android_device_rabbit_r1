/*
 * SPDX-FileCopyrightText: The haretic Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.haretic.wallpaper;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.graphics.Canvas;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.BatteryManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.service.wallpaper.WallpaperService;
import android.view.Choreographer;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.Calendar;

/**
 * haretic's wallpaper, alive: the hares of the still image stir with the wheel, lean as the
 * device is tilted, perk up when the screen wakes, sleep at night and droop when the battery
 * is low. It draws while something moves and only then: at rest it is one frame, the still
 * image's picture, and nothing runs but a timer for the next hare to flick an ear.
 *
 * <p>The wheel's steps come as a wallpaper command from the window the wallpaper shows behind
 * ({@link #COMMAND_WHEEL_STEP}, x the direction), as the wheel's keys go to that window and
 * never to a wallpaper. {@link #SETTING_LIFE} at 0 leaves the still picture.
 */
public final class HareWallpaper extends WallpaperService {
    /** A step of the wheel: x is 1 for a step down or on, -1 for one up or back. */
    public static final String COMMAND_WHEEL_STEP = "org.haretic.wallpaper.WHEEL_STEP";
    /** Settings.Secure: 0 for the picture without its life. */
    public static final String SETTING_LIFE = "haretic_wallpaper_life";

    private static final String COMMAND_WAKING_UP = "android.wallpaper.wakingup";
    /** The wheel's middle down the edge it sits at, and the hours the hares sleep through. */
    private static final float WHEEL_AT = 0.41f;
    private static final int NIGHT_FROM = 23, NIGHT_UNTIL = 6;
    private static final int LOW_BATTERY = 15;
    /** A lean of 35 degrees is all the way; less than a few degrees' change is the hand. */
    private static final float FULL_TILT = 9.81f * 0.574f;
    private static final float HELD_AT = 9.81f * 0.643f;
    private static final float TILT_SLACK = 0.08f;
    private static final long BREATH_FRAME_MS = 80;
    /** How long after the screen woke the wallpaper may show and still greet it. */
    private static final long WOKE_MS = 2500;

    @Override
    public Engine onCreateEngine() {
        return new HareEngine();
    }

    private final class HareEngine extends Engine
            implements Choreographer.FrameCallback, SensorEventListener {
        private final HareField mField = new HareField();
        private final Handler mHandler = new Handler(Looper.getMainLooper());
        private final Runnable mFrame = () -> doFrame(System.nanoTime());
        private final Runnable mIdle = this::idle;
        private final ContentObserver mLifeObserver = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange) {
                readLife();
            }
        };
        private final BroadcastReceiver mPower = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                power(intent, true);
            }
        };
        private SensorManager mSensors;
        private boolean mVisible, mAlive = true, mListening, mScheduled;
        private int mRotation;
        private long mLastFrame, mWokeAt;
        private float mTiltX, mTiltY;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);
            setTouchEventsEnabled(false);
            mSensors = getSystemService(SensorManager.class);
            getContentResolver().registerContentObserver(
                    Settings.Secure.getUriFor(SETTING_LIFE), false, mLifeObserver);
            mAlive = Settings.Secure.getInt(getContentResolver(), SETTING_LIFE, 1) != 0;
        }

        @Override
        public void onDestroy() {
            getContentResolver().unregisterContentObserver(mLifeObserver);
            listen(false);
            super.onDestroy();
        }

        @Override
        public void onSurfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            super.onSurfaceChanged(holder, format, width, height);
            final Context display = getDisplayContext();
            mRotation = display != null && display.getDisplay() != null
                    ? display.getDisplay().getRotation() : Surface.ROTATION_0;
            // The wheel is at the right edge of the screen held upright, the port at its bottom
            switch (mRotation) {
                case Surface.ROTATION_90:
                    mField.setSize(width, height, WHEEL_AT * width, 0, width, height / 2f);
                    break;
                case Surface.ROTATION_180:
                    mField.setSize(width, height, 0, (1 - WHEEL_AT) * height, width / 2f, 0);
                    break;
                case Surface.ROTATION_270:
                    mField.setSize(width, height, (1 - WHEEL_AT) * width, height, 0, height / 2f);
                    break;
                default:
                    mField.setSize(width, height, width, WHEEL_AT * height, width / 2f, height);
                    break;
            }
            still();
            wake();
        }

        @Override
        public void onSurfaceRedrawNeeded(SurfaceHolder holder) {
            draw();
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            mVisible = visible;
            listen(visible && mAlive);
            if (visible) {
                still();
                if (mAlive && SystemClock.uptimeMillis() - mWokeAt < WOKE_MS) {
                    // The screen coming on said so before the wallpaper showed
                    mField.wake();
                }
                wake();
            } else {
                sleep();
            }
        }

        @Override
        public Bundle onCommand(String action, int x, int y, int z, Bundle extras,
                boolean resultRequested) {
            if (COMMAND_WAKING_UP.equals(action)) {
                mWokeAt = SystemClock.uptimeMillis();
            }
            if (mAlive && mVisible) {
                if (COMMAND_WHEEL_STEP.equals(action) && x != 0) {
                    mField.step(x > 0 ? 1 : -1);
                    wake();
                } else if (COMMAND_WAKING_UP.equals(action)) {
                    mField.wake();
                    wake();
                }
            }
            return super.onCommand(action, x, y, z, extras, resultRequested);
        }

        // ---- What it listens to, while it shows ----

        private void listen(boolean on) {
            if (on == mListening) {
                return;
            }
            mListening = on;
            if (on) {
                power(registerReceiver(mPower, new IntentFilter(Intent.ACTION_BATTERY_CHANGED),
                        Context.RECEIVER_NOT_EXPORTED), false);
                final Sensor gravity = mSensors == null ? null
                        : mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                if (gravity != null) {
                    mSensors.registerListener(this, gravity, SensorManager.SENSOR_DELAY_UI,
                            mHandler);
                }
            } else {
                unregisterReceiver(mPower);
                if (mSensors != null) {
                    mSensors.unregisterListener(this);
                }
            }
        }

        private void readLife() {
            final boolean alive =
                    Settings.Secure.getInt(getContentResolver(), SETTING_LIFE, 1) != 0;
            if (alive == mAlive) {
                return;
            }
            mAlive = alive;
            listen(mVisible && alive);
            still();
            if (mVisible) {
                wake();
            }
        }

        private void power(Intent battery, boolean show) {
            if (battery == null) {
                return;
            }
            final int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
            final int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            final boolean charging = battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
            boolean changed = mField.setLow(scale > 0 && level * 100 / scale <= LOW_BATTERY);
            changed |= mField.setCharging(charging, show);
            if (show && changed) {
                wake();
            }
        }

        private void clock() {
            final int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
            mField.setNight(hour >= NIGHT_FROM || hour < NIGHT_UNTIL);
        }

        @Override
        public void onSensorChanged(SensorEvent event) {
            // Which way is down across and along the screen as it is turned now: the sensor
            // gives the push against gravity along the device's own right and top
            final float right = -event.values[0], down = event.values[1];
            final float x, y;
            switch (mRotation) {
                case Surface.ROTATION_90:
                    x = down;
                    y = -right;
                    break;
                case Surface.ROTATION_180:
                    x = -right;
                    y = -down;
                    break;
                case Surface.ROTATION_270:
                    x = -down;
                    y = right;
                    break;
                default:
                    x = right;
                    y = down;
                    break;
            }
            final float tiltX = Math.max(-1, Math.min(1, x / FULL_TILT));
            final float tiltY = Math.max(-1, Math.min(1, (HELD_AT - y) / FULL_TILT));
            if (Math.abs(tiltX - mTiltX) < TILT_SLACK && Math.abs(tiltY - mTiltY) < TILT_SLACK) {
                return;
            }
            mTiltX = tiltX;
            mTiltY = tiltY;
            mField.setTilt(tiltX, tiltY);
            wake();
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
        }

        // ---- Frames, while something moves ----

        /** Everything where it belongs for the hour and the battery, with no way there shown. */
        private void still() {
            if (mAlive) {
                clock();
            } else {
                mField.setNight(false);
                mField.setLow(false);
                mField.setTilt(0, 0);
            }
            mField.settle();
            mLastFrame = 0;
        }

        /** Something may move from here on. */
        private void wake() {
            mHandler.removeCallbacks(mIdle);
            mHandler.removeCallbacks(mFrame);
            if (!mVisible) {
                return;
            }
            if (!mAlive) {
                draw();
                return;
            }
            if (!mScheduled) {
                mScheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            }
        }

        private void sleep() {
            mHandler.removeCallbacks(mIdle);
            mHandler.removeCallbacks(mFrame);
            Choreographer.getInstance().removeFrameCallback(this);
            mScheduled = false;
            mLastFrame = 0;
        }

        private void idle() {
            // One of them does something of its own
            mField.rest(mField.untilIdle());
            clock();
            wake();
        }

        @Override
        public void doFrame(long frameTimeNanos) {
            mScheduled = false;
            if (!mVisible || !mAlive) {
                return;
            }
            final float dt = mLastFrame == 0 ? 1 / 60f
                    : Math.max(0.001f, Math.min(0.1f, (frameTimeNanos - mLastFrame) / 1e9f));
            mLastFrame = frameTimeNanos;
            // In steps no longer than the springs stay steady over
            boolean moving = false;
            for (float left = dt; left > 0; left -= 0.034f) {
                moving = mField.advance(Math.min(left, 0.034f));
            }
            draw();
            if (moving) {
                mScheduled = true;
                Choreographer.getInstance().postFrameCallback(this);
            } else if (mField.breathing()) {
                mHandler.postDelayed(mFrame, BREATH_FRAME_MS);
            } else {
                mLastFrame = 0;
                mHandler.postDelayed(mIdle, (long) (mField.untilIdle() * 1000) + 16);
            }
        }

        private void draw() {
            final SurfaceHolder holder = getSurfaceHolder();
            if (holder.getSurface() == null || !holder.getSurface().isValid()) {
                return;
            }
            Canvas canvas = null;
            try {
                canvas = holder.lockHardwareCanvas();
                if (canvas != null) {
                    mField.draw(canvas);
                }
            } finally {
                if (canvas != null) {
                    holder.unlockCanvasAndPost(canvas);
                }
            }
        }
    }
}
