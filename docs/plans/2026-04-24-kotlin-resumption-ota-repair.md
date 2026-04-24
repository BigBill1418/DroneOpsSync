# Plan: Kotlin resumption + OTA repair — port v2.62.0 / v2.62.1 behavior into Kotlin, cut v1.3.24

**Status:** Ready for aegis execution
**Author:** Terry (research + plan)
**Date:** 2026-04-24
**Target repo:** `BigBill1418/DroneOpsSync` (native Kotlin, working tree at `/home/bbarnard065/DroneOpsSync`)
**Source repo:** `BigBill1418/DroneOpsCommand` (Capacitor fork, working tree at `/home/bbarnard065/droneops`)
**Companion ADR:** `docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md` (narrates the Mar-23 → Apr-24 misdirection)

---

## 1. Executive summary

A prior Claude session (2026-03-23) committed a Capacitor/TypeScript rewrite of DroneOpsSync into `DroneOpsCommand/companion/` calling it v2.33.0, then shipped v2.34.0 → v2.62.1 there. Bill's actual RC Pro runs the original native Kotlin app from **this** repo (`BigBill1418/DroneOpsSync`), last release v1.3.23 on 2026-03-29. The Capacitor companion has zero device installs. All substantive fixes since 2026-03-29 landed in the wrong repo.

This plan:

1. Ports the two client-visible fixes from the Capacitor track into Kotlin — HTTPS coercion (commit `890b875`) and landscape lock + pairing banner + preflight health gate (commit `306a2b8`).
2. Moves CI off `ubuntu-latest` onto `[self-hosted, linux, x64, bos]` per fleet policy ADR-0029, adds `apksigner verify` + `aapt dump badging` gates to `release.yml`.
3. Decommissions the Capacitor track in DroneOpsCommand — deletes `companion/`, removes `companion-apk.yml`, annotates the three orphan GH releases as abandoned, extends DroneOpsCommand's ADR-0002 with a "Kotlin lives" addendum.
4. Leaves version-bump automatic — `version-bump.yml` will bump `1.3.23 → 1.3.24` on first push to `android/**`.
5. Verifies the v1.3.24 APK is signed with the same certificate as v1.3.23 so Android's updater (and the in-app OTA via `GitHubClient`) accept it — **zero sideload** for Bill.

Single aegis session + one code-reviewer handoff. No stopgaps.

---

## 2. Scope — every file to create / modify / delete

### Create (DroneOpsSync)

1. `CHANGELOG.md` — seed with v1.3.23 history (one-line summary reconstructed from git tags) + v1.3.24 entry describing this plan's output
2. `ROADMAP.md` — seed with "post-v1.3.24 follow-ups" (mobile unit tests for `normalizeUrl`, explicit server-config banner polish)
3. `PROGRESS.md` — seed with 2026-04-24 entry pointing to this plan + ADR
4. `docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md` — the misdirection ADR
5. `docs/plans/2026-04-24-kotlin-resumption-ota-repair.md` — this file (already created by Terry)

### Modify (DroneOpsSync)

6. `android/app/src/main/java/com/droneopssync/app/api/ApiClient.kt` — HTTPS coercion in `normalizeUrl` with LAN carve-out (§3)
7. `android/app/src/main/AndroidManifest.xml` — `fullSensor` → `sensorLandscape` on MainActivity (§4)
8. `android/app/src/main/java/com/droneopssync/app/viewmodel/MainViewModel.kt` — add `PairingState` sealed class + `_pairingState` StateFlow + `checkPairing()` + expose `preflightHealth()` gate inside `startAutoFlow`/`onNetworkAvailable`/`performUpload` (§5, §6)
9. `android/app/src/main/java/com/droneopssync/app/ui/screens/HomeScreen.kt` — add pairing banner above the existing `ReadyToSyncBadge` (§5)
10. `.github/workflows/release.yml` — `runs-on` → BOS self-hosted; drop `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24`; add `apksigner verify` + `aapt dump badging` verification steps; add JDK 17 + Android SDK provisioning (§7)
11. `.github/workflows/version-bump.yml` — `runs-on` → BOS self-hosted; drop `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` (§7)
12. `.github/workflows/auto-merge-claude-dev.yml` — `runs-on` → BOS self-hosted (§7)

### Delete (DroneOpsSync)

13. `apply-droneopscommand-update.sh` — abandoned March 2026 one-shot integration script; backend endpoint it created is live
14. `droneopssync-integration.patch` — abandoned March 2026 patch artifact; already applied upstream

### Modify (DroneOpsCommand)

