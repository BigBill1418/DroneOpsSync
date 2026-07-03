package com.droneopssync.app.model

import java.text.SimpleDateFormat
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the flight-date derivation (ADR-0010). No Android
 * framework, no Robolectric — same pattern as [TransferIntegrityTest] /
 * [UploadOutcomeTest].
 *
 * The bug these guard against: SAF cache copies inherit a `lastModified() == 0`
 * from DJI `DocumentFile`s, so `Date(0)` rendered as "31 Dec 1969". The fix
 * derives the authoritative date from the filename and refuses to render an
 * implausible mtime.
 *
 * Filename-parse assertions are timezone-robust: derive-then-format both use
 * the default timezone (parse-local then format-local cancels out), so the
 * asserted calendar date holds regardless of the CI machine's TZ.
 */
class FlightDateTest {

    /** Format a derived instant back to `yyyy-MM-dd HH:mm` in the SAME default
     *  TZ used to parse it — round-trip is TZ-independent for the calendar date. */
    private fun fmt(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(java.util.Date(millis))

    // ── Filename parsing: the real DJI / Litchi / Airdata shapes ────────────────

    @Test
    fun `DJI FlightRecord with bracketed time parses date and time`() {
        val millis = deriveFlightDateMillisFromName("DJIFlightRecord_2026-06-30_[12-34-56].txt")!!
        assertEquals("2026-06-30 12:34", fmt(millis))
    }

    @Test
    fun `DJI FlightRecord underscore time (no brackets) parses`() {
        val millis = deriveFlightDateMillisFromName("DJIFlightRecord_2024-01-05_09-08-07.txt")!!
        assertEquals("2024-01-05 09:08", fmt(millis))
    }

    @Test
    fun `Litchi leading-timestamp csv parses`() {
        val millis = deriveFlightDateMillisFromName("2021-05-03_14-30-00_flightlog.csv")!!
        assertEquals("2021-05-03 14:30", fmt(millis))
    }

    @Test
    fun `Airdata date-only name parses (anchored at local noon)`() {
        val millis = deriveFlightDateMillisFromName("Flight-2023-08-01.csv")!!
        // Date-only → anchored at 12:00 local so the calendar date is stable.
        assertEquals("2023-08-01 12:00", fmt(millis))
    }

    @Test
    fun `name with no date returns null`() {
        assertNull(deriveFlightDateMillisFromName("randomlog.txt"))
        assertNull(deriveFlightDateMillisFromName("FLY001.log"))
    }

    @Test
    fun `impossible calendar date is rejected by non-lenient parse`() {
        // Month 13 / day 32 match the regex shape but must not produce a date.
        assertNull(deriveFlightDateMillisFromName("DJIFlightRecord_2026-13-30_[00-00-00].txt"))
        assertNull(deriveFlightDateMillisFromName("weird-2026-02-32.csv"))
    }

    // ── isPlausibleTimestamp: the epoch-0 / 1969 guard ──────────────────────────

    private val now = 1_785_542_400_000L // 2026-08-01 UTC, a fixed "now" after all sample dates

    @Test
    fun `epoch-0 mtime is NOT plausible (the 1969 bug)`() {
        assertFalse(isPlausibleTimestamp(0L, now))
    }

    @Test
    fun `negative mtime is NOT plausible`() {
        assertFalse(isPlausibleTimestamp(-1L, now))
    }

    @Test
    fun `a real recent mtime is plausible`() {
        assertTrue(isPlausibleTimestamp(now - 5_000L, now))
    }

    @Test
    fun `pre-2010 timestamp is NOT plausible (below the floor)`() {
        assertFalse(isPlausibleTimestamp(PLAUSIBLE_FLOOR_MS - 1L, now))
        assertTrue(isPlausibleTimestamp(PLAUSIBLE_FLOOR_MS, now))
    }

    @Test
    fun `far-future timestamp is NOT plausible`() {
        assertFalse(isPlausibleTimestamp(now + FUTURE_GRACE_MS + 1L, now))
        // within the grace window is allowed (clock skew / TZ slop)
        assertTrue(isPlausibleTimestamp(now + FUTURE_GRACE_MS - 1L, now))
    }

    // ── resolveFlightDisplayMillis: filename-first, mtime-guarded ───────────────

    @Test
    fun `filename date wins even when mtime is a valid but different time`() {
        val validMtime = now - 5_000L
        val millis = resolveFlightDisplayMillis(
            "DJIFlightRecord_2026-06-30_[12-34-56].txt", validMtime, now,
        )!!
        assertEquals("2026-06-30 12:34", fmt(millis))
    }

    @Test
    fun `SAF cache copy with zero mtime and DJI name uses the filename (not 1969)`() {
        // The canonical bug scenario: mtime == 0, name carries the flight date.
        val millis = resolveFlightDisplayMillis(
            "DJIFlightRecord_2026-06-30_[12-34-56].txt", 0L, now,
        )!!
        assertEquals("2026-06-30 12:34", fmt(millis))
    }

    @Test
    fun `no filename date but valid mtime falls back to mtime (legacy passthrough)`() {
        // Non-breaking: a legacy file with no date-in-name still shows its mtime,
        // exactly as before the fix.
        val validMtime = now - 60_000L
        val millis = resolveFlightDisplayMillis("randomlog.txt", validMtime, now)!!
        assertEquals(fmt(validMtime), fmt(millis))
    }

    @Test
    fun `no filename date and zero mtime yields null (caller shows Unknown date)`() {
        assertNull(resolveFlightDisplayMillis("randomlog.txt", 0L, now))
    }

    @Test
    fun `implausible filename date falls through to a valid mtime`() {
        // A filename year below the floor is not trusted; a good mtime rescues it.
        val validMtime = now - 60_000L
        val millis = resolveFlightDisplayMillis("Flight-2005-01-01.csv", validMtime, now)!!
        assertEquals(fmt(validMtime), fmt(millis))
    }

    // ── flightSortKey: the list-order half (ADR-0010, sort) ─────────────────────

    @Test
    fun `sort key of a zero-mtime SAF copy is its filename date, not zero`() {
        // The anomaly: display was fixed, but the key was still 0 → sank to bottom.
        val key = flightSortKey("DJIFlightRecord_2026-06-30_[12-34-56].txt", 0L, now)
        assertEquals("2026-06-30 12:34", fmt(key))
    }

    @Test
    fun `sort key falls back to raw mtime when no date is resolvable`() {
        // Unknown-date row (no filename date, zero mtime): key is the raw mtime so
        // ordering stays total and deterministic instead of a null-key crash.
        assertEquals(0L, flightSortKey("randomlog.txt", 0L, now))
        assertEquals(-5L, flightSortKey("randomlog.txt", -5L, now))
    }

    @Test
    fun `list sorts by derived flight date, NOT by mtime, even with a zero-mtime SAF copy`() {
        // The canonical regression this fix closes. Three logs:
        //   - newest flight (2026-06-30) is a SAF copy with mtime == 0
        //   - middle flight (2026-06-15) has a plausible mtime
        //   - oldest flight (2026-05-01) also plausible mtime
        // Sorting by mtime would sink the zero-mtime newest flight to the bottom.
        // Sorting by flightSortKey must yield newest-flight-first.
        data class Row(val name: String, val mtime: Long)
        val newestSaf = Row("DJIFlightRecord_2026-06-30_[08-00-00].txt", 0L)            // mtime 0
        val middle    = Row("DJIFlightRecord_2026-06-15_[08-00-00].txt", now - 100_000L)
        val oldest    = Row("DJIFlightRecord_2026-05-01_[08-00-00].txt", now - 200_000L)

        // Feed them in a deliberately wrong order to prove the sort does the work.
        val sorted = listOf(middle, oldest, newestSaf)
            .sortedByDescending { flightSortKey(it.name, it.mtime, now) }

        assertEquals(listOf(newestSaf, middle, oldest), sorted)

        // Sanity: a naive mtime sort would NOT produce this order (newestSaf last).
        val byMtime = listOf(middle, oldest, newestSaf).sortedByDescending { it.mtime }
        assertEquals(newestSaf, byMtime.last())
    }

    @Test
    fun `unknown-date rows sink below dated flights`() {
        data class Row(val name: String, val mtime: Long)
        val dated   = Row("DJIFlightRecord_2026-06-30_[08-00-00].txt", 0L)
        val unknown = Row("randomlog.txt", 0L) // no filename date, zero mtime → key 0

        val sorted = listOf(unknown, dated)
            .sortedByDescending { flightSortKey(it.name, it.mtime, now) }
        assertEquals(listOf(dated, unknown), sorted)
    }
}
