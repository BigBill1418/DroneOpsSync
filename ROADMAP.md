# DroneOpsSync Roadmap

## Current phase

**Kotlin resumption** — porting v2.62.0/v2.62.1 Capacitor-fork client fixes into Kotlin; cutting v1.3.24. Tracking plan: `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`. Context ADR: `docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md`.

## v1.3.24 (active)

**Objective:** Get Bill's three pending RC Pro uploads across + lock the safety net (landscape, pairing banner, preflight gate) + move CI to BOS-HQ.

**Success criteria:**
- [ ] v1.3.24 GH release published with APK asset
- [ ] APK signer SHA-256 fingerprint matches v1.3.23's (zero sideload)
- [ ] `aapt dump badging` shows `versionName='1.3.24'` + `targetSdkVersion='29'`
- [ ] CI green on BOS-HQ self-hosted runner
- [ ] Bill's RC Pro picks up v1.3.24 via in-app OTA (no sideload)
- [ ] Three pending FlightRecord uploads succeed end-to-end
- [ ] Pairing banner + preflight gate observed to behave correctly under induced failure

## Near-term follow-ups (post-v1.3.24)

- **FU-1** — unit tests for `ApiClient.normalizeUrl` covering LAN carve-out, HTTPS coercion, path stripping. Not blocking ship; belongs in a follow-up PR.
- **FU-2** — consider whether `checkServerHealth` (unauthenticated `/api/health`) is still needed now that `preflightHealth` (authenticated `/device-health`) is the gate. Possibly collapse to one call.
- **FU-3** — persistent telemetry of first-install version + last-active timestamp, so a future "which devices are on which version?" question doesn't require an all-hands APK audit. Could ride on the same `/device-health` response.
- **FU-4** — add an "Updates available" count badge to the Home screen (today only visible after tapping "Check for Updates" in Settings).

## Medium-term

- Android 13 / 14 `READ_MEDIA_*` permission changes — current code uses `MANAGE_EXTERNAL_STORAGE` which still works on 33+ but shows a scary "All files access" dialog. Evaluate scoped storage alternatives once DJI publishes `targetSdk` 33+ guidance.

## Anti-goals (from ADR-0001)

- **Do not fork this into Capacitor / React Native / Flutter / anything else.** Single app, Kotlin, lives here.
- **Do not add Play Store distribution.** In-app GitHub OTA via `GitHubClient` is sufficient and the operator prefers it.
- **Do not rotate the signing keystore.** Every rotation is a forced sideload for every existing device.

## Archive

- **v2.33.0 → v2.62.1 (Capacitor fork, abandoned)** — 2026-03-23 to 2026-04-24. Zero device installs. See ADR-0001. Source in `git log --all -- companion/` under DroneOpsCommand up to its deletion commit.
