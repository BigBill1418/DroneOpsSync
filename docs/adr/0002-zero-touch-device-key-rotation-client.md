# ADR-0002 — Zero-touch device API key rotation (Kotlin client)

- **Status:** Accepted — shipped 2026-04-24. DroneOpsSync v1.3.25 (CI auto-bump on merge).
- **Date:** 2026-04-24
- **Authors:** aegis (remote routine `trig_01KiBK88vqs6vtRf75rkxcw8` re-run after empty-branch failure)
- **Scope:** Native Kotlin DroneOpsSync companion app (`android/`)
- **Related ADRs:** [`0001-kotlin-resumption-abandon-capacitor-fork.md`](./0001-kotlin-resumption-abandon-capacitor-fork.md). Cross-repo: DroneOpsCommand [`docs/adr/0003-zero-touch-device-key-rotation.md`](https://github.com/BigBill1418/DroneOpsCommand/blob/main/docs/adr/0003-zero-touch-device-key-rotation.md).
- **Plan:** DroneOpsCommand `docs/plans/2026-04-24-zero-touch-key-rotation.md` (shared plan).

---

## 1. Context

After ADR-0001 / v1.3.24 brought the silent-drift watchdog (pairing banner + preflight health gate) to the Kotlin app, one operator interaction was still required: when the operator rotated a device API key on the server (DroneOpsCommand), the paired RC Pro kept presenting the OLD key, hit HTTP 401, and the operator had to walk to the controller and paste the new key.

That tap defeats the camera-less-controller program ([`feedback_dji_rc_pro_no_camera.md`](../../../README.md#feedback) — DJI RC Pro has no usable rear camera). The fix needs to happen on **both ends** — server-side delivery of the new key, client-side pickup. The server-side half is DroneOpsCommand ADR-0003 (24h grace window + new admin endpoint + Redis-backed hint side-channel). This ADR covers the client-side pickup.

---

## 2. Decision

### 2.1 Typed response model

`DroneOpsSyncService.deviceHealth` previously returned `Response<Map<String, Any>>` — sufficient for status-code-only inspection but not for reading specific JSON fields safely. We replace the map with a typed Gson model:

```kotlin
data class DeviceHealthResponse(
    @SerializedName("status")               val status: String,
    @SerializedName("device_label")         val deviceLabel: String? = null,
    @SerializedName("parser_available")     val parserAvailable: Boolean? = null,
    @SerializedName("upload_endpoint")      val uploadEndpoint: String? = null,
    @SerializedName("rotated_key")          val rotatedKey: String? = null,
    @SerializedName("rotation_grace_until") val rotationGraceUntil: String? = null,
)
```

Gson silently ignores unknown JSON fields, so the model is **forward-compatible**: future server-added fields don't crash the parse.

### 2.2 ViewModel pickup logic

`MainViewModel.preflightHealth` runs on every upload attempt, every auto-flow tick, and every network-connect callback. After a successful response, the new `maybeApplyRotatedKey` helper:

1. Reads `body?.rotatedKey`.
2. No-ops if null, empty, or equal to the current key (server idempotency).
3. WARN-logs and no-ops if the key fails sanity checks (must start with `doc_`, length ≥ 40 chars).
4. On a valid hint:
   - Persists to `SharedPreferences[PREF_API_KEY]`.
   - Updates `_apiKey.value` so any UI bindings see the new key.
   - Calls `ApiClient.invalidate()` so the next Retrofit call uses the new header.
   - Calls `refreshPairing()` so the banner clears if it was up.
   - INFO-logs to the diag buffer with the grace expiry timestamp.
   - Emits `"API key auto-updated"` on a `SharedFlow<String>` toast channel.

The validation rules are intentional. The server should never emit a malformed key, but a defensive check ensures a single misbehaving hop (reverse proxy injecting headers, misrouted response, etc.) cannot overwrite a working device key with garbage.

### 2.3 One-shot toast signal

A `MutableSharedFlow<String>` (replay = 0, extraBufferCapacity = 1, onBufferOverflow = DROP_OLDEST) is the right shape: each rotation is a distinct event with content (the toast string), not a sticky boolean. `replay=0` prevents the next-mount of HomeScreen from re-showing a stale rotation toast.

`HomeScreen` collects via `LaunchedEffect(Unit) { viewModel.toastEvents.collect { ... } }` and displays through `android.widget.Toast.makeText(...).show()`. The app does not maintain a `SnackbarHost` in its scaffold; system Toast is the right fit for this kind of rare transient event.

### 2.4 Test scope

Unit tests cover the Gson **parse contract** — the load-bearing piece. The ViewModel persistence + invalidate path uses `SharedPreferences` and `viewModelScope`, both of which require Robolectric to drive in unit tests. This project does not currently use Robolectric and bringing it in for a single SharedFlow + SharedPreferences write is out of proportion to the value. The operator (Bill) verifies the end-to-end flow on the controller after merge.

Tests at `android/app/src/test/java/com/droneopssync/app/api/KeyRotationParseTest.kt` (6 tests, all green):

1. `parses full ADR-0003 rotation hint payload`
2. `tolerates absent rotated_key fields (steady-state response)`
3. `tolerates extra unknown server-added fields` (forward-compat)
4. `tolerates empty rotated_key string`
5. `parses minimal status-only response`
6. `prefix and length validation matches ViewModel contract`

This commit also bootstraps the previously-absent `android/app/src/test/` source tree and adds JUnit + Gson `testImplementation` deps.

---

## 3. Consequences

### 3.1 Positive

- After a server-side rotation the controller picks up the new key on its next preflight call (every upload attempt, every network-reconnect, every app launch). No tap required.
- Old key continues to authenticate for the 24h grace window the server holds open, so an in-flight upload never breaks during rotation.
- Forward-compatible JSON parse — server can add fields without crashing the client.

### 3.2 Negative / cost

- The new code path runs on every successful preflight call, but the work is one Gson field read + one short-circuit. Negligible.
- A hostile network operator who can MITM the device-health response could overwrite the device's stored API key with whatever they choose — but the existing client already requires HTTPS for public hosts (`ApiClient.normalizeUrl()`), so the threat reduces to "attacker who has the TLS private key", at which point the rotation hint is the least of our problems.

### 3.3 Risks and mitigations

| Risk | Likelihood | Impact | Mitigation |
|---|---|---|---|
| Server emits a malformed `rotated_key` | Very low | High (would lock out the device) | Prefix + length validation in `maybeApplyRotatedKey`; WARN log, no-op |
| Toast collector dropped while rotation fires | Low | Low | The persistence + invalidate side-effects all happen regardless; the toast is only confirmation. Operator sees the rotation succeed via the next upload working. |
| Race: rotation hint received twice | Low | Low | The second `maybeApplyRotatedKey` call short-circuits on `newKey == currentKey`. |

---

## 4. Implementation map

| Concern                       | File                                                                         |
|-------------------------------|------------------------------------------------------------------------------|
| Typed response                | `android/app/src/main/java/com/droneopssync/app/model/DeviceHealthResponse.kt` |
| Service signature             | `android/app/src/main/java/com/droneopssync/app/api/DroneOpsSyncService.kt`  |
| ViewModel pickup              | `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt`  |
| Toast wiring (HomeScreen)     | `android/app/src/main/java/com/droneopssync/app/ui/screens/HomeScreen.kt`    |
| Unit tests                    | `android/app/src/test/java/com/droneopssync/app/api/KeyRotationParseTest.kt` |
| Test deps                     | `android/app/build.gradle` (testImplementation block)                        |

---

## 5. Open questions / follow-ups

- **Rotation event in sync history.** Currently a successful rotation is logged to the diag buffer + surfaced via Toast. It is NOT recorded in `SyncHistoryScreen` (that screen lists upload sessions only). A future enhancement could add a "key rotated" entry, but the diag buffer is already the operator's diagnostic surface.
- **Robolectric coverage.** If a future ViewModel refactor breaks the persistence path silently, the unit tests would not catch it. If/when this project picks up Robolectric for any other reason, the rotation pickup path is the right candidate to extend coverage to.
- **First-time key install.** This ADR addresses *rotation* of an existing key. First-time enrollment (taking a controller out of a box) still requires a one-time paste in Settings. That is `feedback_managed_customer_seamless.md` territory and out of scope here.
