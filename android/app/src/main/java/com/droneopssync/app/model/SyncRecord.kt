package com.droneopssync.app.model

import com.google.gson.annotations.SerializedName
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class SyncRecord(
    @SerializedName("ts")        val timestamp: Long = System.currentTimeMillis(),
    @SerializedName("url")       val serverUrl: String,
    @SerializedName("attempted") val filesAttempted: Int,
    @SerializedName("imported")  val imported: Int,
    @SerializedName("skipped")   val skipped: Int,
    @SerializedName("errors")    val errors: Int
) {
    val dateFormatted: String get() =
        SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault()).format(Date(timestamp))

    val summary: String get() = buildString {
        if (imported > 0) append("$imported imported")
        if (skipped > 0)  { if (isNotEmpty()) append(", "); append("$skipped already on server") }
        if (errors > 0)   { if (isNotEmpty()) append(", "); append("$errors error(s)") }
        if (isEmpty())    append("Nothing uploaded")
    }
}
