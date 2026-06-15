# Changelog

All notable changes to DroneOpsSync (native Kotlin Android app for DJI controllers) are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

### Added — async device-upload (202 + poll) + per-file socket-timeout isolation (ADR-0008; pairs with DroneOpsCommand ADR-0023 / v2.71.0)

Closes the field-reliability half of the upload path. The legacy synchronous upload held the HTTP connection open for the **entire** server-side parse of each flight log (matching 120 s read-timeout on both ends); on field cellular/wifi a slow parse tripped the timeout — and, worse, a `SocketTimeoutException` on file *k* set `aborted = true`, which marked **every remaining file ERROR without attempting it**. One slow log nuked the rest of the sortie.

Two fixes, one release:

- **Per-file socket-timeout isolation (Stage C).** The inline per-file outcome logic in `MainViewModel.performUpload` is extracted into a pure, JVM-testable `classifyUploadOutcome(...)` (`upload/UploadOutcome.kt`). A `SocketTimeoutException` now fails **only that file** and the batch continues. `UnknownHostException` (dead host) and HTTP 401/403 (bad device key) remain correct batch-wide aborts. Tests: `UploadOutcomeTest` (10).
- **Async upload adoption (Stage D).** When the server advertises `async_upload_available` on the `device-health` preflight, the client POSTs `…/device-upload/async`, gets a `202 + {batch_id}` (the connection is released after the byte-stream), and polls `…/device-upload/status/{batch_id}` (2 s, backing off to 5 s after 60 s, 10-min ceiling), driving each file's status from `per_file[0].state`. A `202` body that already marks the file `skipped` (SHA-256 dedup) short-circuits with no poll. The 202/poll Gson models live in `model/AsyncUploadModels.kt`; tests in `PollEnvelopeTest` (13).
  - **Graceful fallback:** `async_upload_available` defaults false, so a new APK against an old/legacy server (or before the backend deploy) transparently uses the unchanged synchronous path. Client death mid-poll is safe — the job completes server-side and SHA-256 dedup reconciles on next launch.
- **Timeout retune (only valid now the parse is off-request):** `ApiClient` splits into an upload client (`readTimeout 30 s`) and a poll client (`readTimeout 15 s`); `connectTimeout 20 s` unchanged. `writeTimeout` stays 120 s to bound a slow byte-upload on cellular.
- No version bump in this PR (CI auto-bumps `android/version.properties` on merge — a manual bump folds into the squash, HEAD reads `[skip ci]`, and the release never fires).

ADR: [`docs/adr/0008-device-upload-async-poll-client.md`](docs/adr/0008-device-upload-async-poll-client.md), cross-references DroneOpsCommand ADR-0023 (the canonical contract). Operator end-to-end check: run a multi-file sortie with one deliberately large record and confirm in Diagnostics that a slow file no longer blocks the others and the connection returns immediately with a 202.

### Fixed — SAF persisted grant must include WRITE for delete-on-controller (ADR-0006, amends ADR-0005)

v1.3.27 (ADR-0005) shipped the SAF tree-picker as the RC Pro 2 scan path and promised post-upload `deleteSynced()` would remove the original on the controller via `DocumentsContract.deleteDocument(...)`. The picker took the persistable grant with `FLAG_GRANT_READ_URI_PERMISSION` only, no WRITE; the delete call therefore raised `SecurityException` once the process restarted and the in-memory grant was gone. The exception was swallowed by `runCatching{}.getOrDefault(false)`, so the user-facing toast read `"$deleted deleted, $failed could not be removed (may already be gone)"` — phrasing the operator (Bill, on the RC Pro 2) reasonably interpreted as "the system thinks it deleted them" while the files were still on the controller and got re-scanned as PENDING.

- `android/app/src/main/java/com/droneopssync/app/MainActivity.kt` — new private `OpenDocumentTreeWithWrite` subclass of `ActivityResultContracts.OpenDocumentTree` whose `createIntent` ORs `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION` onto the picker intent (the default contract attaches neither); `takePersistableUriPermission(uri, READ | WRITE)` instead of READ-only. New launcher registered through the subclass.
- `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt::loadSettings` — after parsing the persisted URI, checks `contentResolver.persistedUriPermissions` for the granted URI and, if `isWritePermission == false`, emits a `[PERM]` WARN, clears `PREF_SAF_FLIGHT_LOG_URI` from `SharedPreferences`, and nulls `_safTreeUri`. The existing `_needsSafGrant` assignment re-raises the home-screen banner so the operator re-completes the grant once — the new picker takes WRITE.
- `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt::deleteSafDocument` — silent `runCatching{}.getOrDefault(false)` replaced with explicit `try / catch(SecurityException) / catch(Exception)` that emits ERROR entries on a new `[DELETE]` diag channel. The `false`-return path (provider acknowledged the call but refused) also logs a `[DELETE]` WARN.
- `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt::deleteSynced` — status-message phrasing reworked so partial failure no longer reads as success. All-failed now reads `"Delete failed for $failed file(s) — check Diagnostics ([DELETE] channel)"`; partial reads `"$deleted deleted, $failed could not be removed — see Diagnostics"`.
- No manifest change, no version bump in this PR (CI auto-bumps on tag). No new dependency — `androidx.documentfile:documentfile:1.0.1` from ADR-0005 is sufficient.

**Operator impact on upgrade:** the SAF banner reappears once on first launch of this build for any device whose v1.3.27 grant is READ-only; one tap re-completes the grant with WRITE included. Subsequent SYNC ALL → DELETE actually removes the originals from the controller.

