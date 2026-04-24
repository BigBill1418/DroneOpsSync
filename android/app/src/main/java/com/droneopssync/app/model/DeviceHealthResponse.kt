package com.droneopssync.app.model

import com.google.gson.annotations.SerializedName

/**
 * Typed response body for `GET /api/flight-library/device-health`.
 *
 * Most fields are populated on every successful preflight call. The two
 * rotation fields (`rotated_key` + `rotation_grace_until`) only appear
 * when the request authenticated via the OLD primary key during a
 * 24-hour grace window — see DroneOpsCommand ADR-0003.
 *
 * Forward-compatibility: Gson silently ignores unknown JSON fields, so
 * any future server-added field will not break the parse. This is a
 * load-bearing contract — if it ever changes, the rotation pickup logic
 * in `MainViewModel.preflightHealth` becomes brittle.
 */
data class DeviceHealthResponse(
    @SerializedName("status")
    val status: String,

    @SerializedName("device_label")
    val deviceLabel: String? = null,

    @SerializedName("parser_available")
    val parserAvailable: Boolean? = null,

    @SerializedName("upload_endpoint")
    val uploadEndpoint: String? = null,

    /**
     * The new raw API key, present ONLY when the device just authenticated
     * with the OLD key during a server-side rotation grace window. Null in
     * all steady-state cases.
     */
    @SerializedName("rotated_key")
    val rotatedKey: String? = null,

    /**
     * ISO-8601 UTC instant the grace window closes (suffixed with "Z"),
     * present alongside `rotated_key`. Null in steady-state cases.
     */
    @SerializedName("rotation_grace_until")
    val rotationGraceUntil: String? = null,
)
