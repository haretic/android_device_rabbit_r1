// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.phone;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattServer;
import android.bluetooth.BluetoothGattServerCallback;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.SystemClock;
import android.util.Log;

import cat.kenny.r1.phone.proto.Ams;
import cat.kenny.r1.phone.proto.Ancs;
import cat.kenny.r1.phone.proto.Inbox;
import cat.kenny.r1.phone.proto.Uuids;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * The Bluetooth LE link to the iPhone. The r1 makes itself findable as an accessory asking for
 * the notification service; the phone connects, and on that connection the r1 reads the phone's
 * two services. Everything here runs on the main thread, one GATT operation at a time.
 */
final class Link {
    static final int BLUETOOTH_OFF = 0, WAITING = 1, PAIRING = 2, NO_SERVICES = 3, ON = 4;
    private static final String TAG = "R1Phone";
    private static final UUID CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb");
    /** How long the phone has to answer a question about a notification. */
    private static final long ANSWER_MS = 5000;

    /** What the phone's player says of itself. */
    static final class Music {
        String player = "", title = "", artist = "", album = "";
        Float duration, volume;
        Ams.PlaybackInfo info;
        long infoAt;
        Set<Integer> commands = Set.of();

        boolean playing() {
            return info != null && info.state() == Ams.PLAYING;
        }

        /** Seconds into the track now, the phone having said where it was and how fast. */
        double elapsed() {
            if (info == null) {
                return 0;
            }
            final double since = playing()
                    ? (SystemClock.elapsedRealtime() - infoAt) / 1000.0 * info.rate() : 0;
            final double at = Math.max(0, info.elapsedSeconds() + since);
            return duration == null ? at : Math.min(at, duration);
        }

        void clear() {
            player = title = artist = album = "";
            duration = volume = null;
            info = null;
            commands = Set.of();
        }
    }

    private static final class Op {
        final BluetoothGattCharacteristic characteristic;
        final byte[] value;
        final boolean subscribe;
        final boolean question;

        Op(BluetoothGattCharacteristic characteristic, byte[] value, boolean subscribe,
                boolean question) {
            this.characteristic = characteristic;
            this.value = value;
            this.subscribe = subscribe;
            this.question = question;
        }
    }

    final Inbox inbox = new Inbox();
    final Music music = new Music();
    private final Context mContext;
    private final Runnable mChanged;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final BluetoothManager mManager;
    private final ArrayDeque<Op> mOps = new ArrayDeque<>();
    private BluetoothLeAdvertiser mAdvertiser;
    private BluetoothGattServer mServer;
    private BluetoothGatt mGatt;
    private BluetoothGattCharacteristic mControl, mRemote;
    private Op mRunning;
    private int mState = BLUETOOTH_OFF;
    private boolean mStarted;
    /** Paired phones the r1 reaches for itself, each until one answers. */
    private final List<BluetoothGatt> mReaching = new ArrayList<>();
    /** Paired devices that turned out to share no notifications: not reached for again. */
    private final Set<String> mNotPhones = new HashSet<>();
    private boolean mFindable;

    Link(Context context, Runnable changed) {
        mContext = context;
        mChanged = changed;
        mManager = context.getSystemService(BluetoothManager.class);
    }

    int state() {
        return mState;
    }

    /** The phone's name, once there is one to give. */
    String phone() {
        final String name = mGatt == null ? null : mGatt.getDevice().getName();
        return name == null ? "" : name;
    }

