# RC Pro 2 deep-dive: how competitors actually read DJI Fly flight records

**Date:** 2026-05-01
**Author:** Terry (research)
**Question:** What mechanism do shipping third-party apps use to read
`/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/` on the
DJI RC Pro 2 (Android 11), and what should DroneOpsSync copy?

---

## 1. What works in production today

**AirData UAV (Android, sideloaded APK Build 361+)** is the only third-party
app verified to auto-sync DJI Fly flight records on the RC Pro 2 from inside
the controller itself. AirData tells users to install the APK from
`https://airdata.com/ad`, log in, and enable Auto Sync — and that on
"devices running Android 11 and older, including most smart remote
controllers," the Shizuku workaround they document for Android 13/14 is
**not required**
([AirData Android 13/14 permissions](https://app.airdata.com/wiki/Help/Granting+Permissions+in+Android+13+and+14)).
The mechanism is **Storage Access Framework with a pre-positioned
`ACTION_OPEN_DOCUMENT_TREE` intent**: the app launches the picker pointed
at `Android/data/dji.go.v5`, the user taps "Use this folder → Allow," and
AirData persists the tree URI and reads via `DocumentFile`
([AirData DJI Fly folder grant flow](https://app.airdata.com/wiki/Help/DJI+GO+3,+DJI+GO+4,+DJI+Pilot,+and+DJI+Fly)).
DJI itself confirms the bifurcation: built-in file app cannot see
`Android/data`, but "some file apps can have the access permissions that
the built-in file app does not have"
([DJI forum, RC2 Internal Files](https://forum.dji.com/thread-314364-1-1.html)).
Multiple AirData help pages explicitly cover RC Pro 2 with the same
sideload-APK + Auto-Sync flow as RC Pro
([AirData RC Pro / RC Pro 2 page](https://app.airdata.com/wiki/Help/DJI+RC+Pro,+RC+Pro+2)),
and operator reports on MavicPilots confirm "the AirData App has full
access to it for auto sync" on RC Pro 2 (Aug 2025 thread surfaced by
search; thread 152435).

**Litchi and Dronelink** also "auto-sync to AirData," but they are
*flight* apps that **replace DJI Fly** and write their own logs — they
do not read DJI Fly's `FlightRecord` directory
([AirData Litchi page](https://app.airdata.com/wiki/Help/Litchi)). Not
relevant to our problem.

**Phantom Help / Flight Reader** has no on-device Android app for the RC
Pro 2; for US users (DJI killed cloud sync 2024-06-12) the only paths
are USB MTP from a PC or LAN web upload from a phone
([Flight Reader import options](https://forum.phantomhelp.com/t/all-available-options-for-importing-flight-logs-into-flight-reader/9734)).

## 2. Mechanisms ranked by viability for DroneOpsSync

| Mechanism | Confirmed working? | Controller setup | Per-flight friction | Our impl cost |
|---|---|---|---|---|
| **SAF `OPEN_DOCUMENT_TREE` with EXTRA_INITIAL_URI = `Android/data/dji.go.v5`** | YES — this is what AirData does on RC Pro 2 ([AirData docs](https://app.airdata.com/wiki/Help/Granting+Permissions+in+Android+13+and+14)). The Android 11 SAF loophole closed in **Android 13** ([Esper, Android 13 closed the loophole](https://www.esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)) — RC Pro 2 is on Android 11, so **the loophole is open**. | Tap once after install: pick folder → Allow. Persistent. | Zero | Medium: replace `File.listFiles()` with `DocumentFile.fromTreeUri()` + `ContentResolver.takePersistableUriPermission()`. ~1 day. |
| **Shizuku (wireless ADB)** | Works on phones; **not confirmed on RC Pro 2** in any source I found. RC Pro 2 has Developer Options + USB Debugging (DJI forum threads 270069, 293679); wireless debugging unverified. | High: enable dev options, enable wireless debugging, pair Shizuku with code, authorize app each reboot. | Re-pair after every reboot unless Android 14+ auto-start hack works (RC Pro 2 is Android 11, so manual). | Medium-high: integrate Shizuku API + maintain ADB-shell file reader. |
| **DJI Cloud API for flight logs** | Dead end for US: DJI disabled US flight-record cloud sync 2024-06-12 ([AirData blog 2024](https://airdata.com/blog/2024/dji-to-disable-flight-record-sync-in-the-us-what-this-means-for-airdata-customers)). Cloud API targets enterprise/Pilot 2, not consumer DJI Fly. | N/A | N/A | N/A |
| **DJI MSDK v5 sibling-app IPC / ContentProvider** | No published evidence DJI Fly v5 exposes any ContentProvider for FlightRecord. MSDK v5 reads logs from its OWN app's storage, not DJI Fly's. RC Pro 2 MSDK support also still being requested ([dji-sdk MSDK Android-V5 issue 561](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/561)). | N/A | N/A | High and uncertain. |
| **DJI Fly's in-app Share intent** | DJI Fly's own UI lets a user long-press FlightRecord and tap Share ([Skyzr how-to](https://www.skyzr.com/en/dji/how-to-export-and-view-dji-flight-log-files/)). DroneOpsSync could register a SEND/SEND_MULTIPLE receiver. | One-time: pilot enables receiver as Share target. | Per-flight: pilot must manually share each session. | Low (manifest `<intent-filter>`), but Bill explicitly wants no per-session interaction. |
| **`MANAGE_EXTERNAL_STORAGE` + `targetSdk` 29 + `requestLegacyExternalStorage`** | Already empirically broken on RC Pro 2 (v1.3.26 confirmed `exists=false`). DJI's customized Android 11 evidently enforces the `Android/data/{otherpkg}` lockdown that AOSP allows but Samsung waived. | N/A | N/A | Already paid; doesn't work. |

## 3. Verdict

**Ship the SAF tree-URI flow.** This is what AirData ships, on the same
controller, on the same Android 11, against the same `dji.go.v5`
directory — and we have the AirData help-page text describing the
exact one-tap user flow. It is the only confirmed-working,
zero-per-session-interaction path to DJI Fly's FlightRecord on the RC
Pro 2 today. Implementation: on first launch (and on missing-URI),
fire `Intent(ACTION_OPEN_DOCUMENT_TREE)` with `EXTRA_INITIAL_URI` set
to `content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fdji.go.v5`,
take persistable read permission, store the URI, then walk
`DocumentFile.fromTreeUri(...).listFiles()` for `*.txt` matching the
existing FlightRecord regex. Keep the `File.listFiles()` path as a
fallback for the S25 Ultra (Android 15 with Samsung's permissive MES).

## 4. Killer questions for Bill (≤ 5 min)

1. **On the RC Pro 2 right now**: install AirData (`airdata.com/ad`,
   the APK), log in with any account, tap Settings → Auto Sync. Does it
   prompt you with "Allow access to dji.go.v5 → Use this folder →
   Allow"? Does it then show your test flights? **If yes → confirms SAF
   path works on Bill's specific RC Pro 2 firmware.** (~3 min)
2. **Settings → About Remote Controller → System Version**: what
   firmware version are you on, and does tapping it 7 times reveal
   Developer Options? (Determines whether Shizuku is even on the
   table as a fallback.) (~1 min)
3. **OK with a one-time "Pick folder → Allow" tap** the first time the
   app runs on a new controller? Confirming this is acceptable UX
   before we build it. (~30 sec)

---

## Sources

- [AirData — DJI RC Pro, RC Pro 2 help page](https://app.airdata.com/wiki/Help/DJI+RC+Pro,+RC+Pro+2)
- [AirData — Granting Permissions in Android 13 and 14 (notes Android 11 fallback works without Shizuku)](https://app.airdata.com/wiki/Help/Granting+Permissions+in+Android+13+and+14)
- [AirData — DJI GO/Pilot/Fly folder grant flow ("Use this folder → Allow")](https://app.airdata.com/wiki/Help/DJI+GO+3,+DJI+GO+4,+DJI+Pilot,+and+DJI+Fly)
- [AirData blog 2024 — DJI to disable US cloud flight-record sync](https://airdata.com/blog/2024/dji-to-disable-flight-record-sync-in-the-us-what-this-means-for-airdata-customers)
- [AirData — RC, RC 2 manual upload (USB only — confirms RC Pro 2 is the special case where the in-app SAF flow works)](https://app.airdata.com/wiki/Help/DJI+RC,+RC+2:+Uploads+for+US+Users)
- [Heliguy — RC Pro 2 supports third-party apps via browser/microSD APK install](https://www.heliguy.com/blogs/knowledge-base/does-dji-rc-pro-2-support-installing-third-party-apps/)
- [DJI forum — RC2 Internal Files / Flight Records ("some file apps can have the access permissions the built-in app does not")](https://forum.dji.com/thread-314364-1-1.html)
- [Esper — Android 13 closed the SAF /Android/data loophole (the loophole AirData uses; still open on Android 11 = RC Pro 2)](https://www.esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524)
- [Mishaal Rahman tweet — confirms loophole patch was Android 13](https://x.com/MishaalRahman/status/1719416906174681554)
- [Android docs — `ACTION_OPEN_DOCUMENT_TREE` cannot pick `Android/data` root, but EXTRA_INITIAL_URI to a subdir is the workaround](https://developer.android.com/training/data-storage/shared/documents-files)
- [dji-sdk/Mobile-SDK-Android-V5 issue 561 — RC Pro 2 MSDK support still being requested](https://github.com/dji-sdk/Mobile-SDK-Android-V5/issues/561)
- [AirData — Litchi (third-party flight app, writes own logs, not relevant)](https://app.airdata.com/wiki/Help/Litchi)
- [Phantom Help Flight Reader — import options (no on-controller app for RC Pro 2)](https://forum.phantomhelp.com/t/all-available-options-for-importing-flight-logs-into-flight-reader/9734)
- [Skyzr — DJI Fly Share intent for FlightRecord](https://www.skyzr.com/en/dji/how-to-export-and-view-dji-flight-log-files/)
