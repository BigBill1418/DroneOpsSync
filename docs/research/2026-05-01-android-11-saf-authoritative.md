# Android 11 SAF authoritative research — `Android/data/<other-pkg>` access

**Date:** 2026-05-01
**Context:** DroneOpsSync (Kotlin, sideloaded) needs to read
`/storage/emulated/0/Android/data/dji.go.v5/files/FlightRecord/` on a DJI
RC Pro 2 running a customized Android 11. `MANAGE_EXTERNAL_STORAGE` +
`requestLegacyExternalStorage` both fail. Settle whether SAF is viable
before writing more code.
**Author:** Terry (research-only pass)

---

## Q1 — Can `ACTION_OPEN_DOCUMENT_TREE` + `EXTRA_INITIAL_URI` navigate INTO `Android/data/<other-pkg>` on Android 11?

**Verdict: YES on stock Android 11 and 12. Confirmed working in production
file managers; closed in Android 13.**

The Esper deep-dive ("Android 13 Makes File Managers Less Useful by
Fixing a Loophole") states the workaround "works on Android 12L but not
on Android 13," and that prior to that fix, file managers used SAF with
the initial location pre-set to `/Android/data` or `/Android/obb`. Google
"explicitly blocked SAF directory access to `/Android`" but the
restriction "didn't block SAF directory access to subdirectories under
`/Android`." The Android 13 patch is the `shouldBlockFromTree` regex
addition in `ExternalStorageProvider.java` (AOSP master branch). On
Android 11/12 the method does not contain that branch, so SAF tree
permission grants for `/Android/data/<pkg>` succeed.

The Total Commander developer corroborates this in their forum thread:
TC 3.10 beta 11 shipped exactly this approach — "you have to specifically
ask to access `/storage/emulated/0/Android/data`" via SAF — and a user
confirmed "works on the Pixel 3" (a Pixel 3 max-tops at Android 12L, but
the original report dates from Android 11). Material Files issue #572 and
the broader file-manager community (MiXplorer, FV File Explorer, X-plore)
all ship the same pattern for Android 11+.

**Source:** Esper, "Android 13 Makes File Managers Less Useful by Fixing
a Loophole" (esper.io, 2022); Total Commander forum thread t=72778
(ghisler.ch); Material Files issue #572 (zhanghai/MaterialFiles).
Android Developers, "Access documents and other files from shared
storage" — confirms `EXTRA_INITIAL_URI` is supported on Android 8.0+.

## Q2 — Persistence after `takePersistableUriPermission`

**Verdict: survives process death AND device reboot, by design.** The
official `UriPermission` API reference states persisted grants "will be
remembered across device reboots." The cap was raised from 128 to 512 in
Android 11. Caveat: if the underlying directory is moved or deleted, the
URI grant becomes stale and you must re-prompt — relevant if DJI Fly
ever rotates the FlightRecord directory location, but historically it
has not. **No DJI-specific reports of customized boot flow clearing
persisted SAF grants** were found in MavicPilots, r/dji, or DJI forum
threads. Treat as safe; verify on the device once.

**Source:** developer.android.com/reference/android/content/UriPermission;
CommonsWare, "Count Your SAF Uri Persisted Permissions!" (2020-06-13).

## Q3 — Does `ACTION_OPEN_DOCUMENT_TREE` open the system Documents UI on RC Pro 2?

**Verdict: highly likely yes — but unverified for RC Pro 2 specifically.**
The RC Pro 2 runs a "custom Android OS" but explicitly supports
sideloaded APKs (Heliguy review; mavicpilots threads on installing
TikTok, Instagram, file-transfer apps). DJI's customizations are
launcher/system-app changes — not stripping out core AOSP system
services like `DocumentsUI` and `ExternalStorageProvider`, both of which
are required for Google's own apps to function and for the device's own
"Files" entry. **No forum report found of a sideloaded SAF-based file
manager failing to open the picker on RC Pro / RC 2 / RC Pro 2.** This
is a verifiable-in-5-minutes question (see hands-on test below) and I
won't claim certainty without an empirical observation on Bill's actual
unit.

**Source:** Heliguy, "Does DJI RC Pro 2 support installing third-party
apps?"; mavicpilots.com thread 146543 ("Install 3rd party software for
DJI RC and DIC RC 2"). Caveat noted in research: "RC 2 uses very
primitive Android 11 OS and it has limitations because of that" —
generic complaint, no specific SAF failure cited.

## Q4 — Does `dji.go.v5` expose any exported ContentProvider / FileProvider?

**Verdict: NO public/documented provider for FlightRecord.** No DJI
developer documentation lists a sibling-app ContentProvider for flight
records. The DJI Mobile SDK v5 (developer.dji.com) exposes flight data
*from the connected aircraft via SDK callbacks*, not by reading DJI
Fly's local FlightRecord directory. The official `dji-sdk/FlightRecordParsingLib`
repo is for parsing already-extracted `.txt` files, with no API for
fetching them from another app. APK manifest decompiles surfaced no
exported FileProvider. **Don't plan around this** — it's not a path.

**Source:** developer.dji.com (MSDK v5 docs);
github.com/dji-sdk/FlightRecordParsingLib; absence of evidence after
direct search of "dji.go.v5 AndroidManifest provider."

## Q5 — Other SAF escape hatches for Android 11+

**Verdict: none for `Android/data/<other-pkg>`.** `MediaStore` only
indexes media MIME types — `.txt` flight records are not indexed and
won't appear via `MediaStore.Files`. `MANAGE_EXTERNAL_STORAGE` is
explicitly carved out: "Apps that are granted the
MANAGE_EXTERNAL_STORAGE permission still can't access the app-specific
directories that belong to other apps, because these directories appear
as subdirectories of Android/data/ on a storage volume" (Google's own
docs). `ContentResolver.openInputStream` requires a URI you already
have a grant for — it doesn't bypass anything. **The SAF tree-picker
loophole IS the escape hatch on Android 11/12.**

