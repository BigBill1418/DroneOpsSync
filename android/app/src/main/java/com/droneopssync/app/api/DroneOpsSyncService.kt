package com.droneopssync.app.api

import com.droneopssync.app.model.AsyncUploadResponse
import com.droneopssync.app.model.DeviceHealthResponse
import com.droneopssync.app.model.FlightUploadResponse
import com.droneopssync.app.model.UploadStatusResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Path

interface DroneOpsSyncService {

    /**
     * Upload one or more DJI flight log files to DroneOpsCommand.
     * The server deduplicates by SHA-256, parses, and imports the logs into
     * the Flight Library. Requires the device API key from DroneOpsCommand Settings.
     */
    @Multipart
    @POST("/api/flight-library/device-upload")
    suspend fun uploadFlights(
        @Header("X-Device-Api-Key") apiKey: String,
        @Part files: List<MultipartBody.Part>
    ): Response<FlightUploadResponse>

    /**
     * ADR-0023 / ADR-0008 async ingest. Uploads file(s) exactly as
     * [uploadFlights] (multipart, X-Device-Api-Key) but the server streams the
     * bytes to disk, enqueues a Celery parse job per non-duplicate file, and
     * returns **202 Accepted** + `{batch_id, files}` immediately — the parse no
     * longer happens in-request, so the connection is held only for the byte
     * stream (eliminates B1, the "hang").
     *
     * Used only when the preflight [DeviceHealthResponse.asyncUploadAvailable]
     * advertises support; otherwise the client falls back to [uploadFlights].
     * One batch_id per request (one-file-per-request, ADR-0023 §2.2) so the
     * `per_file` array maps 1:1 onto the existing per-file UploadStatus.
     */
    @Multipart
    @POST("/api/flight-library/device-upload/async")
    suspend fun uploadFlightsAsync(
        @Header("X-Device-Api-Key") apiKey: String,
        @Part files: List<MultipartBody.Part>
    ): Response<AsyncUploadResponse>

    /**
     * ADR-0023 / ADR-0008 async poll. Polls a submitted batch's progress
     * overlay. The client polls every 2 s while non-terminal, backing off to
     * 5 s after 60 s, and drives the per-file UploadStatus from
     * `per_file[0].state` until terminal (`complete` / `failed`). The job
     * completes server-side regardless of whether anyone is polling — client
     * death mid-poll is safe (SHA-256 dedup reconciles on next launch).
     */
    @GET("/api/flight-library/device-upload/status/{batchId}")
    suspend fun pollUpload(
        @Header("X-Device-Api-Key") apiKey: String,
        @Path("batchId") batchId: String
    ): Response<UploadStatusResponse>

    @GET("/api/health")
    suspend fun health(): Response<Map<String, String>>

    /**
     * Authenticated preflight health probe. Invoked before every upload to
     * surface "server rejected key" / "server unreachable" errors *before*
     * attempting the multipart POST (silent retries are exactly the class
     * of failure the 2026-04-23 RC Pro incident hit).
     *
     * The response body is a typed [DeviceHealthResponse]. Beyond confirming
     * connectivity, it carries the ADR-0003 zero-touch rotation hint —
     * `rotatedKey` + `rotationGraceUntil` are populated when the request
     * authenticated via the OLD key during a server-side rotation. See
     * `MainViewModel.preflightHealth` for the pickup behaviour.
     */
    @GET("/api/flight-library/device-health")
    suspend fun deviceHealth(
        @Header("X-Device-Api-Key") apiKey: String
    ): Response<DeviceHealthResponse>
}
