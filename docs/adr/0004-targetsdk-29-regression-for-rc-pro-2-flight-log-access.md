# ADR-0004 — `targetSdk` 35 → 29 to keep DJI flight-log scanning working on the RC Pro 2

> **SUPERSEDED 2026-05-01 by [ADR-0005](0005-saf-tree-picker-for-dji-flight-logs.md).**
>
> The `targetSdk` regression shipped (v1.3.26) and was empirically
> confirmed not to fix the RC Pro 2 — the AOSP-strict legacy-storage
> view does not expose `Android/data/<other-pkg>` even when the legacy
> flag is honored. ADR-0005 reverts the targetSdk drop and adds a SAF
> tree-picker as the primary scan path on Android 11+, which is the
> approach AirData and every shipping file manager use in production
> on this device class.

- **Status:** Superseded by ADR-0005
- **Date:** 2026-05-01
- **Owners:** Bill (operator), Aegis (implementation)
- **Supersedes:** none
- **Related:** ADR-0001 (Kotlin resumption), ADR-0002 (zero-touch key rotation), ADR-0005 (SAF tree-picker — the actual fix), `android/app/build.gradle`, `android/app/src/main/AndroidManifest.xml`, `MainViewModel.scanLogs()` / `performScan()`

---

## Context

DroneOpsSync's whole reason for existing is to scan DJI Fly's flight-log directories on the controller and POST them to the server. The canonical path for the Mavic 4 Pro is:

```
/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/
```

Bill's three operational devices behave very differently on that path:

| Device                              | Android | Storage model                                  | Worked at `targetSdk 35`? |
|-------------------------------------|---------|------------------------------------------------|---------------------------|
| Old DJI RC Pro                      | 7 / 9   | Pre-scoped-storage; `READ_EXTERNAL_STORAGE` is enough | Yes                       |
| Samsung S25 Ultra                   | 15      | AOSP scoped storage, BUT Samsung's `MANAGE_EXTERNAL_STORAGE` impl is permissive — `MES` ignores the AOSP `Android/data/<other-pkg>` lockdown | Yes                       |
| **New DJI RC Pro 2 (Mavic 4 Pro)**  | **11**  | **AOSP-strict.** Apps targeting SDK ≥ 30 cannot read `Android/data/<other-pkg>` even with `MANAGE_EXTERNAL_STORAGE`. | **No** (silent empty scan) |

The diagnosis took three rounds because the S25 Ultra masked the regression — Samsung's permissive MES made it look like the app was working "in general," when in fact the app had been quietly broken on stock-AOSP Android 11+ since whenever `targetSdk` was last bumped.

Bill confirmed by hand on the RC Pro 2 (file manager) that the FlightRecord directory and `.txt` files are present and readable to the user. The OS is hiding them from us, not from him.

## Decision

Lower `targetSdkVersion` from **35 → 29** in `android/app/build.gradle` and rely on Android 11's explicit legacy-storage carve-out:

> Apps that target SDK ≤ 29 and set `android:requestLegacyExternalStorage="true"` on `<application>` get pre-scoped-storage behavior on Android 11 — full `/sdcard` access including `Android/data/<other-pkg>`.

The `requestLegacyExternalStorage="true"` flag is already in the manifest from the original Kotlin port; this change is the gradle one-liner that activates it. The runtime permission flow in `MainActivity.onCreate` already requests `MANAGE_EXTERNAL_STORAGE` on Android 11+ via `Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`, which is the right intent to pair with the carve-out — no UI changes needed.

## Why not the more obvious alternatives

- **SAF tree picker (`ACTION_OPEN_DOCUMENT_TREE` on `Android/data/<dji-pkg>`):** Android 11+ explicitly blocks tree-picker selection of any `Android/data/*` subtree on AOSP, exactly to close this hole. Doesn't work.
- **Shizuku:** Would work but requires a sideloaded helper APK + adb-shell or root activation per device. Bill can't ship that to a single-purpose controller in the field.
- **DJI MSDK ingestion:** Out of scope and changes the whole architecture. The app currently treats DJI Fly as a black box producing files on disk.
- **Filesystem broadcast / file observer:** Same lockdown applies; the issue is read access, not change-notification.

`targetSdk 29` is the only no-code, no-sideload, no-root path on Android 11.

## Consequences

**Immediate, on-fleet:**

- RC Pro 2 starts seeing flight logs again on the next OTA install. Bill confirms with one synthetic flight + scan + upload.
- Old RC Pro and S25 Ultra continue to work — pre-Android-11 has no lockdown to begin with, and Samsung's permissive MES doesn't care about target SDK.
- No app behavior changes. Same scan, same upload, same OTA, same UI.

**Google Play store eligibility:**

- Not in scope. This APK is distributed via GitHub Releases (`release.yml`) and self-OTA (`MainViewModel.checkForUpdate`); never goes through Google Play. Play Console minimum-target-SDK rules are not a gate here.

**Library compatibility:**

- `compileSdk` stays at 35. `targetSdk` only affects runtime behavior (which compatibility shims the OS turns on); it does not affect what APIs we can call. Compose BOM 2024.12.01, AndroidX libraries, and AGP 8.13.2 all continue to work — they care about `compileSdk`.

**Future-fix trigger (multi-year):**

- When DJI eventually pushes Android 12+ to the RC Pro 2, this carve-out stops working at the OS level — Android 12+ enforces the `Android/data/*` lockdown regardless of target SDK. **DJI rarely upgrades controller Android versions** (the original RC Pro stayed on Android 7/9 for years), so we're buying years, not weeks.
- When that day comes, the answer is one of: (a) Shizuku-based access, (b) ship a companion service that reads via `adb`-style root if the controller is rootable, or (c) ingest via DJI MSDK and stop touching the filesystem. Until then, this ADR stands.

## Rollback plan

If `assembleDebug` / `assembleRelease` regresses on a library we didn't anticipate, or if device-side QA finds the change broke something else:

1. Revert the single-line gradle change (`targetSdk 29` → `targetSdk 35`).
2. Revert this ADR + the matching CHANGELOG entry.
3. Open a new ADR documenting the failure mode.

The change is intentionally one line of `targetSdk` and zero lines of Kotlin. Rollback is a `git revert`.

## Out of scope

- Any new SAF tree-picker UI or fallback path.
- Any auto-discovery of new DJI app IDs in `DEFAULT_PATHS` (already covers `dji.go.v5`).
- Any Shizuku integration scaffolding.
- Refactoring `scanLogs()` / `performScan()`.

## References

- Android 11 storage migration guide — "Manage all files on a storage device" §"Opt out of scoped storage": https://developer.android.com/training/data-storage/manage-all-files
- Android 11+ `Android/data` lockdown commentary: AOSP commit `Iaa7b6e9…` (`PackageManager` restrictions on `Android/data` directory access).
- `MANAGE_EXTERNAL_STORAGE` semantics — note explicit exclusion of `Android/data` and `Android/obb` from the grant (AOSP only; Samsung deviates).
- DroneOpsSync `MainActivity.kt` — `Build.VERSION.SDK_INT >= R` branch already requests All Files Access; pairs cleanly with the `targetSdk 29` carve-out without code changes.
