# DroneOpsSync Roadmap

> **Status corrected 2026-09-25 (fleet roadmap-staleness pass).** This file had not been
> touched since PR #57 (2026-06-14) and read as if v1.3.25 were still scheduled and PR #57
> unreleased. Current state, from the GitHub releases and `git log`: **latest release
> v1.3.32 (2026-07-03)**, `android/version.properties` = `1.3.32`. Every release since this
> file was last accurate: v1.3.25 (2026-04-24, key auto-pickup, #53), v1.3.26/27 (2026-05-01),
> v1.3.28 (2026-05-14, SAF WRITE-grant fix, #56 / ADR-0005/0006), v1.3.29 (2026-06-15, async
> upload, #57 `c66931a`), v1.3.30 (2026-07-02, truncated-copy guard, #58), v1.3.31 (2026-07-02,
> real flight date not 1969, #59), v1.3.32 (2026-07-03, sort by derived flight date, #60
> `12b84c5`). No open work item below is "pending release". Items that need an **operator**
> device check are still unchecked — they were never confirmed, not silently done.

## Device-upload async poll client (audit P2-2) — SHIPPED, released v1.3.29 2026-06-15 (PR #57, `c66931a`)

**Objective:** close the field-reliability half of the upload path. Two stages
shipped together: (1) per-file socket-timeout isolation — a `SocketTimeoutException`
fails only the current file instead of aborting the whole sortie (the B2 bug);
(2) adoption of the new server async upload route (202 + poll) with graceful
fallback to the legacy synchronous path. Pairs with DroneOpsCommand v2.71.0.
**Audit P2-2 is now closed** (server leg v2.71.0 + this client leg).

Version intentionally not pinned here — CI auto-bumps `android/version.properties`
on merge and publishes the release APK; the in-app version display reads it from
`BuildConfig.VERSION_NAME`.

**Success criteria:**
- [x] `classifyUploadOutcome(...)` extracted to `upload/UploadOutcome.kt`; `SocketTimeoutException` no longer aborts the batch (`UploadOutcomeTest`, 10 cases)
- [x] `uploadFlightsAsync` (202) + `pollUpload` added to `DroneOpsSyncService`; submit+poll loop in `MainViewModel.uploadFileAsync(...)`
- [x] Capability detection via `async_upload_available` on the preflight; legacy fallback when absent/false
- [x] 202/poll Gson models in `model/AsyncUploadModels.kt` (`PollEnvelopeTest`, 13 cases)
- [x] `ApiClient` split into upload (30 s) / poll (15 s) clients
- [x] ADR-0008 (client) + cross-ref to DroneOpsCommand ADR-0023 (contract)
- [x] APK ships post-merge via CI auto-bump — **released v1.3.29, 2026-06-15** (same-keystore check not re-run in the 2026-09-25 correction)
- [ ] Operator end-to-end: multi-file sortie with one large record — slow file no longer blocks the rest; Diagnostics → `[UPLOAD]` shows `HTTP 202` + `batch_id`

See ADR-0008 + DroneOpsCommand ADR-0023 + shared plan
`DroneOpsCommand/docs/plans/2026-06-15-device-upload-async-decoupling.md`.

## Current phase — none in flight (corrected 2026-09-25; last release v1.3.32, 2026-07-03)

*Historical — this was the current phase on 2026-04-24; it shipped as v1.3.25 the same day:* **v1.3.25 — zero-touch device API key rotation.** Scheduled via Claude Code remote routine `trig_01KiBK88vqs6vtRf75rkxcw8` (fired 2026-04-24T18:58Z). Deliverable: PRs on both DroneOpsCommand + DroneOpsSync implementing grace-window dual-key auth + device-side preflight pickup. ADR will land as `docs/adr/0002-zero-touch-device-key-rotation.md` in this repo; corresponding server-side ADR-0003 lives in DroneOpsCommand.

## v1.3.24 — SHIPPED 2026-04-24

**Objective:** Get Bill's pending RC Pro uploads across + lock the safety net (landscape, pairing banner, preflight gate) + move CI to BOS-HQ.

**Success criteria:**
- [x] v1.3.24 GH release published with APK asset (`DroneOpsSync-v1.3.24.apk`, 11.5 MB, CI run 24904726954)
- [x] APK signer SHA-256 fingerprint matches v1.3.23's (`7406a246...`) — zero sideload confirmed
- [x] `aapt dump badging` shows `versionName='1.3.24'` (targetSdkVersion observed = 35, still ≥ 29 so log access intact)
- [x] CI green on BOS-HQ self-hosted runner `runner-droneopssync`
- [x] Bill's RC Pro picks up v1.3.24 via in-app OTA (no sideload) — operator-confirmed
- [x] Pending FlightRecord uploads succeed end-to-end — operator-confirmed (post key re-paste; see v1.3.25 which eliminates that step)
- [x] Pairing banner + preflight gate observed to behave correctly — operator-confirmed (preflight surfaced the morning's rotated M4TD key, forcing banner which led to re-paste)

## v1.3.25 — SHIPPED 2026-04-24 (was "scheduled"; corrected 2026-09-25)

**Objective:** Eliminate manual key-paste on paired controllers after any server-side device API key rotation.

**Design summary.** Backend grace window (24h) during which both old and new keys authenticate. Device's existing preflight health gate parses a `rotated_key` field from the response body, writes to SharedPreferences, and invalidates the Retrofit cache. Operator sees a transient toast "API key auto-updated"; takes zero action on the controller. Full design in the paired backend ADR-0003 in DroneOpsCommand, mirrored here as ADR-0002.

**Remote routine:** `trig_01KiBK88vqs6vtRf75rkxcw8` — https://claude.ai/code/routines/trig_01KiBK88vqs6vtRf75rkxcw8. Expected output: one PR against `main` here, one PR against `main` in DroneOpsCommand, tests passing in the remote sandbox. CI will fire after Bill merges.

**Success criteria:**
- [x] PR opened against DroneOpsSync `main` (`feat(kotlin): auto-pickup of server-rotated API keys via preflight response`) — branch `claude/auto-rotation-client`, opened 2026-04-24 evening
- [x] PR opened against DroneOpsCommand `main` (`feat: zero-touch device API key rotation (ADR-0003)`) — note: that repo has an `auto-merge-claude.yml` workflow that promoted the branch to `main` 7s after push; commit `e0295a1` is reviewable directly on `main`. Operator can revert if review surfaces issues.
- [x] v1.3.25 APK ships post-merge — **released 2026-04-24T23:00Z** (GH release `v1.3.25`; `release.yml` runs the `apksigner` gate on every release)
- [ ] End-to-end dry-run: operator rotates a dev-tier device key server-side; paired controller picks it up on next sync without any Settings interaction

## Near-term follow-ups (post-v1.3.25)

- ✅ **SHIPPED v1.3.28, 2026-05-14 (PR #56, `72e0241`).** **v1.3.28 hotfix (ADR-0006) — SAF persisted grant must include WRITE for delete-on-controller.** Fix lands on `claude/saf-flight-log-rc-pro-2` on top of ADR-0005's commit `7f8e5d9`. Operator-observable regression: post-upload DELETE returned `"$deleted deleted, $failed could not be removed (may already be gone)"` while files remained on the RC Pro 2. Root cause: picker took READ-only grant; `DocumentsContract.deleteDocument` raised `SecurityException` after process restart; caught by silent `runCatching{}.getOrDefault(false)`. Fix: `OpenDocumentTreeWithWrite` subclass adds WRITE to picker intent + `takePersistableUriPermission(READ | WRITE)`; startup `[PERM]` check forces re-grant on existing READ-only installs; new `[DELETE]` diag channel surfaces previously-swallowed exceptions. CI auto-bumps to v1.3.28 on merge. See ADR-0006.
- **FU-1** — unit tests for `ApiClient.normalizeUrl` covering LAN carve-out, HTTPS coercion, path stripping. Not blocking ship; belongs in a follow-up PR.
- **FU-2** — consider whether `checkServerHealth` (unauthenticated `/api/health`) is still needed now that `preflightHealth` (authenticated `/device-health`) is the gate. Possibly collapse to one call.
- **FU-3** — persistent telemetry of first-install version + last-active timestamp, so a future "which devices are on which version?" question doesn't require an all-hands APK audit. Could ride on the same `/device-health` response.
- **FU-4** — add an "Updates available" count badge to the Home screen (today only visible after tapping "Check for Updates" in Settings).
- **FU-5** — Bill confirmed every controller has its own device API key (no shared M4TD). Good for blast-radius containment; the v1.3.25 rotation design does not need to handle shared-key scenarios.

## Medium-term

- Android 13 / 14 `READ_MEDIA_*` permission changes — current code uses `MANAGE_EXTERNAL_STORAGE` which still works on 33+ but shows a scary "All files access" dialog. Evaluate scoped storage alternatives once DJI publishes `targetSdk` 33+ guidance.

## Anti-goals (from ADR-0001)

- **Do not fork this into Capacitor / React Native / Flutter / anything else.** Single app, Kotlin, lives here.
- **Do not add Play Store distribution.** In-app GitHub OTA via `GitHubClient` is sufficient and the operator prefers it.
- **Do not rotate the signing keystore.** Every rotation is a forced sideload for every existing device.

## Archive

- **v2.33.0 → v2.62.1 (Capacitor fork, abandoned)** — 2026-03-23 to 2026-04-24. Zero device installs. See ADR-0001. Source in `git log --all -- companion/` under DroneOpsCommand up to its deletion commit.
