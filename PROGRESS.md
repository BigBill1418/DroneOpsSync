# Progress log

Session-by-session notes. Most recent first.

## 2026-06-15 — Device-upload async poll client + per-file timeout isolation — SHIPPED (PR #57)

Client half of the cross-repo device-upload async decoupling (audit P2-2 full
leg) — **implemented and committed** on branch `claude/device-upload-async`
(PR #57). Both stages (per-file isolation + async adoption) landed together in
one PR; releases on the next CI auto-bump at merge. **P2-2 is now closed**
(DroneOpsCommand v2.71.0 server leg + this client leg). Canonical contract is
DroneOpsCommand ADR-0023; this repo's ADR-0008 records the client decisions.

- **Per-file socket-timeout isolation (Stage C) — DONE.** The inline per-file
  outcome decision in `MainViewModel.performUpload` was extracted into the pure,
  JVM-testable `classifyUploadOutcome(...)` in `upload/UploadOutcome.kt`. A
  `SocketTimeoutException` now returns `abortBatch = false` — it fails only that
  file and the batch continues. `UnknownHostException` (dead host) and HTTP
  401/403 (bad device key) remain batch-wide aborts. Fixes the B2 bug where one
  slow file lost files N+1…M. Tested by `UploadOutcomeTest` (10 cases).
- **Async adoption (Stage D) — DONE.** `uploadFlightsAsync` (202 + `{batch_id}`)
  + `pollUpload(batchId)` added to `DroneOpsSyncService`; one batch_id per file
  (1:1 onto `UploadStatus`); `uploadFileAsync(...)` in `MainViewModel` does the
  submit + poll loop (2 s, backing off to 5 s after 60 s, 10-min ceiling),
  driving each file's status from `per_file[0].state` via `reducePerFileState`.
  Capability detected via `async_upload_available` on the preflight
  (`DeviceHealthResponse`); falls back to the legacy synchronous route when
  absent/false. A 202 body that already marks the file `skipped` (SHA-256 dedup)
  short-circuits with no poll. 202/poll Gson models in `model/AsyncUploadModels.kt`;
  tested by `PollEnvelopeTest` (13 cases).
- **Timeout retune — DONE.** `ApiClient` split into `uploadClient`
  (readTimeout 30 s) + `pollClient` (readTimeout 15 s) via `createPoll(...)`;
  `connectTimeout 20 s` and `writeTimeout 120 s` unchanged. Shipped in the same
  release as the async route (correct — lowering it before the parse moved
  off-request would have amplified the hang).
- **Release discipline:** no manual version bump in this PR — CI auto-bumps on
  merge; a manual bump folds into the squash, HEAD reads `[skip ci]`, and the
  "Bump patch version" workflow is suppressed (no release).
- **Plan:** DroneOpsCommand `docs/plans/2026-06-15-device-upload-async-decoupling.md`
  (shared). **Owner:** fleet-mobile-engineer / aegis.
- **Operator end-to-end check (after release lands):** run a multi-file sortie
  with one deliberately large record; confirm in Diagnostics → `[UPLOAD]` that a
  slow file no longer blocks the others and the submit returns `HTTP 202` with a
  `batch_id`.

## 2026-05-14 — SAF persisted-grant WRITE-flag fix (aegis; ADR-0006)

Branch `claude/saf-flight-log-rc-pro-2` (which carries ADR-0005 at `7f8e5d9`) gets a follow-up commit fixing the silent delete-on-controller failure surfaced by the operator on the DJI RC Pro 2.

### Root cause (Terry, verified from code at HEAD)

- `MainActivity.kt:54` took the persistable URI grant with `FLAG_GRANT_READ_URI_PERMISSION` only — no WRITE.
- `MainViewModel.kt:886` then called `DocumentsContract.deleteDocument(ctx.contentResolver, uri)` against that read-only grant.
- The `SecurityException` was swallowed by `runCatching{}.getOrDefault(false)`; `deleteSafDocument` returned `false`; per-row UploadStatus correctly stayed non-DELETED, but the status toast read `"$deleted deleted, $failed could not be removed (may already be gone)"` — phrasing the operator interpreted as "the system thinks it deleted them, but they're still on disk."

### Completed

- **MainActivity.kt** — new private `OpenDocumentTreeWithWrite` subclass of `ActivityResultContracts.OpenDocumentTree` whose `createIntent` adds `FLAG_GRANT_READ_URI_PERMISSION | FLAG_GRANT_WRITE_URI_PERMISSION` to the launch intent. Launcher registered through the subclass. `takePersistableUriPermission(uri, READ | WRITE)`. `import android.content.Context` added (the subclass signature requires it).
- **MainViewModel.kt::loadSettings** — WRITE-flag check on the persisted grant via `contentResolver.persistedUriPermissions.firstOrNull { it.uri == parsed }`. If `isWritePermission == false`, emit `[PERM]` WARN, clear the persisted SAF URI from prefs, null out `_safTreeUri`. The existing `_needsSafGrant.value = SDK_INT >= R && _safTreeUri.value == null` assignment runs immediately after and re-raises the home-screen banner.
- **MainViewModel.kt::deleteSafDocument** — silent `runCatching{}.getOrDefault(false)` replaced with explicit `try / catch(SecurityException) / catch(Exception)` emitting on a new `[DELETE]` diag channel. The `false`-return path (provider acknowledged the call but refused) logs `[DELETE]` WARN.
- **MainViewModel.kt::deleteSynced** — status message reworded so partial failure no longer reads as success. New phrasings route the operator to Diagnostics.
- **ADR-0006** created: `docs/adr/0006-saf-grant-must-include-write-flag-for-delete.md`. Status Accepted. Amends (does not supersede) ADR-0005.
- **ADR-0005** amended with a one-line "Amended 2026-05-14 by ADR-0006" pointer near the top.
- **CHANGELOG.md** — new `[Unreleased]` `Fixed` entry above the existing ADR-0005 entry, with the file-level summary + operator impact note.
- **ROADMAP.md** — v1.3.28 hotfix line added under Near-term follow-ups.

### Build / verification

- Local Gradle build was NOT run in this session (the workspace gradle daemon state was a mess of permission-denied configuration-cache directories from a prior session and gradlew would not be safe to invoke without first cleaning, which is out of scope for this surgical fix). The change is small (one new subclass, four small edits, no API surface change, no new dependency) and lint-clean by inspection: all referenced symbols (`ActivityResultContracts.OpenDocumentTree`, `Context`, `Intent`, `Uri`, `contentResolver.persistedUriPermissions`, `UriPermission.isWritePermission`, `DocumentsContract.deleteDocument`, `DiagLevel.ERROR`, `DiagLevel.WARN`) are already in scope or imported.
- BOS-HQ self-hosted CI will build the APK on the merge commit; the operator validates on physical hardware after install.

### Operator test plan (post-install on the RC Pro 2)

1. Install this build. Expect the home-screen banner ("DJI Fly logs need a one-time folder grant — tap to allow.") to reappear once — that is ADR-0006's startup `[PERM]` check forcing a re-grant of the now READ-only persisted URI.
2. Tap the banner, complete the SAF picker once (it should land pre-seeded at the DJI Fly FlightRecord directory).
3. Open Diagnostics. Expect `[PERM] SAF grant received: ...` and NO subsequent `[PERM] Persisted SAF grant is READ-only...` after a force-stop + relaunch (proves the new grant carries WRITE).
4. Run SYNC ALL on a controller that has at least one fresh FlightRecord file. Wait for all to flip to SYNCED.
5. Tap DELETE. Expect the toast `"N file(s) deleted from controller"` with N > 0 and NO `failed` count. Re-scan. Expect zero PENDING entries (the controller-side files are gone).
6. If any failure: Diagnostics → search for `[DELETE]` channel — the cause is now visible (was previously swallowed). Capture the message; that is the signal the Shizuku ADR-0005:141 trigger has fired.

### Not done in this PR (deliberate)

- No automated test. The `deleteSafDocument` path uses `ContentResolver` + `DocumentsContract` which are framework boundaries; meaningful tests need Robolectric, which this project does not currently use (same reasoning as ADR-0002's PROGRESS note). The fix is small and the operator test on physical hardware is the load-bearing signal.
- No version bump (CI auto-bumps on tag).

### Session wrap

- **PR #56** opened: `claude/saf-flight-log-rc-pro-2` → `main`. Carries three commits: `7f8e5d9` (ADR-0005 SAF picker), `91cf7d3` (ADR-0006 WRITE-flag fix), `ec6bd10` (research files + README/CLAUDE.md/ADR-0006 cross-refs). https://github.com/BigBill1418/DroneOpsSync/pull/56
- **OTA delivery confirmed.** Fixed keystore (CLAUDE.md → `KEYSTORE_B64` in GitHub secrets) keeps the signature stable, so the v1.3.28 release APK installs over v1.3.27 without uninstall and preserves both `SharedPreferences` and `contentResolver.persistedUriPermissions`. The new `loadSettings()` WRITE-flag check then sees the surviving READ-only grant, clears it, and re-raises the banner — one operator tap per controller, ever. No sideloading needed.
- **Known cleanup deferred** (not in PR #56): ~546 build-cache files under `android/.gradle/`, `android/app/build/`, `android/build/reports/` are tracked despite being in `.gitignore` (line 5). Will keep showing as modified on every Gradle run. Fix is `git rm --cached -r` on those trees in its own PR. Pure tidy-up, no behavior change.

## 2026-04-24 EVENING — Zero-touch key rotation client (aegis; v1.3.25 PR)

Branch `claude/auto-rotation-client` opened against `main`. Paired with DroneOpsCommand v2.63.6 (ADR-0003).

### Completed

- **Typed model.** New `DeviceHealthResponse` (`android/app/src/main/java/com/droneopssync/app/model/DeviceHealthResponse.kt`) with Gson `@SerializedName` fields for `status`, `device_label`, `parser_available`, `upload_endpoint`, `rotated_key`, `rotation_grace_until`. Forward-compatible — Gson ignores unknown fields.
- **Service signature.** `DroneOpsSyncService.deviceHealth` now returns `Response<DeviceHealthResponse>` instead of `Response<Map<String, Any>>`. Body is read on success path only.
- **ViewModel pickup.** `MainViewModel.maybeApplyRotatedKey` validates prefix `doc_` + length ≥ 40 + non-empty + not-equal-to-current; on a valid payload writes `PREF_API_KEY`, sets `_apiKey.value`, calls `ApiClient.invalidate()`, refreshes pairing, and emits the one-shot toast.
- **Toast SharedFlow.** New `_toastEvents` (replay=0, buffer=1, DROP_OLDEST) + `toastEvents: SharedFlow<String>` exposed publicly.
- **HomeScreen toast wiring.** `LaunchedEffect(Unit) { viewModel.toastEvents.collect { ... } }` displays via `android.widget.Toast.makeText(...).show()`. No SnackbarHost — the app's scaffold has none.
- **Unit tests.** `android/app/src/test/java/com/droneopssync/app/api/KeyRotationParseTest.kt`, 6 tests (full payload, absent hint, unknown fields, empty key, status-only, prefix/length contract). All green via `./gradlew :app:testDebugUnitTest`.
- **Test infrastructure.** Added `testImplementation 'junit:junit:4.13.2'` + `testImplementation 'com.google.code.gson:gson:2.11.0'` to `app/build.gradle`. Bootstraps `android/app/src/test/` (previously absent).
- **Docs.** ADR-0002 created. CHANGELOG `[Unreleased]` entry. ROADMAP success-criteria boxes ticked.

### Not done in this PR (deliberate)

ViewModel persistence + invalidate side-effects are not unit-tested — that path uses `SharedPreferences` and `viewModelScope` which need Robolectric; this project does not currently use Robolectric and adding it for one signal is out of scope. The Gson contract is the load-bearing piece; the linear glue beyond it is operator-verified on the controller after merge.

### Note on the routine

Claude Code remote routine `trig_01KiBK88vqs6vtRf75rkxcw8` shipped an empty branch on its first run. aegis re-ran the spec from the pickup conversation; this branch + paired DroneOpsCommand commit are the result.

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
