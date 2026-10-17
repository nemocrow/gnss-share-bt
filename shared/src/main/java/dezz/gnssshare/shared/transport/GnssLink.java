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

/**
 * A connected byte pipe. TCP sockets and Bluetooth RFCOMM sockets both implement this
 * so the GNSS framing and heartbeat logic does not care which radio is in use.
 */
public interface GnssLink {
    enum Kind {
        TCP,
        BLUETOOTH
    }

    InputStream getInputStream() throws IOException;

    OutputStream getOutputStream() throws IOException;

    void close();

    boolean isClosed();

    /** Human-readable remote endpoint for logs and the client UI. */
    String getRemoteName();

    Kind getKind();

    /**
     * Asks the underlying socket to time out blocking reads.
     * TCP supports this. Bluetooth Classic generally does not, and callers must
     * enforce stalls themselves.
     *
     * @return true if a read timeout is now in effect
     */
    default boolean applyReadTimeout(int timeoutMs) {
        return false;
    }

    /** @return true when {@link #applyReadTimeout(int)} can succeed for this link */
    default boolean supportsReadTimeout() {
        return false;
    }
}
