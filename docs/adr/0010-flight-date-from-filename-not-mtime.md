# ADR-0010: Derive flight date from the filename, guard the mtime fallback

- Status: Accepted
- Date: 2026-07-02
- Related: ADR-0005 (SAF tree-picker), ADR-0009 (transfer-integrity guard — same
  pure-function seam)

## Context

The operator reported flight logs showing a wild date — most visibly **"1969"**.

Root cause: `FlightLog.dateFormatted` rendered
`SimpleDateFormat(...).format(Date(file.lastModified()))`.

Two problems compound:

1. **SAF cache copies carry a zeroed mtime.** For SAF entries `file` is a
   per-scan staging copy in `cacheDir` (ADR-0005). DJI controller
   `DocumentFile`s frequently report `lastModified() == 0`; even though the copy
   path now only stamps `originalMtime` when `> 0` (ADR-0009 hardening), a copy
   whose source reported `0` keeps a `0` mtime. `Date(0)` is the Unix epoch,
   which renders as **"31 Dec 1969"** in the operator's Pacific timezone.
2. **File mtime is the wrong field anyway.** mtime is the file's *write* time on
   the controller, not the flight datetime. The authoritative flight datetime is
   encoded in the DJI log **filename**.

## Decision

Display-only change. Derive the date from the filename first; fall back to mtime
only when that mtime is plausible; otherwise show "Unknown date". No scan /
upload / delete / sync behaviour changes.

New pure, JVM-testable module **`model/FlightDate.kt`** (same seam pattern as
`storage/TransferIntegrity.kt` / `upload/UploadOutcome.kt`; placed in `model`
because its sole consumer is `model/FlightLog.kt`, avoiding a model↔storage
package cycle):

- `deriveFlightDateMillisFromName(name)` — extracts a `YYYY-MM-DD` (optionally
  `[HH-MM-SS]` / `_HH-MM-SS`) from the filename. Covers the real shapes this app
  ingests: DJI FlightRecord `DJIFlightRecord_2026-06-30_[12-34-56].txt` (all DJI
  GO/Fly generations), Litchi leading-timestamp CSV `2026-06-30_12-34-56_*.csv`,
  and Airdata date-in-name `Flight-2026-06-30.csv`. Impossible dates (month 13,
  day 32) are rejected by a non-lenient parse. Date-only names are anchored at
  local **noon** so the rendered calendar date is stable across time zones / DST.
- `isPlausibleTimestamp(millis, now)` — true only in `[2010-01-01,
  now + 48h]`. This is the guard: a `0` / negative / epoch mtime is never
  plausible, so it can never render as 1969 (or any misleading date).
- `resolveFlightDisplayMillis(name, mtime, now)` — filename date (if plausible)
  → else mtime (if plausible) → else `null` (caller shows "Unknown date").

`FlightLog.dateFormatted` now calls `resolveFlightDisplayMillis(...)` and returns
"Unknown date" on `null`. Formatting is unchanged (`dd MMM yyyy  HH:mm`,
`Locale.getDefault()`).

The `> 0` mtime-stamp guard in `SafTreeSource.scan` (ADR-0009) already satisfies
fix requirement #3 — no change was needed there.

## Consequences

- The "1969" (and any epoch-garbage) date can no longer render; such a log shows
  its authoritative filename date, or "Unknown date" if neither source is
  trustworthy.
- **Non-breaking for correct displays.** A legacy file with no date-in-name and a
  valid mtime still shows the mtime-derived date, identical to before. A DJI file
  now shows its filename datetime; the `dd MMM yyyy` portion is the same day as
  the old mtime display (DJI writes the record on landing), while the `HH:mm`
  shifts from write-time to the more authoritative flight-start time in the name.
- **List ordering is unchanged.** `MainViewModel.performScan` still sorts by
  `file.lastModified()`. A SAF copy with a zeroed mtime therefore still sorts
  last even though its date now displays correctly. Re-keying the sort on the
  derived date is a deliberate follow-up (it changes observable ordering and is
  outside this display-only, non-breaking fix).
- Only length-of-name date signals are used; a controller with a mis-set clock
  that also writes a wrong date into the filename would still display that wrong
  (but plausible) date — no client-side truth source beyond the filename exists.
- The Android-runtime touch (`FlightLog.dateFormatted`) was inspection-verified;
  the pure logic in `FlightDate.kt` was compiled and executed against a Kotlin
  compiler (20/20 assertions PASS, across 4 time zones). The instrumented JVM
  suite (`FlightDateTest`, 20 assertions) must pass in CI on a real toolchain.
