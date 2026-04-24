# ADR-0001 — Kotlin resumption; abandon Capacitor companion fork

- **Status:** Accepted
- **Date:** 2026-04-24
- **Deciders:** Bill Barnard (operator)
- **Plan:** `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`

## Context

This repository (`BigBill1418/DroneOpsSync`) is a native Kotlin / Jetpack Compose Android app installed on DJI controllers (RC Pro, RC 2, RC Plus, etc.) to scan DJI flight-record directories and upload the logs to DroneOpsCommand via `POST /api/flight-library/device-upload` with a `X-Device-Api-Key` header. Last release before this ADR: **v1.3.23** on 2026-03-29.

On 2026-03-23 a prior Claude Code session working in the `BigBill1418/DroneOpsCommand` repository committed a Capacitor/TypeScript rewrite of DroneOpsSync into a new `companion/` subdirectory, calling it v2.33.0 (aligning the version with the DroneOpsCommand backend). That fork shipped v2.34.0 → v2.62.1 through 2026-04-24, accumulating six weeks of commits including substantive client-side fixes:

- **v2.62.0 (`890b875`)** — HTTPS-only base URL coercion, `/health` top-level alias, device-upload audit log. Resolved a class of failure where CloudFlare's 80→443 redirect HTML body was being fed to a JSON parser.
- **v2.62.1 (`306a2b8`)** — landscape orientation lock (DJI RC Pro is physically landscape-only) + silent-drift watchdog layers 1 + 2 (pairing banner + preflight health gate), layers 3 + 4 (Celery beat + first-401 Pushover — server-side).

On 2026-04-24, while diagnosing a lingering upload-blocked incident on Bill's RC Pro, it emerged that:

1. Bill's RC Pro has **always** run the Kotlin app from this repo (`BigBill1418/DroneOpsSync`), never the Capacitor fork.
2. The Capacitor companion has **zero device installs, ever.** GitHub release download counts are effectively zero for `companion-v2.33.0` through `companion-v2.62.1`.
3. Every substantive client-side fix since 2026-03-29 landed in the wrong repo. Bill was running an app that hadn't received any of the post-2026-03-29 fixes.
4. The 2026-04-23 upload-blocked incident was misdiagnosed at least twice by a parallel Claude agent ("Aegis") as a stale-APK problem that a sideload would fix. Bill caught the misdiagnosis (`project_droneopssync_upload_fix_20260424.md`): there was zero device traffic in BOS logs from the RC Pro's IP. The actual root cause was device-side Capacitor Preferences drift — but critically, **the device wasn't running Capacitor at all.** The drift theory applied to a device that didn't exist.

This is a 4-week wasted-work incident. A post-mortem surfaced the following contributing factors:

- The session that created `companion/` did not verify it was installed anywhere before proceeding.
- Subsequent sessions trusted the repo layout without asking "which of these two apps does Bill actually have on his controller?"
- The Capacitor-fork release tags (`companion-v2.xxx`) and the native-app release tags (`vX.Y.Z`) live in different repos, which made the split invisible unless you knew to look.
- This repo (DroneOpsSync) lacked a `CHANGELOG.md`, `ROADMAP.md`, `PROGRESS.md`, `docs/plans/`, and `docs/adr/` — so a returning session found no breadcrumbs and defaulted to working in DroneOpsCommand where the docs lived.

## Decision

1. The Kotlin app in this repository is the canonical DroneOpsSync. The Capacitor fork is abandoned.
2. The two substantive client-side fixes from the Capacitor fork (HTTPS coercion + landscape lock + pairing banner + preflight health gate) are ported into Kotlin in PR landing as v1.3.24.
3. Server-side watchdog layers 3 + 4 remain in `BigBill1418/DroneOpsCommand` — they are backend code and are already live.
4. The `companion/` tree in `BigBill1418/DroneOpsCommand` is deleted. The three orphan GH releases (`companion-v2.61.5`, `companion-v2.62.0`, `companion-v2.62.1`) have their bodies prepended with an "ABANDONED — DO NOT INSTALL" banner pointing here. The releases are not deleted — their APK assets remain as forensic evidence that zero devices downloaded them.
5. DroneOpsCommand's `docs/adr/0002-droneopssync-upload-auth.md` gets a new §7 documenting the abandonment and linking back to this ADR.
6. This repository seeds its documentation discipline — `CHANGELOG.md`, `ROADMAP.md`, `PROGRESS.md`, `docs/plans/`, `docs/adr/` — so a future session lands here first and finds breadcrumbs.
7. CI moves from `ubuntu-latest` to `[self-hosted, linux, x64, bos]` per fleet policy ADR-0029 (in DroneOpsCommand).
8. `release.yml` gains `apksigner verify --print-certs` + `aapt dump badging` checks so a keystore swap or versionName mismatch fails in CI, not on Bill's controller.

