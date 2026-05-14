# ADR-0006 — SAF persisted grant must include WRITE for delete-on-controller

- **Status:** Accepted
- **Date:** 2026-05-14
- **Owners:** Bill (operator), Aegis (implementation), Terry (root-cause research)
- **Amends:** ADR-0005 (does NOT supersede — the SAF approach is correct;
  this ADR fixes the grant model only)
- **Related:** `android/app/src/main/java/com/droneopssync/app/MainActivity.kt`,
  `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt`,
  [`docs/adr/0005-saf-tree-picker-for-dji-flight-logs.md`](0005-saf-tree-picker-for-dji-flight-logs.md),
  [`docs/research/2026-05-01-android-11-saf-authoritative.md`](../research/2026-05-01-android-11-saf-authoritative.md),
  [`docs/research/2026-05-01-rc-pro-2-deep-dive-competitors.md`](../research/2026-05-01-rc-pro-2-deep-dive-competitors.md)

---

## Context

ADR-0005 (commit `7f8e5d9`) introduced a Storage Access Framework
tree-picker so the DJI RC Pro 2 (stock-AOSP Android 11) could scan
`Android/data/dji.go.v5/files/FlightRecord` and so post-upload cleanup
could remove the original on the controller via
`DocumentsContract.deleteDocument(...)`. Two facts in ADR-0005's
implementation summary cannot both hold simultaneously:

1. The picker result was taken with
   `takePersistableUriPermission(uri, FLAG_GRANT_READ_URI_PERMISSION)` —
   READ only, no WRITE flag.
2. `deleteSynced()` calls `DocumentsContract.deleteDocument(...)` on the
   persisted URI to remove the original from the controller.

`DocumentsContract.deleteDocument` raises `SecurityException` when the
caller's persisted grant lacks `FLAG_GRANT_WRITE_URI_PERMISSION`. The
exception was swallowed by `runCatching { ... }.getOrDefault(false)`,
so `deleteSafDocument(uri)` returned `false` silently. The per-row
`UploadStatus.DELETED` correctly stayed off for failed entries (the
list view was right), but the user-facing toast read
`"$deleted deleted, $failed could not be removed (may already be gone)"`
— phrasing the operator reasonably interpreted as "the system thinks it
deleted them, but they're still on the controller."

Operator-observable symptom on the RC Pro 2: after SYNC ALL + DELETE,
the controller's `Android/data/dji.go.v5/files/FlightRecord` directory
still contains the same files; re-scan picks them all up again as
PENDING.

The smoking-gun pair (HEAD at `7f8e5d9`):

- `android/app/src/main/java/com/droneopssync/app/MainActivity.kt:54` —
  `val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION`
- `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt:886` —
  `android.provider.DocumentsContract.deleteDocument(ctx.contentResolver, uri)`

The first line shows the persisted grant carries no WRITE flag; the
second line is the call that requires it.

## Decision

Take the persistable grant with both `FLAG_GRANT_READ_URI_PERMISSION`
and `FLAG_GRANT_WRITE_URI_PERMISSION`. Force re-grant on existing
installs whose persisted grant is READ-only. Surface the previously
swallowed `SecurityException` via the existing diagnostic-log machinery
on a new `[DELETE]` channel so any future provider-side failure (Android
13+ AOSP-loophole closure, OEM strangeness, file-not-found races) is
operator-visible instead of silent.

### Implementation summary

- `MainActivity.kt` — introduce a private
  `OpenDocumentTreeWithWrite` subclass of
  `ActivityResultContracts.OpenDocumentTree` whose `createIntent`
  ORs `FLAG_GRANT_READ_URI_PERMISSION |
  FLAG_GRANT_WRITE_URI_PERMISSION` onto the picker intent's flags
  (the default contract does not attach WRITE). Register the launcher
  via this subclass.
- `MainActivity.kt` — `takePersistableUriPermission(uri, READ | WRITE)`
  instead of READ only.
