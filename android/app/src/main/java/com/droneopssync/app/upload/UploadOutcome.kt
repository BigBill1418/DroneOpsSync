package com.droneopssync.app.upload

import com.droneopssync.app.model.FlightUploadResponse
import com.droneopssync.app.model.UploadStatus
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The decision for a single file in an upload batch — extracted as a PURE value
 * (ADR-0008 §Stage C / ADR-0023 §2.5a) so the per-file outcome logic in
 * [com.droneopssync.app.viewmodel.MainViewModel.performUpload] is unit-testable
 * with no Android runtime.
 *
 * @param newStatus     the [UploadStatus] this file lands on.
 * @param abortBatch    when true, the loop stops attempting REMAINING files
 *                      (genuinely batch-wide conditions only: dead host, bad
 *                      key). A per-file socket timeout MUST NOT set this — that
 *                      was the B2 bug (one slow file nuked files N+1…M).
 * @param importedDelta amount to add to the batch's imported counter.
 * @param skippedDelta  amount to add to the batch's skipped counter.
 */
data class FileOutcome(
    val newStatus: UploadStatus,
    val abortBatch: Boolean,
    val importedDelta: Int = 0,
    val skippedDelta: Int = 0,
)

/**
 * Classify a single legacy-path (synchronous `uploadFlights`) upload attempt.
 *
 * Exactly mirrors the `when {…}` + exception classification that lived inline
 * in `performUpload`, with the ONE Stage-C fix: a [SocketTimeoutException] is a
 * per-file condition (this file's parse was slow), not a batch-wide one, so it
 * does NOT abort the batch. [UnknownHostException] (dead host) and HTTP 401/403
 * (bad device key) remain batch-wide aborts.
 *
 * Inputs are primitives rather than a Retrofit `Response` so the classifier is
 * pure/JVM-testable:
 * @param isSuccessful  Retrofit `response.isSuccessful` (ignored when [throwable] != null).
 * @param httpCode      Retrofit `response.code()`, or null on the exception path.
 * @param body          parsed [FlightUploadResponse], or null (null body, or exception path).
 * @param throwable     the caught exception, or null on the normal HTTP path.
 */
fun classifyUploadOutcome(
    isSuccessful: Boolean,
    httpCode: Int?,
    body: FlightUploadResponse?,
    throwable: Throwable?,
): FileOutcome {
    // ── Exception path ───────────────────────────────────────────────────────
    if (throwable != null) {
        return when (throwable) {
            // Host unreachable → the whole batch is doomed. Abort. (Correct.)
            is UnknownHostException ->
                FileOutcome(UploadStatus.ERROR, abortBatch = true)
            // Stage C fix: a socket timeout is THIS file's slow parse, not a
            // batch-wide failure. Fail the file alone; keep attempting the rest.
            is SocketTimeoutException ->
                FileOutcome(UploadStatus.ERROR, abortBatch = false)
            // Any other exception: fail this file, continue the batch.
            else ->
                FileOutcome(UploadStatus.ERROR, abortBatch = false)
        }
    }

    // ── HTTP path ────────────────────────────────────────────────────────────
    return when {
        !isSuccessful -> FileOutcome(
            UploadStatus.ERROR,
            // Bad device key → every file would 403. Abort the batch. (Correct.)
            abortBatch = httpCode == 401 || httpCode == 403,
        )
        body == null -> FileOutcome(UploadStatus.ERROR, abortBatch = false)
        body.errors.isNotEmpty() -> FileOutcome(UploadStatus.ERROR, abortBatch = false)
        body.skipped > 0 -> FileOutcome(
            UploadStatus.DUPLICATE,
            abortBatch = false,
            skippedDelta = 1,
        )
        else -> FileOutcome(
            UploadStatus.SYNCED,
            abortBatch = false,
            importedDelta = body.imported,
        )
    }
}

/**
 * Terminal-state reducer for the ADR-0023 async poll envelope: maps a
 * `per_file[0].state` string to a client [UploadStatus], or null while the file
 * is still non-terminal (the poll loop keeps going).
 *
 * Per-file states (ADR-0023 §2.1):
 *   `imported` → SYNCED      (terminal)
 *   `skipped`  → DUPLICATE   (terminal — already on server by SHA-256 dedup)
 *   `error`    → ERROR       (terminal — a parse failure on THIS file; the batch
 *                             can still complete with other files succeeding)
 *   `pending` / `parsing` / null / unknown → null (non-terminal; keep polling)
 */
fun reducePerFileState(state: String?): UploadStatus? = when (state) {
    "imported" -> UploadStatus.SYNCED
    "skipped" -> UploadStatus.DUPLICATE
    "error" -> UploadStatus.ERROR
    else -> null
}
