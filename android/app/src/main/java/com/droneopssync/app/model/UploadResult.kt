package com.droneopssync.app.model

import com.google.gson.annotations.SerializedName

data class UploadResult(
    @SerializedName("filename") val filename: String,
    @SerializedName("checksum") val checksum: String,
    @SerializedName("size") val size: Long
)
