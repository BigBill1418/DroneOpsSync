package com.droneopssync.app.model

import com.google.gson.annotations.SerializedName

data class FlightUploadResponse(
    @SerializedName("imported") val imported: Int,
    @SerializedName("skipped")  val skipped: Int,
    @SerializedName("errors")   val errors: List<String>
)
