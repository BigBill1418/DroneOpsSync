package com.droneopssync.app.storage

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.documentfile.provider.DocumentFile
import com.droneopssync.app.model.FlightLog
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Read flight-log files off the controller via one of two backends.
 *
 * The same `List<FlightLog>` shape comes back regardless — `MainViewModel`
 * sorts, displays, and uploads them identically. Choice of backend is made
 * per-scan in `MainViewModel.performScan()` based on Android version and
 * whether a SAF tree URI has been granted.
 *
 * See ADR-0005 (`docs/adr/0005-saf-tree-picker-for-dji-flight-logs.md`).
 */
sealed interface FlightLogSource {
    /**
     * Scan the source for flight-log files.
     *
     * @return Pair of (found logs, missing-path strings for diagnostics).
     *         "Missing" semantics differ by backend — see each implementation.
     */
    fun scan(): ScanResult

    /** Human-readable label for diag logging — `LEGACY` or `SAF`. */
    val label: String
}

data class ScanResult(
    val logs: List<FlightLog>,
    val missingPaths: List<String>,
    val perPathDiag: List<String>,
)

private val ALLOWED_EXTENSIONS = setOf("txt", "log", "csv", "json")
private const val SCAN_TAG = "FlightLogSource"

/**
 * Direct `java.io.File` scan. Works on:
 *
 *  - Android <= 10 (no scoped-storage lockdown).
 *  - Android 11+ where `MANAGE_EXTERNAL_STORAGE` happens to bypass the
 *    `Android/data/<other-pkg>` lockdown — true on Samsung's customized
 *    impl (Bill's S25 Ultra), false on stock-AOSP (DJI RC Pro 2).
 *
 * On stock-AOSP Android 11+, `dir.listFiles()` quietly returns null for
 * sibling-package paths under `/Android/data/`, which the caller surfaces
 * as zero hits. That's the case `SafTreeSource` exists to fix.
 */
class LegacyFileSource(private val paths: List<String>) : FlightLogSource {
    override val label: String = "LEGACY"

    override fun scan(): ScanResult {
        val found = mutableListOf<FlightLog>()
        val missing = mutableListOf<String>()
        val diag = mutableListOf<String>()

        for (pathStr in paths) {
            val dir = File(pathStr)
            diag += "$pathStr — exists=${dir.exists()} isDir=${dir.isDirectory}"
            if (dir.exists() && dir.isDirectory) {
                val hits = dir.listFiles { f ->
                    f.isFile && f.extension.lowercase() in ALLOWED_EXTENSIONS
                }?.toList() ?: emptyList()
                hits.forEach { found.add(FlightLog(file = it)) }
                diag += "  ${hits.size} file(s) found"
                hits.forEach { diag += "  ${it.name}  (${it.length()} B)" }
            } else {
                missing += pathStr
                diag += "  Path not found"
            }
        }

        return ScanResult(found, missing, diag)
    }
}

/**
 * SAF tree scan. Walks `DocumentFile.fromTreeUri(treeUri).listFiles()`,
 * copies each matching child into `cacheDir/saf-staging/<safe-name>`, and
 * wraps the cache file in a [FlightLog] so the existing `File`-based
 * upload pipeline works without modification.
 *
 * Cache files are deleted after a successful upload — see
 * `MainViewModel.cleanupSafCacheAfterUpload`. The whole staging dir is
 * also wiped at the start of each scan to prevent indefinite growth.
 *
 * On Android 11/12 this is the only viable path for reading
 * `Android/data/<other-pkg>` from a sideloaded app. Closed by AOSP in
 * Android 13 — when DJI eventually ships a 13+ controller image we'll
 * fall back to Shizuku (deferred to a future ADR).
 */
class SafTreeSource(
    private val treeUri: Uri,
    private val context: Context,
) : FlightLogSource {
    override val label: String = "SAF"

    override fun scan(): ScanResult {
        val found = mutableListOf<FlightLog>()
        val missing = mutableListOf<String>()
        val diag = mutableListOf<String>()

        val tree = DocumentFile.fromTreeUri(context, treeUri)
        if (tree == null || !tree.isDirectory || !tree.canRead()) {
            // Stale grant — directory revoked, deleted, or controller
            // refused to honor the persisted permission. Caller flips
            // _needsSafGrant true on the next pass so the operator
            // re-grants.
            missing += treeUri.toString()
            diag += "treeUri=$treeUri — unreadable (revoked, deleted, or denied)"
            return ScanResult(found, missing, diag)
        }

        val staging = File(context.cacheDir, "saf-staging").apply {
            // Wipe stale entries from prior scans — staging is one-shot
            // per scan; uploads consume cache files immediately after.
            if (exists()) deleteRecursively()
            mkdirs()
        }

        diag += "treeUri=$treeUri — readable (${tree.name ?: "?"})"

        val children = try {
            tree.listFiles()
        } catch (e: Exception) {
            diag += "  listFiles() failed: ${e.javaClass.simpleName}: ${e.message}"
            return ScanResult(found, missing, diag)
        }

        diag += "  ${children.size} entry(ies) under tree"

        var copied = 0
        var skipped = 0
        for (child in children) {
            val rawName = child.name ?: continue
            if (!child.isFile) continue
            val ext = rawName.substringAfterLast('.', "").lowercase()
            if (ext !in ALLOWED_EXTENSIONS) {
                skipped++
                continue
            }

            val sanitized = rawName.replace(Regex("[/\\\\]"), "_")
            val cacheFile = File(staging, sanitized)
            try {
                val stream = context.contentResolver.openInputStream(child.uri)
                if (stream == null) {
                    diag += "  $rawName — openInputStream returned null"
                    continue
                }
                stream.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
                // Preserve mtime so the existing ordering (newest first)
                // and date-formatted display match what the operator
                // sees on the controller's clock.
                val originalMtime = child.lastModified()
                if (originalMtime > 0) cacheFile.setLastModified(originalMtime)
                // sourceUri carries the original DJI-side document URI so
                // a subsequent "delete from controller" can target the
                // real artifact via DocumentsContract instead of silently
                // deleting only our cache copy.
                found.add(FlightLog(file = cacheFile, sourceUri = child.uri))
                copied++
                diag += "  ${rawName}  (${cacheFile.length()} B)  → cache"
            } catch (e: IOException) {
                Log.e(SCAN_TAG, "SAF copy failed for $rawName", e)
                diag += "  $rawName — IOException: ${e.message?.take(120)}"
                cacheFile.delete()
            }
        }

        diag += "  copied=$copied  skipped=$skipped"
        return ScanResult(found, missing, diag)
    }
}