**Source:** developer.android.com/training/data-storage/manage-all-files
("All files access" page); developer.android.com/about/versions/11/privacy/storage.

## Q6 — Non-Shizuku, non-root apps that read `Android/data/<other-pkg>` on Android 11+ in 2024-10+

**Verdict: yes — Material Files, MiXplorer, Solid Explorer, X-plore,
Total Commander, FV File Explorer all ship the SAF tree-picker pattern
with `EXTRA_INITIAL_URI` pointed at `Android/data` or its subpath. Same
trick.** No undocumented hack — the pattern is exactly what Q1
describes. There is no Android 11 escape that doesn't go through SAF
(and no Shizuku alternative that doesn't require ADB pairing, which is
operationally hostile on the RC Pro 2 anyway).

**Source:** F-Droid forum thread 33403; Material Files issue #611 (Allow
Android/data access even without root); folderv.com 2025-09-22 article
on FV File Explorer SAF + Shizuku approaches.

---

## What this means for DroneOpsSync

**SAF is viable on Android 11 — it's the only viable non-root path.**
The previous research recommendation was correct in direction; the
empirical evidence (multiple shipping file managers + Esper's tested
confirmation that Android 12L still permits this and only Android 13
closes it) makes it a confident bet. Implementation:

```kotlin
val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
           or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
    val initialUri = DocumentsContract.buildDocumentUri(
        "com.android.externalstorage.documents",
        "primary:Android/data/dji.go.v5/files/FlightRecord"
    )
    putExtra(DocumentsContract.EXTRA_INITIAL_URI, initialUri)
}
```

Then `contentResolver.takePersistableUriPermission(treeUri, FLAG_GRANT_READ_URI_PERMISSION)`,
walk children with `DocumentFile.fromTreeUri(...).listFiles()`, open
each via `contentResolver.openInputStream(child.uri)`. **One-time
prompt, persists across reboots.**

**Realistic options ranked:**
1. **SAF tree-picker (do this)** — works on Android 11/12, persists,
   no root, no Shizuku, single user prompt. The whole reason this
   research existed.
2. **Wired pull from desktop** — keep the existing manual workflow as
   fallback if SAF fails the empirical check on the RC Pro 2 (Q3).
3. **MSDK v5 telemetry stream** — different data shape (live aircraft
   telemetry, not the parsed `.txt` flight records DJI Fly writes), and
   re-architects the app. Last resort only.

**Not viable: MES, legacy storage flag, MediaStore, sibling ContentProvider, root.**

## 5-minute empirical test for Bill on the RC Pro 2

Before writing any code — settle Q3 with this:

1. On the RC Pro 2, sideload **Material Files** APK from F-Droid
   (`https://f-droid.org/packages/me.zhanghai.android.files/` —
   download the APK on a desktop, copy to microSD or USB, install via
   the RC Pro 2's built-in Files app). Free, open-source, no signup,
   ~10 MB.
2. Open Material Files. Tap the hamburger menu → "Add storage."
3. Choose "External storage." Material Files invokes
   `ACTION_OPEN_DOCUMENT_TREE`.
4. **Verify the system Documents UI opens** (this answers Q3
   empirically). It should look like a stock file picker with a
   hamburger on the left listing storage volumes.
5. In the picker, navigate Internal storage → Android → data →
   `dji.go.v5` → files → FlightRecord. Tap "Use this folder," then
   "Allow."
6. **Confirm Material Files can now list the `.txt` files** in that
   directory and open one for reading.

If step 4 fails (no system picker, DJI replaced it with a stub) — SAF is
dead on this device and the answer becomes wired-pull fallback. If
steps 5-6 succeed — the SAF approach is fully validated and Bill is
clear to ship. Total time: ~5 minutes including download.

---

## Sources

- Esper, "Android 13 Makes File Managers Less Useful by Fixing a Loophole" — esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524
- Total Commander forum thread t=72778 — ghisler.ch/board/viewtopic.php?t=72778
- Material Files issue #572 — github.com/zhanghai/MaterialFiles/issues/572
- Material Files issue #611 — github.com/zhanghai/MaterialFiles/issues/611
- Google, "Access documents and other files from shared storage" — developer.android.com/training/data-storage/shared/documents-files
- Google, "Storage updates in Android 11" — developer.android.com/about/versions/11/privacy/storage
- Google, "Manage all files on a storage device" — developer.android.com/training/data-storage/manage-all-files
- Google, `UriPermission` reference — developer.android.com/reference/android/content/UriPermission
- AOSP, `ExternalStorageProvider.java` (master) — cs.android.com/android/platform/superproject/+/master:frameworks/base/packages/ExternalStorageProvider/src/com/android/externalstorage/ExternalStorageProvider.java
- CommonsWare, "Count Your SAF Uri Persisted Permissions!" — commonsware.com/blog/2020/06/13/count-your-saf-uri-permission-grants.html
- Heliguy, "Does DJI RC Pro 2 support installing third-party apps?" — heliguy.com/blogs/knowledge-base/does-dji-rc-pro-2-support-installing-third-party-apps
- mavicpilots.com thread 146543 — install 3rd party software for DJI RC / RC 2
- mavicpilots.com thread 152435 — navigating RC Pro 2 to find flight logs
- DJI support, Flight Record export guide — support.dji.com (en-us03400006894)
- AirData wiki, DJI RC / RC 2 uploads — app.airdata.com/wiki/Help/DJI+RC,+RC+2
- F-Droid forum thread 33403 — Open Source File manager for app data folders
