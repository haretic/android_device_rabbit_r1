// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.toolbox;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.view.Surface;

import cat.kenny.r1.toolbox.engine.LevelMath;

/** The accelerometer into a LevelMath, turned to the way the screen is held. */
final class LevelSensor implements SensorEventListener {

    private final SensorManager mSensors;
    private final Sensor mAccelerometer;
    private final Context mContext;
    private final Runnable mChanged;
    final LevelMath math = new LevelMath();
    /** Whether the r1 lies more on its back than it stands, from the last sample. */
    boolean lying = true;

    LevelSensor(Context context, Runnable changed) {
        mContext = context;
        mChanged = changed;
        mSensors = context.getSystemService(SensorManager.class);
        mAccelerometer = mSensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
    }

    boolean available() {
        return mAccelerometer != null;
    }

    void start() {
        if (mAccelerometer != null) {
            mSensors.registerListener(this, mAccelerometer, SensorManager.SENSOR_DELAY_UI);
        }
    }

    void stop() {
        mSensors.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        final float x = event.values[0];
        final float y = event.values[1];
        final float z = event.values[2];
        lying = Math.abs(z) > Math.hypot(x, y);
        // The sensor's axes are the device's; "right" and "away" are the screen's as held
        switch (mContext.getDisplay().getRotation()) {
            case Surface.ROTATION_90:
                math.update(-y, x, z, event.timestamp);
                break;
            case Surface.ROTATION_180:
                math.update(-x, -y, z, event.timestamp);
                break;
            case Surface.ROTATION_270:
                math.update(y, -x, z, event.timestamp);
                break;
            default:
                math.update(x, y, z, event.timestamp);
        }
        mChanged.run();
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }
}
