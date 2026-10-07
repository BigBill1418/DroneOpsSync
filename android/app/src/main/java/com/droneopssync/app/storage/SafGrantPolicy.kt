package com.droneopssync.app.storage

/**
 * When a persisted SAF tree grant must be forgotten and the operator re-prompted.
 *
 * A grant that no longer resolves (revoked, or the DJI app's data folder deleted
 * and recreated by an update) makes every scan return 0 files. Keeping it showed
 * "No log files found" forever with no re-grant banner and no server contact —
 * how two DJI Fly controllers went silent (2026-05-17, 2026-09-19).
 */
object SafGrantPolicy {
    private const val ANDROID_11 = 30

    fun mustRegrant(sdkInt: Int, uriPresent: Boolean, treeReadable: Boolean): Boolean =
        sdkInt >= ANDROID_11 && uriPresent && !treeReadable
}
