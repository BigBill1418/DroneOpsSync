package com.droneopssync.app.storage

import com.droneopssync.app.model.UploadStatus

/**
 * Client-side transfer-integrity gate (transfer-integrity ADR, number TBD).
 *
 * A SAF copy off the DJI controller goes through `InputStream.copyTo`, which
 * returns **normally** on a short/interrupted content-provider read — leaving a
 * TRUNCATED file in cache. A truncated flight log can still parse server-side,
 * come back `imported` → SYNCED, and then become eligible for the
 * delete-from-controller flow — deleting the only copy of irreplaceable field
 * data. Comparing the bytes that landed against the source document's reported
 * length is the only client-side signal that catches this.
 *
 * Extracted as pure top-level functions (no Android runtime) so the decision
 * logic is unit-testable — same seam pattern as `upload/UploadOutcome.kt`.
 */

/**
 * True iff the copied byte count can be TRUSTED to equal the source: the source
 * length is known (`> 0`) AND the copy matches it exactly.
 *
 * When the source length is unknown (`<= 0` — some content providers report
 * `0` or `-1`) this returns false: the copy is still usable for upload but must
 * never be treated as verified for the destructive delete path.
 */
fun isTransferVerified(sourceLen: Long, copiedLen: Long): Boolean =
    sourceLen > 0L && copiedLen == sourceLen

/**
 * True iff a copy must be DISCARDED as truncated: the source length is known
 * (`> 0`) and the copy does not match it. An unknown source length is NOT a
 * truncation signal — it is merely unverifiable, and discarding on it would
 * break upload for providers that hide the length.
 */
fun isTruncatedCopy(sourceLen: Long, copiedLen: Long): Boolean =
    sourceLen > 0L && copiedLen != sourceLen

/**
 * Delete-from-controller eligibility (transfer-integrity guard). A file may have its ORIGINAL
 * on the controller auto-deleted only when the server confirmed the import
 * (SYNCED / DUPLICATE) **and** its transfer was verified. An unverified
 * transfer (unknown source length, or a copy we never size-checked) is
 * uploadable but must NEVER be auto-deleted — the fail-safe against replacing a
 * good log with a partial one and then erasing the good one.
 */
fun isDeleteEligible(status: UploadStatus, verifiedTransfer: Boolean): Boolean =
    verifiedTransfer && (status == UploadStatus.SYNCED || status == UploadStatus.DUPLICATE)
