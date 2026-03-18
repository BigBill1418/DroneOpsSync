package com.droneopssync.app.api

import com.droneopssync.app.model.FlightUploadResponse
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

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

    @GET("/api/health")
    suspend fun health(): Response<Map<String, String>>
}
