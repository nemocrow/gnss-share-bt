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

import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.net.DhcpInfo;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dezz.gnssshare.shared.transport.GnssFraming;
import dezz.gnssshare.shared.transport.GnssLink;
import dezz.gnssshare.shared.transport.TcpLink;

public class ConnectionManager {
    private static final String TAG = "ConnectionManager";
    private static final long RECONNECT_DELAY = 500;
    private static final long CONNECTION_CHECK_INTERVAL = 1000;
    private static final int BLUETOOTH_RECONNECT_MIN_MS = 2000;
    private static final int BLUETOOTH_RECONNECT_MAX_MS = 15000;

    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
    }

    public interface ConnectionListener {
        void onConnectionStateChanged(ConnectionState state, String message, String serverAddress, boolean failure);

        void onConnectionEstablished(GnssLink link, String serverAddress);

        void onDisconnected();
    }

    private final ConnectionListener listener;
    private final Context context;

    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ConnectionState currentState = ConnectionState.DISCONNECTED;
    private String gatewayIP = null;
    private String serverAddress = null;
    private GnssLink link;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);
    private final AtomicBoolean isNetworkAvailable = new AtomicBoolean(false);
    private final AtomicBoolean bluetoothRetryAllowed = new AtomicBoolean(true);
    private final AtomicInteger connectGeneration = new AtomicInteger();
    private int bluetoothDelayMs = BLUETOOTH_RECONNECT_MIN_MS;

    private String statusMessage;
    private boolean statusFailure;
    private String lastNotifiedDetail;
    private boolean lastNotifiedFailure;

    private final Handler heartbeatHandler = new Handler(Looper.getMainLooper());
    private final Runnable heartbeatRunnable;

    private final Handler gatewayIpGetHandler = new Handler(Looper.getMainLooper());
    private final Runnable gatewayIpGetRunnable;

    public ConnectionManager(Context context, ConnectionListener listener) {
        this.context = context;
        this.listener = listener;

        this.heartbeatRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState != ConnectionState.DISCONNECTED && link != null) {
                    sendHeartbeat();
                    heartbeatHandler.postDelayed(this, GnssFraming.HEARTBEAT_INTERVAL_MS);
                }
            }
        };

        this.gatewayIpGetRunnable = new Runnable() {
            @Override
            public void run() {
                if (currentState == ConnectionState.CONNECTING && !isBluetoothMode()) {
                    findGatewayIP();
                    if (gatewayIP == null) {
                        gatewayIpGetHandler.postDelayed(this, CONNECTION_CHECK_INTERVAL);
                    } else {
                        // Now we know server address, let's update it in UI
                        setState(ConnectionState.CONNECTING, "Attempting to connect to server...", gatewayIP);
                        serverAddress = gatewayIP;
                        doConnect(connectGeneration.get());
                    }
                }
            }
        };
    }

    private void sendHeartbeat() {
        GnssLink current = link;
        if (current == null) {
            return;
        }
        executor.execute(() -> {
            try {
                synchronized (current) {
                    if (current.isClosed()) {
                        return;
                    }
                    GnssFraming.writeHeartbeat(current.getOutputStream());
                }
                Log.v(TAG, "Heartbeat sent");
            } catch (IOException e) {
                Log.w(TAG, "Failed to send heartbeat", e);
                mainHandler.post(() -> {
                    if (link == current) {
                        handleConnectionLoss();
                    }
                });
            }
        });
    }

    public void onNetworkAvailable() {
        Log.d(TAG, "Network available");
        isNetworkAvailable.set(true);
        gatewayIP = null;
        if (isBluetoothMode() || shutdown.get()) {
            return;
        }
        if (currentState == ConnectionState.DISCONNECTED) {
            connect();
        }
    }

    public void onNetworkLost() {
        Log.d(TAG, "Network lost");
        gatewayIP = null;
        isNetworkAvailable.set(false);
        if (isBluetoothMode() || shutdown.get()) {
            return;
        }
        if (currentState != ConnectionState.DISCONNECTED) {
            disconnect(context.getString(R.string.wifi_disconnected));
        }
    }

    public void onBluetoothAdapterStateChanged(int state) {
        if (!isBluetoothMode() || shutdown.get()) {
            return;
        }
        if (state == BluetoothAdapter.STATE_ON) {
            Log.i(TAG, "Bluetooth adapter is on");
            bluetoothRetryAllowed.set(true);
            resetBluetoothBackoff();
            if (currentState != ConnectionState.CONNECTED) {
                connect();
            }
        } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
            Log.i(TAG, "Bluetooth adapter is off");
            bluetoothRetryAllowed.set(false);
            String message = context.getString(R.string.bt_error_adapter_off);
            if (currentState != ConnectionState.DISCONNECTED || link != null) {
                disconnect(message);
            } else {
                setState(ConnectionState.DISCONNECTED, message, serverAddress, true);
            }
        }
    }

    public void onBluetoothAclConnected(String address) {
        if (!isBluetoothMode() || shutdown.get() || address == null) {
            return;
        }
        String selected = Preferences.bluetoothDeviceAddress(context);
        if (selected == null || !selected.equalsIgnoreCase(address)) {
            return;
        }
        if (currentState == ConnectionState.DISCONNECTED) {
            Log.i(TAG, "Selected Bluetooth device ACL is up, connecting");
            bluetoothRetryAllowed.set(true);
            resetBluetoothBackoff();
            connect();
        }
    }

    public void onBluetoothAclDisconnected(String address) {
        if (!isBluetoothMode() || shutdown.get() || address == null) {
            return;
        }
        String selected = Preferences.bluetoothDeviceAddress(context);
        if (selected == null || !selected.equalsIgnoreCase(address)) {
            return;
        }
        if (currentState != ConnectionState.CONNECTED) {
            // A new attempt may already be in progress. Don't abort it because the
            // previous RFCOMM socket closed. If we are idle, try again.
            if (currentState == ConnectionState.DISCONNECTED) {
                bluetoothRetryAllowed.set(true);
                scheduleReconnect();
            }
            return;
        }
        String label = serverAddress != null ? serverAddress : address;
        Log.i(TAG, "Bluetooth ACL dropped for " + label);
        bluetoothRetryAllowed.set(true);
        disconnect(context.getString(R.string.bt_error_disconnected, label));
        scheduleReconnect();
    }

    /** Re-read transport settings and open a fresh connection. */
    public void onSettingsChanged() {
        if (shutdown.get()) {
            return;
        }
        resetBluetoothBackoff();
        bluetoothRetryAllowed.set(true);
        statusMessage = null;
        statusFailure = false;
        lastNotifiedDetail = null;
        lastNotifiedFailure = false;
        disconnect("Transport settings changed", false);
        if (isBluetoothMode() || isNetworkAvailable.get()) {
            connect();
        }
    }

    public void connect() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTED) {
            return;
        }

        final int generation = connectGeneration.incrementAndGet();
        if (isBluetoothMode()) {
            connectBluetooth(generation);
            return;
        }

        boolean useGatewayIp = Preferences.useGatewayIp(context);
        if (useGatewayIp) {
            serverAddress = gatewayIP;
        } else {
            serverAddress = Preferences.serverAddress(context);
        }

        setState(ConnectionState.CONNECTING, "Attempting to connect to server...", serverAddress);

        if (useGatewayIp && serverAddress == null) {
            gatewayIpGetHandler.post(gatewayIpGetRunnable);
        } else {
            doConnect(generation);
        }
    }

    private void connectBluetooth(int generation) {
        BluetoothConnector.Attempt inspected = BluetoothConnector.inspect(context);
        serverAddress = inspected.endpoint;
        if (!inspected.readyToConnect()) {
            bluetoothRetryAllowed.set(inspected.retry);
            setState(ConnectionState.CONNECTING, inspected.message, inspected.endpoint, true);
            if (inspected.retry) {
                scheduleReconnect();
            }
            return;
        }

        bluetoothRetryAllowed.set(true);
        setState(ConnectionState.CONNECTING,
                context.getString(R.string.bt_connecting, inspected.endpoint),
                inspected.endpoint,
                false);

        executor.execute(() -> {
            BluetoothConnector.Attempt attempt = BluetoothConnector.connect(context);
            if (generation != connectGeneration.get() || shutdown.get()) {
                if (attempt.link != null) {
                    attempt.link.close();
                }
                return;
            }
            if (attempt.link == null) {
                Log.w(TAG, "Bluetooth connect failed: " + attempt.message);
                mainHandler.post(() -> {
                    if (generation != connectGeneration.get() || shutdown.get()) {
                        return;
                    }
                    bluetoothRetryAllowed.set(attempt.retry);
                    setState(ConnectionState.CONNECTING, attempt.message, attempt.endpoint, true);
                    if (attempt.retry) {
                        scheduleReconnect();
                    }
                });
                return;
            }

            resetBluetoothBackoff();
            link = attempt.link;
            serverAddress = attempt.endpoint;
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatHandler.post(heartbeatRunnable);
            mainHandler.post(() -> {
                if (generation != connectGeneration.get() || shutdown.get()) {
                    attempt.link.close();
                    if (link == attempt.link) {
                        link = null;
                    }
                    return;
                }
                listener.onConnectionEstablished(attempt.link, attempt.endpoint);
            });
        });
    }

    private void doConnect(int generation) {
        executor.execute(() -> {
            Socket socket = null;
            try {
                Log.i(TAG, "Connecting to " + serverAddress + ":" + GnssFraming.TCP_PORT);
                socket = new Socket();
                socket.connect(new InetSocketAddress(serverAddress, GnssFraming.TCP_PORT), GnssFraming.TCP_CONNECT_TIMEOUT_MS);
                socket.setSoTimeout(GnssFraming.CLIENT_READ_TIMEOUT_MS);

                if (generation != connectGeneration.get() || shutdown.get()) {
                    socket.close();
                    return;
                }
            } catch (IOException e) {
                Log.w(TAG, "Connection failed: " + e.getMessage());
                if (socket != null) {
                    try {
                        socket.close();
                    } catch (IOException ex) {
                        Log.w(TAG, "Error closing socket", ex);
                    }
                }
                if (generation != connectGeneration.get() || shutdown.get() || isBluetoothMode()) {
                    return;
                }
                scheduleReconnect();
                return;
            }

            TcpLink connected = new TcpLink(socket);
            link = connected;
            heartbeatHandler.removeCallbacks(heartbeatRunnable);
            heartbeatHandler.post(heartbeatRunnable);
            String address = serverAddress;
            mainHandler.post(() -> {
                if (generation != connectGeneration.get() || shutdown.get()) {
                    connected.close();
                    if (link == connected) {
                        link = null;
                    }
                    return;
                }
                listener.onConnectionEstablished(connected, address);
            });
        });
    }

    public void disconnect(String message) {
        disconnect(message, true);
    }

    /**
     * Closes {@code active} if it is still the current link. Used by the reader thread so a
     * newer connection, or a disconnect that already explained the failure, is left alone.
     */
    public void disconnectIfActive(GnssLink active, String message) {
        if (shutdown.get() || active == null || link != active) {
            return;
        }
        disconnect(message);
        scheduleReconnect();
    }

    private void disconnect(String message, boolean failure) {
        connectGeneration.incrementAndGet();
        setState(ConnectionState.DISCONNECTED, message, null, failure);

        heartbeatHandler.removeCallbacks(heartbeatRunnable);
        gatewayIpGetHandler.removeCallbacks(gatewayIpGetRunnable);
        mainHandler.removeCallbacksAndMessages(null);

        GnssLink closing = link;
        link = null;
        if (closing != null) {
            closing.close();
        }

        listener.onDisconnected();
    }

    public ConnectionState getCurrentState() {
        return currentState;
    }

    public boolean isConnected() {
        return currentState == ConnectionState.CONNECTED;
    }

    public String getServerAddress() {
        return serverAddress;
    }

    public String getStatusMessage() {
        return statusMessage;
    }

    public boolean isStatusFailure() {
        return statusFailure;
    }

    void setState(ConnectionState newState, String message, String serverAddress) {
        setState(newState, message, serverAddress, false);
    }

    private void setState(ConnectionState newState, String message, String serverAddress, boolean failure) {
        if (failure) {
            statusMessage = message;
            statusFailure = true;
        } else if (newState == ConnectionState.CONNECTED) {
            statusMessage = null;
            statusFailure = false;
        }
        if (currentState != newState
                || !Objects.equals(this.serverAddress, serverAddress)
                || !Objects.equals(lastNotifiedDetail, statusMessage)
                || lastNotifiedFailure != statusFailure) {
            Log.d(TAG, "State change: " + currentState + " -> " + newState + " (" + message + ")");
            currentState = newState;
            lastNotifiedDetail = statusMessage;
            lastNotifiedFailure = statusFailure;
            listener.onConnectionStateChanged(newState, statusMessage, serverAddress, statusFailure);
        }
    }

    private void findGatewayIP() {
        gatewayIP = getGatewayIpAddress(context);
        if (gatewayIP == null) {
            Log.w(TAG, "Can't get gateway IP address");
        } else {
            Log.d(TAG, "Gateway IP: " + gatewayIP);
        }
    }

    private void handleConnectionLoss() {
        if (shutdown.get()) {
            return;
        }

        if (currentState == ConnectionState.CONNECTED) {
            disconnect(context.getString(R.string.connection_lost));
            scheduleReconnect();
        }
    }

    public void scheduleReconnect() {
        if (shutdown.get()) {
            return;
        }
        if (isBluetoothMode()) {
            if (!bluetoothRetryAllowed.get()) {
                return;
            }
            int delay = consumeBluetoothDelay();
            mainHandler.postDelayed(() -> {
                if (!shutdown.get() && bluetoothRetryAllowed.get() && isBluetoothMode()
                        && currentState != ConnectionState.CONNECTED) {
                    Log.i(TAG, "Scheduling Bluetooth reconnection attempt in " + delay + "ms");
                    connect();
                }
            }, delay);
            return;
        }

        if (!this.isNetworkAvailable.get()) {
            return;
        }

        mainHandler.postDelayed(() -> {
            if (!shutdown.get() && this.isNetworkAvailable.get() && !isBluetoothMode()) {
                Log.i(TAG, "Scheduling reconnection attempt in " + RECONNECT_DELAY + "ms");
                connect();
            }
        }, RECONNECT_DELAY);
    }

    public void shutdown() {
        shutdown.set(true);
        bluetoothRetryAllowed.set(false);
        disconnect("Shutting down", false);
        executor.shutdown();
    }

    private boolean isBluetoothMode() {
        return Preferences.transport(context) == Preferences.Transport.BLUETOOTH;
    }

    private int consumeBluetoothDelay() {
        int delay = bluetoothDelayMs;
        bluetoothDelayMs = Math.min(bluetoothDelayMs * 2, BLUETOOTH_RECONNECT_MAX_MS);
        return delay;
    }

    private void resetBluetoothBackoff() {
        bluetoothDelayMs = BLUETOOTH_RECONNECT_MIN_MS;
    }

    private static String getGatewayIpAddress(Context context) {
        WifiManager wifiManager = context.getSystemService(WifiManager.class);
        if (wifiManager == null) {
            return null;
        }
        DhcpInfo dhcpInfo = wifiManager.getDhcpInfo();
        if (dhcpInfo == null || dhcpInfo.gateway == 0) {
            return null;
        }
        return intToIp(dhcpInfo.gateway);
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." +
                ((i >> 8) & 0xFF) + "." +
                ((i >> 16) & 0xFF) + "." +
                ((i >> 24) & 0xFF);
    }
}