    void start() {
        mStarted = true;
        final IntentFilter filter = new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothDevice.ACTION_PAIRING_REQUEST);
        mContext.registerReceiver(mBluetooth, filter);
        mNotPhones.clear();
        open();
    }

    void stop() {
        mStarted = false;
        findable(false);
        mContext.unregisterReceiver(mBluetooth);
        close();
    }

    private void set(int state) {
        if (mState != state) {
            mState = state;
            Log.i(TAG, "link state " + state);
        }
        mChanged.run();
    }

    private void open() {
        final BluetoothAdapter adapter = mManager.getAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            set(BLUETOOTH_OFF);
            return;
        }
        if (mServer == null) {
            // Only there to be told of the phone connecting: it serves nothing
            mServer = mManager.openGattServer(mContext, mServerCallback);
        }
        advertise(adapter);
        set(WAITING);
        reach(adapter);
    }

    /**
     * While the page waits for a phone, the r1 can be found the way an iPhone looks for it:
     * in its Bluetooth settings an iPhone lists the r1 as it does a speaker, found by the old
     * Bluetooth's search, which the r1 answers only while asked to. Two minutes, asked again
     * while the page stays up.
     */
    void findable(boolean on) {
        final BluetoothAdapter adapter = mManager.getAdapter();
        if (on == mFindable || adapter == null || !adapter.isEnabled()) {
            return;
        }
        mHandler.removeCallbacks(mStayFindable);
        try {
            if (on) {
                adapter.setDiscoverableTimeout(Duration.ofSeconds(120));
                mHandler.postDelayed(mStayFindable, 110_000);
            }
            adapter.setScanMode(on ? BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE
                    : BluetoothAdapter.SCAN_MODE_CONNECTABLE);
            mFindable = on;
        } catch (RuntimeException e) {
            // Bluetooth went away under it
            mFindable = false;
        }
    }

    private final Runnable mStayFindable = () -> {
        if (mFindable) {
            mFindable = false;
            findable(true);
        }
    };

    /**
     * Reaches for the phones paired with the r1, however they were paired: from the phone's
     * list after this page asked, or in the r1's own Bluetooth settings. A paired iPhone does
     * not come by itself; asked, it answers whenever it is near, now or days later.
     */
    private void reach(BluetoothAdapter adapter) {
        if (mGatt != null || !mStarted) {
            return;
        }
        final Set<BluetoothDevice> bonded;
        try {
            bonded = adapter.getBondedDevices();
        } catch (RuntimeException e) {
            return;
        }
        for (BluetoothDevice device : bonded) {
            final BluetoothClass kind = device.getBluetoothClass();
            final int major = kind == null ? BluetoothClass.Device.Major.UNCATEGORIZED
                    : kind.getMajorDeviceClass();
            if ((major != BluetoothClass.Device.Major.PHONE
                    && major != BluetoothClass.Device.Major.UNCATEGORIZED)
                    || device.getType() == BluetoothDevice.DEVICE_TYPE_CLASSIC
                    || mNotPhones.contains(device.getAddress())
                    || mReaching.stream().anyMatch(gatt -> gatt.getDevice().equals(device))) {
                continue;
            }
            Log.i(TAG, "reaching for a paired phone");
            mReaching.add(device.connectGatt(mContext, true, mCallback,
                    BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK, mHandler));
        }
    }

    private void stopReaching(BluetoothGatt but) {
        for (BluetoothGatt gatt : mReaching) {
            if (gatt != but) {
                gatt.close();
            }
        }
        mReaching.clear();
    }

    private void advertise(BluetoothAdapter adapter) {
        mAdvertiser = adapter.getBluetoothLeAdvertiser();
        if (mAdvertiser == null) {
            return;
        }
        // Asking for the notification service is what makes an iPhone list the r1 and, once
        // paired, come back to it by itself. Once a second: found in a few, cheap to keep up
        mAdvertiser.startAdvertising(new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                .setConnectable(true).setTimeout(0).build(),
                new AdvertiseData.Builder()
                        .addServiceSolicitationUuid(new ParcelUuid(Uuids.ANCS_SERVICE)).build(),
                new AdvertiseData.Builder().setIncludeDeviceName(true).build(), mAdvertising);
    }

    private void close() {
        mHandler.removeCallbacksAndMessages(null);
        stopReaching(null);
        if (mAdvertiser != null) {
            try {
                mAdvertiser.stopAdvertising(mAdvertising);
            } catch (RuntimeException e) {
                // Bluetooth went off under it: nothing is advertised then
            }
            mAdvertiser = null;
        }
        drop();
        if (mServer != null) {
            mServer.close();
            mServer = null;
        }
    }

    /** The phone is gone: what it said is only good for the connection it said it on. */
    private void drop() {
        if (mGatt != null) {
            mGatt.close();
            mGatt = null;
        }
        mControl = mRemote = null;
        mOps.clear();
        mRunning = null;
        inbox.clear();
        music.clear();
    }

    private final AdvertiseCallback mAdvertising = new AdvertiseCallback() {
        @Override
        public void onStartFailure(int error) {
            Log.w(TAG, "advertising failed " + error);
        }
    };

    private final BroadcastReceiver mBluetooth = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                final int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0);
                if (state == BluetoothAdapter.STATE_ON) {
                    open();
                } else if (state == BluetoothAdapter.STATE_TURNING_OFF
                        || state == BluetoothAdapter.STATE_OFF) {
                    close();
                    set(BLUETOOTH_OFF);
                }
                return;
            }
            if (BluetoothDevice.ACTION_PAIRING_REQUEST.equals(intent.getAction())) {
                if (mFindable) {
                    // The phone asked to pair while this page invited it: the system's
                    // question goes on top now, where it would wait among the notifications
                    try {
                        mContext.startActivity(new Intent(intent).setComponent(null)
                                .setPackage(null).setClassName("com.android.settings",
                                        "com.android.settings.bluetooth.BluetoothPairingDialog")
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                    } catch (RuntimeException e) {
                        // No such dialog on this system: the notification is still there
                    }
                }
                return;
            }
            final BluetoothDevice device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            final int bond = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, 0);
            if (mGatt == null) {
                if (bond == BluetoothDevice.BOND_BONDED && mStarted
                        && mManager.getAdapter() != null) {
                    // Newly paired, wherever that was done: maybe the phone
                    mNotPhones.remove(device.getAddress());
                    reach(mManager.getAdapter());
                }
                return;
            }
            if (!mGatt.getDevice().equals(device)) {
                return;
            }
            if (bond == BluetoothDevice.BOND_BONDED) {
                // The phone shows its services to a paired accessory only
                mGatt.discoverServices();
            } else if (bond == BluetoothDevice.BOND_NONE && mState == PAIRING) {
                mGatt.disconnect();
            }
        }
    };

    private final BluetoothGattServerCallback mServerCallback =
            new BluetoothGattServerCallback() {
        @Override
        public void onConnectionStateChange(BluetoothDevice device, int status, int state) {
            mHandler.post(() -> {
                if (state == BluetoothProfile.STATE_CONNECTED && mGatt == null && mStarted
                        && mReaching.stream().noneMatch(
                                gatt -> gatt.getDevice().equals(device))) {
                    // The same connection, now from the reading side
                    mGatt = device.connectGatt(mContext, false, mCallback,
                            BluetoothDevice.TRANSPORT_LE, BluetoothDevice.PHY_LE_1M_MASK,
                            mHandler);
                }
            });
        }
    };

    private final BluetoothGattCallback mCallback = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt gatt, int status, int state) {
            if (gatt != mGatt) {
                if (mGatt != null || !mReaching.contains(gatt)
                        || state != BluetoothProfile.STATE_CONNECTED) {
                    // One reached for that is not there yet keeps being reached for
                    return;
                }
                // The first paired phone to answer is the one
                mGatt = gatt;
                stopReaching(gatt);
            }
            if (state == BluetoothProfile.STATE_CONNECTED) {
                mRefreshed = false;
                if (mAdvertiser != null) {
                    mAdvertiser.stopAdvertising(mAdvertising);
                }
                // Room for a notification's text in few packets
                if (!gatt.requestMtu(247)) {
                    gatt.discoverServices();
                }
            } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                mHandler.removeCallbacks(mTick);
                drop();
                if (mStarted) {
                    open();
                }
            }
        }

        @Override
        public void onMtuChanged(BluetoothGatt gatt, int mtu, int status) {
            if (gatt == mGatt) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServiceChanged(BluetoothGatt gatt) {
            if (gatt == mGatt) {
                gatt.discoverServices();
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt gatt, int status) {
            if (gatt == mGatt) {
                setUp();
            }
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor,
                int status) {
            if (gatt == mGatt) {
                done(status);
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, int status) {
            if (gatt == mGatt) {
                done(status);
            }
        }

        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt,
                BluetoothGattCharacteristic characteristic, byte[] value) {
            if (gatt != mGatt) {
                return;
            }
            final UUID uuid = characteristic.getUuid();
            if (Uuids.ANCS_NOTIFICATION_SOURCE.equals(uuid)) {
                inbox.onEvent(Ancs.parseEvent(value));
            } else if (Uuids.ANCS_DATA_SOURCE.equals(uuid)) {
                inbox.onData(value, SystemClock.elapsedRealtime());
            } else if (Uuids.AMS_REMOTE_COMMAND.equals(uuid)) {
                final Set<Integer> commands = Ams.parseRemoteCommands(value);
                music.commands = commands == null ? Set.of() : commands;
            } else if (Uuids.AMS_ENTITY_UPDATE.equals(uuid)) {
                heard(Ams.parseUpdate(value));
            }
            ask();
            mChanged.run();
        }
    };

    private void heard(Ams.Update update) {
        if (update == null) {
            return;
        }
        final String value = update.value();
        if (update.entity() == Ams.PLAYER) {
            if (update.attribute() == Ams.PLAYER_NAME) {
                music.player = value;
            } else if (update.attribute() == Ams.PLAYBACK_INFO) {
                music.info = Ams.parsePlaybackInfo(value);
                music.infoAt = SystemClock.elapsedRealtime();
            } else if (update.attribute() == Ams.VOLUME) {
                music.volume = Ams.parseVolume(value);
            }
        } else if (update.entity() == Ams.TRACK) {
            if (update.attribute() == Ams.TITLE) {
                music.title = value;
            } else if (update.attribute() == Ams.ARTIST) {
                music.artist = value;
            } else if (update.attribute() == Ams.ALBUM) {
                music.album = value;
            } else if (update.attribute() == Ams.DURATION) {
                music.duration = Ams.parseDuration(value);
            }
        }
    }

    /** The services are known: ask to be told of notifications and of the music. */
    private void setUp() {
        mOps.clear();
        mRunning = null;
        mControl = mRemote = null;
        final BluetoothGattService ancs = mGatt.getService(Uuids.ANCS_SERVICE);
        if (ancs == null) {
            if (mGatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                set(PAIRING);
                mGatt.getDevice().createBond(BluetoothDevice.TRANSPORT_LE);
            } else if (mGatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                // Paired, and not allowed to see notifications: the phone tells of a change
                set(NO_SERVICES);
                final BluetoothGatt gatt = mGatt;
                mHandler.postDelayed(() -> {
                    if (gatt == mGatt && mState == NO_SERVICES
                            && mManager.getAdapter().getBondedDevices().size() > 1) {
                        // Not an iPhone, or one that will not share: the others get their turn
                        mNotPhones.add(gatt.getDevice().getAddress());
                        gatt.disconnect();
                    }
                }, 20_000);
            }
            return;
        }
        final BluetoothGattCharacteristic data =
                ancs.getCharacteristic(Uuids.ANCS_DATA_SOURCE);
        final BluetoothGattCharacteristic source =
                ancs.getCharacteristic(Uuids.ANCS_NOTIFICATION_SOURCE);
        mControl = ancs.getCharacteristic(Uuids.ANCS_CONTROL_POINT);
        if (source == null) {
            set(NO_SERVICES);
            return;
        }
        inbox.clear();
        // The answers' channel first, so that none is missed once events come
        if (data != null) {
            mOps.add(new Op(data, null, true, false));
        }
        mOps.add(new Op(source, null, true, false));
        final BluetoothGattService ams = mGatt.getService(Uuids.AMS_SERVICE);
        if (ams != null) {
            mRemote = ams.getCharacteristic(Uuids.AMS_REMOTE_COMMAND);
            final BluetoothGattCharacteristic update =
                    ams.getCharacteristic(Uuids.AMS_ENTITY_UPDATE);
            if (mRemote != null) {
                mOps.add(new Op(mRemote, null, true, false));
            }
            if (update != null) {
                mOps.add(new Op(update, null, true, false));
                mOps.add(new Op(update, Ams.subscription(Ams.PLAYER, Ams.PLAYER_NAME,
                        Ams.PLAYBACK_INFO, Ams.VOLUME), false, false));
                mOps.add(new Op(update, Ams.subscription(Ams.TRACK, Ams.ARTIST, Ams.ALBUM,
                        Ams.TITLE, Ams.DURATION), false, false));
            }
        }
        set(ON);
        mHandler.removeCallbacks(mTick);
        mHandler.postDelayed(mTick, 1000);
        next();
    }

    private void next() {
        while (mRunning == null && mGatt != null && !mOps.isEmpty()) {
            final Op op = mOps.poll();
            final boolean sent;
            if (op.subscribe) {
                final BluetoothGattDescriptor descriptor = op.characteristic.getDescriptor(CCCD);
                sent = descriptor != null
                        && mGatt.setCharacteristicNotification(op.characteristic, true)
                        && mGatt.writeDescriptor(descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == 0;
            } else {
                sent = mGatt.writeCharacteristic(op.characteristic, op.value,
                        BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == 0;
            }
            if (sent) {
                mRunning = op;
            } else if (op.question) {
                inbox.writeFailed();
            }
        }
    }

    /** Whether this link already had the phone's services read afresh, see done(). */
    private boolean mRefreshed;

    private void done(int status) {
        final Op op = mRunning;
        mRunning = null;
        if (status != BluetoothGatt.GATT_SUCCESS) {
            Log.w(TAG, "write failed " + status);
            if ((status == BluetoothGatt.GATT_INSUFFICIENT_AUTHENTICATION
                    || status == BluetoothGatt.GATT_INSUFFICIENT_ENCRYPTION)
                    && mGatt.getDevice().getBondState() == BluetoothDevice.BOND_NONE) {
                // The phone wants the r1 paired first: after that, from the top
                mOps.clear();
                set(PAIRING);
                mGatt.getDevice().createBond(BluetoothDevice.TRANSPORT_LE);
                return;
            }
            if (!mRefreshed && mGatt.getDevice().getBondState() == BluetoothDevice.BOND_BONDED) {
                // The phone's services are remembered from an earlier link and it has moved
                // them since, or its link was not yet encrypted when they were read: forgotten
                // and read afresh, once a link
                mRefreshed = true;
                mOps.clear();
                final BluetoothGatt gatt = mGatt;
                gatt.refresh();
                mHandler.postDelayed(() -> {
                    if (gatt == mGatt) {
                        gatt.discoverServices();
                    }
                }, 1500);
                return;
            }
            if (op != null && op.question) {
                inbox.writeFailed();
            }
        }
        next();
        ask();
    }

    /** Sends the next question the inbox has about a notification, one at a time. */
    private void ask() {
        if (mState != ON || mControl == null || mGatt == null) {
            return;
        }
        final long now = SystemClock.elapsedRealtime();
        if (inbox.needsResync()) {
            // An answer never came whole. The wait for it has passed, and what may still come
            // of it fits no later question, so the reader throws it out
            inbox.acknowledgeResync();
        }
        final byte[] command = inbox.nextCommand(now, ANSWER_MS);
        if (command != null) {
            mOps.add(new Op(mControl, command, false, true));
            next();
        }
    }

    /** Questions the phone never answered are given up on by the clock, not by a packet. */
    private final Runnable mTick = new Runnable() {
        @Override
        public void run() {
            if (mState == ON) {
                final int before = inbox.requestInFlight() ? 1 : 0;
                ask();
                if (before != (inbox.requestInFlight() ? 1 : 0)) {
                    mChanged.run();
                }
                mHandler.postDelayed(this, 1000);
            }
        }
    };

    /** Presses a notification's own button on the phone: its yes or its no. */
    /** Clears a notification: on the phone where it offers that, and from the list here. */
    void clear(Inbox.Notice notice) {
        if (notice.negativeAction()) {
            act(notice.uid(), false);
        }
        inbox.dismiss(notice.uid());
        // The r1's own copy of it in the shade goes with it
        mChanged.run();
    }

    void act(long uid, boolean positive) {
        if (mState == ON && mControl != null) {
            mOps.add(new Op(mControl, Ancs.action(uid, positive ? Ancs.ACTION_POSITIVE
                    : Ancs.ACTION_NEGATIVE), false, false));
            next();
        }
    }

    /** A key of the phone's player, if it has that key now. */
    boolean press(int command) {
        if (mState != ON || mRemote == null || !music.commands.contains(command)) {
            return false;
        }
        mOps.add(new Op(mRemote, Ams.command(command), false, false));
        next();
        return true;
    }
}
