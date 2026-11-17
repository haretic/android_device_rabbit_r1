// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.knob;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * The r1 as a Bluetooth input device: a keyboard, the media keys and a mouse wheel in one, which
 * every computer, tablet and television takes without anything installed. Lives as long as the
 * page: Android lets only the app in front be one.
 */
final class Hid {
    private static final String TAG = "R1Knob";

    static final int VOLUME = 0, SCROLL = 1, SLIDES = 2, MUSIC = 3, ZOOM = 4;

    private static final int KEYS = 1, MEDIA = 2, MOUSE = 3;
    // Media key bits, in the descriptor's order
    private static final int VOL_UP = 1, VOL_DOWN = 2, MUTE = 4, PLAY = 8, NEXT = 16, PREV = 32;
    // Keyboard usages
    private static final int KEY_B = 0x05, KEY_0 = 0x27, KEY_MINUS = 0x2d, KEY_EQUAL = 0x2e,
            KEY_RIGHT = 0x4f, KEY_LEFT = 0x50, CTRL = 0x01;

    private static final byte[] DESCRIPTOR = {
        // Keyboard: modifiers, a reserved byte, six keys
        0x05, 0x01, 0x09, 0x06, (byte) 0xa1, 0x01, (byte) 0x85, KEYS,
        0x05, 0x07, 0x19, (byte) 0xe0, 0x29, (byte) 0xe7, 0x15, 0x00, 0x25, 0x01,
        0x75, 0x01, (byte) 0x95, 0x08, (byte) 0x81, 0x02,
        (byte) 0x95, 0x01, 0x75, 0x08, (byte) 0x81, 0x01,
        (byte) 0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x25, 0x65, 0x19, 0x00, 0x29, 0x65,
        (byte) 0x81, 0x00, (byte) 0xc0,
        // Media keys: volume up, volume down, mute, play/pause, next, previous
        0x05, 0x0c, 0x09, 0x01, (byte) 0xa1, 0x01, (byte) 0x85, MEDIA,
        0x15, 0x00, 0x25, 0x01, 0x75, 0x01, (byte) 0x95, 0x06,
        0x09, (byte) 0xe9, 0x09, (byte) 0xea, 0x09, (byte) 0xe2, 0x09, (byte) 0xcd,
        0x09, (byte) 0xb5, 0x09, (byte) 0xb6, (byte) 0x81, 0x02,
        (byte) 0x95, 0x02, (byte) 0x81, 0x01, (byte) 0xc0,
        // Mouse: three buttons, x, y and the wheel
        0x05, 0x01, 0x09, 0x02, (byte) 0xa1, 0x01, (byte) 0x85, MOUSE, 0x09, 0x01,
        (byte) 0xa1, 0x00, 0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        (byte) 0x95, 0x03, 0x75, 0x01, (byte) 0x81, 0x02, (byte) 0x95, 0x01, 0x75, 0x05,
        (byte) 0x81, 0x01, 0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, (byte) 0x81, 0x25, 0x7f, 0x75, 0x08, (byte) 0x95, 0x03, (byte) 0x81, 0x06,
        (byte) 0xc0, (byte) 0xc0,
    };

    interface Listener {
        /** The link changed: who is at the other end, or null, and whether still coming up. */
        void onLink(BluetoothDevice host, boolean connecting);
    }

    private final Context mContext;
    private final Listener mListener;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private BluetoothHidDevice mHid;
    private BluetoothDevice mHost;
    private boolean mRehearsed;
    private boolean mRegistered;
    private BluetoothDevice mWanted;

    Hid(Context context, Listener listener) {
        mContext = context;
        mListener = listener;
    }

    boolean start() {
        final BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        return adapter != null && adapter.isEnabled() && adapter.getProfileProxy(mContext,
                mProxy, BluetoothProfile.HID_DEVICE);
    }

    void stop() {
        mHandler.removeCallbacksAndMessages(null);
        if (mHid != null) {
            try {
                if (mHost != null) {
                    mHid.disconnect(mHost);
                }
                mHid.unregisterApp();
            } catch (RuntimeException e) {
                Log.w(TAG, "stopping", e);
            }
            BluetoothAdapter.getDefaultAdapter().closeProfileProxy(BluetoothProfile.HID_DEVICE,
                    mHid);
            mHid = null;
        }
        mHost = null;
        mRegistered = false;
    }

    BluetoothDevice host() {
        return mHost;
    }

