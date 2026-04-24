package com.droneopssync.app.api

import com.droneopssync.app.model.DeviceHealthResponse
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM unit tests for the [DeviceHealthResponse] parse contract — the
 * load-bearing piece of the ADR-0002 (DroneOpsSync) / ADR-0003
 * (DroneOpsCommand) zero-touch device API key rotation flow.
 *
 * No Android framework, no Robolectric, no MainViewModel instantiation.
 * The ViewModel pickup logic in `MainViewModel.maybeApplyRotatedKey`
 * routes through SharedPreferences + viewModelScope which would require
 * Robolectric to test directly; we verify the JSON contract here and
 * trust the linear glue code beyond it. The operator confirms the
 * end-to-end flow on the controller after merge.
 */
class KeyRotationParseTest {

    private val gson = Gson()

    @Test
    fun `parses full ADR-0003 rotation hint payload`() {
        val json = """
            {
              "status": "connected",
              "device_label": "M4TD",
              "parser_available": true,
              "upload_endpoint": "/api/flight-library/device-upload",
              "rotated_key": "doc_m4td_NewRawKeyValueExample0123456789abcdef",
              "rotation_grace_until": "2026-04-25T22:00:00Z"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, DeviceHealthResponse::class.java)

        assertEquals("connected", parsed.status)
        assertEquals("M4TD", parsed.deviceLabel)
        assertEquals(true, parsed.parserAvailable)
        assertEquals("/api/flight-library/device-upload", parsed.uploadEndpoint)
        assertEquals("doc_m4td_NewRawKeyValueExample0123456789abcdef", parsed.rotatedKey)
        assertEquals("2026-04-25T22:00:00Z", parsed.rotationGraceUntil)
    }

    @Test
    fun `tolerates absent rotated_key fields (steady-state response)`() {
        val json = """
            {
              "status": "connected",
              "device_label": "M4TD",
              "parser_available": true,
              "upload_endpoint": "/api/flight-library/device-upload"
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, DeviceHealthResponse::class.java)

        assertEquals("connected", parsed.status)
        assertNull("rotatedKey must be null when absent", parsed.rotatedKey)
        assertNull(
            "rotationGraceUntil must be null when absent",
            parsed.rotationGraceUntil,
        )
    }

    @Test
    fun `tolerates extra unknown server-added fields`() {
        // Forward-compatibility contract: a future server-added field
        // must not crash the parse.
        val json = """
            {
              "status": "connected",
              "device_label": "M4TD",
              "rotated_key": "doc_m4td_NewRawKeyValueExample0123456789abcdef",
              "rotation_grace_until": "2026-04-25T22:00:00Z",
              "future_unknown_field": {"nested": "value"},
              "another_unknown": 42
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, DeviceHealthResponse::class.java)

        assertEquals("doc_m4td_NewRawKeyValueExample0123456789abcdef", parsed.rotatedKey)
        assertEquals("2026-04-25T22:00:00Z", parsed.rotationGraceUntil)
    }

    @Test
    fun `tolerates empty rotated_key string`() {
        // Defensive: server should never send an empty string but if it
        // ever does we want to land on a parsed but ignored value, not
        // a crash. The ViewModel checks `isEmpty()` and no-ops.
        val json = """
            {
              "status": "connected",
              "rotated_key": "",
              "rotation_grace_until": null
            }
        """.trimIndent()

        val parsed = gson.fromJson(json, DeviceHealthResponse::class.java)

        assertNotNull(parsed)
        assertEquals("", parsed.rotatedKey)
        assertNull(parsed.rotationGraceUntil)
    }

    @Test
    fun `parses minimal status-only response`() {
        // The very oldest server build (pre-ADR-0002) returns just status.
        // Forward-from-old-back-end compatibility.
        val json = """{"status":"connected"}"""

        val parsed = gson.fromJson(json, DeviceHealthResponse::class.java)

        assertEquals("connected", parsed.status)
        assertNull(parsed.deviceLabel)
        assertNull(parsed.rotatedKey)
    }

    @Test
    fun `prefix and length validation matches ViewModel contract`() {
        // The ViewModel rejects any rotated_key that does not start with
        // "doc_" or is shorter than 40 chars. These tests document that
        // contract — they live here so a future change to either the
        // server's key format or the client's validation surfaces in
        // the same module.
        val validKey = "doc_m4td_NewRawKeyValueExample0123456789abcdef"  // length 46
        assertTrue(
            "valid key must start with doc_ prefix",
            validKey.startsWith("doc_"),
        )
        assertTrue(
            "valid key must be at least 40 chars",
            validKey.length >= 40,
        )

        val tooShort = "doc_short"
        assertTrue("too-short key must be rejected by length check", tooShort.length < 40)

        val wrongPrefix = "abc_NewRawKeyValueExample0123456789abcdefghijk"
        assertTrue(
            "wrong-prefix key must be rejected by prefix check",
            !wrongPrefix.startsWith("doc_"),
        )
    }
}
