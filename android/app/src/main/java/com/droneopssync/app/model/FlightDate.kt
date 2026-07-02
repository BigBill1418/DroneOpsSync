package com.droneopssync.app.model

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Client-side flight-date derivation (ADR-0010).
 *
 * The date shown next to a flight log used to be `Date(file.lastModified())`.
 * For SAF entries `file` is a per-scan cache copy in `cacheDir`, and DJI
 * controller `DocumentFile`s frequently report `lastModified() == 0`, so the
 * cache copy inherited `0` and rendered as **"31 Dec 1969"** in the operator's
 * timezone (`Date(0)` = the Unix epoch). Worse, file mtime is the *write* time,
 * not the flight time, so it was the wrong value even when non-zero.
 *
 * The authoritative flight datetime lives in the DJI log FILENAME:
 *
 *   `DJIFlightRecord_2026-06-30_[12-34-56].txt`   (DJI GO/Fly, all generations)
 *   `2026-06-30_12-34-56_flightlog.csv`           (Litchi export, leading stamp)
 *   `Flight-2026-06-30.csv`                        (Airdata export, date-in-name)
 *
 * Extracted as pure top-level functions (no Android runtime — only `java.text`)
 * so the parse + plausibility decision is unit-testable, same seam pattern as
 * `storage/TransferIntegrity.kt` and `upload/UploadOutcome.kt`. Lives in
 * `model` (its only consumer is [FlightLog]) to avoid a model↔storage cycle.
 */

/** 2010-01-01T00:00:00Z — floor below any real DJI flight, far above the
 *  epoch-0 / 1969 garbage a zeroed mtime produces. First consumer DJI drone
 *  (Phantom) shipped 2013; FlightRecords never predate that. */
const val PLAUSIBLE_FLOOR_MS: Long = 1_262_304_000_000L

/** Grace added to "now" for the future check — absorbs controller clock skew
 *  and the timezone slop of parsing a date-only name at local noon. */
const val FUTURE_GRACE_MS: Long = 48L * 60L * 60L * 1000L

/**
 * `YYYY-MM-DD` anywhere in the name, optionally followed by a separator
 * (`_`, space, `T`, or DJI's `[`) and an `HH-MM-SS` time. The date may appear
 * anywhere — after `DJIFlightRecord_`, as a leading Litchi stamp, or mid-name
 * in an Airdata export. Range validity (no month 13 / day 32) is enforced by
 * the non-lenient parse below, not by the regex.
 */
private val DATE_TIME_REGEX =
    Regex("""(\d{4})-(\d{2})-(\d{2})(?:[ _T\[]+(\d{2})-(\d{2})-(\d{2}))?""")

/**
 * Derive the flight instant (epoch millis, interpreted in the device-local
 * timezone) from a DJI / Litchi / Airdata flight-log FILENAME, or `null` if the
 * name carries no parseable date.
 *
 * Date-only matches (no time component) are anchored at local **noon** so the
 * rendered calendar date is stable across time zones and the DST midnight edge.
 * The instant is built with the default timezone because the DJI filename stamp
 * is the controller's local wall-clock — which is exactly what the operator
 * expects to read back.
 */
fun deriveFlightDateMillisFromName(name: String): Long? {
    val m = DATE_TIME_REGEX.find(name) ?: return null
    val g = m.groupValues
    val date = "${g[1]}-${g[2]}-${g[3]}"
    val hasTime = g[4].isNotEmpty()
    val time = if (hasTime) "${g[4]}-${g[5]}-${g[6]}" else "12-00-00"
    val fmt = SimpleDateFormat("yyyy-MM-dd HH-mm-ss", Locale.US).apply { isLenient = false }
    return try {
        fmt.parse("$date $time")?.time
    } catch (_: Exception) {
        null
    }
}

/**
 * A timestamp is plausible for display iff it is at/after [PLAUSIBLE_FLOOR_MS]
 * and not beyond `now` (plus a small [FUTURE_GRACE_MS] tolerance). A zeroed /
 * negative / epoch-garbage mtime fails this and must never render as a date.
 */
fun isPlausibleTimestamp(millis: Long, nowMillis: Long): Boolean =
    millis in PLAUSIBLE_FLOOR_MS..(nowMillis + FUTURE_GRACE_MS)

/**
 * Resolve the millis to DISPLAY as a flight's date, or `null` → the caller
 * shows "Unknown date" / "—".
 *
 * Order:
 *  1. The filename-derived date (authoritative), if it is itself plausible.
 *  2. Else the file mtime, but only if it is a plausible timestamp — this is
 *     the guard that stops a `0`/negative/epoch mtime from rendering as 1969.
 *  3. Else `null`.
 */
fun resolveFlightDisplayMillis(name: String, mtimeMillis: Long, nowMillis: Long): Long? {
    deriveFlightDateMillisFromName(name)?.let { if (isPlausibleTimestamp(it, nowMillis)) return it }
    if (isPlausibleTimestamp(mtimeMillis, nowMillis)) return mtimeMillis
    return null
}
