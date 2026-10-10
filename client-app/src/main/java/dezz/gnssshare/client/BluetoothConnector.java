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

package dezz.gnssshare.client;

import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.core.content.ContextCompat;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import dezz.gnssshare.shared.transport.BluetoothLink;
import dezz.gnssshare.shared.transport.GnssFraming;
import dezz.gnssshare.shared.transport.GnssLink;

/**
 * Opens an RFCOMM connection to the paired phone selected in preferences.
 * Fast failures (permission, adapter, pairing) are reported without blocking.
 */
final class BluetoothConnector {
    private static final String TAG = "BluetoothConnector";

    static final class Attempt {
        final GnssLink link;
        final String endpoint;
        final String message;
        final boolean retry;

        private Attempt(GnssLink link, String endpoint, String message, boolean retry) {
            this.link = link;
            this.endpoint = endpoint;
            this.message = message;
            this.retry = retry;
        }

        static Attempt connected(GnssLink link, String endpoint) {
            return new Attempt(link, endpoint, null, false);
        }

        static Attempt failed(String endpoint, String message, boolean retry) {
            return new Attempt(null, endpoint, message, retry);
        }

        boolean readyToConnect() {
            return link == null && message == null;
        }
    }

    private BluetoothConnector() {
    }

    /**
     * Checks permission, adapter and bond state. When the returned attempt has a null link
     * and a null message, the caller should proceed to {@link #connect(Context)}.
     */
    static Attempt inspect(Context context) {
        String address = Preferences.bluetoothDeviceAddress(context);
        String endpoint = endpointLabel(context, address);

        if (!hasConnectPermission(context)) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_permission), false);
        }
        if (address == null || address.isEmpty()) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_no_device), false);
        }

        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_no_adapter), false);
        }
        if (!adapter.isEnabled()) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_adapter_off), false);
        }

        BluetoothDevice device;
        try {
            device = adapter.getRemoteDevice(address);
        } catch (IllegalArgumentException e) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_not_paired, endpoint), false);
        }
        if (!isBonded(device)) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_not_paired, endpoint), true);
        }
        return new Attempt(null, endpoint, null, true);
    }

    @SuppressLint("MissingPermission")
    static Attempt connect(Context context) {
        Attempt inspected = inspect(context);
        if (!inspected.readyToConnect()) {
            return inspected;
        }

        BluetoothAdapter adapter = adapter(context);
        if (adapter == null) {
            return Attempt.failed(inspected.endpoint, context.getString(R.string.bt_error_no_adapter), false);
        }

        BluetoothDevice device = adapter.getRemoteDevice(Preferences.bluetoothDeviceAddress(context));
        BluetoothSocket socket = null;
        ExecutorService connectPool = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "bt-rfcomm-connect");
            thread.setDaemon(true);
            return thread;
        });
        try {
            try {
                adapter.cancelDiscovery();
            } catch (SecurityException e) {
                Log.d(TAG, "cancelDiscovery not permitted, continuing", e);
            }

            socket = device.createRfcommSocketToServiceRecord(GnssFraming.RFCOMM_UUID);
            BluetoothSocket connecting = socket;
            Future<Void> connectTask = connectPool.submit(() -> {
                connecting.connect();
                return null;
            });
            try {
                connectTask.get(GnssFraming.BLUETOOTH_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                connecting.close();
                String raw = "timed out after " + (GnssFraming.BLUETOOTH_CONNECT_TIMEOUT_MS / 1000) + "s";
                return Attempt.failed(inspected.endpoint,
                        context.getString(R.string.bt_error_timeout, inspected.endpoint, raw),
                        adapter.isEnabled());
            } catch (ExecutionException e) {
                connecting.close();
                Throwable cause = e.getCause() == null ? e : e.getCause();
                return failureFrom(context, inspected.endpoint, adapter, cause);
            }
            if (!adapter.isEnabled()) {
                connecting.close();
                return Attempt.failed(inspected.endpoint, context.getString(R.string.bt_error_adapter_off), false);
            }
            return Attempt.connected(new BluetoothLink(connecting), inspected.endpoint);
        } catch (SecurityException e) {
            closeQuietly(socket);
            Log.e(TAG, "Bluetooth connect permission denied", e);
            return Attempt.failed(inspected.endpoint, context.getString(R.string.bt_error_permission), false);
        } catch (IOException e) {
            closeQuietly(socket);
            return failureFrom(context, inspected.endpoint, adapter, e);
        } catch (InterruptedException e) {
            closeQuietly(socket);
            Thread.currentThread().interrupt();
            return Attempt.failed(inspected.endpoint,
                    context.getString(R.string.bt_error_failed, inspected.endpoint, "interrupted"),
                    true);
        } finally {
            connectPool.shutdownNow();
        }
    }

    static boolean hasConnectPermission(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(context, android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static Attempt failureFrom(Context context, String endpoint, BluetoothAdapter adapter, Throwable error) {
        Log.w(TAG, "RFCOMM connect failed", error);
        if (adapter == null || !adapter.isEnabled()) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_adapter_off), false);
        }
        String raw = error.getMessage();
        if (raw == null || raw.isBlank()) {
            raw = error.getClass().getSimpleName();
        }
        String lower = raw.toLowerCase(Locale.US);
        if (lower.contains("timed out") || lower.contains("timeout")) {
            return Attempt.failed(endpoint, context.getString(R.string.bt_error_timeout, endpoint, raw), true);
        }
        return Attempt.failed(endpoint, context.getString(R.string.bt_error_failed, endpoint, raw), true);
    }

    @SuppressLint("MissingPermission")
    private static boolean isBonded(BluetoothDevice device) {
        try {
            return device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException e) {
            Log.w(TAG, "Bond state unavailable", e);
            return false;
        }
    }

    private static String endpointLabel(Context context, String address) {
        String name = Preferences.bluetoothDeviceName(context);
        if (name != null && !name.isEmpty()) {
            return name;
        }
        if (address != null && !address.isEmpty()) {
            return address;
        }
        return context.getString(R.string.bluetooth_device_none);
    }

    private static BluetoothAdapter adapter(Context context) {
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    private static void closeQuietly(BluetoothSocket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException e) {
            Log.w(TAG, "Error closing Bluetooth socket", e);
        }
    }
}
