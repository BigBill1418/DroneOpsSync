# Claude Code Instructions — DroneOpsSync

## Version bumps are fully automated — do NOT bump manually

The CI pipeline handles versioning automatically. **Never add a version bump commit to a PR.**

- `VERSION_NAME` in `android/version.properties` is bumped by the "Bump patch version" workflow the moment a PR lands on main.
- `versionCode` is auto-calculated from git commit count in `build.gradle`.
- The release APK is built and published automatically after the bump completes.

### Why manual bumps break the pipeline

A manual `[skip ci]` version bump commit in a PR gets folded into the squash merge body. GitHub sees `[skip ci]` anywhere in the HEAD commit message and suppresses **all** push-triggered workflows — including "Bump patch version" — so the release never fires.

---

## Workflow summary

1. Make code changes on `claude/...` branch
2. Commit changes with a descriptive message (no version bump)
3. Push branch: `git push -u origin <branch>`
4. Create PR: `gh pr create --repo BigBill1418/DroneOpsSync --base main`
5. Merge PR: `gh pr merge <number> --repo BigBill1418/DroneOpsSync --squash`

CI takes it from there: bump → build → publish release automatically.

---

## Architecture overview

**Build system**
- AGP 8.13.2 · Gradle 8.13 · Kotlin 2.1.0 · Compose BOM 2024.12.01
- `assembleRelease` with `aaptOptions { cruncherEnabled false }` (required for `barnard_hq_logo.png`)
- Fixed keystore stored in GitHub secrets (`KEYSTORE_B64`, `KEYSTORE_PASSWORD`) — same config applied to both `release` and `debug` build types so OTA installs over either variant
- `versionCode` = git commit count via `ValueSource` (config-cache safe)

**Navigation** (`MainActivity`)
- Routes: `splash` → `home` → `settings` / `diag` / `history`
- `ConnectivityManager.NetworkCallback` registered in `onStart`/`onStop` for foreground auto-sync
- `startAutoFlow()` called on launch — scans, waits for health check, uploads if server reachable

**ViewModel** (`MainViewModel`)
- All network calls on `Dispatchers.IO`
- `StateFlow` for all UI state; no LiveData
- `SharedPreferences` reference stored on first `loadSettings()` call
- Sync history persisted as JSON (Gson) under `PREF_SYNC_HISTORY`; capped at 100 records
- `_isScanning: MutableStateFlow<Boolean>` — drives pull-to-refresh indicator
- `_autoSyncEnabled: MutableStateFlow<Boolean>` — persisted under `PREF_AUTO_SYNC`; gates both `startAutoFlow()` and `onNetworkAvailable()`
- `_promptDelete: MutableStateFlow<Boolean>` — one-shot signal; `HomeScreen` observes via `LaunchedEffect` to auto-show delete dialog

**Key features**
| Feature | Entry point |
|---------|-------------|
| Auto-flow on launch | `startAutoFlow()` — scan → wait for health check → upload → prompt delete |
| Scan flight logs | `scanLogs()` / `performScan()` — `.txt .log .csv .json` from configured paths |
| Pull-to-refresh | Drag down on log list → re-scan; driven by `isScanning` StateFlow |
| Upload to server | `uploadAll()` / `performUpload()` — multipart POST, per-file status, abort on auth failure |
| Per-file retry | Long-press **or** swipe right on any ERROR card → `retrySingle(log)` |
| Haptic feedback | `HapticFeedbackType.LongPress` on both long-press and swipe-retry |
| Delete confirmed files | `deleteSynced()` — SYNCED + DUPLICATE; auto-prompted after upload |
| Sync history | `SyncHistoryScreen` — persistent, sorted newest-first, clearable |
| Auto-sync on connect | `onNetworkAvailable()` — fires when network connects (foreground only) |
| Auto-sync toggle | Settings → Auto Sync — disables launch auto-flow and network-connect sync |
| OTA update | `checkForUpdate()` + `downloadUpdate()` → `PackageReplacedReceiver` auto-relaunches |
| Diagnostics | `DiagScreen` — real-time log buffer, share/clear |

**Screens**
- `HomeScreen` — drone animation, status badge, scan/sync/delete buttons, OTA banner, pull-to-refresh log list with swipe-to-retry
- `SettingsScreen` — server URL, API key, log paths, auto-sync toggle, manual update check
- `SyncHistoryScreen` — past upload sessions with colour-coded results
- `DiagScreen` — low-level network/scan/upload log
- `SplashScreen` — 1.6 s animated intro

**Permissions**
- `INTERNET` — upload and OTA
- `ACCESS_NETWORK_STATE` — `ConnectivityManager` callback for auto-sync on connect
- Android 11+: `MANAGE_EXTERNAL_STORAGE` (All Files Access) — required to read DJI log paths
- Android ≤10: `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`

**Remote access**
The server URL accepts any reachable address — LAN IP, WireGuard peer IP, or a public HTTPS endpoint. The app makes plain HTTP/S POST requests; transport security is handled at the network layer (WireGuard VPN or TLS termination on the server). No app changes are required to switch between local and remote access — update the server URL in Settings.

**CI/CD** (`.github/workflows/`)
- `version-bump.yml` — triggers on `push` to `main` matching `android/**`; guard prevents double-bump
- `release.yml` — triggers on `workflow_run` after bump (or `workflow_dispatch`); decodes keystore, `assembleRelease`, publishes GitHub release
