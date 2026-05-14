# Mavic 4 Pro / RC Pro 2 — DroneOpsSync flight-log scan returns zero

**Date:** 2026-05-01
**Status:** Research complete, ready for Aegis spec
**Owner:** Terry (research) → Aegis (implementation)

## Changelog

- **2026-05-01 (PM, correction):** Bill verified Settings → About reports **Android 11**, not Android 13 as the morning brief assumed. Verdict + Aegis Spec rewritten on that ground truth. Controller / Firmware / Log Path / Code-side Default sections unchanged. Pattern B (Share intent) demoted to fallback; SAF-with-pre-seeded-URI promoted; Shizuku now ranked #2 (was deferred); `targetSdkVersion=29` regression confirmed dead end.

## Controller

The Mavic 4 Pro Creator Combo ships with the **DJI RC Pro 2** (7" 2000-nit Mini-LED, USB-C, no removable SD). DJI does not publish the underlying Android version on the spec sheet, but the device exposes a normal Android Files app, accepts MTP over USB-C, and behaves as **Android 13-class** (matches RC Pro 2's `dji.go.v5`-only sandbox and the SAF lockdown documented in DJI/MavicPilots threads). It is *not* a hard-locked appliance — it is an Android tablet running a DJI launcher, with the same scoped-storage rules as any other modern Android device. ([Adorama listing][1], [MavicPilots: RC Pro 2 file paths][2])

## Firmware

- **Aircraft (Mavic 4 Pro):** **v01.00.0500**, released 2026-01-13. No log-storage changes; adds mid-recording focal-length switching, refined low-battery RTH, Dynamic Home Point on RC 2. ([DroneDJ Jan 2026 firmware coverage][3])
- **Controller (DJI RC Pro 2):** **v01.00.0600** + DJI Fly **v1.21.0**, released 2026-04-23. Adds screen-lock and broader drone-model support; **no path or storage-permission changes**. ([Heliguy March 2026 firmware roundup][4], [Grey Arrows RC Pro 2 firmware thread][5])

Both are current as of today. Bill should confirm the controller is on `v01.00.0600` (Settings → About) before we ship.

## Log Path

DJI Fly v5 on the RC Pro 2 writes flight logs to **the same path the legacy RC Pro used**:

```
/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/
```

Files are `DJIFlightRecord_<DATE>_<TIME>.txt` (DAT-lite). No new path was introduced for the M4P generation. ([MavicPilots: Navigating the RC Pro 2 to upload to AirData][2], [AirData wiki — DJI RC, RC 2][6])

**This path is already in `DEFAULT_PATHS`.** The bug is not path drift.

## Code-side Default

`android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt:47-52` — `DEFAULT_PATHS` already contains `/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord` (line 48).

Manifest at `android/app/src/main/AndroidManifest.xml:9` declares `MANAGE_EXTERNAL_STORAGE`; line 13 adds `requestLegacyExternalStorage="true"` (ignored on Android 11+). Scan logic at `MainViewModel.kt:432-450` does plain `File(path).listFiles {…}` — the classic pre-scoped-storage call.

## Verdict (revised 2026-05-01 PM — Android 11 confirmed)

**Permission/scoped-storage problem, not a path problem.** Same root cause as the morning brief, but the Android-11 ground truth opens an option that was assumed closed.

Android 11 (API 30) blocks `File.listFiles()` against `/Android/data/<other-package>/...` for any third-party app, even one holding `MANAGE_EXTERNAL_STORAGE` — this is the well-documented "scoped storage" tightening. ([Android 11 storage policy][7], [Manage all files docs][8]) It also blocks `ACTION_OPEN_DOCUMENT_TREE` from selecting `/Android/data` itself. ([developer.android.com — Android 11 storage page][7])

**However, Android 11 has a documented carve-out the morning brief missed:** the SAF picker permits selection of `/Android/data/<pkg>/` and `/Android/obb/<pkg>/` *subdirectories* when the app pre-seeds `DocumentsContract.EXTRA_INITIAL_URI`. This is the same loophole every Android 11/12 file manager (Solid Explorer, MiXplorer, X-plore, FX) uses. **Google closed this loophole in Android 13** by extending `ExternalStorageProvider.shouldBlockFromTree` to subdirectories. ([Esper — Android 13 closed the loophole][9], [CommonsWare R book — SAF Restrictions][12]) On Android 11 it remains open and is the cleanest fix.

This is why the M3P + old RC Pro worked (older Android image without scoped-storage enforcement) and the new M4P + RC Pro 2 returns zero (Android 11 scoped storage, even though Bill granted "All files access"). The diag log should print `MANAGE_EXTERNAL_STORAGE: GRANTED` and **still** find no files — that's the smoking gun (`MainViewModel.kt:303-307`).

### Three options, ranked

**1. SAF + pre-seeded `EXTRA_INITIAL_URI` (RECOMMENDED)** — Open a one-time `ACTION_OPEN_DOCUMENT_TREE` intent with `EXTRA_INITIAL_URI` set to `content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fdata%2Fdji.go.v5%2Ffiles%2FFlightRecord`. The Android 11 SAF picker honors that initial URI, the user taps "Use this folder" → "Allow", and the app `takePersistableUriPermission`'s the result. From then on, `DocumentFile.fromTreeUri(...)` enumerates the directory exactly like `listFiles()` did before. Persistent across reboots. **One-time setup per controller; zero per-session interaction. Survives DJI Fly updates. Works on every M4P/RC-Pro-2 unit running Android 11.** Code change is contained: ~80 LOC plus a one-screen onboarding flow.

**2. Shizuku** — RC Pro 2 *does* expose Developer Options (Settings → Devices → tap Build Number repeatedly per [peterreinold.com guide][13]) and Android 11 *does* expose Wireless Debugging in Developer Options. AirData officially recommends Shizuku for this exact problem on Android 13/14 ([AirData "Granting Permissions in Android 13 and 14"][14]) — strong precedent. Implementation cost is meaningful (`shizuku-api` dependency, Shizuku app install, one-time wireless-pair flow per controller, Shizuku must be re-started after every reboot per [Shizuku setup guide][15]). DJI's "ADB blocked from installing apps" ([MavicPilots — RC 2 install thread][16]) does NOT block Shizuku — Shizuku only needs `WRITE_SECURE_SETTINGS` granted via the wireless-pair shell, not `pm install`. **Risk:** OEM ROMs sometimes silently disable wireless debugging after reboot ([Shizuku #1911 — StarOS][17]) and DJI's "primitive Android 11" customizations ([forum.dji.com thread 265378][18]) may behave the same — needs hands-on confirmation. Also: Shizuku must be re-started after every controller reboot, which violates Bill's "no per-session interaction" constraint unless we automate it via Tasker/Automate, which is fragile.

**3. `targetSdkVersion=29` regression** — DEAD END. Android docs are unambiguous: "If your app targets Android 11, it cannot access the files in any other app's data directory, even if the other app targets Android 8.1 (API level 27) or lower and has made the files in its data directory world-readable." ([Android 11 storage policy][7]) The restriction is enforced by the **device's** Android version, not the **app's** target SDK. Dropping `targetSdkVersion` to 29 keeps `requestLegacyExternalStorage` honored for media-store-style access — but `Android/data/<other-package>/` is gated separately and is blocked regardless. Also kills Play Store uploads (Play requires targetSdkVersion ≥ 34 in 2026). Not viable.

Pattern B (Share intent) from the morning brief is no longer the recommendation — it costs Bill one tap per record per session, which fails his "per-session interaction is not acceptable" rule. Keeping it as a fallback is fine but it shouldn't ship as the primary path.

## Aegis Spec — SAF + pre-seeded `EXTRA_INITIAL_URI`

**Goal:** Restore zero-touch, zero-per-session-interaction log capture on RC Pro 2. One-time per-controller setup is acceptable.

**Approach:** Add a SAF-based scan path that runs in parallel to the existing `File.listFiles()` path. On first launch (or when scan returns zero on Android 11+), prompt the user with a one-screen "Grant DJI Flight Records folder access" dialog that opens the system picker pre-seeded to the DJI FlightRecord directory. Persist the granted URI. All subsequent scans use `DocumentFile.fromTreeUri(...)`. Existing `File.listFiles()` path stays for Android ≤10, RC Plus, and phones — where `MANAGE_EXTERNAL_STORAGE` still works.

**Files to change:**

1. `android/app/src/main/java/com/droneopssync/app/storage/SafFlightLogReader.kt` (NEW) — wraps `DocumentFile.fromTreeUri(ctx, persistedUri).listFiles()`, filters by extension, exposes `read(uri): InputStream` via `contentResolver.openInputStream`. Mirrors the API surface of the current `File`-based reader so `MainViewModel` can call either.
2. `android/app/src/main/java/com/droneopssync/app/MainActivity.kt` — register `ActivityResultContracts.OpenDocumentTree()` launcher. On result, call `contentResolver.takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` and persist to `SharedPreferences` under new key `PREF_SAF_DJI_TREE_URI`.
3. `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt` — `performScan()` (lines 432-450): if `Build.VERSION.SDK_INT >= 30` AND `prefs.contains(PREF_SAF_DJI_TREE_URI)`, route through `SafFlightLogReader`; otherwise current `File.listFiles()` path. Add `_needsSafGrant: MutableStateFlow<Boolean>` that fires when scan returns zero on Android 11+ AND no persisted SAF URI exists.
4. `android/app/src/main/java/com/droneopssync/app/ui/SafGrantPrompt.kt` (NEW) — Compose dialog with copy: *"DJI's flight log folder is locked by Android. Tap Grant, then 'Use this folder' → 'Allow' on the next screen. One-time setup."* Button fires the launcher with `EXTRA_INITIAL_URI = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Android/data/dji.go.v5/files/FlightRecord")`.
5. `android/app/src/main/java/com/droneopssync/app/MainActivity.kt` (HomeScreen route) — observe `needsSafGrant`, show `SafGrantPrompt` via `LaunchedEffect`.
6. `android/app/src/main/AndroidManifest.xml` — keep `MANAGE_EXTERNAL_STORAGE` (still useful on Android ≤10 and RC Plus). No new permissions needed for SAF.
7. `MainViewModel.kt:300-311` — diag block now also reports `SAF_TREE_URI: <uri or NONE>` and counts files visible via SAF reader.
8. `SettingsScreen.kt` — add "Re-grant DJI folder access" button that re-fires the launcher. Useful if user revokes permission or migrates controllers.
9. `CHANGELOG.md` + new `docs/adr/0004-saf-pre-seeded-uri-flight-record-access.md` — record decision: why SAF over Shizuku (zero per-session interaction, no third-party app, survives reboot natively); why not `targetSdkVersion=29` (Android docs explicitly block); when this stops working (Android 13 upgrade — at which point the controller will need Shizuku or re-fork to Pattern B).

**Test plan on Bill's actual RC Pro 2 before claiming success:**

1. Install the new APK. Launch fresh. Confirm `SafGrantPrompt` appears within ~2s of the launch scan returning zero.
2. Tap "Grant" → confirm system picker opens already inside `Android/data/dji.go.v5/files/FlightRecord/` (not at the root). Tap "Use this folder" → "Allow".
3. Auto-rescan fires. Confirm DJIFlightRecord_*.txt files now appear in the home-screen list with the correct count vs what Bill sees in DJI Fly's own log UI.
4. Trigger upload-all. Confirm files reach DroneOps backend `/api/flights/upload`, sync history shows green.
5. **Reboot the controller.** Re-launch DroneOpsSync. Confirm scan still works without re-prompting (persisted URI honored across boot).
6. Take a fresh flight, return to controller, re-launch DroneOpsSync. Confirm the new `DJIFlightRecord_<today>_*.txt` is visible and uploads.
7. Existing path-scan still works on Bill's S25 Ultra (regression check — phone is on Android 14+, will hit the same SAF prompt; that's correct behavior).
8. Diag screen reports `SAF_TREE_URI: content://...` and a non-zero file count.

