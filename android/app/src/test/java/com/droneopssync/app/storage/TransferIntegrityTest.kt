package com.droneopssync.app.storage

import com.droneopssync.app.model.UploadStatus
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the client-side transfer-integrity gate (transfer-integrity ADR).
 * No Android framework, no Robolectric — same pattern as [UploadOutcomeTest].
 *
 * The data-loss bug these guard against: a SAF copy off the DJI controller goes
 * through `InputStream.copyTo`, which returns *normally* on a short/interrupted
 * content-provider read, leaving a TRUNCATED file in cache. That truncated log
 * still parses server-side, comes back `imported` → SYNCED, and then becomes
 * eligible for delete-from-controller — deleting the only copy of irreplaceable
 * field data. The bytes-landed vs source-length comparison is the only
 * client-side signal that catches it.
 */
class TransferIntegrityTest {

    // ── isTransferVerified: trust the copy only on an exact known-length match ──

    @Test
    fun `exact match on a known source length is verified`() {
        assertTrue(isTransferVerified(sourceLen = 4096, copiedLen = 4096))
    }

    @Test
    fun `short read (truncated copy) is NOT verified`() {
        // The canonical bug: source is 4096 B, only 1500 landed, copyTo returned
        // normally. Must NOT be verified.
        assertFalse(isTransferVerified(sourceLen = 4096, copiedLen = 1500))
    }

    @Test
    fun `over-read (impossible, but defensive) is NOT verified`() {
        assertFalse(isTransferVerified(sourceLen = 4096, copiedLen = 5000))
    }

    @Test
    fun `unknown source length is NOT verified even when copy is non-empty`() {
        // Some providers report 0 / -1 for length. We cannot verify, so the copy
        // is usable for upload but must never be treated as verified (→ never
        // auto-deletable). Both zero-length and negative-length are unverifiable.
        assertFalse(isTransferVerified(sourceLen = 0, copiedLen = 1500))
        assertFalse(isTransferVerified(sourceLen = -1, copiedLen = 1500))
    }

    // ── isTruncatedCopy: only a KNOWN, mismatched length is a truncation signal ─

    @Test
    fun `known length mismatch is flagged as truncated`() {
        assertTrue(isTruncatedCopy(sourceLen = 4096, copiedLen = 1500))
    }

    @Test
    fun `known length exact match is NOT truncated`() {
        assertFalse(isTruncatedCopy(sourceLen = 4096, copiedLen = 4096))
    }

    @Test
    fun `unknown source length is NOT a truncation signal (merely unverifiable)`() {
        // We must not discard a copy just because the provider hid the length —
        // that would break upload on such providers. It's unverified, not bad.
        assertFalse(isTruncatedCopy(sourceLen = 0, copiedLen = 1500))
        assertFalse(isTruncatedCopy(sourceLen = -1, copiedLen = 1500))
    }

    // ── isDeleteEligible: the fail-safe on the destructive path ─────────────────

    @Test
    fun `SYNCED and verified is delete-eligible`() {
        assertTrue(isDeleteEligible(UploadStatus.SYNCED, verifiedTransfer = true))
    }

    @Test
    fun `DUPLICATE and verified is delete-eligible`() {
        assertTrue(isDeleteEligible(UploadStatus.DUPLICATE, verifiedTransfer = true))
    }

    @Test
    fun `SYNCED but UNVERIFIED is NOT delete-eligible (the data-loss guard)`() {
        // This is the whole point: a file that reached SYNCED off an unverified
        // (possibly truncated) transfer must NEVER have its controller original
        // auto-deleted.
        assertFalse(isDeleteEligible(UploadStatus.SYNCED, verifiedTransfer = false))
    }

    @Test
    fun `DUPLICATE but UNVERIFIED is NOT delete-eligible`() {
        assertFalse(isDeleteEligible(UploadStatus.DUPLICATE, verifiedTransfer = false))
    }

    @Test
    fun `non-terminal or failed statuses are never delete-eligible`() {
        assertFalse(isDeleteEligible(UploadStatus.PENDING, verifiedTransfer = true))
        assertFalse(isDeleteEligible(UploadStatus.UPLOADING, verifiedTransfer = true))
        assertFalse(isDeleteEligible(UploadStatus.ERROR, verifiedTransfer = true))
        assertFalse(isDeleteEligible(UploadStatus.DELETED, verifiedTransfer = true))
    }
}