## Consequences

### Positive

- Single source of truth for the mobile app. Future sessions can't accidentally fork it again.
- Bill's RC Pro gets the HTTPS coercion + landscape lock + pairing banner + preflight gate via v1.3.24 OTA (no sideload — same keystore).
- DroneOpsCommand repo is ~50 MB lighter and no longer confuses new contributors with two competing "DroneOpsSync" directories.
- CI becomes consistent with the rest of the fleet (BOS-HQ self-hosted).

### Negative

- Six weeks of Capacitor commit history in DroneOpsCommand becomes archaeology. The three abandoned GH releases remain as forensic evidence; the source is only retrievable via `git log --all -- companion/` after the deletion merges.
- If a future decision re-introduces a cross-platform (iOS) requirement, the Capacitor approach will need to be re-researched from scratch. This is acceptable: iOS is not on the roadmap and DJI's Android-only controllers are the entire deployment target.

### Neutral

- Layers 3 + 4 of the §5 silent-drift watchdog (Celery beat + first-401 Pushover) remain the DroneOpsCommand project's concern — no change. They already alert on silent drift regardless of client implementation.

## Alternatives considered

### A. Keep both apps in parallel

**Rejected.** Bill uses one. Maintaining two doubles the surface area. The Capacitor fork has no differentiated value — it exists because a prior session thought it did, and six weeks of commits cemented the sunk cost without any deployment.

### B. Migrate the device to Capacitor (sideload v2.62.1 onto the RC Pro)

**Rejected.** Would require Bill to uninstall v1.3.23 + sideload v2.62.1 (different signing cert → Android treats it as a fundamentally different app) + lose his settings + re-pair. Sideload is specifically disallowed per the operator's written directive: "Zero sideload. v1.3.24 must sign with the same keystore as v1.3.23 so Android's updater accepts it." Going Capacitor violates that directive by definition.

### C. Port the Capacitor fixes into Kotlin (this ADR)

**Accepted.** Smallest blast radius, preserves the existing install base, honors the zero-sideload directive, and consolidates the project.

## Prevention — future drift

New files in this repo that make a second drift much less likely:

- `CHANGELOG.md` (seeded with v1.3.23 history + v1.3.24 entry) — returning sessions read this first.
- `ROADMAP.md` — explicit "don't fork this into Capacitor again" entry under anti-goals.
- `PROGRESS.md` — session-by-session log.
- `docs/plans/` — plan files live here, not somewhere else.
- `docs/adr/` — this file is the first; subsequent non-obvious decisions land next to it numbered.

Per user's global rule in `/home/bbarnard065/.claude/CLAUDE.md` §"Documentation Discipline (MANDATORY)" — these files are not optional.

## Research sources

- `/home/bbarnard065/droneops` commit `890b875` — Capacitor v2.62.0 HTTPS coercion + server /health alias + audit log.
- `/home/bbarnard065/droneops` commit `306a2b8` — Capacitor v2.62.1 landscape lock + silent-drift watchdog.
- `/home/bbarnard065/droneops/docs/adr/0002-droneopssync-upload-auth.md` — original device-API-key auth ADR; §5 adds the watchdog; §7 addendum (to be added) documents this abandonment.
- `BigBill1418/DroneOpsSync` tag `v1.3.23` — last native Kotlin release before this ADR.
- `project_droneopssync_upload_fix_20260424` (user's auto-memory) — post-mortem of the 2026-04-23 incident that surfaced the split.
- `project_gh_runner_bos_hq_20260421` (user's auto-memory) — BOS-HQ self-hosted runner + ADR-0029 fleet policy requiring all CI to run there.
