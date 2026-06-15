package com.droneopssync.app.upload

import com.droneopssync.app.model.AsyncUploadResponse
import com.droneopssync.app.model.UploadStatus
import com.droneopssync.app.model.UploadStatusResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM Gson-contract tests for the ADR-0023 async upload envelopes
 * ([AsyncUploadResponse] 202 body, [UploadStatusResponse] poll body) and the
 * terminal-state reducer [reducePerFileState]. Mirrors KeyRotationParseTest's
 * style — no Android framework, no Robolectric.
 *
 * The field names asserted here MUST match the FastAPI backend envelopes
 * byte-for-byte (the backend is built against the same ADR-0023); a drift in
 * either repo surfaces as a failing assertion here.
 */
class PollEnvelopeTest {

    private val gson = Gson()

    @Test
    fun `parses full status poll envelope`() {
        val json = """
            {
              "batch_id": "5f2c1e9a-0000-4444-aaaa-bbbbccccdddd",
              "status": "running",
              "phase": "parsing",
              "progress": 50,
              "per_file": [
                {
                  "name": "DJIFlightRecord_2026-06-15_[12-30-00].txt",
                  "state": "parsing",
                  "imported": 0,
                  "skipped": 0,
                  "error": null
                }
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, UploadStatusResponse::class.java)

        assertEquals("5f2c1e9a-0000-4444-aaaa-bbbbccccdddd", parsed.batchId)
        assertEquals("running", parsed.status)
        assertEquals("parsing", parsed.phase)
        assertEquals(50, parsed.progress)
        assertEquals(1, parsed.perFile.size)
        assertEquals("DJIFlightRecord_2026-06-15_[12-30-00].txt", parsed.perFile[0].name)
        assertEquals("parsing", parsed.perFile[0].state)
        assertEquals(0, parsed.perFile[0].imported)
        assertEquals(0, parsed.perFile[0].skipped)
        assertNull(parsed.perFile[0].error)
    }

    @Test
    fun `parses complete envelope with imported per-file`() {
        val json = """
            {
              "batch_id": "abc",
              "status": "complete",
              "phase": "done",
              "progress": 100,
              "per_file": [
                {"name": "a.txt", "state": "imported", "imported": 2, "skipped": 0, "error": null}
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, UploadStatusResponse::class.java)

        assertEquals("complete", parsed.status)
        assertEquals("imported", parsed.perFile[0].state)
        assertEquals(2, parsed.perFile[0].imported)
    }

    @Test
    fun `parses complete-with-error per-file (batch completes, file failed)`() {
        // A batch can be status=complete while a per-file state=error — the
        // per-file state is authoritative, not the rollup (ADR-0023 §2.4).
        val json = """
            {
              "batch_id": "abc",
              "status": "complete",
              "phase": "done",
              "progress": 100,
              "per_file": [
                {"name": "a.txt", "state": "error", "imported": 0, "skipped": 0,
                 "error": "a.txt: flight-parser service unavailable"}
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, UploadStatusResponse::class.java)

        assertEquals("complete", parsed.status)
        assertEquals("error", parsed.perFile[0].state)
        assertEquals("a.txt: flight-parser service unavailable", parsed.perFile[0].error)
    }

    @Test
    fun `tolerates absent optional fields`() {
        // A partial/early overlay must not crash the parse.
        val json = """{"batch_id": "abc", "status": "queued"}"""

        val parsed = gson.fromJson(json, UploadStatusResponse::class.java)

        assertEquals("abc", parsed.batchId)
        assertEquals("queued", parsed.status)
        assertNull(parsed.phase)
        assertNull(parsed.progress)
        assertTrue("per_file defaults to empty when absent", parsed.perFile.isEmpty())
    }

    @Test
    fun `tolerates extra unknown server-added fields`() {
        val json = """
            {
              "batch_id": "abc",
              "status": "running",
              "future_unknown_field": {"nested": "value"},
              "another_unknown": 42,
              "per_file": [
                {"name": "a.txt", "state": "parsing", "unexpected_extra": true}
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, UploadStatusResponse::class.java)

        assertEquals("running", parsed.status)
        assertEquals("parsing", parsed.perFile[0].state)
    }

    @Test
    fun `parses 202 async submit body`() {
        val json = """
            {
              "batch_id": "5f2c1e9a-0000-4444-aaaa-bbbbccccdddd",
              "files": [
                {"name": "a.txt", "state": "pending"}
              ]
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, AsyncUploadResponse::class.java)

        assertEquals("5f2c1e9a-0000-4444-aaaa-bbbbccccdddd", parsed.batchId)
        assertEquals(1, parsed.files.size)
        assertEquals("a.txt", parsed.files[0].name)
        assertEquals("pending", parsed.files[0].state)
    }

    @Test
    fun `202 body can short-circuit to skipped (pre-parse dedup)`() {
        // ADR-0023 §2.1 step 2: an already-present file is marked skipped in the
        // 202 body, no job/poll needed.
        val json = """{"batch_id": "abc", "files": [{"name": "a.txt", "state": "skipped"}]}"""

        val parsed = gson.fromJson(json, AsyncUploadResponse::class.java)

        assertEquals(UploadStatus.DUPLICATE, reducePerFileState(parsed.files[0].state))
    }

    // ── Terminal-state reducer ─────────────────────────────────────────────────

    @Test
    fun `reducer maps imported to SYNCED`() {
        assertEquals(UploadStatus.SYNCED, reducePerFileState("imported"))
    }

    @Test
    fun `reducer maps skipped to DUPLICATE`() {
        assertEquals(UploadStatus.DUPLICATE, reducePerFileState("skipped"))
    }

    @Test
    fun `reducer maps error to ERROR`() {
        assertEquals(UploadStatus.ERROR, reducePerFileState("error"))
    }

    @Test
    fun `reducer treats non-terminal states as null (keep polling)`() {
        assertNull(reducePerFileState("pending"))
        assertNull(reducePerFileState("parsing"))
        assertNull(reducePerFileState(null))
        assertNull(reducePerFileState("some_future_state"))
    }
}
