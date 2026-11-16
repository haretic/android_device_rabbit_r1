// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.walkie.net;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.security.SecureRandom;
import java.util.Objects;

/** One multicast socket and receive thread own presence, talk arbitration and timers. */
public final class Radio {
    public interface Listener {
        void onPeers();
        void onTalkStart(long id, String name);
        void onTalkEnd(long id, int frames, int lost);
    }

    private final long id;
    private final Listener listener;
    private final InetAddress group;
    private final int port;
    private final boolean loopback;
    private final Peers peers = new Peers();
    private final SecureRandom random = new SecureRandom();
    private final byte[] sendBytes = new byte[Packet.MAX];
    private final byte[] encoded = new byte[Packet.FRAME];
    private final DatagramPacket sendPacket;
    /** The same bytes again to the subnet's broadcast address, null where there is none. */
    private DatagramPacket broadcastPacket;
    private String name;
    private int channel = 1;
    private MulticastSocket socket;
    private NetworkInterface network;
    private Thread receiver;
    private long helloAt;
    private long peersVersion;
    private boolean talking;
    private int outgoingTalk;
    private int sent;
    private JitterBuffer incoming;
    private boolean receiving;
    private long sender;
    private int incomingTalk;
    private long voiceAt;
    private int receivedChannel;
    private boolean havePrevious;
    private long previousSender;
    private int previousTalk;
    private long previousAt;

    public Radio(long id, String name, Listener listener) {
        this(id, name, listener, defaultGroup(), 47149, true);
    }

    public Radio(long id, String name, Listener listener, InetAddress group,
            int port, boolean loopback) {
        if (!(group instanceof Inet4Address) || !group.isMulticastAddress()) {
            throw new IllegalArgumentException("IPv4 multicast group");
        }
        if (port < 1 || port > 65535) throw new IllegalArgumentException("port");
        this.id = id;
        this.name = Peers.clean(name);
        this.listener = Objects.requireNonNull(listener);
        this.group = group;
        this.port = port;
        this.loopback = loopback;
        sendPacket = new DatagramPacket(sendBytes, 0, group, port);
    }

    public synchronized void start(NetworkInterface nif) throws IOException {
        Objects.requireNonNull(nif);
        if (socket != null) return;
        MulticastSocket opened = new MulticastSocket(null);
        try {
            opened.setReuseAddress(true);
            opened.bind(new InetSocketAddress(port));
            opened.setNetworkInterface(nif);
            opened.setTimeToLive(1);
            opened.setOption(StandardSocketOptions.IP_MULTICAST_LOOP, loopback);
            opened.setSoTimeout(20);
            opened.joinGroup(new InetSocketAddress(group, port), nif);
            // A switch that tracks group members without anyone asking for them stops passing
            // the group on once one device has joined, common at home. Broadcast always passes,
            // so every packet goes out both ways and the receiver drops the second copy.
            opened.setBroadcast(true);
            broadcastPacket = null;
            for (InterfaceAddress address : nif.getInterfaceAddresses()) {
                if (address.getBroadcast() != null) {
                    broadcastPacket = new DatagramPacket(sendBytes, 0, address.getBroadcast(),
                            port);
                    break;
                }
            }
        } catch (IOException | RuntimeException e) {
            opened.close();
            throw e;
        }
        socket = opened;
        network = nif;
        helloAt = 0;
        receiving = talking = havePrevious = false;
        incoming = null;
        receiver = new Thread(() -> receive(opened), "walkie-receive");
        receiver.setDaemon(true);
        receiver.start();
    }

