# DroneOpsSync Roadmap

## Current phase

**v1.3.25 — zero-touch device API key rotation.** Scheduled via Claude Code remote routine `trig_01KiBK88vqs6vtRf75rkxcw8` (fired 2026-04-24T18:58Z). Deliverable: PRs on both DroneOpsCommand + DroneOpsSync implementing grace-window dual-key auth + device-side preflight pickup. ADR will land as `docs/adr/0002-zero-touch-device-key-rotation.md` in this repo; corresponding server-side ADR-0003 lives in DroneOpsCommand.

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

## v1.3.25 — scheduled 2026-04-24T18:58Z

**Objective:** Eliminate manual key-paste on paired controllers after any server-side device API key rotation.

**Design summary.** Backend grace window (24h) during which both old and new keys authenticate. Device's existing preflight health gate parses a `rotated_key` field from the response body, writes to SharedPreferences, and invalidates the Retrofit cache. Operator sees a transient toast "API key auto-updated"; takes zero action on the controller. Full design in the paired backend ADR-0003 in DroneOpsCommand, mirrored here as ADR-0002.

**Remote routine:** `trig_01KiBK88vqs6vtRf75rkxcw8` — https://claude.ai/code/routines/trig_01KiBK88vqs6vtRf75rkxcw8. Expected output: one PR against `main` here, one PR against `main` in DroneOpsCommand, tests passing in the remote sandbox. CI will fire after Bill merges.

**Success criteria:**
- [x] PR opened against DroneOpsSync `main` (`feat(kotlin): auto-pickup of server-rotated API keys via preflight response`) — branch `claude/auto-rotation-client`, opened 2026-04-24 evening
- [x] PR opened against DroneOpsCommand `main` (`feat: zero-touch device API key rotation (ADR-0003)`) — note: that repo has an `auto-merge-claude.yml` workflow that promoted the branch to `main` 7s after push; commit `e0295a1` is reviewable directly on `main`. Operator can revert if review surfaces issues.
- [ ] v1.3.25 APK ships post-merge with same keystore as v1.3.24 (`apksigner verify --print-certs` match)
- [ ] End-to-end dry-run: operator rotates a dev-tier device key server-side; paired controller picks it up on next sync without any Settings interaction

## Near-term follow-ups (post-v1.3.25)

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
