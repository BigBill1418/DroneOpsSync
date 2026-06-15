package com.droneopssync.app.model

import com.google.gson.annotations.SerializedName

/**
 * Gson contract models for the ADR-0023 (DroneOpsCommand) /
 * ADR-0008 (DroneOpsSync) async device-upload handshake.
 *
 * Canonical contract: DroneOpsCommand `docs/adr/0023-device-upload-async-celery-decoupling.md`.
 * The field names below MUST match the backend envelopes byte-for-byte — the
 * FastAPI backend is implemented against the SAME ADR. Any drift breaks the
 * integration.
 *
 * Forward-compatibility: Gson silently ignores unknown JSON fields, so a
 * future server-added field will not break the parse (same load-bearing
 * contract as [DeviceHealthResponse], proven by KeyRotationParseTest /
 * PollEnvelopeTest).
 */

/**
 * 202 Accepted body returned by `POST /api/flight-library/device-upload/async`.
 *
 * ```jsonc
 * { "batch_id": "…", "files": [{"name": "…", "state": "pending|skipped|…"}] }
 * ```
 *
 * With one-file-per-request (ADR-0023 §2.2) the `files` array has a single
 * entry; a pre-parse dedup short-circuit may already mark it `skipped`.
 */
data class AsyncUploadResponse(
    @SerializedName("batch_id")
    val batchId: String,

    @SerializedName("files")
    val files: List<AsyncUploadFile> = emptyList(),
)

data class AsyncUploadFile(
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("state")
    val state: String? = null,
)

/**
 * Body returned by `GET /api/flight-library/device-upload/status/{batchId}`.
 *
 * ```jsonc
 * {
 *   "batch_id": "…",
 *   "status":   "queued|running|complete|failed",   // batch-level rollup
 *   "phase":    "queued|parsing|done",
 *   "progress": 0..100,                              // files-done / files-total
 *   "per_file": [
 *     {"name": "…", "state": "pending|parsing|imported|skipped|error",
 *      "imported": 0, "skipped": 0, "error": null}
 *   ]
 * }
 * ```
 *
 * `status` and `per_file[].state` are nullable defensively so a partial or
 * older overlay never crashes the parse; the reducer treats absent values
 * as non-terminal.
 */
data class UploadStatusResponse(
    @SerializedName("batch_id")
    val batchId: String? = null,

    @SerializedName("status")
    val status: String? = null,

    @SerializedName("phase")
    val phase: String? = null,

    @SerializedName("progress")
    val progress: Int? = null,

    @SerializedName("per_file")
    val perFile: List<PerFileStatus> = emptyList(),
)

data class PerFileStatus(
    @SerializedName("name")
    val name: String? = null,

    @SerializedName("state")
    val state: String? = null,

    @SerializedName("imported")
    val imported: Int? = null,

    @SerializedName("skipped")
    val skipped: Int? = null,

    @SerializedName("error")
    val error: String? = null,
)
