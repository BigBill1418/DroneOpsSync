# Progress log

Session-by-session notes. Most recent first.

## 2026-04-24 — Kotlin resumption executed (aegis; ports + CI move + cleanup)

### Completed

- **BOS-HQ runner registered** for `BigBill1418/DroneOpsSync`. New
  `runner-droneopssync` + `dind-droneopssync` added to
  `/opt/gh-runner/docker-compose.yml` on BOS-HQ; systemd unit restarted;
  GH API confirms `status: online` with labels
  `self-hosted linux x64 bos android docker`. Uses the narrow `GH_PAT`
  (fine-grained) which was already authorized against DroneOpsSync.
- **HTTPS coercion** ported into
  `android/app/src/main/java/com/droneopssync/app/api/ApiClient.kt::normalizeUrl`
  with LAN carve-out that operates on a parsed host (not raw-URL
  substring), so `10.example.com` is correctly rejected as non-private.
  Default server URL changed from stale `http://10.50.0.5:3080` to
  `https://droneops.barnardhq.com`.
- **Landscape lock** ported — `AndroidManifest.xml` MainActivity
  `screenOrientation` `fullSensor` → `sensorLandscape`, `configChanges`
  extended with `keyboardHidden`.
- **Pairing banner (layer 1)** ported — `PairingState` sealed class +
  StateFlow in `MainViewModel` + `PairingBanner` composable rendered
  above `ReadyToSyncBadge` in `HomeScreen`. `refreshPairing()` is called
  on `loadSettings`, `saveSettings`, and all three upload entry-points'
  blank-credential bail-outs.
- **Preflight health gate (layer 2)** ported — `preflightHealth()`
  calling `GET /api/flight-library/device-health` before every upload.
  Wired into `performUpload`, `startAutoFlow`, and `onNetworkAvailable`.
  Replaces the old 8s `_serverReachable.value == null` polling loop in
  `startAutoFlow` which was racing the unauthenticated `/health` probe.
- **CI on BOS-HQ self-hosted**. All three workflows
  (`release.yml`, `version-bump.yml`, `auto-merge-claude-dev.yml`) moved
  from `runs-on: ubuntu-latest` to `[self-hosted, linux, x64, bos]`;
  `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` env removed (ineffective on
  self-hosted); `release.yml` now provisions JDK 17 + Android SDK
  (platform-tools / android-33 / build-tools 33.0.2) and gates on
  `apksigner verify --print-certs` + `aapt dump badging versionName`
  before uploading the release asset. Workflow expression interpolation
  moved into `env:` blocks per GitHub Actions command-injection guidance.
- **Stale artifacts removed** — `apply-droneopscommand-update.sh` +
  `droneopssync-integration.patch` deleted. Both were March-2026
  one-shots whose server-side payload has been live in production since
  2026-03-18.

### Next (post-push)

- Aegis pushes the branch + opens a PR; squash-merging fires the
  `version-bump` workflow (1.3.23 → 1.3.24), which in turn triggers
  `release.yml` on the BOS-HQ runner, which publishes
  `DroneOpsSync-v1.3.24.apk`.
- Aegis verifies: run IDs green, signer fingerprint matches v1.3.23,
  `aapt dump badging versionName='1.3.24'`, GH release exists with APK
  asset.
- Aegis decommissions Capacitor track in DroneOpsCommand per plan §8.
- Bill performs end-to-end verification on his RC Pro per plan
  §11.5–§11.9.

## 2026-04-24 — Kotlin resumption plan written (Terry, research + plan only; no code changes)

### Completed
- Researched the Mar-23 → Apr-24 Capacitor-fork misdirection; confirmed `companion/` has zero device installs.
- Verified this repo's actual state: Kotlin + Jetpack Compose, last release v1.3.23 (2026-03-29), in-app OTA via `GitHubClient`, CI on `ubuntu-latest`.
- Verified source commits for the two client-visible fixes:
  - `890b875` in DroneOpsCommand — HTTPS coercion in `companion/src/sync.ts::validateServerUrl`.
  - `306a2b8` in DroneOpsCommand — landscape lock + pairing banner (layer 1) + preflight health gate (layer 2) in `companion/src/App.tsx` + `companion/src/sync.ts` + `companion/scripts/patch-android.cjs`.