    /** Reach out to a computer that knows the r1 already. */
    void connect(BluetoothDevice device) {
        mWanted = device;
        if (mHid != null && mRegistered) {
            mListener.onLink(device, true);
            mHid.connect(device);
        }
    }

    /**
     * The dial's page as with {@code device} linked, with nothing sent anywhere: for the self
     * test, which has no computer to link to (am start --ez rehearse true). Leaving ends it.
     */
    void rehearse(BluetoothDevice device) {
        mRehearsed = true;
        mHost = device;
    }

    boolean rehearsed() {
        return mRehearsed;
    }

    void leave() {
        mWanted = null;
        if (mRehearsed) {
            mRehearsed = false;
            mHost = null;
            mListener.onLink(null, false);
            return;
        }
        if (mHid != null && mHost != null) {
            mHid.disconnect(mHost);
        }
    }

    /** One step of the wheel, up being more: louder, further up the page, back a slide. */
    void turn(int mode, boolean up) {
        switch (mode) {
            case VOLUME: media(up ? VOL_UP : VOL_DOWN); break;
            case SCROLL: mouse(0, up ? 1 : -1); break;
            case SLIDES: key(0, up ? KEY_LEFT : KEY_RIGHT); break;
            case MUSIC: media(up ? PREV : NEXT); break;
            default: key(CTRL, up ? KEY_EQUAL : KEY_MINUS); break;
        }
    }

    /** The press: the one thing that goes with the turn. */
    void press(int mode) {
        switch (mode) {
            case VOLUME: media(MUTE); break;
            case SCROLL: mouse(1, 0); mouse(0, 0); break;
            case SLIDES: key(0, KEY_B); break;
            case MUSIC: media(PLAY); break;
            default: key(CTRL, KEY_0); break;
        }
    }

    private void key(int modifiers, int usage) {
        send(KEYS, new byte[] {(byte) modifiers, 0, (byte) usage, 0, 0, 0, 0, 0});
        send(KEYS, new byte[8]);
    }

    private void media(int bits) {
        send(MEDIA, new byte[] {(byte) bits});
        send(MEDIA, new byte[] {0});
    }

    private void mouse(int buttons, int wheel) {
        send(MOUSE, new byte[] {(byte) buttons, 0, 0, (byte) wheel});
    }

    private void send(int id, byte[] data) {
        if (mHid != null && mHost != null && !mRehearsed) {
            mHid.sendReport(mHost, id, data);
        }
    }

    private final BluetoothProfile.ServiceListener mProxy = new BluetoothProfile.ServiceListener() {
        @Override
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mHid = (BluetoothHidDevice) proxy;
            mHid.registerApp(new BluetoothHidDeviceAppSdpSettings("haretic rabbit r1",
                    "Wheel and side button", "haretic", BluetoothHidDevice.SUBCLASS1_COMBO,
                    DESCRIPTOR), null, null, mHandler::post, mCallback);
        }

        @Override
        public void onServiceDisconnected(int profile) {
            mHid = null;
            mHost = null;
            mRegistered = false;
            mListener.onLink(null, false);
        }
    };

    private final BluetoothHidDevice.Callback mCallback = new BluetoothHidDevice.Callback() {
        @Override
        public void onAppStatusChanged(BluetoothDevice plugged, boolean registered) {
            mRegistered = registered;
            Log.i(TAG, "input device " + (registered ? "offered" : "withdrawn"));
            if (registered && mWanted != null) {
                connect(mWanted);
            }
        }

        @Override
        public void onConnectionStateChanged(BluetoothDevice device, int state) {
            Log.i(TAG, "link state " + state);
            if (mRehearsed && state != BluetoothProfile.STATE_CONNECTED) {
                return;
            }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                mRehearsed = false;
                mHost = device;
                mListener.onLink(device, false);
            } else if (state == BluetoothProfile.STATE_CONNECTING) {
                mListener.onLink(device, true);
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                if (device.equals(mHost)) {
                    mHost = null;
                }
                mListener.onLink(null, false);
            }
        }

        @Override
        public void onGetReport(BluetoothDevice device, byte type, byte id, int size) {
            // Nothing is held down between two reports: the answer is always "all up"
            if (mHid != null) {
                mHid.replyReport(device, type, id, new byte[id == KEYS ? 8 : id == MOUSE ? 4 : 1]);
            }
        }

        @Override
        public void onSetReport(BluetoothDevice device, byte type, byte id, byte[] data) {
            // A keyboard's lights: the r1 has none, and says the report was taken
            if (mHid != null) {
                mHid.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS);
            }
        }
    };
}
