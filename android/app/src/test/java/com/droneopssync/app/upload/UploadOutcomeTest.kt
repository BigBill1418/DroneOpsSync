package com.droneopssync.app.upload

import com.droneopssync.app.model.FlightUploadResponse
import com.droneopssync.app.model.UploadStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * Pure-JVM unit tests for [classifyUploadOutcome] — the per-file upload outcome
 * decision extracted from `MainViewModel.performUpload` (ADR-0008 §Stage C /
 * ADR-0023 §2.5a). No Android framework, no Robolectric, no ViewModel.
 *
 * The load-bearing assertion is the B2 fix: a [SocketTimeoutException] is a
 * per-file condition and MUST NOT abort the batch (it used to set
 * `aborted = true`, marking every remaining file ERROR without attempting it).
 * [UnknownHostException] and HTTP 401/403 remain genuinely batch-wide aborts.
 */
class UploadOutcomeTest {

    @Test
    fun `socket timeout fails this file only and does NOT abort the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = false,
            httpCode = null,
            body = null,
            throwable = SocketTimeoutException("read timed out"),
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertFalse("socket timeout must NOT abort the batch (B2 fix)", outcome.abortBatch)
    }

    @Test
    fun `unknown host aborts the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = false,
            httpCode = null,
            body = null,
            throwable = UnknownHostException("no route to host"),
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertTrue("dead host is batch-wide — must abort", outcome.abortBatch)
    }

    @Test
    fun `HTTP 403 aborts the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = false,
            httpCode = 403,
            body = null,
            throwable = null,
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertTrue("bad device key (403) is batch-wide — must abort", outcome.abortBatch)
    }

    @Test
    fun `HTTP 401 aborts the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = false,
            httpCode = 401,
            body = null,
            throwable = null,
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertTrue("bad device key (401) is batch-wide — must abort", outcome.abortBatch)
    }

    @Test
    fun `other non-2xx fails this file but does NOT abort the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = false,
            httpCode = 500,
            body = null,
            throwable = null,
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertFalse("a 500 on one file must not abort the rest", outcome.abortBatch)
    }

    @Test
    fun `body with non-empty errors fails this file and does NOT abort the batch`() {
        val body = FlightUploadResponse(
            imported = 0,
            skipped = 0,
            errors = listOf("DJIFlightRecord_x.txt: parser returned 422"),
        )
        val outcome = classifyUploadOutcome(
            isSuccessful = true,
            httpCode = 200,
            body = body,
            throwable = null,
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertFalse("a per-file parse error must not abort the batch", outcome.abortBatch)
    }

    @Test
    fun `null body fails this file and does NOT abort the batch`() {
        val outcome = classifyUploadOutcome(
            isSuccessful = true,
            httpCode = 200,
            body = null,
            throwable = null,
        )
        assertEquals(UploadStatus.ERROR, outcome.newStatus)
        assertFalse(outcome.abortBatch)
    }

    @Test
    fun `skipped greater than zero maps to DUPLICATE`() {
        val body = FlightUploadResponse(imported = 0, skipped = 1, errors = emptyList())
        val outcome = classifyUploadOutcome(
            isSuccessful = true,
            httpCode = 200,
            body = body,
            throwable = null,
        )
        assertEquals(UploadStatus.DUPLICATE, outcome.newStatus)
        assertFalse(outcome.abortBatch)
        assertEquals(1, outcome.skippedDelta)
    }

    @Test
    fun `clean import maps to SYNCED and carries imported delta`() {
        val body = FlightUploadResponse(imported = 3, skipped = 0, errors = emptyList())
        val outcome = classifyUploadOutcome(
            isSuccessful = true,
            httpCode = 200,
            body = body,
            throwable = null,
        )
        assertEquals(UploadStatus.SYNCED, outcome.newStatus)
        assertFalse(outcome.abortBatch)
        assertEquals(3, outcome.importedDelta)
    }
}
