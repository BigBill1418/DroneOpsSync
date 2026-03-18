# DroneOpsSync
Android companion app for **DroneOpsCommand** — syncs DJI flight logs from smart controllers directly into the DroneOpsCommand Flight Management System via Cloudflare tunnel.

Part of the DroneOps platform. No separate server required — DroneOpsCommand IS the backend.

---

## Overview

DroneOpsSync runs on your DJI smart controller (sideloaded APK). It scans the controller's local storage for flight log files and uploads them in a single batch to your DroneOpsCommand instance, reachable from anywhere over the internet via Cloudflare tunnel. Once confirmed on the server, logs can be deleted from the controller with a single tap.

---

## Prerequisites

1. **DroneOpsCommand** deployed and running (see [DroneOpsCommand](https://github.com/BigBill1418/DroneOpsCommand))
2. **Cloudflare tunnel** configured in DroneOpsCommand (the `cloudflared` service is included in the docker-compose stack — add your `CLOUDFLARE_TUNNEL_TOKEN`)
3. **Device API key** generated in DroneOpsCommand → Settings → Device Access

---

## Setup

### Step 1 — DroneOpsCommand: Generate a Device API Key

In your DroneOpsCommand web UI:
1. Go to **Settings → Device Access**
2. A `Device API Key` is auto-generated. Copy it.

This key authenticates the Android app without requiring a user login. Keep it safe.

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
| DroneOpsCommand URL | Your Cloudflare tunnel URL (e.g. `https://ops.yourdomain.com`) or local IP |
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

| DJI App | Path |
|---------|------|
| DJI Pilot 2 | `/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord` |
| DJI GO 5 | `/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord` |
| DJI Fly | `/storage/emulated/0/Android/data/com.dji.fly/files/FlightRecord` |

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

DroneOpsSync talks directly to DroneOpsCommand's `/api/flight-library/device-upload` endpoint.
This endpoint is part of DroneOpsCommand — there is no separate middleware or relay server.

The DroneOpsCommand stack already includes:
- **`flight-parser`** — Rust service that parses DJI `.txt` log format
- **`cloudflared`** — Cloudflare tunnel for secure internet access
- **PostgreSQL** — persistent flight record storage

All backend resources are self-contained within DroneOpsCommand.

---

## Logo

Place your logo PNG at:
```
android/app/src/main/res/drawable/droneops_sync_logo.png
```
Recommended size: 1024 × 410 px on a transparent background. The app will automatically use it; the text fallback renders until the file is present.

---

## License
MIT