15. Delete tree `companion/` (entire directory — `git rm -r companion/`)
16. Delete `.github/workflows/companion-apk.yml`
17. Append new §7 to `docs/adr/0002-droneopssync-upload-auth.md` titled **"Addendum 2026-04-24 — Kotlin app lives, Capacitor fork abandoned"**, linking to DroneOpsSync `docs/adr/0001-...`
18. `CHANGELOG.md` — entry dated 2026-04-24: "Remove abandoned `companion/` Capacitor fork. Kotlin app resumes in `BigBill1418/DroneOpsSync` at v1.3.24. See ADR-0002 §7."
19. Edit **body** of three GH releases via `gh release edit`: `companion-v2.61.5`, `companion-v2.62.0`, `companion-v2.62.1` — prepend banner (§8)

### Version bump

20. `android/version.properties` — **do NOT edit manually**. `version-bump.yml` will bump `1.3.23 → 1.3.24` on first push to `main` that touches `android/**`.

---

## 3. Port step 1 — HTTPS coercion in ApiClient.kt::normalizeUrl

### Behavior to mirror (from `companion/src/sync.ts::validateServerUrl` in commit `890b875`)

- Trim + strip trailing slashes.
- If scheme is `https://` → accept.
- If scheme is `http://` → accept **only** if host is RFC-1918, loopback, or link-local (`localhost` / `127.0.0.1` / `::1` / `10.x` / `192.168.x` / `172.16-31.x` / `169.254.x`). Otherwise **silently upgrade to `https://`**. (The Capacitor version *throws* instead of upgrading; in Kotlin we will upgrade silently so an existing field that persisted `http://droneops.barnardhq.com` keeps working after the upgrade, with a WARN diag log recording the coercion. This is the operator-friendlier choice per `feedback_managed_customer_seamless.md` — we don't want Bill's first v1.3.24 launch to land him on a red "configure me" screen when silent upgrade works.)
- If scheme is neither → prepend `https://` (today's code already does this implicitly by falling through).

### Diff-as-plan

Current `ApiClient.kt` lines 42–50:

```kotlin
private fun normalizeUrl(url: String): String {
    var base = url.trim()
    if (base.contains("://")) {
        val pathStart = base.indexOf('/', base.indexOf("://") + 3)
        if (pathStart > 0) base = base.substring(0, pathStart)
    }
    if (!base.endsWith("/")) base = "$base/"
    return base
}
```

Replace with:

```kotlin
private fun normalizeUrl(url: String): String {
    var base = url.trim().trimEnd('/')
    if (!base.contains("://")) {
        // No scheme → assume HTTPS (aligned with companion v2.62.0 default).
        base = "https://$base"
    }
    // Strip any path — we use Retrofit's baseUrl which must be host-only.
    val schemeEnd = base.indexOf("://") + 3
    val pathStart = base.indexOf('/', schemeEnd)
    if (pathStart > 0) base = base.substring(0, pathStart)

    // ── HTTPS-only coercion (ADR-0002 §4) ────────────────────────────
    // Plaintext http:// is only allowed for LAN hosts. Public hostnames
    // on http:// get silently upgraded to https:// — CloudFlare's
    // 80→443 redirect returns an HTML body that Gson cannot parse,
    // which was the root cause of the 2026-04-23 upload-blocked incident
    // against Bill's RC Pro. See docs/adr/0001-kotlin-resumption-...
    if (base.startsWith("http://", ignoreCase = true)) {
        val host = base.substringAfter("://").substringBefore(':').substringBefore('/')
        val isPrivate = host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host.startsWith("10.") ||
            host.startsWith("192.168.") ||
            Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(host) ||
            host.startsWith("169.254.")
        if (!isPrivate) {
            val upgraded = "https://" + base.removePrefix("http://")
            Log.w(TAG, "normalizeUrl: coerced plaintext public URL to HTTPS: $base -> $upgraded")
            base = upgraded
        }
    }

    return "$base/"
}
```

### Also change (same file)

Change the default server URL constant in `MainViewModel.kt` from `http://10.50.0.5:3080` (a stale private LAN IP) to `https://droneops.barnardhq.com` — this mirrors the Capacitor v2.62.0 `DEFAULT_SERVER_URL` change. Line 57 of `MainViewModel.kt`:

```kotlin
// Before
private const val DEFAULT_SERVER    = "http://10.50.0.5:3080"

// After
// Pre-baked public URL for the primary instance (mirrors EyesOn
// ADR-0019's BuildConfig.DEFAULT_SERVER_URL pattern and the companion
// v2.62.0 default). v2.34-era code blanked this to stop leaking an
// internal 10.x IP; shipping the public hostname reinstates out-of-box
// usability without leaking anything.
private const val DEFAULT_SERVER    = "https://droneops.barnardhq.com"
```

---

## 4. Port step 2 — Orientation lock

### Change

`android/app/src/main/AndroidManifest.xml` line 24:

```xml
<!-- Before -->
android:screenOrientation="fullSensor"

<!-- After -->
android:screenOrientation="sensorLandscape"
```

### Also extend `configChanges` (already present on line 25)

Current value: `"orientation|screenSize|screenLayout|smallestScreenSize"` — already covers orientation + screenSize. Add `keyboardHidden` to match the Capacitor `patch-android.cjs` guard (protects against soft-keyboard appearance recreating the activity):

```xml
android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"
```

### Rationale

Commit `306a2b8` chose `sensorLandscape` over `landscape` so 180°-flipped mounts work on DJI RC 2 and similar controllers. `fullSensor` today allows portrait — on Bill's RC Pro (physically locked to landscape) that's moot, but it also re-creates the activity on rotate events that the hardware doesn't send, which would destroy the upload in progress on a controller that *did* rotate. `sensorLandscape` refuses portrait entirely.

---

## 5. Port step 3 — Pairing banner (Layer 1)

### Behavior to mirror (from `companion/src/App.tsx` + `companion/src/sync.ts::checkPairing` in commit `306a2b8`)

- If `serverUrl` is blank → banner copy: **"Server URL is not set. Open Settings and enter the DroneOps server URL."**
- If `apiKey` is blank → **"API key is not set. Open Settings → paste the key from DroneOps → Settings → Device Access."**
- If `serverUrl` fails validation (e.g. malformed, unsupported scheme) → **"Server URL is not valid ({reason}). Open Settings."**
- Otherwise → paired, no banner.
- Banner is persistent and red; includes an "OPEN SETTINGS" button unless the user is already on Settings.

### Kotlin implementation

**MainViewModel additions (top of class, alongside existing state flows):**

```kotlin
// ── Pairing state (ADR-0002 §5 layer 1, via docs/adr/0001) ───────────
sealed class PairingState {
    data object Paired : PairingState()
    data class Unpaired(val reason: String, val message: String) : PairingState()
}

private val _pairingState = MutableStateFlow<PairingState>(PairingState.Paired)
val pairingState: StateFlow<PairingState> = _pairingState

private fun checkPairing(url: String = _serverUrl.value, key: String = _apiKey.value): PairingState {
    if (url.isBlank()) return PairingState.Unpaired(
        "missing_url",
        "Server URL is not set. Open Settings and enter the DroneOps server URL."
    )
    if (key.isBlank()) return PairingState.Unpaired(
        "missing_key",
        "API key is not set. Open Settings → paste the key from DroneOps → Settings → Device Access."
    )
    if (!url.isValidUrl()) return PairingState.Unpaired(
        "invalid_url",
        "Server URL must start with http:// or https://. Open Settings."
    )
    return PairingState.Paired
}

/** Call after any state change that could affect pairing. */
private fun refreshPairing() {
    _pairingState.value = checkPairing()
}
```

**Hook points:** call `refreshPairing()` at the end of `loadSettings` and `saveSettings` (add to both). Keep `checkPairing()` pure — no side effects — so it can also be called inline from `performUpload` / `startAutoFlow`.

**HomeScreen addition** (before `ReadyToSyncBadge` around line 366):

```kotlin
val pairing by viewModel.pairingState.collectAsState()

if (pairing is MainViewModel.PairingState.Unpaired) {
    val unpaired = pairing as MainViewModel.PairingState.Unpaired
    Card(
        modifier = Modifier
            .fillMaxWidth(0.82f)
            .padding(bottom = 16.dp),
        colors = CardDefaults.cardColors(containerColor = DocRed.copy(alpha = 0.08f)),
        border = BorderStroke(2.dp, DocRed)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                "DEVICE NOT PAIRED",
                color = DocRed,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(unpaired.message, color = DocRed, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                onClick = onOpenSettings,
                border = BorderStroke(1.5.dp, DocRed)
            ) {
                Text("OPEN SETTINGS", color = DocRed)
            }
        }
    }
}
```

**Signature change:** add `onOpenSettings: () -> Unit` parameter to the HomeScreen composable and wire it from the callsite (`MainActivity` — it already has a nav-to-settings lambda because `SettingsScreen` is in the route graph; reuse it).

### Reuse note

The existing `ReadyToSyncBadge` + `connectionError` surface is network-reachability UI, not pairing UI. They're orthogonal: server might be reachable but the key blank; the key might be valid but DNS failing. Keep both. The pairing banner sits **above** ReadyToSyncBadge — if pairing fails, the network badge is still informational but the action is "fix pairing first."

---

## 6. Port step 4 — Preflight health gate (Layer 2)

### Behavior to mirror (from `companion/src/sync.ts::preflightHealth` in commit `306a2b8`)

Before any upload attempt: call `/api/flight-library/device-health` with `X-Device-Api-Key` header. If it fails, classify the failure and surface a specific banner — **do not silently retry**:

- Unreachable (network error / DNS / timeout) → **"Cannot reach {server} — check Wi-Fi and server status."**
- HTTP 401 → **"Server rejected the API key. Open Settings → Device Access on the server, copy the key, and re-paste it here."**
- Any other non-2xx → **"Server returned {status}. Try again; if it persists, check the DroneOps server status."**

Upload must be **blocked** on failure — no attempt to POST the multipart body. The 2026-04-23 class of failure was exactly "try and fail silently against a blank server URL."

### Kotlin implementation

**Add to `DroneOpsSyncService` (new endpoint):**

```kotlin
@GET("/api/flight-library/device-health")
suspend fun deviceHealth(
    @Header("X-Device-Api-Key") apiKey: String
): Response<Map<String, Any>>
```

(The server already has this endpoint — it's what the Capacitor companion calls. No server change needed.)

**Add to `MainViewModel` alongside `checkServerHealth`:**

```kotlin
sealed class PreflightResult {
    data class Ok(val body: Map<String, Any>) : PreflightResult()
    data class Fail(val code: String, val status: Int?, val message: String) : PreflightResult()
}

/**
 * Preflight health gate — run BEFORE every upload. Returns structured
 * result so callers surface a specific banner instead of silently
 * retrying. ADR-0002 §5 layer 2 (via docs/adr/0001).
 */
private suspend fun preflightHealth(url: String, apiKey: String): PreflightResult {
    return try {
        val response = ApiClient.create(url).deviceHealth(apiKey)
        when {
            response.isSuccessful -> {
                val body = response.body() ?: emptyMap()
                PreflightResult.Ok(body)
            }
            response.code() == 401 -> PreflightResult.Fail(
                "invalid_key", 401,
                "Server rejected the API key. Open Settings → Device Access on the server, copy the key, and re-paste it here."
            )
            else -> PreflightResult.Fail(
                "server_error", response.code(),
                "Server returned HTTP ${response.code()}. Try again; if it persists, check the DroneOps server status."
            )
        }
    } catch (e: UnknownHostException) {
        PreflightResult.Fail("unreachable", null, "Cannot reach $url — check Wi-Fi and server status.")
    } catch (e: SocketTimeoutException) {
        PreflightResult.Fail("unreachable", null, "Server at $url timed out — check Wi-Fi and server status.")
    } catch (e: IOException) {
        PreflightResult.Fail("unreachable", null, "Network error reaching $url: ${e.message?.take(120)}")
    }
}
```

**Wire into `performUpload` (MainViewModel line ~311):** after the existing `url.isBlank()` / `key.isBlank()` / `url.isValidUrl()` bail-outs and before `_isUploading.value = true`, insert:

```kotlin
when (val pre = preflightHealth(url, key)) {
    is PreflightResult.Ok -> {
        // Clear any stale connection-error banner.
        _connectionError.value = null
        _serverReachable.value = true
    }
    is PreflightResult.Fail -> {
        _statusMessage.value = pre.message
        _connectionError.value = pre.message
        _serverReachable.value = false
        diag(DiagLevel.ERROR, "PREFLIGHT", "${pre.code} status=${pre.status} msg=${pre.message}")
        return 0
    }
}
```

**Wire into `startAutoFlow` (MainViewModel line ~450):** replace the 8-second `_serverReachable.value == null` wait loop with a direct preflight call — the old loop is racing `checkServerHealth` which uses `/api/health` (no key); we want to gate on the key-bearing `/device-health` instead:

```kotlin
// Replace lines 461-466 with:
val pre = preflightHealth(url, key)
if (pre !is PreflightResult.Ok) {
    _statusMessage.value = (pre as PreflightResult.Fail).message
    _connectionError.value = pre.message
    diag(DiagLevel.WARN, "AUTO", "Preflight failed — aborting auto-sync: ${pre.message}")
    return@launch
}
_serverReachable.value = true
_connectionError.value = null
```

**Wire into `onNetworkAvailable` (line ~477):** same pattern — call `preflightHealth` before `performUpload` and bail out with a diag log on failure.

### Server-side (DO NOT TOUCH)

Layers 3 + 4 — the Celery `check_device_silence_task` beat job and the first-401 Pushover tripwire — are server-side and **already live** in `BigBill1418/DroneOpsCommand` (shipped by commits `890b875` + `306a2b8`). DroneOpsSync repo is client-only; no changes to Layer 3 + 4 belong here.

---

## 7. CI move to BOS-HQ self-hosted

### `.github/workflows/release.yml`

```yaml
# Before
jobs:
  release:
    if: ${{ github.event_name == 'workflow_dispatch' || github.event.workflow_run.conclusion == 'success' }}
    runs-on: ubuntu-latest
    permissions:
      contents: write
    env:
      FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true
```

```yaml
# After
jobs:
  release:
    if: ${{ github.event_name == 'workflow_dispatch' || github.event.workflow_run.conclusion == 'success' }}
    runs-on: [self-hosted, linux, x64, bos]
    permissions:
      contents: write
```

**Drop `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24`** — this env var is a workaround for GitHub-hosted runners transitioning off Node 20 to Node 24 for JS actions. On BOS self-hosted, node is managed by `actions/setup-node` (when needed) and the runtime used by JS actions is whatever the runner bundles (currently Node 20.x, per the `self-hosted-smoke-test.yml` evidence at `/usr/local/node20/bin/node`). The flag has no effect on self-hosted and can confuse diagnostics — remove it.

**Add Android SDK provisioning** (BOS runner is ephemeral — each run is fresh, just like `companion-apk.yml` does it):

```yaml
      - name: Set up Android SDK
        uses: android-actions/setup-android@v3
        with:
          packages: 'platform-tools platforms;android-33 build-tools;33.0.2'
```

Insert this right after `actions/setup-java@v4`. JDK 17 is provisioned by `actions/setup-java` — the runner doesn't ship with JDK pre-installed (confirmed by the companion-apk.yml precedent).

**Add post-build verification steps** — fail fast on signing / SDK mismatches:

```yaml
      - name: Verify APK signature (fingerprint must match v1.3.23's cert)
        run: |
          APK="DroneOpsSync-v${{ steps.version.outputs.name }}.apk"
          apksigner verify --print-certs "$APK"
          # Extract SHA-256 fingerprint and print it prominently so the
          # workflow log makes post-merge sanity check trivial.
          FINGERPRINT=$(apksigner verify --print-certs "$APK" | grep -i "SHA-256" | head -1)
          echo "::notice title=APK signer fingerprint::$FINGERPRINT"

      - name: Verify APK metadata (versionName + targetSdk)
        run: |
          APK="DroneOpsSync-v${{ steps.version.outputs.name }}.apk"
          aapt dump badging "$APK" | grep -E "package:|application:" | head -5
          VERSION_IN_APK=$(aapt dump badging "$APK" | grep -oE "versionName='[^']+'" | cut -d"'" -f2)
          if [ "$VERSION_IN_APK" != "${{ steps.version.outputs.name }}" ]; then
            echo "::error::versionName mismatch: APK has $VERSION_IN_APK, expected ${{ steps.version.outputs.name }}"
            exit 1
          fi
```

(`apksigner` and `aapt` are installed by `android-actions/setup-android@v3` under `$ANDROID_HOME/build-tools/33.0.2/` and put on PATH.)

### `.github/workflows/version-bump.yml`

```yaml
# Change `runs-on: ubuntu-latest` → `runs-on: [self-hosted, linux, x64, bos]`
# Drop the `env: FORCE_JAVASCRIPT_ACTIONS_TO_NODE24: true` block (3 lines).
```

### `.github/workflows/auto-merge-claude-dev.yml`

```yaml
# Change `runs-on: ubuntu-latest` → `runs-on: [self-hosted, linux, x64, bos]`
# No env block to remove.
```

### BOS-HQ runner prerequisite check

Before aegis pushes, it must verify BOS-HQ's self-hosted runner pool has capacity and is online:

```bash
gh api repos/BigBill1418/DroneOpsSync/actions/runners --jq '.runners[] | {name, status, labels: [.labels[].name]}'
```

Expected: at least one runner with labels including `self-hosted`, `linux`, `x64`, `bos`, status `online`. If no BOS runner is registered against `BigBill1418/DroneOpsSync` yet (it was only used for `DroneOpsCommand` + `EyesOn` historically per `project_gh_runner_bos_hq_20260421.md`), **this is a blocker** — aegis must either:

1. Register the existing BOS runner group against `BigBill1418/DroneOpsSync` via the GitHub web UI (Settings → Actions → Runners → Manage), OR
2. Confirm the runner is registered at the org level and picks up this repo automatically.

Flag this as an open check in aegis's first turn.

---

## 8. Decommission Capacitor track (DroneOpsCommand)

Executed in `/home/bbarnard065/droneops` (NOT DroneOpsSync). Separate commit, separate push.

### Step 8.1 — delete the companion tree

```bash
cd /home/bbarnard065/droneops
git rm -r companion/
git rm .github/workflows/companion-apk.yml
```

### Step 8.2 — extend ADR-0002

Append to `docs/adr/0002-droneopssync-upload-auth.md`:

```markdown
## §7 — Addendum 2026-04-24: Kotlin app lives, Capacitor fork abandoned

On 2026-04-24 we discovered that the `companion/` Capacitor fork shipped
here as v2.33.0 → v2.62.1 has zero device installs. Bill's RC Pro has
always run the native Kotlin app from `BigBill1418/DroneOpsSync`, last
released as v1.3.23 on 2026-03-29. Every substantive client-side fix
committed here since 2026-03-29 — including §4 HTTPS coercion and §5
landscape lock + silent-drift watchdog layers 1 + 2 — landed in the
wrong repo.

Decision: the Kotlin app is authoritative. The Capacitor fork is
removed from this repo in commit {SHA} (2026-04-24). Layers 3 + 4 of
the §5 watchdog (server-side Celery beat + first-401 Pushover) remain
in this repo because they are backend code. Layers 1 + 2 (pairing
banner + preflight health gate) are re-implemented in Kotlin in
`BigBill1418/DroneOpsSync` v1.3.24.

See:
- `BigBill1418/DroneOpsSync/docs/adr/0001-kotlin-resumption-abandon-capacitor-fork.md`
- `BigBill1418/DroneOpsSync/docs/plans/2026-04-24-kotlin-resumption-ota-repair.md`
```

### Step 8.3 — CHANGELOG entry

Add to `CHANGELOG.md` under a new `## 2026-04-24` heading (or extend the existing one):

```markdown
- **Removed:** `companion/` Capacitor DroneOpsSync fork and
  `companion-apk.yml` workflow. Six weeks of commits there had zero
  device impact — Bill's RC Pro runs the native Kotlin app from
  `BigBill1418/DroneOpsSync`. Client-side equivalents of v2.62.0 +
  v2.62.1 ported into Kotlin and shipped as v1.3.24. See ADR-0002 §7.
```

### Step 8.4 — annotate the three orphan GH releases

For each of `companion-v2.61.5`, `companion-v2.62.0`, `companion-v2.62.1`:

```bash
gh release view <tag> --repo BigBill1418/DroneOpsCommand --json body -q .body > /tmp/body.txt
# Prepend banner
cat > /tmp/banner.txt << 'EOF'
> **ABANDONED — DO NOT INSTALL**
>
> This was a Capacitor/TypeScript rewrite of DroneOpsSync that shipped
> here by mistake. It has zero device installs. The real DroneOpsSync
> app is the native Kotlin build at
> https://github.com/BigBill1418/DroneOpsSync/releases/latest.
>
> See `docs/adr/0002-droneopssync-upload-auth.md` §7 for context.

---

EOF
cat /tmp/banner.txt /tmp/body.txt > /tmp/new_body.txt
gh release edit <tag> --repo BigBill1418/DroneOpsCommand --notes-file /tmp/new_body.txt
```

Do **not** delete the releases — the APK assets are evidence. Just annotate.

### Step 8.5 — commit + push

```bash
cd /home/bbarnard065/droneops
git add -A
git commit -m "chore: remove abandoned companion/ Capacitor fork; Kotlin lives in DroneOpsSync (ADR-0002 §7)"
git push origin main
```

---

## 9. Cleanup (DroneOpsSync root)

Read both files before deleting. They are Mar-2026 integration artifacts:

- `apply-droneopscommand-update.sh` — one-shot script that clones `DroneOpsCommand`, creates branch `claude/device-upload-pN2gE`, writes the backend `device_api_key` model + `validate_device_api_key` dep + `/device-upload` endpoint. **Already applied upstream** — backend has been running this code in production since 2026-03-18. Safe to delete.
- `droneopssync-integration.patch` — the patch body referenced by the script above. Same status, same verdict.

```bash
rm apply-droneopscommand-update.sh droneopssync-integration.patch
```

Document in CHANGELOG under v1.3.24: "Removed stale March-2026 DroneOpsCommand integration artifacts (`apply-droneopscommand-update.sh`, `droneopssync-integration.patch`) — their contents are live on the backend."

---

## 10. Version bump (automatic)

Per `DroneOpsSync/CLAUDE.md`:

> Version bumps are fully automated — do NOT bump manually.

`version-bump.yml` bumps `VERSION_NAME` in `android/version.properties` on every push to `main` that touches `android/**`. Aegis's v1.3.24 push will touch `android/app/src/main/AndroidManifest.xml` + `android/app/src/main/java/...` files, so the bump fires automatically. **Do not edit `android/version.properties` manually.** Do not add `[skip ci]` to the PR body.

`versionCode` auto-increments from git commit count in `build.gradle` (config-cache safe).

PR workflow:

```bash
cd /home/bbarnard065/DroneOpsSync
git checkout -b claude/kotlin-resumption-v1.3.24
# ... make all edits from §3–§7 + §9 + init CHANGELOG/ROADMAP/PROGRESS/ADR ...
git add -A
git commit -m "feat(kotlin): resume Kotlin app; port HTTPS coercion + landscape lock + pairing banner + preflight gate from companion v2.62.0/v2.62.1 (ADR-0001)"
git push -u origin claude/kotlin-resumption-v1.3.24
gh pr create --repo BigBill1418/DroneOpsSync --base main --title "Resume Kotlin app — port v2.62.0/v2.62.1 client fixes, cut v1.3.24" --body-file docs/plans/2026-04-24-kotlin-resumption-ota-repair.md
gh pr merge <number> --repo BigBill1418/DroneOpsSync --squash
# CI fires: version-bump.yml → release.yml → publishes DroneOpsSync-v1.3.24.apk
```

---

## 11. E2E verification procedure

Execute in order. Each step has observable evidence. Stop and escalate on any failure.

### 11.1 — CI green (pre-release)

```bash
gh run list --repo BigBill1418/DroneOpsSync --workflow="Build & Release APK" --limit 1
gh run view <id> --repo BigBill1418/DroneOpsSync --log | tail -40
```

**Expected:** status `completed` conclusion `success`. Last ~40 lines include `::notice title=APK signer fingerprint::SHA-256...` and the `versionName` check passing.

### 11.2 — Signing fingerprint matches v1.3.23

```bash
cd /tmp
gh release download v1.3.23 --repo BigBill1418/DroneOpsSync --pattern '*.apk' --clobber
gh release download v1.3.24 --repo BigBill1418/DroneOpsSync --pattern '*.apk' --clobber
apksigner verify --print-certs DroneOpsSync-v1.3.23.apk | grep -i "SHA-256"
apksigner verify --print-certs DroneOpsSync-v1.3.24.apk | grep -i "SHA-256"
```

**Expected:** identical SHA-256 fingerprints on both APKs. If they differ, Android's updater will refuse v1.3.24 as "not from the same author" and Bill will be forced to uninstall + sideload. **This is a ship-blocker.** Abort and investigate `KEYSTORE_B64` secret.

### 11.3 — APK metadata correct

```bash
aapt dump badging DroneOpsSync-v1.3.24.apk | grep -E "^(package|application-label|sdkVersion|targetSdkVersion):"
```

**Expected:** `versionName='1.3.24'`, `targetSdkVersion='29'` (per `build.gradle` — required for DJI log path access on Android 10+), `package: name='com.droneopssync.app'`.

### 11.4 — GH release exists with APK asset

```bash
gh release view v1.3.24 --repo BigBill1418/DroneOpsSync --json tagName,assets -q '{tag: .tagName, apk: [.assets[] | select(.name | endswith(".apk")) | .name]}'
```

**Expected:** `{"tag":"v1.3.24","apk":["DroneOpsSync-v1.3.24.apk"]}`.

### 11.5 — OTA flow on Bill's RC Pro (human-in-the-loop)

Bill performs:

1. Open DroneOpsSync on RC Pro (v1.3.23).
2. Settings → Check for Updates.
3. Observe "v1.3.24 available" banner appears within ~3 s.
4. Tap Install / Update.
5. App relaunches.
6. Splash screen → home.
7. Footer (or DiagScreen top) shows v1.3.24.

**Evidence:** Bill confirms via a message or screenshot. **Do not mark complete without Bill's confirmation.**

### 11.6 — Orientation lock

Bill rotates the RC Pro (or attempts to, via the developer "force rotate" gesture). App **stays landscape**. No flash of portrait layout.

### 11.7 — Pairing banner

Bill:

1. Settings → clear API key field → Save.
2. Return to Home.
3. **Expected:** red "DEVICE NOT PAIRED" banner with copy "API key is not set. Open Settings → paste the key from DroneOps → Settings → Device Access."
4. Paste the key back → banner disappears.

### 11.8 — Preflight health gate

Operator-side (can be done from a dev machine with fleet access):

1. Bill sets server URL to `https://droneops.barnardhq.com` (default) with a valid key.
2. From BOS-HQ: `docker compose stop droneops-api` (briefly — ~15 s).
3. On RC Pro: tap SYNC ALL.
4. **Expected:** status message reads something like "Cannot reach https://droneops.barnardhq.com — check Wi-Fi and server status." No crash. No silent failure. The upload is **not attempted**.
5. `docker compose start droneops-api`.
6. Tap SYNC ALL again → uploads succeed.

### 11.9 — Actual upload succeeds

Bill syncs the 3 pending FlightRecord files from the RC Pro.

**Evidence:** BOS-HQ server log (via `docker compose logs droneops-api | grep device_upload | tail`) shows three structured INFO audit-log entries like:

```json
{"event":"device_upload","device_label":"M4TD","device_id":"...","file_count":1,"total_bytes":5800000,"imported":1,"skipped":0,"error_count":0}
```

Also visible in DroneOps Flight Library UI as three new entries.

### 11.10 — Decommission cleanup verified

```bash
gh release view companion-v2.62.1 --repo BigBill1418/DroneOpsCommand --json body -q .body | head -10
```

**Expected:** first 10 lines contain the "**ABANDONED — DO NOT INSTALL**" banner.

```bash
cd /home/bbarnard065/droneops && git log --oneline -1
ls companion/ 2>&1
```

**Expected:** HEAD commit is the decommission commit; `ls companion/` returns "No such file or directory".

---

## 12. Code-reviewer handoff note

When aegis completes, code-reviewer should focus on:

1. **HTTPS coercion correctness** — verify the LAN carve-out regex doesn't accidentally admit public IPs that look private (e.g. `10.example.com` is not private; `10.0.0.1` is). Kotlin `startsWith("10.")` on a hostname passes `10.example.com` — confirm the check is against a parsed host, not a raw URL substring. The plan above uses `host.startsWith("10.")` on the already-split host; verify aegis didn't simplify it away.
2. **Kotlin Compose state hoisting** — `PairingState` is observed in `HomeScreen` via `collectAsState()`; confirm aegis didn't put business logic in the composable (e.g. calling `checkPairing()` inside composition). The state flow update belongs in the ViewModel.
3. **Preflight is called from all three upload entry points** — `performUpload` (manual), `startAutoFlow` (launch), `onNetworkAvailable` (connectivity). Missing any one of these re-creates the 2026-04-23 silent-failure class.
4. **CI workflow correctness** — three workflows all on `[self-hosted, linux, x64, bos]`; `FORCE_JAVASCRIPT_ACTIONS_TO_NODE24` env removed everywhere; `apksigner verify` + `aapt dump badging` steps added to `release.yml` only (not bump, not auto-merge); BOS runner is registered against `BigBill1418/DroneOpsSync`.
5. **Release notes accuracy** — v1.3.24 release body (generated by `generate_release_notes: true`) should list the pairing banner + landscape lock + HTTPS coercion PR. If the body is auto-generated from commit messages, the single squashed commit message must be descriptive.
6. **Decommission atomicity** — `companion/` deletion, `companion-apk.yml` removal, ADR-0002 §7 addendum, CHANGELOG entry, and the three GH release body edits all in a single DroneOpsCommand commit (plus a single `gh release edit` sequence). No half-state.

---

## 13. Anti-goals — what aegis MUST NOT do

- **Do NOT rebuild the OTA updater.** `GitHubClient.kt` polling `/repos/{owner}/{repo}/releases/latest` works today and is how every current device will pick up v1.3.24.
- **Do NOT rotate the keystore.** `secrets.KEYSTORE_B64` + `secrets.KEYSTORE_PASSWORD` in `BigBill1418/DroneOpsSync` are the same keystore that signed v1.3.23 (and every release before it). Rotating means every device needs to uninstall + sideload.
- **Do NOT add Play Store / FCM / any background update service.** Scope creep. The in-app OTA is sufficient.
- **Do NOT touch the server.** Layers 3 + 4 of the §5 watchdog (Celery beat + first-401 Pushover) are already live on `BigBill1418/DroneOpsCommand` BOS-HQ. Server changes belong in that repo, not DroneOpsSync. Per DroneOpsCommand CLAUDE.md "Failover & Resilience Guard," server changes carry failover risk that a DroneOpsSync PR has no business taking.
- **Do NOT keep `companion/` "just in case."** It's dead code, it's misleading new contributors, and GitHub search turns up hits that waste time. Delete it clean.
- **Do NOT edit `android/version.properties` manually.** CLAUDE.md §"Version bumps are fully automated" is explicit — the bump commit gets folded into the squash and the `[skip ci]` in the bump commit body suppresses the downstream workflows. Let `version-bump.yml` do it.
- **Do NOT ship without the BOS runner pre-check.** If no BOS runner is registered against this repo, `runs-on: [self-hosted, ...]` will hang waiting for a runner to pick up the job. Verify first.
- **Do NOT skip the `apksigner verify` verification step locally** before declaring done. The in-CI check is a belt; the local fingerprint comparison (§11.2) is the braces.

---

## 14. Open questions

**None.** The situation is fully scoped. Aegis can start.

(If the BOS-HQ runner is not registered against `BigBill1418/DroneOpsSync`, that is an execution-time blocker aegis surfaces on turn 1, not an open design question.)
