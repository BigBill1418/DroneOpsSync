package com.droneopssync.app.api

import com.droneopssync.app.model.UploadResult
import okhttp3.MultipartBody
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

interface DroneOpsSyncService {

    @Multipart
    @POST("/upload")
    suspend fun uploadFile(@Part file: MultipartBody.Part): Response<UploadResult>

    @GET("/health")
    suspend fun health(): Response<Map<String, String>>
}