- `MainViewModel.kt::loadSettings` — after parsing the persisted URI,
  inspect `appContext.contentResolver.persistedUriPermissions`. If a
  matching record exists and `isWritePermission == false`, log a `[PERM]`
  WARN, clear the persisted key from `SharedPreferences`, and null out
  `_safTreeUri`. The next assignment to `_needsSafGrant` (already in
  `loadSettings`) then re-raises the home-screen banner so the operator
  re-completes the grant once — the new picker takes WRITE.
- `MainViewModel.kt::deleteSafDocument` — replace silent
  `runCatching{}.getOrDefault(false)` with an explicit
  `try { ... } catch (SecurityException) { ... } catch (Exception) { ... }`
  block that emits a `[DELETE]` ERROR diag entry on `SecurityException`
  (the WRITE-flag canary) and a generic `[DELETE]` ERROR on any other
  exception. Truncates `e.message` to 120 chars to keep the diag buffer
  bounded.
- `MainViewModel.kt::deleteSynced` — reword the user-visible status
  message so the operator no longer reads partial failure as success.
  New phrasing routes them to Diagnostics:
  - all-clear: `"$deleted file(s) deleted from controller"` (unchanged)
  - all-failed: `"Delete failed for $failed file(s) — check Diagnostics ([DELETE] channel)"`
  - partial: `"$deleted deleted, $failed could not be removed — see Diagnostics"`

### Diagnostic logging

`[PERM]` now records the WRITE-flag check outcome on startup. `[DELETE]`
is a new channel reserved for `deleteSafDocument`'s catch arms; it
fires on `SecurityException` (the WRITE-missing case, plus any future
provider-side revocation) and on any other exception thrown by
`DocumentsContract.deleteDocument`. The `false`-return path (provider
acknowledged the call but refused to delete) also logs a `[DELETE]`
WARN so a "file was already gone server-side" race is visible.

## Consequences

**Positive:**

- Delete-on-controller actually works on the RC Pro 2 and any future
  Android 11/12 device behind SAF.
- Operators upgrading from a v1.3.27 install whose grant is READ-only
  see the SAF banner once more on first launch of this build; one tap
  re-completes the grant with WRITE included.
- If `ExternalStorageProvider` refuses WRITE on `Android/data/<other-pkg>`
  subtrees on a specific device (an outcome ADR-0005:141 flagged as the
  trigger for a future Shizuku ADR), the failure now surfaces in
  Diagnostics under `[DELETE]` instead of silent-dropping. The Shizuku
  trigger gets a real signal.
- No protocol change — no manifest change, no new permissions, no new
  dependency. The `androidx.documentfile:documentfile:1.0.1` from
  ADR-0005 is sufficient.

**Negative:**

- Existing v1.3.27 installs need one extra operator tap (re-complete the
  SAF picker once). The `[PERM]` WARN at startup explains why if the
  operator wonders why the banner reappeared.
- If the operator dismisses the picker without completing it, the app
  is back in the "needs SAF grant" state and scans return zero on the
  RC Pro 2 — same state as a fresh install. Recoverable via Settings
  → GRANT FLIGHT LOG FOLDER.

**Future fix trigger:**

If a future Android version closes the `Android/data/<other-pkg>`
SAF loophole (Android 13 already did at AOSP; DJI hasn't shipped Android
13 on shipped controllers yet), the new `[DELETE]` channel will report
`SecurityException` even with WRITE-flagged grants. That is the signal
to fall through to Shizuku per ADR-0005's "future fix trigger" — the
Shizuku ADR itself is still a future ADR.

## References

- Google, `Intent#FLAG_GRANT_WRITE_URI_PERMISSION` —
  developer.android.com/reference/android/content/Intent#FLAG_GRANT_WRITE_URI_PERMISSION
- Google, `UriPermission#isWritePermission()` —
  developer.android.com/reference/android/content/UriPermission#isWritePermission()
- Google, `DocumentsContract#deleteDocument` —
  developer.android.com/reference/android/provider/DocumentsContract#deleteDocument(android.content.ContentResolver,%20android.net.Uri)
- AOSP `ExternalStorageProvider.java` — same source cited in ADR-0005;
  the `shouldBlockFromTree` patch closed the loophole on Android 13.
- ADR-0005 (this repo) — the picker design this ADR amends.
