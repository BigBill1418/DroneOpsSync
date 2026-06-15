# ADR-0008 — Device-upload async poll client + per-file timeout isolation

- **Status:** **Proposed** — design only, no code shipped. Client-side half of
  the cross-repo device-upload async decoupling (audit P2-2 full leg).
- **Date:** 2026-06-15
- **Authors:** Terry (research/architect). Implementation handoff to
  fleet-mobile-engineer / aegis.
- **Scope:** Native Kotlin DroneOpsSync companion app (`android/`) — the upload
  path in `viewmodel/MainViewModel.kt`, the Retrofit service in
  `api/DroneOpsSyncService.kt`, and the OkHttp client in `api/ApiClient.kt`.
- **Related ADRs:** [`0002-zero-touch-device-key-rotation-client.md`](./0002-zero-touch-device-key-rotation-client.md)
  (the device-health preflight this builds on; same cross-repo pairing shape).
  **Canonical cross-repo ADR (the contract owner):** DroneOpsCommand
  [`docs/adr/0023-device-upload-async-celery-decoupling.md`](https://github.com/BigBill1418/DroneOpsCommand/blob/main/docs/adr/0023-device-upload-async-celery-decoupling.md).
  Read that first — it defines the protocol, the 202/poll envelope, the
  backwards-compat decision, and the risk matrix. This ADR records only the
  **client** decisions.
- **Plan:** DroneOpsCommand `docs/plans/2026-06-15-device-upload-async-decoupling.md`
  (shared, cross-repo plan; the client stages live in its §"DroneOpsSync leg").

---

## 1. Context

Today `MainViewModel.performUpload()`
(`viewmodel/MainViewModel.kt:610-766`) uploads one file per request in a
`for (log in pending)` loop against `POST /api/flight-library/device-upload`,
which **parses synchronously in-request** on the server. Two client-visible
defects (full evidence in the DroneOpsCommand ADR-0023):

- **B1:** the connection is held open for the full server-side parse; field
  cellular reaps it; a >120 s parse trips OkHttp `readTimeout`
  (`api/ApiClient.kt:115`).
- **B2:** `SocketTimeoutException` (`:721-725`) sets `aborted = true`, and the
  loop guard (`:661-665`) then marks **every remaining file** in the sortie
  `ERROR` without attempting them. One slow file loses files N+1…M.

The app ships as a native APK via GitHub release + the in-app updater — **no
OTA**. Every client change is a real release each operator must install, so the
server stays backwards-compatible indefinitely (DroneOpsCommand owns that
guarantee via a *separate* async route; the legacy synchronous route this
client uses today keeps working forever).

---

## 2. Decision

### 2.1 Fast-follow first: socket-timeout is per-file, not per-batch

Ship a **standalone APK release before** the async work that makes a single
line change: in `MainViewModel.performUpload`, the `catch
(SocketTimeoutException)` block (`:721-725`) **must not** set `aborted = true`.
Mark the current file `ERROR`, increment `totalErrors`, **continue the loop**.

Leave `aborted = true` on:
- `UnknownHostException` (`:716-720`) — host is unreachable; the whole batch is
  doomed; correct.
- HTTP 401 / 403 (`:690`) — the device key is bad; every file would 403;
  correct.

Only the socket-timeout coupling is the bug. This works against today's
synchronous server, needs no backend change, and converts "one slow file kills
the sortie" into "one slow file fails alone; long-press to retry it."

### 2.2 Async adoption: 202 + poll

In a *later* release, point the upload at the new server route and adopt the
poll handshake defined in DroneOpsCommand ADR-0023:

- Add to `DroneOpsSyncService`:
  - `@POST("/api/flight-library/device-upload/async")
    uploadFlightsAsync(...)` → returns `202` + `{batch_id, files:[…]}`.
  - `@GET("/api/flight-library/device-upload/status/{batchId}")
    pollUpload(...)` → returns the `{status, phase, progress, per_file[]}`
    envelope.
- Keep **one file per request** (one `batch_id` per file) — this maps 1:1 onto
  the existing per-`LogFile` `UploadStatus` model, so the UI barely changes. A
  file goes `UPLOADING` on the 202, then the poll loop drives it to
  `SYNCED` / `DUPLICATE` / `ERROR` from the `per_file[0].state`.
- **Poll cadence:** every 2 s while non-terminal, back off to 5 s after 60 s,
  stop the *foreground* poll at a generous ceiling (the job completes
  server-side regardless; next launch reconciles via SHA-256 dedup).
- **Capability detection:** read `async_upload_available` from the existing
  `deviceHealth` preflight response (`DeviceHealthResponse` already tolerates
  unknown fields — ADR-0002 §2.1, `KeyRotationParseTest`). If absent/false,
  fall back to the legacy synchronous `uploadFlights` call. This keeps a new
  APK working against an old backend during the rollout window.

### 2.3 Timeout retune — only in the async release

Once the held-connection parse is gone, the upload POST returns in
byte-streaming time. **In the same release that adopts the async route**
(never before): split the OkHttp timeouts — upload `readTimeout` ~30 s, a
separate short poll `readTimeout` ~15 s, `connectTimeout` unchanged (20 s).
Lowering the 120 s timeout *before* the async leg would amplify B1, so the
fast-follow (§2.1) keeps 120 s untouched.

### 2.4 Preflight unchanged

The `deviceHealth` preflight gate (`MainViewModel:634`) stays exactly as is —
it gates the batch on reachability + key validity and carries the ADR-0003
rotation hint. The poll loop is additive and only runs after a 202. The
preflight is not replaced by the poll.

---

## 3. Consequences

- §2.1 is a one-line deletion with a large reliability payoff on the operator's
  primary field workflow; independent of backend; ships first.
- §2.2/§2.3 add a poll loop + state machine but reuse the existing per-file
  `UploadStatus` rendering and error-string handling (`body.errors`,
  `MainViewModel:680`).
- Client-death/mid-poll is safe: the server completes the job and dedup makes
  re-scan/re-submit idempotent (see ADR-0023 §2.4).

## 4. Rollout note (DroneOpsSync release discipline)

**Do NOT manually bump the version** in any of these PRs. GitHub folds a manual
bump into the squash, the HEAD commit reads `[skip ci]`, and the "Bump patch
version" workflow is suppressed — the release never fires and operators never
get the APK. **CI auto-bumps on merge; let it.** This applies to both the
fast-follow PR (§2.1) and the async-adoption PR (§2.2/§2.3).

## 5. Validation

- Fast-follow (§2.1): JVM unit test under
  `android/app/src/test/java/com/droneopssync/app/` proving a simulated
  socket-timeout on file *k* leaves files *k+1…M* attemptable (extract the
  loop's per-file decision into a pure testable function, per the plan).
- Async adoption (§2.2/§2.3): JVM tests on the poll-envelope parse
  (mirroring `KeyRotationParseTest`'s Gson-contract style) + the terminal-state
  reducer; operator confirms end-to-end on a controller after merge.
