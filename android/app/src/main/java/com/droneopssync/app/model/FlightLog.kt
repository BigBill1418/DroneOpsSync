package com.droneopssync.app.model

import android.net.Uri
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * @param file       the bytes the upload pipeline consumes — for SAF
 *                   entries this is a per-scan staging copy in `cacheDir`,
 *                   for Legacy entries this is the actual file on /sdcard.
 * @param sourceUri  ADR-0005: when set, the original SAF document URI on
 *                   the controller. The upload still keys off `file`;
 *                   `sourceUri` exists so post-upload "delete from
 *                   controller" can call `DocumentsContract.deleteDocument`
 *                   on the right artifact instead of silently deleting only
 *                   our cache copy.
 * @param verifiedTransfer  Transfer-integrity guard: whether the bytes backing `file` are
 *                   trusted to be complete. Legacy entries are the original
 *                   on-disk file (no copy occurred) → `true`. SAF entries are
 *                   a staging copy: `true` only when the copied byte count
 *                   exactly matched the source document length. An unverified
 *                   SAF copy is still uploadable but is NEVER eligible for the
 *                   destructive delete-from-controller flow — see
 *                   `storage/TransferIntegrity.kt::isDeleteEligible`.
 */
data class FlightLog(
    val file: File,
    val uploadStatus: UploadStatus = UploadStatus.PENDING,
    val sourceUri: Uri? = null,
    val verifiedTransfer: Boolean = true,
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val sourcePath: String get() = file.parent ?: ""

    val sizeFormatted: String get() {
        val kb = sizeBytes / 1024.0
        return if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
    }

    /**
     * Flight date for display (ADR-0010). Derived from the DJI/Litchi/Airdata
     * FILENAME when possible (the authoritative flight datetime); falls back to
     * file mtime only when that mtime is a plausible timestamp. A zeroed SAF
     * cache-copy mtime (`Date(0)` → "31 Dec 1969") never renders — it shows
     * "Unknown date" instead. See [resolveFlightDisplayMillis].
     */
    val dateFormatted: String get() {
        val millis = resolveFlightDisplayMillis(name, file.lastModified(), System.currentTimeMillis())
            ?: return "Unknown date"
        val sdf = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
        return sdf.format(Date(millis))
    }
}

enum class UploadStatus {
    PENDING,
    UPLOADING,
    SYNCED,       // Server confirmed new import
    DUPLICATE,    // Already on server (deduped by server-side hash)
    DELETED,
    ERROR
}

enum class DiagLevel { INFO, WARN, ERROR }

data class DiagLog(
    val timestamp: Long = System.currentTimeMillis(),
    val level: DiagLevel,
    val tag: String,
    val message: String
)
