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

**ViewModel** (`MainViewModel`)
- All network calls on `Dispatchers.IO`
- `StateFlow` for all UI state; no LiveData
- `SharedPreferences` reference stored on first `loadSettings()` call
- Sync history persisted as JSON (Gson) under `PREF_SYNC_HISTORY`; capped at 100 records

**Key features**
| Feature | Entry point |
|---------|-------------|
| Scan flight logs | `scanLogs()` — `.txt .log .csv .json` from configured paths |
| Upload to server | `uploadAll()` — multipart POST, per-file status, abort on auth failure |
| Per-file retry | Long-press any ERROR card → `retrySingle(log)` |
| Delete confirmed files | `deleteSynced()` — SYNCED + DUPLICATE |
| Sync history | `SyncHistoryScreen` — persistent, sorted newest-first, clearable |
| Auto-sync | `onNetworkAvailable()` — fires when network connects (foreground only) |
| OTA update | `checkForUpdate()` + `downloadUpdate()` → `PackageReplacedReceiver` auto-relaunches |
| Diagnostics | `DiagScreen` — real-time log buffer, share/clear |

**Screens**
- `HomeScreen` — drone animation, status badge, scan/sync/delete buttons, OTA banner, log list
- `SettingsScreen` — server URL, API key, log paths, manual update check
- `SyncHistoryScreen` — past upload sessions with colour-coded results
- `DiagScreen` — low-level network/scan/upload log
- `SplashScreen` — 1.6 s animated intro

**CI/CD** (`.github/workflows/`)
- `version-bump.yml` — triggers on `push` to `main` matching `android/**`; guard prevents double-bump
- `release.yml` — triggers on `workflow_run` after bump (or `workflow_dispatch`); decodes keystore, `assembleRelease`, publishes GitHub release
