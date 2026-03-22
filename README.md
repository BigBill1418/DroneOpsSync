# DroneOpsSync

Android companion app for **DroneOpsCommand** — syncs DJI flight logs from smart controllers directly into the DroneOpsCommand Flight Management System.

Part of the DroneOps platform. No separate server required — DroneOpsCommand IS the backend.

---

## Overview

DroneOpsSync runs on your DJI smart controller (sideloaded APK). It scans the controller's local storage for flight log files and uploads them in a single batch to your DroneOpsCommand instance over plain HTTP — either directly on your local network, or remotely via WireGuard VPN. Once confirmed on the server, logs can be deleted from the controller with a single tap.

---

## Prerequisites

1. **DroneOpsCommand** deployed and running (see [DroneOpsCommand](https://github.com/BigBill1418/DroneOpsCommand))
2. **Device API key** generated in DroneOpsCommand → Settings → Device Access
3. **Network access** to the server — LAN (direct IP) or WireGuard VPN for remote

---

## Network Setup

### On the same LAN (most common)

The controller connects directly to the server's local IP over HTTP. No tunnel, no TLS, no extra config. Just make sure both devices are on the same network and the server port is reachable.

```
DJI Controller → http://192.168.1.50:8080
```

### Remote access via WireGuard

Connect the controller to your WireGuard VPN before uploading. The app then talks to the server's VPN IP exactly like LAN — plain HTTP, no certificate issues.

```
DJI Controller → WireGuard VPN → http://10.8.0.1:8080
```

WireGuard has an official Android APK that runs on Android 8+, compatible with all DJI smart controllers.

---

## Setup

### Step 1 — Generate a Device API Key

In your DroneOpsCommand web UI:
1. Go to **Settings → Device Access**
2. Copy the auto-generated `Device API Key`

### Step 2 — Build & Sideload the APK

```bash
cd android
./gradlew assembleRelease
# APK at: app/build/outputs/apk/release/app-release.apk
```

Sideload the APK onto each DJI smart controller via ADB or a file manager.

### Step 3 — Configure the App

Open DroneOpsSync on the controller, tap the **Settings** (gear) icon, and enter:

| Field | Value |
|-------|-------|
| DroneOpsCommand URL | Local IP (e.g. `http://192.168.1.50:8080`) or WireGuard VPN IP |
| Device API Key | The key copied from DroneOpsCommand Settings |

Tap **Save**. The status indicator will turn green when the server is reachable.

---

## Sync Flow

1. **SCAN FOR LOGS** — finds `.txt` / `.log` files in all configured paths
2. **SYNC ALL** — uploads all pending logs to DroneOpsCommand in a single batch. The server parses them, deduplicates by hash, and imports into the Flight Library.
3. **DELETE** — after confirmation, removes synced files from the controller only

Files are **never deleted automatically** — explicit confirmation is always required.

---

## Default Scan Paths

Pre-configured in the app; additional paths can be added in Settings.

| DJI App | Path | Works on |
|---------|------|----------|
| DJI Fly | `/storage/emulated/0/DJI/FlightRecord` | Android 11+ phones |
| DJI Fly | `/storage/emulated/0/Android/data/com.dji.fly/files/FlightRecord` | DJI controllers (Android ≤10) |
| DJI Pilot 2 | `/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord` | All |
| DJI GO 5 | `/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord` | DJI controllers (Android ≤10) |

> **Android 11+ note:** Paths under `Android/data/<package>/` are blocked by the OS even with All Files Access granted. This is a deliberate Android security restriction. On Android 11+ phones, DJI Fly writes logs to the public `/storage/emulated/0/DJI/FlightRecord` path instead.

---

## Upload Status Reference

| Badge | Meaning |
|-------|---------|
| PENDING | Found locally, not yet uploaded |
| SYNCING | Upload in progress |
| SYNCED | Confirmed in DroneOpsCommand Flight Library |
| ON SERVER | Already existed on server (deduplicated) |
| ERROR | Upload or parse failed — check connection/key |
| DELETED | Removed from controller after sync |

---

## Backend Integration

DroneOpsSync talks directly to DroneOpsCommand's `/api/flight-library/device-upload` endpoint over plain HTTP. There is no middleware, relay server, or tunnel required.

The DroneOpsCommand stack includes:
- **`flight-parser`** — Rust service that parses DJI `.txt` log format
- **PostgreSQL** — persistent flight record storage

---

## Logo

Place your logo PNG at:
```
android/app/src/main/res/drawable/droneops_sync_logo.png
```
Recommended size: 1024 × 410 px on a transparent background.

---

## License
MIT
