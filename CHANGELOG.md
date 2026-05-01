# Changelog

All notable changes to DroneOpsSync (native Kotlin Android app for DJI controllers) are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Fixed — DJI flight-log scanning on the new RC Pro 2 (ADR-0004)

The new DJI RC Pro 2 controller (Mavic 4 Pro Creators Combo) ships with stock-AOSP Android 11. With `targetSdk 35` the OS silently blocked reads of `/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/`, so the scan returned zero logs even though the files were sitting there. The same target-SDK regression had been hiding on every AOSP-strict Android 11+ device — the Samsung S25 Ultra masked it because Samsung's `MANAGE_EXTERNAL_STORAGE` impl ignores the AOSP `Android/data/<other-pkg>` lockdown, and the old RC Pro masked it because pre-Android-11 has no lockdown at all.

- `android/app/build.gradle` — `targetSdk 35` → **`targetSdk 29`** (one-line change). Engages Android 11's legacy-storage carve-out: apps targeting SDK ≤ 29 with `android:requestLegacyExternalStorage="true"` (already in our manifest) get pre-scoped-storage behavior on Android 11, including full `/sdcard` access. `compileSdk` stays at 35 so AndroidX / Compose BOM 2024.12.01 / AGP 8.13.2 are unaffected.
- No Kotlin changes. The runtime permission flow in `MainActivity.onCreate` already requests `MANAGE_EXTERNAL_STORAGE` on Android 11+ via `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, which is the right intent to pair with the carve-out. Android ≤ 10 path still requests `READ_EXTERNAL_STORAGE` + `WRITE_EXTERNAL_STORAGE`.
- OTA install path (`MainViewModel.downloadUpdate` → `cacheDir` → `FileProvider`) is target-SDK-independent — verified untouched.

**Device matrix after this change:**

| Device                         | Android | Status                                           |
|--------------------------------|---------|--------------------------------------------------|
| Old DJI RC Pro                 | 7 / 9   | Continues to work (no lockdown pre-11)           |
| Samsung S25 Ultra              | 15      | Continues to work (Samsung MES is permissive)    |
| **New DJI RC Pro 2**           | **11**  | **Fixed** — carve-out restores `/sdcard` reads   |
| Future RC Pro 2 on Android 12+ | 12+     | Will break again (carve-out gone); see ADR-0004 §"Future-fix trigger" — likely Shizuku, MSDK ingestion, or rooted helper. DJI rarely upgrades controller Android versions, so this is a multi-year horizon, not a stopgap. |

**Rollback:** revert the single-line gradle change. No data migration, no manifest change, no Kotlin change.

ADR: [`docs/adr/0004-targetsdk-29-regression-for-rc-pro-2-flight-log-access.md`](docs/adr/0004-targetsdk-29-regression-for-rc-pro-2-flight-log-access.md). Branch: `claude/targetsdk-29-rc-pro-2-storage`. PR opens against `main` per the standard DroneOpsSync flow; CI auto-bumps the version on merge.

### Added — zero-touch device API key rotation client (ADR-0002)

Paired with DroneOpsCommand v2.63.6 (ADR-0003). When the operator rotates a device's API key on the server, the paired RC Pro picks up the new key automatically on its next preflight call — no manual paste.

- `DeviceHealthResponse` typed Gson model replaces the prior `Response<Map<String, Any>>` shape on `DroneOpsSyncService.deviceHealth`. Forward-compatible — Gson silently ignores unknown JSON fields.
- `MainViewModel.preflightHealth` parses the new `rotated_key` + `rotation_grace_until` fields (populated by the server only on OLD-key auth during a 24h grace window). Validates prefix `doc_` and length ≥ 40 before persisting; bad payloads log WARN and are ignored.
- On a valid hint: persists to `SharedPreferences[PREF_API_KEY]`, calls `ApiClient.invalidate()`, refreshes pairing state, and emits a one-shot `"API key auto-updated"` toast via a new `MainViewModel.toastEvents: SharedFlow<String>` (replay=0, DROP_OLDEST).
- `HomeScreen` collects the toast flow and displays via `android.widget.Toast` (no SnackbarHost in this app's scaffold).
- New unit-test infrastructure: `android/app/src/test/java/com/droneopssync/app/api/KeyRotationParseTest.kt` (6 tests, all green) covering the parse contract — full payload, absent hint, unknown extra fields, empty key, status-only minimal response, and the prefix/length validation contract.
- `testImplementation 'junit:junit:4.13.2'` + `testImplementation 'com.google.code.gson:gson:2.11.0'` added to `app/build.gradle`.

ADR: [`docs/adr/0002-zero-touch-device-key-rotation-client.md`](docs/adr/0002-zero-touch-device-key-rotation-client.md). Plan: cross-references DroneOpsCommand `docs/plans/2026-04-24-zero-touch-key-rotation.md`.

Branch: `claude/auto-rotation-client`. PR opens against `main` per the standard DroneOpsSync flow (claude/* → PR → squash merge); CI auto-bumps to v1.3.25 on merge.

## [1.3.24] — 2026-04-24

Shipped via CI run `24904726954` on BOS-HQ self-hosted runner. APK signed with same keystore as v1.3.23 (cert fingerprint `7406a246...`) → OTA-upgrade-compatible, zero sideload. Plan: `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`. See ADR-0001 for the Kotlin resumption context.

- HTTPS coercion for public server URLs (silent upgrade of `http://public.host` → `https://public.host`; LAN hosts on `http://` preserved). Ported from DroneOpsCommand companion commit `890b875`.
- Default server URL pre-baked to `https://droneops.barnardhq.com` (mirrors companion v2.62.0 default).
- Landscape orientation lock — MainActivity `screenOrientation` changes `fullSensor` → `sensorLandscape`; `configChanges` extended with `keyboardHidden`. Ported from DroneOpsCommand companion commit `306a2b8`.
- Pairing banner (layer 1) — persistent red "DEVICE NOT PAIRED" banner when `serverUrl` or `apiKey` are missing / malformed. Ported from companion v2.62.1.
- Preflight health gate (layer 2) — `GET /api/flight-library/device-health` with `X-Device-Api-Key` runs before every upload; failure renders a specific banner and blocks the upload. Ported from companion v2.62.1.
- CI moved off `ubuntu-latest` onto `[self-hosted, linux, x64, bos]` per fleet ADR-0029 (in DroneOpsCommand).
- `release.yml` adds `apksigner verify --print-certs` + `aapt dump badging` verification steps.
- Removed: Mar-2026 integration artifacts `apply-droneopscommand-update.sh` + `droneopssync-integration.patch` (already applied upstream).

### Docs

- `docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md` narrates the 4-week Capacitor-fork misdirection and the decision to resume Kotlin development here.
- Initialized `CHANGELOG.md`, `ROADMAP.md`, `PROGRESS.md`, `docs/plans/`, `docs/adr/` per global doc-discipline rule.

## [1.3.23] — 2026-03-29

Last release before the 4-week Capacitor-fork misdirection. See ADR-0001 for context. Detailed changelog prior to this ADR was not maintained; consult `git log v1.3.23` for commit-level history.

- Final v1.3.x baseline shipped to Bill's RC Pro with MANAGE_EXTERNAL_STORAGE + device-API-key auth + in-app GitHub OTA updater.
