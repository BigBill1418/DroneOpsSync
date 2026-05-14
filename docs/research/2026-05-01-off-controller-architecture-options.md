# Off-Controller Architecture Options — DroneOpsSync RC Pro 2 Bypass

**Date:** 2026-05-01
**Status:** Research complete
**Owner:** Terry → Aegis (if pivot is approved)
**Companion docs:** `2026-05-01-mavic-4-pro-flight-logs.md` (on-controller SAF/Shizuku paths)

## TL;DR

**The killer find: AirData ships a sideloaded `.apk` purpose-built for the DJI RC Pro 2 that handles auto-upload of flight logs from `dji.go.v5`'s sandboxed directory.** They've already solved the Android-11 scoped-storage problem the on-controller research thread is wrestling with. Combined with AirData's REST API (Enterprise tier), DroneOpsSync becomes a thin poller against AirData rather than a controller-resident filesystem scanner. Total architecture cost: AirData Enterprise subscription + ~80 LOC backend poller.

If Bill won't pay for AirData Enterprise: MTP-from-laptop is the zero-dependency fallback (Bill already confirmed MTP works), but it requires "plug controller into laptop" as a per-session operator action.

## Quick Table

| # | Option | Viable | Per-session cost | Setup cost | Source |
|---|---|---|---|---|---|
| 1 | DJI Cloud API (REST flight-record retrieval) | **N** for consumer | n/a | n/a — Enterprise/Pilot 2 only; DJI killed US cloud sync June 2024 | [DJI Cloud API tutorial](https://developer.dji.com/doc/cloud-api-tutorial/en/), [DJI viewpoints — US sync deletion](https://viewpoints.dji.com/blog/download-your-data-before-it-gets-deleted) |
| 2a | AirData app on RC Pro 2 (auto-upload) | **Y** | Zero (Auto-Sync) | One-time `.apk` sideload + login | [AirData wiki — DJI RC Pro/RC Pro 2](https://app.airdata.com/wiki/Help/DJI+RC+Pro) |
| 2b | AirData REST API (poll for Bill's flights) | **Conditional** — Enterprise tier required | Zero (poll cron) | Enterprise sub + API key + ~80 LOC poller | [AirData API docs](https://app.airdata.com/docs/api/) |
| 3 | DJI MSDK v5 flight-record download | **N** | n/a | `IFlightLogManager.getLogPath()` returns the *same* `dji.go.v5` path; doesn't bypass scoped storage. Mavic 4 Pro is "onboard-only" support (no full MSDK control) | [IFlightLogManager docs](https://developer.dji.com/api-reference-v5/Components/IFlightLogManager/IFlightLogManager.html), [MSDK v5 supported drones](https://sdk-forum.dji.net/hc/en-us/articles/25552010279961) |
| 4 | DJI Fly built-in Share / Sync to PC / Email | **Conditional** (Share only, one-by-one) | One tap per record per session — fails Bill's no-per-session rule | DJI official docs do not document multi-select Share, Sync-to-PC, or Email-Report features for DJI Fly v1.21 on RC Pro 2 | [DJI Fly export guide](https://support.dji.com/help/content?customId=en-us03400006894) |
| 5 | MTP from desktop helper | **Y** | Plug controller into laptop after each session | ~150 LOC Python `pymtp`/Go `gomtpfs` daemon. Already-running NUC/desktop required | Bill empirically confirmed MTP browses `Internal shared storage/Android/data/dji.go.v5/files/FlightRecord` from Windows |
| 6 | DJI account web export (`fly.dji.com`) | **N** | n/a | DJI killed US cloud sync June 2024 + Oct/Nov 2024 deletion; no consumer web portal exists | [DJI viewpoints](https://viewpoints.dji.com/blog/download-your-data-before-it-gets-deleted) |
| 7a | DJI Pilot 2 Enterprise on RC Pro 2 | **N** | n/a | Pilot 2 doesn't load on consumer RC Pro 2; requires Enterprise drone + controller (DJI Dock / Matrice class) | [DJI Cloud feature set — Pilot access](https://github.com/dji-sdk/Cloud-API-Doc) |
| 7b | RC Pro 2 HDMI/USB-C → laptop "screen scrape" Share | **N** | per-record taps, brittle | Same one-by-one Share constraint as #4, plus added laptop layer | n/a |
| 7c | Wi-Fi-direct / SMB push from controller | **N** | n/a | RC Pro 2 has no built-in SMB client/server; sideloading one (Solid Explorer, X-plore) re-introduces the SAF prompt — same problem as on-controller path | [CommonsWare R book — SAF subdir carve-out](https://commonsware.com/R/pages/chap-scoped-006.html) |

## Top 2 Picks

### Pick #1 — AirData app on controller + AirData REST API on backend (Option 2a + 2b)

**Why:** AirData has already shipped, in production, the exact app DroneOpsSync is trying to build. Their `.apk` (downloaded from `airdata.com/ad` via Firefox on the controller) handles `dji.go.v5` directory access on every Android version DJI ships, including the locked-down RC Pro 2 image. Auto-Sync uploads each flight log to AirData's cloud within seconds of landing. On the backend, DroneOpsSync polls `GET /flights` against the AirData REST API on a 5-min cron, downloads new records via the per-flight `csvLink`/`originalLink` fields, and writes them into the existing droneops.barnardhq.com pipeline. The on-controller half of DroneOpsSync — the entire scoped-storage problem — disappears. Bill never touches the controller for log management again. **Cost:** AirData "HD 360 PRO" or "Enterprise" subscription (API access is gated to Enterprise per their docs); per their pricing page, Enterprise starts at $19.99/mo per pilot. Ongoing transitive dependency on AirData staying in business.

### Pick #2 — MTP-from-laptop daemon (Option 5)

**Why:** If AirData Enterprise is a non-starter (cost or vendor-lock-in objection), MTP is the only path that **provably works today** and **requires zero on-controller code**. Bill already confirmed Windows can browse the FlightRecord directory via MTP — that means the directory is exposed via MTP's media-store-style listing, which scoped storage doesn't gate (MTP runs through `MtpServer` on the device, not through SAF). A small Python daemon (`pymtp` or `libmtp` bindings) running on a laptop or NUC, polling for connected DJI controllers every 30s and uploading new records to `droneops.barnardhq.com`, takes ~150 LOC. **Tradeoff:** requires "plug controller into laptop" as a per-session physical action and a machine that's running. The CLAUDE.md constraint flags this as a caveat. For Bill's use case (single operator, post-flight workflow at home/office), this is probably acceptable; for a fleet operator or field-only workflow, it's not.

## Killer Combo — AirData primary + MTP fallback

The strongest hybrid is **Option 2a (AirData auto-sync from controller) as the primary path + Option 5 (MTP desktop daemon) as the offline / catch-up fallback**, with Option 2b (AirData API) as the backend poller.

- **Routine flights:** controller has Wi-Fi → AirData app uploads automatically → DroneOpsSync backend polls AirData API → records land in droneops.barnardhq.com within 5 min of landing. **Zero touches.**
- **Field with no Wi-Fi (or AirData app fails):** records sit in `dji.go.v5/files/FlightRecord/` on the controller. When Bill returns to the office and plugs the controller into a laptop, the MTP daemon catches up the backlog. **One physical action.**
- **AirData outage / Bill kills the subscription:** MTP daemon takes over fully. **Same one physical action.**

This combo eliminates the on-controller code path entirely (no Shizuku, no SAF prompt, no APK fork tracking DJI Fly updates), and gives Bill defense-in-depth against any single failure of either path. Total backend code: ~80 LOC AirData poller + ~150 LOC MTP daemon. Total on-controller code: zero (just sideload AirData's `.apk`).

**Recommendation:** if Bill will tolerate ~$20/mo for AirData Enterprise, ship the killer combo. If not, ship MTP-only and accept the per-session plug-in cost.

## Risks and Mitigations

- **AirData vendor risk:** AirData is privately held, ~10 yrs old, no public revenue figures. Mitigation: MTP fallback is independently functional; DroneOpsSync never becomes API-locked.
- **AirData filename-format drift:** AirData wiki notes DJI changed log filename conventions on 2025-05-21, requiring an AirData app update. This will recur. Mitigation: pin the AirData app version and update on a quarterly cadence; backend poller is filename-agnostic.
- **AirData API tier change:** Enterprise gating could change. Mitigation: monitor AirData pricing changes quarterly; have MTP fallback ready.
- **MTP polling permission on Windows:** First-time controller plug-in requires user accept "Allow access" prompt on the controller (one-time). Subsequent plug-ins are silent.
- **DJI Fly v1.21 may add a Sync-to-PC button later:** would obsolete much of this. Mitigation: revisit this doc on each DJI Fly major version bump.

## Sources

- [DJI Cloud API tutorial](https://developer.dji.com/doc/cloud-api-tutorial/en/)
- [DJI Cloud API Demo (GitHub)](https://github.com/dji-sdk/DJI-Cloud-API-Demo)
- [DJI viewpoints — US flight record sync deletion (2024)](https://viewpoints.dji.com/blog/download-your-data-before-it-gets-deleted)
- [DJI MSDK v5 IFlightLogManager API reference](https://developer.dji.com/api-reference-v5/Components/IFlightLogManager/IFlightLogManager.html)
- [DJI MSDK v5 supported drones (Mavic 4 Pro = "onboard-only")](https://sdk-forum.dji.net/hc/en-us/articles/25552010279961-What-devices-does-Mobile-SDK-V5-support)
- [AirData wiki — DJI RC Pro / RC Pro 2 sideload + Auto-Sync](https://app.airdata.com/wiki/Help/DJI+RC+Pro)
- [AirData wiki — DJI RC, RC 2 (predecessor docs)](https://app.airdata.com/wiki/Help/DJI+RC,+RC+2)
- [AirData REST API documentation](https://app.airdata.com/docs/api/)
- [AirData blog — DJI cloud-sync disabled in US (2024)](https://airdata.com/blog/2024/dji-to-disable-flight-record-sync-in-the-us-what-this-means-for-airdata-customers)
- [MavicPilots — Navigating RC Pro 2 to upload to AirData](https://mavicpilots.com/threads/navigating-the-rc-pro-2-to-find-and-upload-flight-logs-to-airdata.152435/)
- [DJI Fly & GO 4 Flight Record export guide](https://support.dji.com/help/content?customId=en-us03400006894)
- [DJI Cloud-API-Doc — Pilot access to cloud (Enterprise scoping)](https://github.com/dji-sdk/Cloud-API-Doc/blob/master/docs/en/30.feature-set/10.pilot-feature-set/10.pilot-access-to-cloud.md)
- [CommonsWare R book — SAF subdir carve-out](https://commonsware.com/R/pages/chap-scoped-006.html)