- Confirmed server-side watchdog layers 3 + 4 (Celery beat + first-401 Pushover) are already live in DroneOpsCommand — no server change needed here.
- Wrote plan file `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`.
- Wrote `docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md`.
- Initialized `CHANGELOG.md`, `ROADMAP.md`, `PROGRESS.md` per global doc-discipline rule.

### In progress
- None — handoff to aegis for execution of the plan.

### Blocked
- None anticipated, but BOS-HQ self-hosted runner registration against `BigBill1418/DroneOpsSync` must be verified on aegis's first turn (see plan §7 prerequisite).

### Decisions
- Kotlin is canonical (ADR-0001). Capacitor fork abandoned.
- HTTPS coercion will **silently upgrade** plaintext public URLs rather than throw (the Capacitor behavior); LAN hosts preserved. Rationale in plan §3: operator-friendlier on first v1.3.24 launch.
- `version-bump.yml` handles the 1.3.23 → 1.3.24 bump automatically on first push; aegis must NOT edit `version.properties`.
- Layers 3 + 4 of the §5 watchdog are not in scope here — they live in DroneOpsCommand and are already shipped.

### Next
- Aegis executes `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`.
- On completion, code-reviewer verifies per plan §12.
- Bill performs end-to-end verification per plan §11.5–§11.9 on his RC Pro.

## 2026-04-24 PM — Kotlin resumption SHIPPED; v1.3.25 queued

### Completed this session
- Aegis executed the Kotlin-resumption plan in full. Commits on `main`: `98ac7a3` (feature, PR #49), `54e4877` (auto-bump to 1.3.24), `c6e10ab` (CI PATH fix, PR #50), `832585c` (CHANGELOG release-heading, PR #51).
- BOS-HQ self-hosted runner `runner-droneopssync` + dind sidecar added to `/opt/gh-runner/docker-compose.yml`; registered against this repo. Runner online, labels `self-hosted linux x64 bos android docker`.
- Release `v1.3.24` — https://github.com/BigBill1418/DroneOpsSync/releases/tag/v1.3.24. APK 11,528,646 B. Signer SHA-256 `7406a246572ce65c3d7f81a88d27b5a26f89e7817f583f9e2975c67c2b475508` — identical to v1.3.23's → zero-sideload gate PASSED.
- CI run IDs (all on self-hosted runner): `24904190685` (version-bump), `24904726954` (release build).
- Code-reviewer pass found one HIGH (CHANGELOG `[Unreleased]` heading on shipped content) — fixed in PR #51. No BLOCKERs. No other HIGHs. Four NITs captured as future FU items.
- Operator (Bill) installed v1.3.24 via in-app Check-for-Updates on RC Pro. OTA flow worked zero-sideload. Pairing banner + preflight gate surfaced correctly when the earlier-morning rotated M4TD key was detected as `invalid_key`; Bill re-pasted the new key and the 3 pending FlightRecord uploads succeeded.
- Operator confirmed: none of his other DJI controllers share the M4TD key. Per-controller independence — v1.3.25 auto-rotation design does not need to handle shared-key scenarios.

### Scheduled (not yet executed)
- Remote routine `trig_01KiBK88vqs6vtRf75rkxcw8` (fired 2026-04-24T18:58Z) will open PRs for v1.3.25 zero-touch device API key rotation across this repo and DroneOpsCommand. See ROADMAP.md `v1.3.25` section for design + success criteria.

### Decisions
- Kotlin resumption is complete. v1.3.24 baseline is signed-correctly and OTA-distributable. Future work resumes from here.
- Bill's "unfortunate" re-paste today was caused by the morning's M4TD rotation, NOT by the upgrade (SharedPreferences survives same-keystore upgrades). The preflight gate correctly surfaced the invalid-key state instead of silent-failing — working as designed. v1.3.25 eliminates even this one-time paste for future rotations.
- Pushover watchdog verified end-to-end live to `PrimaryPhoneS25` (request `3eac9d51-fc67-4248-8a8a-038daac5a023`). Env lives at `~/droneops/.env` on BOS-HQ (not `/opt/droneops/.env` as earlier memory claimed). USER_KEY is Bill's canonical; TOKEN is a dedicated DroneOps Pushover app (separate rate-limit bucket from NOC).

### Next
- Wait for `trig_01KiBK88vqs6vtRf75rkxcw8` to complete and open PRs. Bill reviews + squash-merges; post-merge CI builds v1.3.25.
- Operator: install v1.3.24 on remaining DJI controllers via each one's Check-for-Updates. Same keystore + same OTA flow = zero-sideload per device.
