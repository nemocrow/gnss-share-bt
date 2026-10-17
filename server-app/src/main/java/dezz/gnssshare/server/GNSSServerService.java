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

package dezz.gnssshare.server;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.Granularity;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import java.io.IOException;
import java.io.InputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dezz.gnssshare.proto.LocationProto;
import dezz.gnssshare.shared.ServerStatus;
import dezz.gnssshare.shared.transport.BluetoothLink;
import dezz.gnssshare.shared.transport.GnssFraming;
import dezz.gnssshare.shared.transport.GnssLink;
import dezz.gnssshare.shared.transport.TcpLink;

public class GNSSServerService extends Service {
    private static final String TAG = "GNSSServerService";
    private static final String CHANNEL_ID = "GNSSServerChannel";
    private static final int NOTIFICATION_ID = 1;
    private static final String PREF_IS_SERVICE_ENABLED = "isServiceEnabled";
    private static final long BT_AUTO_STOP_DELAY_MS = 10000; // 10 seconds

    private static boolean running = false;
    private static GNSSServerService instance = null;

    private String serverStartError = null;
    private volatile String bluetoothStatusDetail;

    private ServerSocket serverSocket;
    private BluetoothServerSocket bluetoothServerSocket;
    private final AtomicInteger bluetoothGeneration = new AtomicInteger();
    private final AtomicBoolean bluetoothAutostarted = new AtomicBoolean(false);
    private LocationManager locationManager = null;
    private FusedLocationProviderClient fusedLocationProviderClient = null;
    private final com.google.android.gms.location.LocationListener fusedLocationListener = this::handleLocationUpdate;
    private final LocationListener locationListener = new LocationListener() {
        @Override
        public void onLocationChanged(@NonNull Location location) {
            handleLocationUpdate(location);
        }

        @Override
        public void onProviderEnabled(@NonNull String provider) {
            Log.d(TAG, "Provider enabled: " + provider);
        }

        @Override
        public void onProviderDisabled(@NonNull String provider) {
            Log.d(TAG, "Provider disabled: " + provider);
        }
    };

    private NotificationManager notificationManager;

    private final ArrayList<ClientHandler> connectedClients = new ArrayList<>();
    private final ExecutorService executor = Executors.newCachedThreadPool();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final GnssStatus.Callback gnssStatusCallback = new GnssStatus.Callback() {
        @Override
        public void onSatelliteStatusChanged(@NonNull GnssStatus status) {
            gnssStatus = status;
            lastServerResponse.setSatellites(getSatelliteCount());

            if (isServiceRunning() && !connectedClients.isEmpty() && !lastServerResponse.hasLocationUpdate()) {
                mainHandler.post(() -> updateNotification("GNSS status changed"));
            }
        }
    };

    private final LocationProto.ServerResponse.Builder lastServerResponse = LocationProto.ServerResponse.newBuilder()
            .setStatus(ServerStatus.UNINITIALIZED.name());

    // We need to use such runnable to make scheduled stopping cancelable
    private final Runnable stopLocationUpdates = this::stopLocationUpdates;

    // Bluetooth auto-stop runnable
    private final Runnable btAutoStopRunnable = this::btAutoStopService;

    private GnssStatus gnssStatus = null;
    private boolean isGnssActive = false;