**Fallback if SAF picker on RC Pro 2 refuses to honor `EXTRA_INITIAL_URI`** (DJI customizations could in theory disable this — see "load-bearing claims" below): cut a v1.3.25-shizuku branch that adds `dev.rikka.shizuku:api` and follows AirData's published flow. The `SafFlightLogReader` interface keeps both implementations swappable.

**Out of scope for this Aegis run:** Shizuku integration (only ship if SAF fails the test plan), Pattern B Share-intent (only if both fail), Android 13+ forward path (revisit when DJI ships an RC Pro 2 firmware bumping the Android base — none on roadmap as of this brief).

## Sources

- [1] Adorama — DJI Mavic 4 Pro Creator Combo with RC Pro 2: <https://www.adorama.com/djimavic4pcc.html>
- [2] MavicPilots — Navigating the RC Pro 2 to find and upload flight logs to AirData: <https://mavicpilots.com/threads/navigating-the-rc-pro-2-to-find-and-upload-flight-logs-to-airdata.152435/>
- [3] DroneDJ — Mavic 4 Pro firmware patch (Jan 2026): <https://dronedj.com/2026/01/29/mavic-4-pro-drone-firmware/>
- [4] Heliguy — DJI firmware updates March 2026: <https://www.heliguy.com/blogs/posts/dji-firmware-updates-march-2026/>
- [5] Grey Arrows — DJI RC Pro 2 firmware updates thread: <https://greyarro.ws/t/dji-rc-pro-2-firmware-updates/96241>
- [6] AirData — DJI RC, RC 2 upload help: <https://app.airdata.com/wiki/Help/DJI+RC,+RC+2>
- [7] Android Developers — Storage updates in Android 11: <https://developer.android.com/about/versions/11/privacy/storage>
- [8] Android Developers — Manage all files on a storage device: <https://developer.android.com/training/data-storage/manage-all-files>
- [9] Esper — Android Dessert Bites #28: SAF loophole closed: <https://www.esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524>
- [10] Mishaal Rahman on X — SAF Android/data block: <https://x.com/MishaalRahman/status/1719416906174681554>
- [11] DJI Support — DJI Fly & GO 4 Flight Record export guide: <https://support.dji.com/help/content?customId=en-us03400006894>
- [12] CommonsWare — SAF Restrictions (Android 11+ subdir carve-out): <https://commonsware.com/R/pages/chap-scoped-006.html>
- [13] peterreinold.com — Tips & Tricks for Your DJI RC Pro 2 and RC 2 Controller (Developer Options enable): <https://peterreinold.com/tips-tricks-for-your-dji-rc-pro-2/>
- [14] AirData — Granting Permissions in Android 13 and 14 (Shizuku-based flow): <https://app.airdata.com/wiki/Help/Granting+Permissions+in+Android+13+and+14>
- [15] Shizuku — Setup Guide (wireless debugging method, restart-after-reboot caveat): <https://shizuku.rikka.app/guide/setup/>
- [16] MavicPilots — Install 3rd party software for DJI RC and RC 2 (DJI ADB install restrictions): <https://mavicpilots.com/threads/install-3rd-party-software-for-dji-rc-and-dic-rc-2.146543/>
- [17] Shizuku Issue #1911 — OEM ROMs disabling wireless debugging: <https://github.com/RikkaApps/Shizuku/issues/1911>
- [18] DJI Forum — DJI RC-Full Android Access (RC 2 "primitive Android 11" thread): <https://forum.dji.com/thread-265378-1-1.html>
