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

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** {@link GnssLink} backed by a connected Bluetooth Classic RFCOMM socket. */
public final class BluetoothLink implements GnssLink {
    private static final String TAG = "BluetoothLink";

    private final BluetoothSocket socket;
    private final String remoteName;
    private volatile boolean closed;

    public BluetoothLink(BluetoothSocket socket) {
        this.socket = socket;
        this.remoteName = readRemoteName(socket);
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
        closed = true;
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing Bluetooth socket", e);
        }
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    @Override
    public String getRemoteName() {
        return remoteName;
    }

    @Override
    public Kind getKind() {
        return Kind.BLUETOOTH;
    }

    @SuppressLint("MissingPermission")
    private static String readRemoteName(BluetoothSocket socket) {
        BluetoothDevice device = socket.getRemoteDevice();
        if (device == null) {
            return "bluetooth";
        }
        String name = null;
        try {
            name = device.getName();
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth name unavailable", e);
        }
        String address = device.getAddress();
        if (name == null || name.isEmpty()) {
            return address != null ? address : "bluetooth";
        }
        return address == null ? name : name + " (" + address + ")";
    }
}
