package com.droneopssync.app.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-10-06: two DJI Fly controllers (RC Pro / Mavic 3 Pro since 2026-05-17,
 * the Mavic 4 Pro's controller since 2026-09-19) stopped uploading with no
 * server contact at all. A persisted SAF grant that no longer resolves (e.g.
 * the DJI app's data folder recreated by an update) was kept, so every scan
 * returned 0 files and showed "No log files found" instead of the re-grant
 * banner. A dead grant must be forgotten and the operator re-prompted.
 */
class SafGrantPolicyTest {
    @Test fun deadGrantOnAndroid11PlusMustRegrant() {
        assertTrue(SafGrantPolicy.mustRegrant(sdkInt = 30, uriPresent = true, treeReadable = false))
        assertTrue(SafGrantPolicy.mustRegrant(sdkInt = 34, uriPresent = true, treeReadable = false))
    }

    @Test fun readableGrantIsKept() {
        assertFalse(SafGrantPolicy.mustRegrant(sdkInt = 34, uriPresent = true, treeReadable = true))
    }

    @Test fun noGrantOrPreAndroid11IsNotThisCase() {
        assertFalse(SafGrantPolicy.mustRegrant(sdkInt = 34, uriPresent = false, treeReadable = false))
        assertFalse(SafGrantPolicy.mustRegrant(sdkInt = 29, uriPresent = true, treeReadable = false))
    }
}
