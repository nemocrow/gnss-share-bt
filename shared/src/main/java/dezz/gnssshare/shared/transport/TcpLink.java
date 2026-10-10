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

import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;

/** {@link GnssLink} backed by a TCP {@link Socket}. */
public final class TcpLink implements GnssLink {
    private static final String TAG = "TcpLink";

    private final Socket socket;

    public TcpLink(Socket socket) {
        this.socket = socket;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return socket.getInputStream();
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return socket.getOutputStream();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing TCP socket", e);
        }
    }

    @Override
    public boolean isClosed() {
        return socket.isClosed();
    }

    @Override
    public String getRemoteName() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    @Override
    public Kind getKind() {
        return Kind.TCP;
    }

    @Override
    public boolean supportsReadTimeout() {
        return true;
    }

    @Override
    public boolean applyReadTimeout(int timeoutMs) {
        try {
            socket.setSoTimeout(timeoutMs);
            return true;
        } catch (IOException e) {
            Log.w(TAG, "Failed to set TCP read timeout", e);
            return false;
        }
    }
}
