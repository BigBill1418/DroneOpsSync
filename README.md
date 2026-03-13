# DroneDump
Automated Flight Log Management Utility for DJI Smart Controllers

Scans DJI flight log folders, uploads to a local NAS, verifies with SHA-256 checksums, then deletes from the controller only after confirmed transfer.

---

## Components

### 1. DroneDump Server (`/server`)
FastAPI service running in Docker on Synology NAS. Accepts uploads on port **7474** and saves files to the Open DroneLog watch folder.

**Deploy on Synology:**
```bash
cd server
# Edit docker-compose.yml — set the volume path to your Open DroneLog folder
docker-compose up -d
```

### 2. DroneDump Android App (`/android`)
Kotlin + Jetpack Compose APK. Sideload onto each DJI controller.

**Build APK:**
```bash
cd android
./gradlew assembleRelease
# APK at app/build/outputs/apk/release/app-release.apk
```

---

## Default scan paths (pre-configured in app)
| App | Path |
|-----|------|
| DJI Pilot 2 | `/storage/emulated/0/DJI/com.dji.industry.pilot/FlightRecord` |
| DJI GO 5    | `/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord` |

Additional paths can be added in the app's Settings screen.

---

## Upload flow
1. **SCAN** — finds all `.txt`/`.log` files in configured paths
2. **SYNC ALL** — computes SHA-256 locally, uploads, server returns its checksum, both must match
3. **DELETE** — explicit confirmation dialog before removing files from the controller

Files are **never deleted automatically**.
