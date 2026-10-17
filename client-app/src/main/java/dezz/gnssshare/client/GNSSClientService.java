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

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.location.Location;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.IntentCompat;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import dezz.gnssshare.proto.LocationProto;
import dezz.gnssshare.shared.transport.GnssFraming;
import dezz.gnssshare.shared.transport.GnssLink;

public class GNSSClientService extends Service implements ConnectionManager.ConnectionListener {
    private static final String TAG = "GNSSClientService";
    private static final String CHANNEL_ID = "GNSSClientChannel";
    private static final int NOTIFICATION_ID = 1;
    static final String ACTION_SETTINGS_CHANGED = "dezz.gnssshare.client.SETTINGS_CHANGED";

    private static GNSSClientService instance = null;

    private ConnectionManager connectionManager;
    private MockLocationManager mockLocationManager;
    private NotificationManager notificationManager;
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final AtomicBoolean isReceivingUpdates = new AtomicBoolean(false);

    private GnssLink currentLink;
    private Location lastReceivedLocation;
    private static long lastUpdateTime;
    private long lastLocationTimestamp = 0;
    private int lastBroadcastSatelliteCount = -1;
    private String failureDetail;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong lastRxAt = new AtomicLong();
    private Runnable receiveWatchdog;

    private static final String WIDGET_SATELLITE_STATUS_ACTION = "dezz.gnssshare.action.SATELLITE_STATUS";
    private static final String WIDGET_PACKAGE = "dezz.status.widget";

    public static boolean isServiceEnabled(Context context) {
        return Preferences.serviceEnabled(context);
    }

    public static boolean isServiceRunning() {
        return instance != null;
    }

