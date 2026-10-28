// SPDX-License-Identifier: Apache-2.0
package cat.kenny.r1.usbemu;

import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class Transport {
    static LocalSocket connect(String name, String generation) throws IOException {
        LocalSocket socket = new LocalSocket(LocalSocket.SOCKET_SEQPACKET);
        try {
            socket.connect(new LocalSocketAddress(name, LocalSocketAddress.Namespace.RESERVED));
            socket.setSoTimeout(1500);
            socket.setSendBufferSize(4096); socket.setReceiveBufferSize(4096);
            socket.getOutputStream().write(generation.getBytes(StandardCharsets.US_ASCII));
            return socket;
        } catch (IOException e) { socket.close(); throw e; }
    }
}
