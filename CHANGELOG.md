# Changelog

All notable changes to DroneOpsSync (native Kotlin Android app for DJI controllers) are documented here. Format follows [Keep a Changelog](https://keepachangelog.com/).

## [Unreleased]

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
