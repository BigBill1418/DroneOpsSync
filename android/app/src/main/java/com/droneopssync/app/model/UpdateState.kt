package com.droneopssync.app.model

sealed class UpdateState {
    object Idle : UpdateState()
    object Checking : UpdateState()
    data class Available(val version: String, val downloadUrl: String, val sizeBytes: Long) : UpdateState()
    data class Downloading(val progress: Int) : UpdateState()
    data class ReadyToInstall(val apkPath: String) : UpdateState()
    object UpToDate : UpdateState()
}
