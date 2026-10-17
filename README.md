# GNSS Sharing System

A client-server Android application system that shares GNSS location data from a smartphone to a car multimedia system. The phone can serve the same protobuf stream over Wi-Fi (TCP) and, optionally, Bluetooth Classic (RFCOMM). The client uses one transport at a time and injects the fixes as a system-wide mock location.

## System Overview

**Server (Smartphone):**
- Collects high-precision GNSS data
- Runs as a background service with a foreground notification
- Streams location updates to connected clients via TCP port 8887
- Optionally listens on a Bluetooth RFCOMM socket at the same time, using the same framing and heartbeat
- Shows debugging information in the notification panel
- Automatically manages power consumption
- Handles multiple client connections with individual heartbeat monitoring

**Client (Car Multimedia System):**
- Chooses Wi-Fi/TCP or Bluetooth Classic
- Implements robust connection management with auto-reconnection
- Wi-Fi mode is Wi-Fi-aware. Bluetooth mode reconnects when the adapter turns off/on or the selected device drops
- Receives location data and provides system-wide mock GPS
- Shows connection failures in the main screen, including the Bluetooth stack's own error text
- Displays connection status and reconnection attempts

The wire protocol is the same on both transports:

- Client → server: one heartbeat byte (`0x01`) per second
- Server → client: 4-byte big-endian length, then a protobuf `ServerResponse`

Bluetooth uses an app-specific RFCOMM service UUID:

`f6c1b2a0-3e57-4d8a-9c14-7b2e6d5a1f30`

## Setup Instructions

### Building the Applications

1. **Clone and Setup:**
```bash
git clone 
cd gnss-share
```

2. **Build Server & Client Apps:**
```bash
./gradlew assembleDebug
```

Package IDs for this fork (different from upstream DezzK, so they install as separate apps):

- Server: `dezz.gnssshare.bt.server`
- Client: `dezz.gnssshare.bt.client`

## Usage Instructions

### Wi-Fi (existing mode)

1. **On the smartphone (server):**
   - Launch "GNSS Server"
   - Grant the requested permissions
   - Turn on the Wi-Fi hotspot
   - Tap "Start"
   - The notification should show "Server Running"

2. **On the car system (client):**
   - Connect Wi-Fi to the phone hotspot
   - Launch "GNSS Client"
   - Leave the transport on **Wi-Fi (TCP)** (the default)
   - Grant the requested permissions and select this app as the mock location provider
   - Start the service. It connects to the hotspot gateway on port 8887

### Bluetooth Classic (no hotspot)

Use this when the head unit should not join the phone's hotspot. The phone's Wi-Fi listener stays available; only the client switches transports.

1. **Pair the two devices** in the system Bluetooth settings. The client only connects to a paired phone. RFCOMM is authenticated, so an unpaired device is rejected.

2. **On the smartphone (server):**
   - Grant Nearby devices / Bluetooth access when asked (required on Android 12+)
   - Turn on **Listen for Bluetooth clients**
   - Start the service
   - The Bluetooth card and the notification should say that the phone is listening
   - TCP port 8887 still accepts Wi-Fi clients

3. **On the car system (client):**
   - Select **Bluetooth**
   - Grant Bluetooth / Nearby devices if the head unit is on Android 12 or newer. Older releases use the install-time Bluetooth permission
   - Tap **Choose paired phone** and pick the phone running GNSS Server
   - Start the service

The client opens an RFCOMM socket to the UUID above and feeds the same mock-location pipeline as Wi-Fi. It retries with a 2–15 second backoff. Turning Bluetooth off stops retries until the adapter is back on. An ACL drop of the selected phone, or a dead RFCOMM socket, starts a new attempt.

### If the head unit Bluetooth stack is busy

Geely Flyme Auto and similar units often keep the Bluetooth radio busy with hands-free calling or CarPlay. When RFCOMM cannot be opened, the client status line stays red and shows the stack's message (service discovery failed, socket closed, timeout, and so on) plus a hint that the radio may be busy. The service keeps retrying. Practical things to try:

- Wait until the hands-free or CarPlay session has finished connecting, then leave the client service running
- Disconnect the other profile and let the client retry
- Confirm the phone toggle **Listen for Bluetooth clients** is on and the phone Bluetooth adapter is on
- Confirm the two devices are still paired
- Export logs from both apps if the status text is not enough

### Contributing

1. Fork the repository
2. Create a feature branch
3. Make your changes
4. Submit a pull request

### License

This project is licensed under [GNU General Public License v3.0](LICENSE).