    public static ConnectionManager.ConnectionState getConnectionState() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getCurrentState() : ConnectionManager.ConnectionState.DISCONNECTED;
    }

    public static String getServerAddress() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getServerAddress() : null;
    }

    public static String getStatusMessage() {
        return instance != null && instance.connectionManager != null ? instance.connectionManager.getStatusMessage() : null;
    }

    public static boolean isStatusFailure() {
        return instance != null && instance.connectionManager != null && instance.connectionManager.isStatusFailure();
    }

    public static void notifySettingsChanged(Context context) {
        Intent intent = new Intent(ACTION_SETTINGS_CHANGED);
        intent.setPackage(context.getPackageName());
        context.sendBroadcast(intent);
    }

    private final BroadcastReceiver bluetoothReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || intent.getAction() == null || connectionManager == null) {
                return;
            }
            switch (intent.getAction()) {
                case BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
                    connectionManager.onBluetoothAdapterStateChanged(state);
                }
                case BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    BluetoothDevice device = bluetoothDevice(intent);
                    if (device != null) {
                        connectionManager.onBluetoothAclConnected(device.getAddress());
                    }
                }
                case BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    BluetoothDevice device = bluetoothDevice(intent);
                    if (device != null) {
                        connectionManager.onBluetoothAclDisconnected(device.getAddress());
                    }
                }
                default -> {
                }
            }
        }
    };

    private final BroadcastReceiver settingsReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (connectionManager == null) {
                return;
            }
            Log.i(TAG, "Transport settings changed");
            startAsForeground();
            connectionManager.onSettingsChanged();
        }
    };

    private final ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(@NonNull Network network) {
            connectionManager.onNetworkAvailable();
        }

        @Override
        public void onLost(@NonNull Network network) {
            connectionManager.onNetworkLost();
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();

        notificationManager = getSystemService(NotificationManager.class);
        mockLocationManager = new MockLocationManager(this);
        connectionManager = new ConnectionManager(this, this);

        registerWiFiStateReceiver();
        registerBluetoothReceivers();
        createNotificationChannel();

        instance = this;
        startAsForeground();

        if (Preferences.transport(this) == Preferences.Transport.BLUETOOTH) {
            connectionManager.connect();
        }
    }

    private void registerWiFiStateReceiver() {
        NetworkRequest networkRequest = new NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build();

        ConnectivityManager connectivityManager = getSystemService(ConnectivityManager.class);
        connectivityManager.registerNetworkCallback(networkRequest, networkCallback);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        instance = null;
        disarmReceiveWatchdog();

        super.onDestroy();

        try {
            unregisterReceiver(bluetoothReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Bluetooth receiver was not registered", e);
        }
        try {
            unregisterReceiver(settingsReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Settings receiver was not registered", e);
        }

        if (connectionManager != null) {
            connectionManager.shutdown();
        }
        executor.shutdown();
    }

    public IBinder onBind(Intent intent) {
        return null;
    }

    // ConnectionManager.ConnectionListener implementation
    @Override
    public void onConnectionStateChanged(ConnectionManager.ConnectionState state, String message, String serverAddress, boolean failure) {
        Log.d(TAG, "Connection state: " + state + " - " + message);
        failureDetail = failure ? message : null;

        updateNotification();

        // Notify activity about connection status change
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .putExtra("state", state.toString())
                .putExtra("serverAddress", serverAddress)
                .putExtra("message", message)
                .putExtra("failure", failure));
    }

    @Override
    public void onConnectionEstablished(GnssLink link, String serverAddress) {
        Log.i(TAG, "Connection established, starting location updates");

        this.currentLink = link;
        startReceivingLocationUpdates(serverAddress);
    }

    @Override
    public void onDisconnected() {
        Log.i(TAG, "Connection lost, stopping location updates");

        this.currentLink = null;
        lastLocationTimestamp = 0;
        lastBroadcastSatelliteCount = -1;
        broadcastSatelliteStatusToWidget(0);

        stopReceivingLocationUpdates();

        // State callback already carried the message. Repeat it so a second
        // observer still sees the failure text instead of a blank disconnect.
        sendBroadcast(new Intent("dezz.gnssshare.CONNECTION_CHANGED")
                .putExtra("state", ConnectionManager.ConnectionState.DISCONNECTED.toString())
                .putExtra("message", connectionManager != null ? connectionManager.getStatusMessage() : failureDetail)
                .putExtra("failure", connectionManager != null && connectionManager.isStatusFailure()));
    }

    private void startReceivingLocationUpdates(String serverAddress) {
        if (isReceivingUpdates.get() || currentLink == null) {
            return;
        }

        isReceivingUpdates.set(true);
        final GnssLink link = currentLink;
        armReceiveWatchdog(link);

        if (!MockLocationManager.isMockLocationEnabled(getContentResolver())) {
            Log.w(TAG, "Mock locations not enabled - please enable in Developer Options");
            broadcastMockLocationStatus(getString(R.string.mock_location_enable_message), true);
        }

        try {
            mockLocationManager.startMockLocationProvider();
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up mock location provider", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }

        executor.execute(() -> {
            try {
                InputStream inputStream = link.getInputStream();

                while (isReceivingUpdates.get() && !link.isClosed()) {
                    try {
                        byte[] messageData = GnssFraming.readFrame(inputStream);
                        lastRxAt.set(System.currentTimeMillis());

                        // Parse protobuf message
                        LocationProto.ServerResponse response =
                                LocationProto.ServerResponse.parseFrom(messageData);

                        if (!connectionManager.isConnected()) {
                            connectionManager.setState(ConnectionManager.ConnectionState.CONNECTED, "Received first server response", serverAddress);
                        }

                        if (response.hasLocationUpdate()) {
                            handleLocationUpdate(response);
                        } else {
                            Log.i(TAG, "Server status: " + response.getStatus());
                            // Broadcast satellite info to activity
                            Intent intent = new Intent("dezz.gnssshare.LOCATION_UPDATE");
                            intent.putExtra("satellites", response.getSatellites());
                            sendBroadcast(intent);
                        }
                        broadcastSatelliteStatusToWidget(response.getSatellites());
                    } catch (IOException e) {
                        if (!link.isClosed()) {
                            Log.e(TAG, "Error receiving location update", e);
                        }
                        // Let ConnectionManager handle the reconnection
                        break;
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Error in location update receiver", e);
            }

            disarmReceiveWatchdog();
            connectionManager.disconnectIfActive(link, getString(R.string.connection_lost));
        });
    }

    private void stopReceivingLocationUpdates() {
        isReceivingUpdates.set(false);
        disarmReceiveWatchdog();

        // Stop providing mock locations
        if (instance == null) {
            mockLocationManager.shutdown();
        } else {
            mockLocationManager.stopMockLocationProvider(5000);
        }
    }

    private void armReceiveWatchdog(GnssLink link) {
        disarmReceiveWatchdog();
        if (link.supportsReadTimeout()) {
            return;
        }
        lastRxAt.set(System.currentTimeMillis());
        receiveWatchdog = new Runnable() {
            @Override
            public void run() {
                if (!isReceivingUpdates.get() || currentLink != link) {
                    return;
                }
                if (System.currentTimeMillis() - lastRxAt.get() > GnssFraming.CLIENT_READ_TIMEOUT_MS) {
                    Log.w(TAG, "No data for " + GnssFraming.CLIENT_READ_TIMEOUT_MS + "ms, closing link");
                    link.close();
                    return;
                }
                mainHandler.postDelayed(this, 1000);
            }
        };
        mainHandler.postDelayed(receiveWatchdog, GnssFraming.CLIENT_READ_TIMEOUT_MS);
    }

    private void disarmReceiveWatchdog() {
        if (receiveWatchdog != null) {
            mainHandler.removeCallbacks(receiveWatchdog);
            receiveWatchdog = null;
        }
    }

    private void handleLocationUpdate(LocationProto.ServerResponse response) {
        try {
            LocationProto.LocationUpdate locationUpdate = response.getLocationUpdate();
            // Create Android Location object
            Location location = new Location(LocationManager.GPS_PROVIDER);
            location.setLatitude(locationUpdate.getLatitude());
            location.setLongitude(locationUpdate.getLongitude());
            location.setTime(locationUpdate.getTimestamp());
            location.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
            location.setAltitude(locationUpdate.getAltitude());
            location.setAccuracy(locationUpdate.getAccuracy());
            location.setBearing(locationUpdate.getBearing());
            location.setSpeed(locationUpdate.getSpeed());

            Log.i(TAG, "Received location update: " + location);

            // Update internal state
            lastReceivedLocation = location;
            lastUpdateTime = System.currentTimeMillis();

            // Update notification with new location data
            updateNotification();

            // Broadcast location update to activity
            Intent intent = new Intent("dezz.gnssshare.LOCATION_UPDATE");
            intent.putExtra("location", location);
            intent.putExtra("satellites", response.getSatellites());
            intent.putExtra("provider", locationUpdate.getProvider());
            intent.putExtra("locationAge", locationUpdate.getLocationAge());
            sendBroadcast(intent);

            // Only push to mock locations if GPS timestamp is new
            // (avoids re-pushing stale location when server has no fresh GPS fix)
            long gpsTimestamp = locationUpdate.getTimestamp();
            if (gpsTimestamp != lastLocationTimestamp) {
                lastLocationTimestamp = gpsTimestamp;
                mockLocationManager.setMockLocation(location);
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Security exception - mock location permission denied", e);
            broadcastMockLocationStatus(getString(R.string.mock_location_permission_denied), true);
        } catch (Exception e) {
            Log.e(TAG, "Error setting mock location", e);
            broadcastMockLocationStatus(String.format(getString(R.string.mock_location_setup_failed), e.getMessage()), true);
        }
    }

    private void broadcastMockLocationStatus(String message, boolean error) {
        Intent intent = new Intent("dezz.gnssshare.MOCK_LOCATION_STATUS");
        intent.putExtra("message", message);
        intent.putExtra("error", error);
        sendBroadcast(intent);
    }

    /**
     * Broadcasts the satellite count to the external widget app, but only when the count changes.
     * Uses an explicit package target — required for receivers registered with RECEIVER_NOT_EXPORTED.
     */
    private void broadcastSatelliteStatusToWidget(int count) {
        if (count == lastBroadcastSatelliteCount) {
            return;
        }
        lastBroadcastSatelliteCount = count;
        Intent intent = new Intent(WIDGET_SATELLITE_STATUS_ACTION);
        intent.setPackage(WIDGET_PACKAGE);
        intent.putExtra("count", count);
        sendBroadcast(intent);
    }

    public static long getLastUpdateTime() {
        return lastUpdateTime;
    }

    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_LOW
        );
        channel.setDescription(String.format(getString(R.string.notification_channel_description), getString(R.string.app_name)));

        notificationManager.createNotificationChannel(channel);
    }

    private Notification createNotification(boolean isConnected) {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String title = isConnected ?
                String.format(getString(R.string.notification_title_connected), getString(R.string.app_name)) :
                String.format(getString(R.string.notification_title_disconnected), getString(R.string.app_name));

        String text = isConnected ?
                (lastReceivedLocation != null ?
                        String.format(getString(R.string.notification_text_connected),
                                (System.currentTimeMillis() - lastUpdateTime) / 1000.0) :
                        getString(R.string.notification_text_connected_no_age)) :
                (failureDetail != null ? failureDetail : getString(R.string.notification_text_disconnected));

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification() {
        if (notificationManager == null) {
            return;
        }
        boolean isConnected = connectionManager != null && connectionManager.isConnected();

        notificationManager.notify(NOTIFICATION_ID, createNotification(isConnected));
    }

    private void startAsForeground() {
        boolean connected = connectionManager != null && connectionManager.isConnected();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            if (Preferences.transport(this) == Preferences.Transport.BLUETOOTH
                    && BluetoothConnector.hasConnectPermission(this)) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            startForeground(NOTIFICATION_ID, createNotification(connected), type);
        } else {
            startForeground(NOTIFICATION_ID, createNotification(connected));
        }
    }

    private void registerBluetoothReceivers() {
        IntentFilter bluetoothFilter = new IntentFilter();
        bluetoothFilter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        bluetoothFilter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        bluetoothFilter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ContextCompat.registerReceiver(this, bluetoothReceiver, bluetoothFilter, ContextCompat.RECEIVER_EXPORTED);

        IntentFilter settingsFilter = new IntentFilter(ACTION_SETTINGS_CHANGED);
        ContextCompat.registerReceiver(this, settingsReceiver, settingsFilter, ContextCompat.RECEIVER_NOT_EXPORTED);
    }

    private static BluetoothDevice bluetoothDevice(Intent intent) {
        try {
            return IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        } catch (SecurityException e) {
            Log.w(TAG, "Bluetooth device extra requires permission", e);
            return null;
        }
    }
}
