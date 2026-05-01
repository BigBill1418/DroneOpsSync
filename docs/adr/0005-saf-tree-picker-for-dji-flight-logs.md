# ADR-0005 — Storage Access Framework tree-picker for DJI flight-log scanning

- **Status:** Accepted
- **Date:** 2026-05-01
- **Owners:** Bill (operator), Aegis (implementation)
- **Supersedes:** ADR-0004 (`targetSdk` 35 → 29 regression)
- **Related:** `android/app/src/main/java/com/droneopssync/app/storage/FlightLogSource.kt`,
  `android/app/src/main/java/com/droneopssync/app/MainActivity.kt`,
  `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt`,
  `docs/research/2026-05-01-android-11-saf-authoritative.md`,
  `docs/research/2026-05-01-rc-pro-2-deep-dive-competitors.md`

---

## Context

The DJI RC Pro 2 (Mavic 4 Pro Creators Combo) ships with stock-AOSP
Android 11. DroneOpsSync needs to read flight logs from
`/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/`, a path
that is owned by another package (`dji.go.v5`).

Two attempts have failed:

1. **Attempt 1 — `MANAGE_EXTERNAL_STORAGE` only.** Worked on Bill's
   Samsung S25 Ultra (Samsung's customized impl is permissive) and on
   the old DJI RC Pro (pre-Android-11, no scoped-storage lockdown).
   Returned zero logs on the RC Pro 2 because Google's own docs are
   explicit: "Apps that are granted the `MANAGE_EXTERNAL_STORAGE`
   permission still can't access the app-specific directories that
   belong to other apps, because these directories appear as
   subdirectories of `Android/data/` on a storage volume."
2. **Attempt 2 — ADR-0004: `targetSdk` 35 → 29 + `requestLegacyExternalStorage="true"`.**
   The legacy-storage flag is documented as honored only when `targetSdk
   <= 29`. The hope was that the resulting "legacy view" would expose
   the sibling-package directory. It did not, on stock-AOSP Android 11+:
   Google closed the legacy-MES + sibling-`Android/data/<other-pkg>`
   loophole at the same time as scoped storage. The targetSdk regression
   shipped (v1.3.26) and confirmed empty on the RC Pro 2.

Three independent research streams (see
`docs/research/2026-05-01-android-11-saf-authoritative.md` and
`-rc-pro-2-deep-dive-competitors.md`) converged on the same answer:
**Storage Access Framework with `EXTRA_INITIAL_URI` is the only
non-root, non-Shizuku path that works on Android 11+.** AirData ships
exactly this approach in their sideloaded RC Pro 2 APK. Material Files,
MiXplorer, Solid Explorer, X-plore, Total Commander, and FV File
Explorer all ship the same pattern. The Esper deep-dive on the AOSP
patch ("Android 13 Makes File Managers Less Useful by Fixing a
Loophole") confirms the pattern works on Android 11 and 12 and was only
closed in Android 13.

## Decision

Add SAF as the primary scan path on Android 11+. Keep the existing
`File`-based scan as a fallback for permissive OEMs (Samsung) and
Android <= 10. Both paths share a single `FlightLogSource` interface
that returns the same `List<FlightLog>` shape; the rest of the app
(upload, retry, delete, sync history) is unchanged.

```
if (Build.VERSION.SDK_INT >= 30 && safUri != null) → SafTreeSource
else                                                → LegacyFileSource
```

When Legacy succeeds on Android 11+, `_needsSafGrant` is lowered (no
nagging on Samsung devices). When Legacy returns 0 hits on Android 11+
and no SAF grant is held, the home-screen banner appears prompting the
operator to complete a one-time grant.

### Implementation summary

- `storage/FlightLogSource.kt` — sealed interface + two implementations.
  `LegacyFileSource` wraps the prior `File.listFiles` logic.
  `SafTreeSource` walks `DocumentFile.fromTreeUri(...).listFiles()`,
  copies each matching child to `cacheDir/saf-staging/<safe-name>` so
  the existing upload pipeline (`MultipartBody.Part` from
  `File.asRequestBody`) works without modification, and tracks the
  original SAF document URI for post-upload cleanup.
- `MainActivity.kt` — registers an `OpenDocumentTree` launcher seeded
  with `DocumentsContract.buildDocumentUri("com.android.externalstorage.documents",
  "primary:Android/data/dji.go.v5/files/FlightRecord")` so the picker
  lands one tap away from "Use this folder." On result, calls
  `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)`,
  persists the URI in `SharedPreferences`, and notifies the ViewModel.
- `MainViewModel.kt` — new `_safTreeUri` and `_needsSafGrant`
  StateFlows; `performScan()` dispatches to the right source.
  `cleanupSafCacheFile(log)` deletes the staging file after a
  successful upload (SYNCED or DUPLICATE). `deleteSynced()` uses
  `DocumentsContract.deleteDocument(...)` for SAF-sourced entries so
  the original on the controller (not just our cache copy) is removed.
- `FlightLog.kt` — additive `sourceUri: Uri? = null` field, defaults
  preserved so all existing call sites compile unchanged. Upload still
  keys off `file: File`.
- `HomeScreen.kt` — `SafGrantBanner` shown above the status badge when
  `needsSafGrant` is true.
- `SettingsScreen.kt` — "GRANT FLIGHT LOG FOLDER" / "RE-GRANT FLIGHT
  LOG FOLDER" button surfaces the picker for users who reset their
  controller or want to point at a different folder.
- `build.gradle` — `targetSdk 29 → 35` (revert ADR-0004),
  `androidx.documentfile:documentfile:1.0.1` added.

### Diagnostic logging

`[SCAN]` channel now logs `source=LEGACY` or `source=SAF` per scan,
plus the persisted SAF URI on startup, plus per-file entries with name
and size in the same format Legacy already used. This is the
load-bearing signal if the empirical test on Bill's RC Pro 2 fails for
a reason we didn't anticipate.

## Consequences

**Positive:**

- Unblocks the RC Pro 2 today on its current Android 11 image.
- Persistent grant — operator taps once, survives reboots
  (`UriPermission` API ref: "remembered across device reboots,"
  cap raised to 512 in Android 11).
- Targets SDK 35 again — modern AndroidX / future-OS-warning surface
  is back in scope.
- Same-source-of-truth abstraction (`FlightLogSource`) means future
  storage backends (Shizuku helper, MTP daemon) can be added behind
  the same interface without touching the upload pipeline.
- Delete semantics now correct on SAF entries — the original is
  removed from the controller, not just our cache copy.

**Negative:**

- Operator must complete a one-time SAF grant on Android 11+
  controllers that aren't Samsung-permissive. The grant survives
  reboots; this is a once-per-controller cost.
- SAF copies bytes through `cacheDir` for upload — adds disk write +
  read overhead vs. a direct `File` upload. Files are typically a few
  hundred KB to a few MB; staging is wiped at the start of each scan
  and individual cache files are deleted after upload.
- The picker UI is provided by the system (`DocumentsUI`); on a device
  where DJI's customizations have stripped or replaced it, this path
  fails and we fall back to the wired-pull workflow. Q3 of the SAF
  research notes this is empirically unverified on the RC Pro 2 — the
  diagnostic logging is the canary.

**Future fix trigger:**

Android 13 closed this loophole at AOSP. If DJI ships an RC Pro
firmware bumped to Android 13+, SAF will stop working and the next
move is Shizuku (operator pairs ADB once over USB or wireless, app
delegates the file read to the privileged Shizuku service). That
decision lives in a future ADR, not this one — DJI has historically
not bumped Android versions on shipped controllers, so this is a
multi-year horizon.

## References

- AOSP `ExternalStorageProvider.java` master branch (the
  `shouldBlockFromTree` patch that closed the loophole in Android 13):
  cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java
- Esper, "Android 13 Makes File Managers Less Useful by Fixing a
  Loophole" (esper.io)
- Google, "Access documents and other files from shared storage" —
  developer.android.com/training/data-storage/shared/documents-files
- Google, `UriPermission` reference —
  developer.android.com/reference/android/content/UriPermission
- `docs/research/2026-05-01-android-11-saf-authoritative.md` (in this
  repo, written 2026-05-01 by Terry as the precursor to this ADR)
- `docs/research/2026-05-01-rc-pro-2-deep-dive-competitors.md` (in
  this repo, AirData production-reference confirmation)