ADR: [`docs/adr/0006-saf-grant-must-include-write-flag-for-delete.md`](docs/adr/0006-saf-grant-must-include-write-flag-for-delete.md). Amends ADR-0005 (which remains accepted; the SAF approach is correct — only the grant model needed the fix). Branch: `claude/saf-flight-log-rc-pro-2`.

### Fixed — DJI flight-log scanning on the RC Pro 2 via SAF (ADR-0005, supersedes ADR-0004)

5th attempt — landed. The `MANAGE_EXTERNAL_STORAGE` and `targetSdk 29 + requestLegacyExternalStorage` paths both fail on stock-AOSP Android 11+: Google's docs are explicit that MES does not grant access to `Android/data/<other-pkg>`, and the legacy-storage flag honors the same lockdown. v1.3.26 shipped the targetSdk regression and confirmed empty on the RC Pro 2 in the field. This release reverts that hack and adds a Storage Access Framework tree-picker as the primary scan path on Android 11+ — the approach AirData ships in production on the same controller, and the same pattern Material Files / MiXplorer / Solid Explorer / X-plore / Total Commander / FV File Explorer all use.

- `android/app/build.gradle` — **`targetSdk 29` → `targetSdk 35`** (revert ADR-0004) + adds `androidx.documentfile:documentfile:1.0.1` for tree-URI traversal.
- New `android/app/src/main/java/com/droneopssync/app/storage/FlightLogSource.kt` — sealed interface + two implementations:
  - `LegacyFileSource(paths)` — wraps the prior `File.listFiles()` logic. Used on Android ≤ 10 and on permissive OEMs (Samsung S25 Ultra) where MES bypasses the AOSP lockdown.
  - `SafTreeSource(treeUri, context)` — walks `DocumentFile.fromTreeUri(...).listFiles()`, copies each matching child into `cacheDir/saf-staging/<safe-name>`, returns the same `List<FlightLog>` shape the upload pipeline already consumes.
- `MainActivity.kt` — registers `OpenDocumentTree()` launcher pre-seeded via `EXTRA_INITIAL_URI` to `primary:Android/data/dji.go.v5/files/FlightRecord` (built with `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", ...)`) so the picker lands the operator one tap away from "Use this folder." On result, calls `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` and persists the URI under `PREF_SAF_FLIGHT_LOG_URI` in `SharedPreferences`. Survives reboots per the `UriPermission` API contract.
- `MainViewModel.kt` — new `safTreeUri: StateFlow<Uri?>` and `needsSafGrant: StateFlow<Boolean>`. `performScan()` now dispatches: `if SDK >= 30 && safUri != null → SafTreeSource else → LegacyFileSource`. When Legacy succeeds on Android 11+ (Samsung path), `_needsSafGrant` is lowered automatically — no nagging. New `cleanupSafCacheFile(log)` deletes the staging file after upload SYNCED/DUPLICATE; `deleteSynced()` uses `DocumentsContract.deleteDocument(...)` for SAF-sourced entries so the original on the controller (not just our cache copy) is removed.
- `model/FlightLog.kt` — additive `sourceUri: Uri? = null` field. Existing call sites compile unchanged; upload still keys off `file: File`.
- `ui/screens/HomeScreen.kt` — new `SafGrantBanner` shown above the status badge when `needsSafGrant` is true: *"DJI Fly logs need a one-time folder grant — tap to allow."*
- `ui/screens/SettingsScreen.kt` — new "GRANT FLIGHT LOG FOLDER" / "RE-GRANT FLIGHT LOG FOLDER" section between Flight Log Paths and Auto Sync, displaying the granted tree URI when present.
- Manifest unchanged — `MANAGE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage="true"` are kept for the Samsung-permissive Legacy fallback. SAF grants are per-URI and don't require manifest declaration.
- Diag (`[SCAN]`, `[PERM]`) now records: source label per scan (`LEGACY` vs `SAF`), the persisted SAF URI on app startup, per-file SAF entries with name + size matching the existing legacy log format. This is the canary if the empirical test on Bill's RC Pro 2 fails for an unanticipated reason.

**Device matrix after this change:**

| Device                       | Android | Path used                          | Status                         |
|------------------------------|---------|------------------------------------|--------------------------------|
| Old DJI RC Pro               | 7 / 9   | Legacy `File.listFiles`            | Works (no lockdown pre-11)     |
| Samsung S25 Ultra            | 15      | Legacy via permissive Samsung MES  | Works (no SAF prompt shown)    |
| **New DJI RC Pro 2**         | **11**  | **SAF tree-picker (one-time grant)** | **Fixed**                    |
| Future RC Pro 2 on Android 13+ | 13+   | SAF closed at AOSP                 | Future ADR (Shizuku candidate) |

**Rollback:** revert this PR. ADR-0004's targetSdk regression is also reverted as part of this change; rolling back drops the SAF code AND restores targetSdk 35 — there is no intermediate state worth reverting to.

ADR: [`docs/adr/0005-saf-tree-picker-for-dji-flight-logs.md`](docs/adr/0005-saf-tree-picker-for-dji-flight-logs.md). ADR-0004 marked superseded. Research: `docs/research/2026-05-01-android-11-saf-authoritative.md`. Branch: `claude/saf-flight-log-rc-pro-2`. PR opens against `main` per the standard DroneOpsSync flow; CI auto-bumps the version on merge.

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