    private final BroadcastReceiver bluetoothAdapterReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null || !BluetoothAdapter.ACTION_STATE_CHANGED.equals(intent.getAction())) {
                return;
            }
            if (!Preferences.bluetoothTransportEnabled(GNSSServerService.this)) {
                return;
            }
            int state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR);
            if (state == BluetoothAdapter.STATE_ON) {
                Log.i(TAG, "Bluetooth adapter on, restarting RFCOMM listener");
                restartBluetoothListener();
            } else if (state == BluetoothAdapter.STATE_OFF || state == BluetoothAdapter.STATE_TURNING_OFF) {
                Log.i(TAG, "Bluetooth adapter off, stopping RFCOMM listener");
                stopBluetoothListener();
                setBluetoothStatus(getString(R.string.bt_transport_adapter_off));
            }
        }
    };

    @Override
    public void onCreate() {
        notificationManager = getSystemService(NotificationManager.class);

        createNotificationChannel();

        running = true;
        instance = this;

        ContextCompat.registerReceiver(
                this,
                bluetoothAdapterReceiver,
                new IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED);

        refreshForegroundNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        serverStartError = null;
        startServer();

        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        running = false;
        instance = null;

        try {
            unregisterReceiver(bluetoothAdapterReceiver);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Bluetooth adapter receiver was not registered", e);
        }

        cancelBluetoothAutoStop();
        stopServer();
        stopLocationUpdates();

        locationManager = null;

        executor.shutdown();

        notificationManager.cancel(NOTIFICATION_ID);
        notificationManager = null;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void initializeLocationManager() {
        if (locationManager != null) {
            return;
        }

        locationManager = getSystemService(LocationManager.class);

        try {
            locationManager.registerGnssStatusCallback(gnssStatusCallback, mainHandler);

            Log.d(TAG, "GNSS status callback registered");
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to register GNSS status callback", e);
        }
    }

    private void initializeFusedLocationProviderClient() {
        // Supported on Android 12+
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return;
        }

        // User opted out
        if (!Preferences.fusedLocationEnabled(this)) {
            return;
        }

        try {
            // Google Play Services are required
            if (!isGooglePlayServicesAvailable(this)) {
                return;
            }

            if (fusedLocationProviderClient != null) {
                return;
            }

            fusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(this);
        } catch (NoClassDefFoundError e) {
            Log.w(TAG, "Google Play Services not available on this device", e);
            fusedLocationProviderClient = null;
        }
    }

    public static boolean isFusedLocationSupported(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return false;
        }
        try {
            GoogleApiAvailability api = GoogleApiAvailability.getInstance();
            return api.isGooglePlayServicesAvailable(context) == ConnectionResult.SUCCESS;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    private void startServer() {
        executor.execute(() -> {
            try {
                serverSocket = new ServerSocket(GnssFraming.TCP_PORT);
                Log.d(TAG, "Server started on port " + GnssFraming.TCP_PORT);
            } catch (Throwable e) {
                Log.e(TAG, "Error starting server", e);
                serverStartError = e.getMessage();
                stopServer();
                return;
            }

            while (serverSocket != null && !serverSocket.isClosed()) {
                try {
                    Socket clientSocket = serverSocket.accept();
                    Log.d(TAG, "Client connected: " + clientSocket.getRemoteSocketAddress());
                    acceptClient(new TcpLink(clientSocket));
                } catch (IOException e) {
                    if (serverSocket != null && !serverSocket.isClosed()) {
                        Log.e(TAG, "Error accepting client connection", e);
                    }
                }
            }
        });

        if (bluetoothAutostarted.compareAndSet(false, true)) {
            if (Preferences.bluetoothTransportEnabled(this)) {
                restartBluetoothListener();
            } else {
                setBluetoothStatus(getString(R.string.bt_transport_disabled));
            }
        }
    }

    private void acceptClient(GnssLink link) {
        ClientHandler clientHandler = new ClientHandler(link);
        synchronized (connectedClients) {
            connectedClients.add(clientHandler);
            // Start location updates when first client connects
            if (connectedClients.size() == 1) {
                mainHandler.post(this::startLocationUpdates);
            }
        }
        executor.execute(clientHandler);

        // Cancel any pending BT auto-stop since a client just connected
        cancelBluetoothAutoStop();

        updateNotification("New client connected");
    }

    private void stopServer() {
        Log.d(TAG, "Stopping server");
        stopBluetoothListener();
        try {
            if (serverSocket != null) {
                if (!serverSocket.isClosed()) {
                    serverSocket.close();
                }
                serverSocket = null;
            }

            // Copy clients list to avoid concurrent modification
            ArrayList<ClientHandler> clients;
            synchronized (connectedClients) {
                clients = new ArrayList<>(connectedClients);
            }
            for (ClientHandler client : clients) {
                client.disconnect();
            }
        } catch (IOException e) {
            Log.e(TAG, "Error stopping server", e);
        }
    }

    private void startLocationUpdates() {
        // If location updates were scheduled to be stopped, remove the scheduled action
        mainHandler.removeCallbacks(this.stopLocationUpdates);

        initializeLocationManager();
        initializeFusedLocationProviderClient();

        try {
            Log.d(TAG, "Starting location updates...");

            lastServerResponse.setStatus(ServerStatus.AWAITING_LOCATION.name());

            final int MIN_INTERVAL_MS = 500;
            final int MIN_DISTANCE_M = 0;
            if (fusedLocationProviderClient != null) {
                LocationRequest request = new LocationRequest.Builder(MIN_INTERVAL_MS)
                        .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
                        .setWaitForAccurateLocation(false)
                        .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                        .setGranularity(Granularity.GRANULARITY_FINE)
                        .build();
                fusedLocationProviderClient.requestLocationUpdates(request, fusedLocationListener, Looper.getMainLooper());
            } else {
                locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        MIN_INTERVAL_MS,
                        MIN_DISTANCE_M,
                        locationListener
                );
            }

            Log.d(TAG, "Location updates started");

            isGnssActive = true;

            updateNotification("Started location updates");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        } catch (Exception e) {
            Log.e(TAG, "Error starting location updates", e);
        }
    }

    private void stopLocationUpdates() {
        if (running && !connectedClients.isEmpty()) {
            Log.w(TAG, "Location updates not stopped: still have clients connected");
            return;
        }

        Log.d(TAG, "Stopping location updates...");

        if (locationManager != null) {
            locationManager.removeUpdates(locationListener);
            locationManager.unregisterGnssStatusCallback(gnssStatusCallback);
            locationManager = null;
        }

        if (fusedLocationProviderClient != null) {
            fusedLocationProviderClient.removeLocationUpdates(fusedLocationListener);
            fusedLocationProviderClient = null;
        }

        Log.d(TAG, "Location updates stopped");

        isGnssActive = false;
        lastServerResponse.setStatus(ServerStatus.LOCATION_STOPPED.name());

        updateNotification("Stopped location updates");
    }

    private void handleLocationUpdate(Location location) {
        Log.d(TAG, String.format("Handling location update: %s", location));

        // Create protobuf message
        LocationProto.LocationUpdate.Builder builder = LocationProto.LocationUpdate.newBuilder()
                .setTimestamp(location.getTime())
                .setLatitude(location.getLatitude())
                .setLongitude(location.getLongitude())
                .setProvider(location.getProvider())
                .setLocationAge((System.currentTimeMillis() - location.getTime()) / 1000.0f);

        if (location.hasAltitude()) {
            builder.setAltitude(location.getAltitude());
        }
        if (location.hasAccuracy()) {
            builder.setAccuracy(location.getAccuracy());
        }
        if (location.hasBearing()) {
            builder.setBearing(location.getBearing());
        }
        if (location.hasSpeed()) {
            builder.setSpeed(location.getSpeed());
        }

        lastServerResponse.setStatus(ServerStatus.TRANSMITTING_LOCATION.name())
                .setLocationUpdate(builder.build());

        updateNotification("Received location update");

        // Broadcast to all connected clients
        Log.d(TAG, "Broadcasting location to " + connectedClients.size() + " clients: " + location);
        executor.execute(() -> broadcastLocationUpdate(lastServerResponse.build()));
    }

    private void broadcastLocationUpdate(LocationProto.ServerResponse serverResponse) {
        // Copy clients list to avoid concurrent modification
        ArrayList<ClientHandler> clients;
        synchronized (connectedClients) {
            clients = new ArrayList<>(connectedClients);
        }
        for (ClientHandler client : clients) {
            client.sendResponse(serverResponse);
        }
    }

    private void onClientDisconnected(ClientHandler client) {
        synchronized (connectedClients) {
            boolean wasRemoved = connectedClients.remove(client);
            if (wasRemoved) {
                Log.d(TAG, "Client removed: " + client.getClientAddress() +
                        ". Remaining clients: " + connectedClients.size());

                if (running && connectedClients.isEmpty()) {
                    Log.d(TAG, "No clients remaining, scheduling stopping of location updates in 15 seconds");
                    mainHandler.removeCallbacks(this.stopLocationUpdates);
                    mainHandler.postDelayed(this.stopLocationUpdates, 15000);
                }
            } else {
                Log.d(TAG, "Client was already removed: " + client.getClientAddress());
            }
        }

        // Evaluate auto-stop (will schedule only if both BT and clients are gone)
        evaluateAutoStop();

        mainHandler.post(() -> updateNotification("Client disconnected"));
    }

    public static boolean isServiceRunning() {
        return running;
    }

    // Public methods for checking service state
    public static boolean isServiceEnabled(Context context) {
        return getPrefs(context).getBoolean(PREF_IS_SERVICE_ENABLED, false);
    }

    public static void setServiceEnabled(Context context, boolean enabled) {
        getPrefs(context).edit().putBoolean(PREF_IS_SERVICE_ENABLED, enabled).apply();
    }

    private static SharedPreferences getPrefs(Context context) {
        return context.getSharedPreferences(context.getPackageName() + "_preferences", MODE_PRIVATE);
    }

    // Notifications

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.app_name),
                    NotificationManager.IMPORTANCE_LOW
            );
            channel.setDescription(getString(R.string.app_description));
            notificationManager.createNotificationChannel(channel);
        }
    }

    private Notification createNotification() {
        Intent intent = new Intent(this, MainActivity.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(
                this, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String content;
        if (serverStartError == null) {
            synchronized (connectedClients) {
                Log.d(TAG, String.format("Clients connected: %d", connectedClients.size()));
                if (connectedClients.isEmpty()) {
                    Log.d(TAG, "No clients connected");
                    content = getString(R.string.notification_no_clients);
                } else {
                    content = String.format(
                            getString(R.string.notification_clients),
                            connectedClients.size()
                    );
                }
            }

            content += getString(R.string.notification_divider);

            if (isGnssActive) {
                content += String.format(
                        getString(R.string.notification_satellites),
                        getSatelliteCount()
                );


                if (lastServerResponse.hasLocationUpdate()) {
                    content += getString(R.string.notification_divider) + String.format(
                            getString(R.string.notification_age),
                            (System.currentTimeMillis() - lastServerResponse.getLocationUpdate().getTimestamp()) / 1000.0
                    );
                }
            } else {
                content += getString(R.string.notification_gnss_inactive);
            }

            if (Preferences.bluetoothTransportEnabled(this) && bluetoothStatusDetail != null) {
                content += getString(R.string.notification_divider) + bluetoothStatusDetail;
            }
        } else {
            content = serverStartError;
        }

        // Stop action for notification shade
        Intent stopIntent = new Intent("dezz.gnssshare.server.STOP");
        stopIntent.setPackage(getPackageName());
        PendingIntent stopPendingIntent = PendingIntent.getBroadcast(
                this, 1, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(String.format(getString(serverStartError == null ? R.string.notification_title : R.string.notification_failed_title), getString(R.string.app_name)))
                .setContentText(content)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentIntent(pendingIntent)
                .addAction(android.R.drawable.ic_media_pause, getString(R.string.disable_service), stopPendingIntent)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String reason) {
        if (notificationManager == null) {
            return;
        }
        Log.d(TAG, "Updating notification: " + reason);
        notificationManager.notify(NOTIFICATION_ID, createNotification());
    }

    public int getSatelliteCount() {
        if (gnssStatus == null) {
            return 0;
        }
        return gnssStatus.getSatelliteCount();
    }

    private boolean isGooglePlayServicesAvailable(Context context) {
        GoogleApiAvailability googleApiAvailability = GoogleApiAvailability.getInstance();
        int resultCode = googleApiAvailability.isGooglePlayServicesAvailable(context);
        return resultCode == ConnectionResult.SUCCESS;
    }

    // Bluetooth auto-stop methods
    //
    // Unified logic:
    //   - evaluateAutoStop() is called on BT disconnect and on last client disconnect.
    //     Schedules a 10s stop only when BOTH all BT trigger devices AND all clients are gone.
    //   - cancelBluetoothAutoStop() is called on BT reconnect and on new client connect.
    //   - btAutoStopService() re-checks conditions as a safety net before actually stopping.

    /**
     * Called from BluetoothReceiver (BT disconnect) and onClientDisconnected (last client gone).
     * Schedules auto-stop only if both conditions are met.
     */
    public static void evaluateAutoStop() {
        if (instance != null) {
            instance.doEvaluateAutoStop();
        }
    }

    /** Called from BluetoothReceiver (BT reconnect) and startServer (new client connect). */
    public static void cancelBluetoothAutoStopRequest() {
        if (instance != null) {
            instance.cancelBluetoothAutoStop();
        }
    }

    private void doEvaluateAutoStop() {
        if (!running) return;

        // Only auto-stop if BT auto-start/stop feature is enabled in preferences
        if (!Preferences.bluetoothAutoStartEnabled(this)) {
            Log.d(TAG, "BT auto-start/stop disabled in preferences, skipping auto-stop evaluation");
            return;
        }

        boolean btGone = BluetoothReceiver.allTriggerDevicesDisconnected();
        boolean clientsGone;
        synchronized (connectedClients) {
            clientsGone = connectedClients.isEmpty();
        }

        if (btGone && clientsGone) {
            Log.d(TAG, "All BT devices and clients disconnected, scheduling auto-stop in " + BT_AUTO_STOP_DELAY_MS + "ms");
            mainHandler.removeCallbacks(btAutoStopRunnable);
            mainHandler.postDelayed(btAutoStopRunnable, BT_AUTO_STOP_DELAY_MS);
        } else {
            Log.d(TAG, "Auto-stop not needed (BT connected: " + !btGone + ", clients connected: " + !clientsGone + ")");
        }
    }

    private void cancelBluetoothAutoStop() {
        Log.d(TAG, "Cancelling Bluetooth auto-stop");
        mainHandler.removeCallbacks(btAutoStopRunnable);
    }

    /**
     * Called when the user toggles Bluetooth location sharing.
     * Wi-Fi/TCP keeps running either way.
     */
    public static void onBluetoothTransportPreferenceChanged() {
        if (instance != null) {
            instance.applyBluetoothTransportPreference();
        }
    }

    /** Restarts the RFCOMM listener when it is not already accepting. Safe to call from the UI. */
    public static void retryBluetoothListenerIfIdle() {
        if (instance != null) {
            instance.retryBluetoothIfIdle();
        }
    }

    public static String bluetoothStatusDetail(Context context) {
        if (!Preferences.bluetoothTransportEnabled(context)) {
            return context.getString(R.string.bt_transport_disabled);
        }
        if (instance == null || instance.bluetoothStatusDetail == null) {
            return context.getString(R.string.bt_transport_waiting_service);
        }
        return instance.bluetoothStatusDetail;
    }

    private void applyBluetoothTransportPreference() {
        refreshForegroundNotification();
        if (Preferences.bluetoothTransportEnabled(this)) {
            restartBluetoothListener();
        } else {
            stopBluetoothListener();
            disconnectBluetoothClients();
            setBluetoothStatus(getString(R.string.bt_transport_disabled));
        }
    }

    private void retryBluetoothIfIdle() {
        if (!running || !Preferences.bluetoothTransportEnabled(this)) {
            return;
        }
        synchronized (this) {
            if (bluetoothServerSocket != null) {
                return;
            }
        }
        restartBluetoothListener();
    }

    private void refreshForegroundNotification() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            int type = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION;
            // connectedDevice is only legal once a Bluetooth runtime permission is held.
            // Without it, startForeground throws and would take down Wi-Fi sharing too.
            if (Preferences.bluetoothTransportEnabled(this) && hasBluetoothConnectPermission()) {
                type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE;
            }
            startForeground(NOTIFICATION_ID, createNotification(), type);
        } else {
            startForeground(NOTIFICATION_ID, createNotification());
        }
    }

    private void restartBluetoothListener() {
        final int generation = bluetoothGeneration.incrementAndGet();
        closeBluetoothServerSocket();
        if (!running || !Preferences.bluetoothTransportEnabled(this)) {
            setBluetoothStatus(getString(R.string.bt_transport_disabled));
            return;
        }
        executor.execute(() -> listenBluetooth(generation));
    }

    private void stopBluetoothListener() {
        bluetoothGeneration.incrementAndGet();
        closeBluetoothServerSocket();
    }

    private void closeBluetoothServerSocket() {
        BluetoothServerSocket server;
        synchronized (this) {
            server = bluetoothServerSocket;
            bluetoothServerSocket = null;
        }
        if (server != null) {
            try {
                server.close();
            } catch (IOException e) {
                Log.w(TAG, "Error closing Bluetooth server socket", e);
            }
        }
    }

    private void disconnectBluetoothClients() {
        ArrayList<ClientHandler> clients;
        synchronized (connectedClients) {
            clients = new ArrayList<>(connectedClients);
        }
        for (ClientHandler client : clients) {
            if (client.getKind() == GnssLink.Kind.BLUETOOTH) {
                client.disconnect();
            }
        }
    }

    private void setBluetoothStatus(String detail) {
        bluetoothStatusDetail = detail;
        Log.i(TAG, "Bluetooth transport: " + detail);
        mainHandler.post(() -> updateNotification("Bluetooth status"));
    }

    @SuppressLint("MissingPermission")
    private void listenBluetooth(int generation) {
        if (generation != bluetoothGeneration.get() || !running) {
            return;
        }
        if (!hasBluetoothConnectPermission()) {
            setBluetoothStatus(getString(R.string.bt_transport_permission));
            return;
        }
        BluetoothAdapter adapter = bluetoothAdapter();
        if (adapter == null) {
            setBluetoothStatus(getString(R.string.bt_transport_failed, getString(R.string.bt_transport_no_adapter)));
            return;
        }
        if (!adapter.isEnabled()) {
            setBluetoothStatus(getString(R.string.bt_transport_adapter_off));
            return;
        }

        BluetoothServerSocket server = null;
        try {
            Log.i(TAG, "Listening for RFCOMM clients, SDP \"" + GnssFraming.RFCOMM_SDP_NAME
                    + "\" uuid " + GnssFraming.RFCOMM_UUID);
            server = adapter.listenUsingRfcommWithServiceRecord(
                    GnssFraming.RFCOMM_SDP_NAME, GnssFraming.RFCOMM_UUID);
            synchronized (this) {
                if (generation != bluetoothGeneration.get() || !running) {
                    server.close();
                    return;
                }
                bluetoothServerSocket = server;
            }
            setBluetoothStatus(getString(R.string.bt_transport_listening));

            while (generation == bluetoothGeneration.get() && running) {
                BluetoothSocket client = server.accept();
                if (generation != bluetoothGeneration.get() || !running) {
                    client.close();
                    break;
                }
                Log.i(TAG, "Bluetooth client connected");
                acceptClient(new BluetoothLink(client));
            }
        } catch (SecurityException e) {
            Log.e(TAG, "Bluetooth listen permission denied", e);
            if (generation == bluetoothGeneration.get()) {
                setBluetoothStatus(getString(R.string.bt_transport_permission));
            }
        } catch (IOException e) {
            if (generation != bluetoothGeneration.get() || !running) {
                return;
            }
            BluetoothAdapter current = bluetoothAdapter();
            if (current == null || !current.isEnabled()) {
                setBluetoothStatus(getString(R.string.bt_transport_adapter_off));
                return;
            }
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Log.w(TAG, "Bluetooth listen failed: " + detail, e);
            setBluetoothStatus(getString(R.string.bt_transport_failed, detail));
            mainHandler.postDelayed(() -> {
                if (generation == bluetoothGeneration.get() && running
                        && Preferences.bluetoothTransportEnabled(this)) {
                    executor.execute(() -> listenBluetooth(generation));
                }
            }, 5000);
        } finally {
            if (server != null) {
                try {
                    server.close();
                } catch (IOException ignored) {
                }
            }
            synchronized (this) {
                if (bluetoothServerSocket == server) {
                    bluetoothServerSocket = null;
                }
            }
        }
    }

    private boolean hasBluetoothConnectPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private BluetoothAdapter bluetoothAdapter() {
        BluetoothManager manager = getSystemService(BluetoothManager.class);
        return manager != null ? manager.getAdapter() : null;
    }

    private void btAutoStopService() {
        // Safety net: re-check conditions before stopping
        boolean btGone = BluetoothReceiver.allTriggerDevicesDisconnected();
        boolean clientsGone;
        synchronized (connectedClients) {
            clientsGone = connectedClients.isEmpty();
        }
        if (!btGone || !clientsGone) {
            Log.i(TAG, "Bluetooth auto-stop skipped (BT connected: " + !btGone + ", clients connected: " + !clientsGone + ")");
            return;
        }
        Log.i(TAG, "Bluetooth auto-stop triggered - stopping service");
        setServiceEnabled(this, false);
        stopSelf();
    }

    private class ClientHandler implements Runnable {
        private final GnssLink link;
        private final String clientAddress;
        private final AtomicBoolean closed = new AtomicBoolean(false);
        private volatile long lastHeartbeatTime;
        private long lastResponseTime = 0;

        private final Runnable heartbeatWatchdog = new Runnable() {
            @Override
            public void run() {
                if (closed.get()) {
                    return;
                }
                long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
                if (timeSinceLastHeartbeat > GnssFraming.HEARTBEAT_TIMEOUT_MS) {
                    Log.w(TAG, "Heartbeat timeout for client: " + clientAddress +
                            " (last heartbeat " + timeSinceLastHeartbeat + "ms ago)");
                    disconnect();
                    return;
                }
                mainHandler.postDelayed(this, GnssFraming.SERVER_READ_TIMEOUT_MS);
            }
        };

        public ClientHandler(GnssLink link) {
            this.link = link;
            this.clientAddress = link.getRemoteName();
            this.lastHeartbeatTime = System.currentTimeMillis();

            Log.i(TAG, "New client connected: " + clientAddress + " via " + link.getKind());
        }

        public String getClientAddress() {
            return clientAddress;
        }

        public GnssLink.Kind getKind() {
            return link.getKind();
        }

        @Override
        public void run() {
            boolean readTimesOut = link.applyReadTimeout(GnssFraming.SERVER_READ_TIMEOUT_MS);
            if (!readTimesOut) {
                // Bluetooth reads block until the peer writes or the socket is closed.
                mainHandler.postDelayed(heartbeatWatchdog, GnssFraming.SERVER_READ_TIMEOUT_MS);
            }
            try {
                sendResponse(lastServerResponse.build());

                InputStream input = link.getInputStream();
                byte[] buffer = new byte[1];
                while (!closed.get()) {
                    try {
                        int result = input.read(buffer);
                        if (closed.get()) {
                            Log.i(TAG, "Client closed connection: " + clientAddress);
                            break;
                        }
                        if (result < 0) {
                            Log.i(TAG, "Client closed connection: " + clientAddress);
                            break;
                        }
                        if (result > 0) {
                            if (buffer[0] == GnssFraming.HEARTBEAT) {
                                lastHeartbeatTime = System.currentTimeMillis();
                                Log.v(TAG, "Heartbeat received from: " + clientAddress);

                                // Send a response if the last one is older than RESPONSE_INTERVAL_MS,
                                // so the client can tell the server is still alive.
                                if (lastResponseTime < lastHeartbeatTime - GnssFraming.RESPONSE_INTERVAL_MS ||
                                        !lastServerResponse.hasLocationUpdate()) {
                                    sendResponse(lastServerResponse.build());
                                }
                                continue;
                            } else {
                                Log.w(TAG, "Unknown packet received from client: " + buffer[0]);
                            }
                        }
                    } catch (java.net.SocketTimeoutException e) {
                        // Heartbeat timeout will be processed after this block
                    } catch (IOException e) {
                        Log.i(TAG, "Client disconnected: " + clientAddress + " - " + e.getMessage());
                        break;
                    }

                    // Checked after a read timeout and after an unknown packet.
                    // Bluetooth uses heartbeatWatchdog instead, because its reads do not time out.
                    if (readTimesOut) {
                        long timeSinceLastHeartbeat = System.currentTimeMillis() - lastHeartbeatTime;
                        if (timeSinceLastHeartbeat > GnssFraming.HEARTBEAT_TIMEOUT_MS) {
                            Log.w(TAG, "Heartbeat timeout for client: " + clientAddress +
                                    " (last heartbeat " + timeSinceLastHeartbeat + "ms ago)");
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error in client handler for " + clientAddress, e);
            } finally {
                disconnect();
            }
        }

        private void sendResponse(LocationProto.ServerResponse response) {
            if (closed.get() || link.isClosed()) {
                return;
            }
            try {
                synchronized (link) {
                    if (closed.get() || link.isClosed()) {
                        return;
                    }
                    GnssFraming.writeFrame(link.getOutputStream(), response.toByteArray());
                }
                Log.v(TAG, "Response sent to: " + clientAddress);
                lastResponseTime = System.currentTimeMillis();
            } catch (IOException e) {
                Log.w(TAG, "Error sending location update to client", e);
                disconnect();
            }
        }

        public void disconnect() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            mainHandler.removeCallbacks(heartbeatWatchdog);
            link.close();
            onClientDisconnected(this);
        }
    }
}