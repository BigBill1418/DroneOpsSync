package com.droneopssync.app.model

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class FlightLog(
    val file: File,
    var uploadStatus: UploadStatus = UploadStatus.PENDING
) {
    val name: String get() = file.name
    val sizeBytes: Long get() = file.length()
    val sourcePath: String get() = file.parent ?: ""

    val sizeFormatted: String get() {
        val kb = sizeBytes / 1024.0
        return if (kb < 1024) "%.1f KB".format(kb) else "%.1f MB".format(kb / 1024.0)
    }

    val dateFormatted: String get() {
        val sdf = SimpleDateFormat("dd MMM yyyy  HH:mm", Locale.getDefault())
        return sdf.format(Date(file.lastModified()))
    }
}

enum class UploadStatus {
    PENDING,
    UPLOADING,
    VERIFIED,
    DELETED,
    ERROR
}