    public void stop() {
        Thread thread;
        synchronized (this) {
            if (socket == null) return;
            if (talking) talkEnd();
            send(Packet.bye(sendBytes, id, channel));
            try {
                socket.leaveGroup(new InetSocketAddress(group, port), network);
            } catch (IOException ignored) {
                // Closing still releases membership if the interface disappeared.
            }
            socket.close();
            socket = null;
            talking = receiving = false;
            thread = receiver;
        }
        if (thread != Thread.currentThread()) {
            try {
                thread.join(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public synchronized void setChannel(int channel) {
        if (channel < 1 || channel > 8) throw new IllegalArgumentException("channel");
        if (this.channel == channel) return;
        if (talking) talkEnd();
        this.channel = channel;
        // The receive thread ends the old talk before admitting this channel's audio.
        helloAt = 0;
    }

    public synchronized int channel() {
        return channel;
    }

    public synchronized void setName(String name) {
        this.name = Peers.clean(name);
        helloAt = 0;
    }

    public Peers peers() {
        return peers;
    }

    public synchronized void talkStart() {
        if (socket == null || talking) return;
        outgoingTalk = random.nextInt();
        sent = 0;
        talking = true;
    }

    public synchronized void talkFrame(short[] pcm320) {
        Objects.checkFromIndexSize(0, Packet.FRAME, pcm320.length);
        if (socket == null || !talking) return;
        MuLaw.encode(pcm320, 0, Packet.FRAME, encoded, 0);
        send(Packet.voice(sendBytes, id, channel, outgoingTalk, sent, encoded, 0));
        sent++;
    }

    public synchronized void talkEnd() {
        if (socket == null || !talking) return;
        talking = false;
        int length = Packet.end(sendBytes, id, channel, outgoingTalk, sent);
        boolean interrupted = false;
        for (int i = 0; i < 3; i++) {
            if (i > 0) {
                long until = System.nanoTime() + 20000000L;
                while (System.nanoTime() < until) {
                    try {
                        long left = until - System.nanoTime();
                        if (left > 0) Thread.sleep(left / 1000000, (int) (left % 1000000));
                    } catch (InterruptedException e) {
                        interrupted = true;
                    }
                }
            }
            send(length);
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    public synchronized JitterBuffer incoming() {
        return incoming;
    }

    private void receive(MulticastSocket opened) {
        // The extra byte makes oversized datagrams distinguishable from valid VOICE.
        byte[] bytes = new byte[Packet.MAX + 1];
        DatagramPacket datagram = new DatagramPacket(bytes, bytes.length);
        Packet packet = new Packet();
        while (!opened.isClosed()) {
            try {
                datagram.setLength(bytes.length);
                opened.receive(datagram);
                synchronized (this) {
                    if (socket != opened) break;
                    int length = datagram.getLength();
                    if (Packet.headerValid(bytes, length)) {
                        int type = bytes[3] & 255;
                        if ((type == Packet.HELLO || type == Packet.BYE
                                || bytes[12] == channel) && packet.parse(bytes, length)
                                && packet.id != id) {
                            handle(packet, now());
                        }
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Timers share this thread so every callback has the same owner.
            } catch (IOException e) {
                if (opened.isClosed()) break;
            } catch (RuntimeException ignored) {
                // A bad datagram or app callback cannot kill reception.
            }
            synchronized (this) {
                if (socket != opened) break;
                try {
                    tick(now());
                } catch (RuntimeException ignored) {
                    // Listener failures must not disable presence or timeout handling.
                }
            }
        }
    }

    private void handle(Packet packet, long now) {
        if (receiving && receivedChannel != channel) {
            endIncoming(now, incoming.receivedLimit());
        }
        if (packet.type == Packet.BYE) {
            peers.left(packet.id);
            if (receiving && sender == packet.id) endIncoming(now, incoming.receivedLimit());
            return;
        }
        peers.heard(packet.id, packet.channel, packet.name, now);
        if (packet.type == Packet.HELLO) return;
        if (packet.type == Packet.VOICE) {
            if (havePrevious && packet.id == previousSender && packet.talk == previousTalk
                    && now - previousAt < 10000) {
                // Accept a reordered tail without announcing the same talk twice.
                if (!receiving && incoming != null && sender == packet.id
                        && incomingTalk == packet.talk) incoming.put(packet.seq, packet.audio());
                return;
            }
            if (receiving && (sender != packet.id || incomingTalk != packet.talk)) return;
            boolean began = !receiving;
            if (began) {
                incoming = new JitterBuffer(3);
                receiving = true;
                sender = packet.id;
                incomingTalk = packet.talk;
                receivedChannel = packet.channel;
            }
            incoming.put(packet.seq, packet.audio());
            voiceAt = now;
            if (began) listener.onTalkStart(sender, peers.name(sender));
        } else if (packet.type == Packet.END && receiving && sender == packet.id
                && incomingTalk == packet.talk) {
            endIncoming(now, packet.frames);
        }
    }

    private void tick(long now) {
        if (helloAt == 0 || now >= helloAt) {
            send(Packet.hello(sendBytes, id, channel, name));
            helloAt = now + 3000;
        }
        peers.expire(now);
        if (receiving && (now - voiceAt >= 500 || receivedChannel != channel)) {
            endIncoming(now, incoming.receivedLimit());
        }
        long version = peers.version();
        if (version != peersVersion) {
            peersVersion = version;
            listener.onPeers();
        }
    }

    private void endIncoming(long now, int frames) {
        incoming.finish(frames);
        receiving = false;
        havePrevious = true;
        previousSender = sender;
        previousTalk = incomingTalk;
        previousAt = now;
        listener.onTalkEnd(sender, frames, incoming.lost());
    }

    private void send(int length) {
        if (socket == null) return;
        sendPacket.setLength(length);
        try {
            socket.send(sendPacket);
            if (broadcastPacket != null) {
                broadcastPacket.setLength(length);
                socket.send(broadcastPacket);
            }
        } catch (IOException ignored) {
            // Wi-Fi can disappear between frames; the peer's silence timer ends the talk.
        }
    }

    private static long now() {
        return System.nanoTime() / 1000000L;
    }

    private static InetAddress defaultGroup() {
        try {
            return InetAddress.getByAddress(new byte[] {(byte) 239, (byte) 255, 71, 49});
        } catch (IOException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
