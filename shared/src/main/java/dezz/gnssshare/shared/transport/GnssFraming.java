/*
 * Copyright © 2025-2026 Dezz (https://github.com/DezzK)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package dezz.gnssshare.shared.transport;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

/**
 * Wire protocol shared by the Wi-Fi/TCP and Bluetooth RFCOMM transports.
 * <p>
 * Client to server: a single heartbeat byte once per second.
 * Server to client: a 4-byte big-endian length followed by a protobuf {@code ServerResponse}.
 */
public final class GnssFraming {
    public static final int TCP_PORT = 8887;

    /**
     * App-specific RFCOMM/SDP service UUID. Both sides must use this value.
     * It is intentionally not the standard SPP UUID, so it does not collide with
     * other serial profiles on the phone.
     */
    public static final UUID RFCOMM_UUID = UUID.fromString("f6c1b2a0-3e57-4d8a-9c14-7b2e6d5a1f30");
    public static final String RFCOMM_SDP_NAME = "GNSS Share";

    public static final byte HEARTBEAT = 0x01;
    public static final int HEARTBEAT_INTERVAL_MS = 1000;
    public static final int HEARTBEAT_TIMEOUT_MS = 3000;
    public static final int RESPONSE_INTERVAL_MS = 1000;
    public static final int CLIENT_READ_TIMEOUT_MS = 2500;
    public static final int SERVER_READ_TIMEOUT_MS = 1000;
    public static final int TCP_CONNECT_TIMEOUT_MS = 500;
    public static final int BLUETOOTH_CONNECT_TIMEOUT_MS = 12000;

    /** Reject absurd lengths instead of allocating them. Location messages are tiny. */
    private static final int MAX_FRAME_BYTES = 1024 * 1024;

    private GnssFraming() {
    }

    public static void writeHeartbeat(OutputStream out) throws IOException {
        out.write(HEARTBEAT);
        out.flush();
    }

    public static void writeFrame(OutputStream out, byte[] payload) throws IOException {
        out.write(intToBytes(payload.length));
        out.write(payload);
        out.flush();
    }

    public static byte[] readFrame(InputStream in) throws IOException {
        byte[] lengthBytes = new byte[4];
        readFully(in, lengthBytes);
        int length = bytesToInt(lengthBytes);
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Invalid frame length: " + length);
        }
        byte[] payload = new byte[length];
        if (length > 0) {
            readFully(in, payload);
        }
        return payload;
    }

    private static void readFully(InputStream in, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int read = in.read(buffer, offset, buffer.length - offset);
            if (read < 0) {
                throw new IOException("Connection closed by peer");
            }
            offset += read;
        }
    }

    private static byte[] intToBytes(int value) {
        return new byte[]{
                (byte) (value >>> 24),
                (byte) (value >>> 16),
                (byte) (value >>> 8),
                (byte) value
        };
    }

    private static int bytesToInt(byte[] bytes) {
        return ((bytes[0] & 0xFF) << 24) |
                ((bytes[1] & 0xFF) << 16) |
                ((bytes[2] & 0xFF) << 8) |
                (bytes[3] & 0xFF);
    }
}
