# resolveDevHostIp() review, Phase A (read-only)

Branch: `fix/assess-ordering-and-chip-wrapping`. Commit under review: `01c1303`. File:
`app/build.gradle.kts:27-49` (function), call site `:90`.

Status: PHASE A ONLY. No production code changed. Nothing here is approved.

## Q1. What does it do at runtime, precisely?

**VERIFIED (read, `app/build.gradle.kts:27-49`).**

```kotlin
fun resolveDevHostIp(): String {
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
        for (iface in Collections.list(interfaces)) {
            if (!iface.isUp || iface.isLoopback || iface.isPointToPoint) continue
            val name = iface.name
            if (name.startsWith("docker") || name.startsWith("br-") || name.startsWith("virbr")
                || name.startsWith("tailscale") || name.startsWith("vboxnet") || name.startsWith("zeth")) {
                continue
            }
            for (addr in Collections.list(iface.inetAddresses)) {
                if (addr is Inet4Address && !addr.isLoopbackAddress) {
                    val ip = addr.hostAddress
                    if (!ip.startsWith("127.") && !ip.startsWith("169.254.")) {
                        return ip
                    }
                }
            }
        }
    } catch (_: Exception) {
        // Fallback if network interface query fails
    }
    return "127.0.0.1"
}
```

This is a **Gradle build script function**, run on the host machine at configuration time
(when Gradle evaluates `app/build.gradle.kts` for a build), not on the Android device at
runtime. "Runtime" here means build-time execution on the developer's or CI's host JVM.

**Enumeration order**: `NetworkInterface.getNetworkInterfaces()`'s order is JVM/OS defined
(kernel enumeration order), not sorted or ranked by this code. No preference for Ethernet over
Wi-Fi, physical over virtual, or private over any other address class - the loop takes the
**first** interface that survives the filters and has a matching address, in whatever order
the JVM handed the interfaces to it.

**Filters applied, in order**: interface must be up, not loopback, not point-to-point; name
must not start with `docker`, `br-`, `virbr`, `tailscale`, `vboxnet`, or `zeth`. This is an
explicit deny-list of six specific prefixes, not an allow-list of expected LAN adapter names.
Address must be `Inet4Address`, not loopback, not starting with `127.` or `169.254.` (link-local).

**On multiple candidates** (VPN + Docker + hotspot + Ethernet + Wi-Fi all up at once): the
function returns whichever survives the filter and is enumerated first. Docker bridges,
`virbr*`, Tailscale and VirtualBox host-only adapters are explicitly excluded by name. A VPN
interface is **not** explicitly excluded by name (no `tun`, `utun`, `wg`, or `ppp` prefix in the
deny-list) and is excluded only if the OS/JVM reports it as `isPointToPoint` - true for some
VPN client implementations, not guaranteed for all (a TAP-mode or split-tunnel client can present
as a normal non-P2P interface with a real IPv4 address).

**On none found**: falls through to `return "127.0.0.1"` at the very end of the function
(line 48), reached whether the loop completed with no match, or the initial
`getNetworkInterfaces()` call returned null (early return, same value), or an exception was
thrown and caught (falls through to the same final line). Every failure path returns the
identical literal string `"127.0.0.1"` - never null, never empty, never propagates an exception.

## Q2. How is "auto" reached? Trace every caller.

**VERIFIED (read, `app/build.gradle.kts:88-94`).**

```kotlin
val configuredBackend = localProperties.getProperty("BACKEND_BASE_URL", "").trim()
val resolvedDevBackend = if (configuredBackend.isEmpty() || configuredBackend.equals("auto", ignoreCase = true)) {
    "http://${resolveDevHostIp()}:8080/"
} else {
    configuredBackend
}
buildConfigField("String", "BACKEND_BASE_URL", "\"$resolvedDevBackend\"")
```

`localProperties` (`:21-25`) is a `java.util.Properties` loaded once at the top of the file from
`local.properties` (git-ignored, never read here beyond this existing, pre-existing mechanism -
I did not open, print, or otherwise inspect the contents of `local.properties` itself, per the
hard rule; only this build script's own handling of a property VALUE it reads was reviewed).
`resolveDevHostIp()` fires when `BACKEND_BASE_URL` is absent from `local.properties`, blank, or
the literal string `auto` (case-insensitive). Its only caller is this one line, `:90`. Its result
is interpolated directly into `BuildConfig.BACKEND_BASE_URL` for the `dev` flavor only - there is
no other reader of "auto" anywhere in the codebase (confirmed by a repo-wide grep in Q3 below,
which found the string only inside this one `dev` flavor block).

## Q3. Is it flavour-gated? Can a release build ever reach this path?

**VERIFIED (read, `app/build.gradle.kts:78-128`). Verdict: NO for any build meant for
distribution (staging or prod, any build type). YES it is buildable, but only inside the `dev`
flavor, under any build type including `release` - see the precise distinction below.**

`flavorDimensions += "environment"` with three sibling flavors in one dimension:

```
productFlavors {
    create("dev") { ... BACKEND_BASE_URL via localProperties / resolveDevHostIp() ... }   // :80-112
    create("staging") { ... buildConfigField("String", "BACKEND_BASE_URL", "\"https://staging.samd.example.com/backend/\"") ... }  // :113-120
    create("prod") { ... buildConfigField("String", "BACKEND_BASE_URL", "\"https://api.samd.example.com/backend/\"") ... }          // :121-127
}
```

Each `create(name) { ... }` block's lambda contributes `buildConfigField` calls **only to that
flavor's variant** - this is how Android Gradle Plugin's flavor dimension model works: a build
variant selects exactly one flavor per dimension, and only that flavor's configuration lambda
runs for it. The `staging` and `prod` blocks contain zero reference to `localProperties`,
`resolveDevHostIp`, or the string `"auto"` - confirmed by grep, the only three hits for
`resolveDevHostIp` in the whole repository (`.kts`/`.gradle` files) are the function definition,
one comment, and the one call site, all inside `app/build.gradle.kts:27-90`, entirely within or
above the `dev` block. `staging`/`prod` hardcode HTTPS URLs directly with no build-time host
resolution of any kind.

`applicationId` is `com.example.samdapp` in `defaultConfig` (`:58`); only `dev` (`:82`) and
`staging` (`:115`) add an `applicationIdSuffix` (`.dev`, `.staging`); `prod` adds none. A build
distributed as the production article is a `prod`-flavor build, and that flavor's block never
calls this function.

**The precise distinction the question needs**: "release" is a build **type**
(`buildTypes { release { ... } }`, `:144-150`), orthogonal to the flavor dimension. AGP produces
one variant per flavor times build-type pair: `devDebug`, `devRelease`, `stagingDebug`,
`stagingRelease`, `prodDebug`, `prodRelease`. **`devRelease` is a real, buildable variant**, and
because it is still the `dev` flavor, it **would** still call `resolveDevHostIp()` and honor
"auto" - the release build **type** alone does not gate this code, only the flavor does. That
variant still carries `applicationId com.example.samdapp.dev` and is not the artifact the
signing config (`:133-141`, keyed off `KEYSTORE_PATH`/`KEYSTORE_PASSWORD`/`KEY_ALIAS`/
`KEY_PASSWORD` env vars, CI-only per its own comment) is described as producing for distribution.

So: a `prod` or `staging` build, in either build type, can never reach this function. A `dev`
build, in either build type including `devRelease`, can. Stated as the plain yes/no the operator
asked for: **no, a release build in the sense of the distributed production article
(`prodRelease`) cannot reach it; yes, a `dev`-flavored build tagged with the `release` build type
is buildable and would still reach it, but that is not the same artifact.**

## Q4. The silent catch: what does the caller receive, and what happens next?

**VERIFIED (read, `app/build.gradle.kts:45-48`, and traced forward to `:90`, `:94`).**

Every failure path inside `resolveDevHostIp()` - `getNetworkInterfaces()` returning null, the
scan loop finding nothing, or the scan throwing - returns the same literal string `"127.0.0.1"`.
The caller at `:90` receives a normal, well-formed string; nothing propagates, nothing crashes,
nothing is empty. `BuildConfig.BACKEND_BASE_URL` is set to `"http://127.0.0.1:8080/"`.

On a physical device this is not "my own machine's loopback" - `127.0.0.1` on the phone refers
to the phone itself, which is not running a backend. On the standard Android emulator, the
host's loopback is conventionally reached via `10.0.2.2`, not `127.0.0.1`, so this address is
wrong there too. Either way, the app attempts to connect to an address with nothing listening,
and the resulting connection-refused or timeout is indistinguishable from "the real backend is
down" - the same masking pattern the handoff memo's Problem 2 already describes for the `.local`
DNS failure ("looking exactly like gateway down"). Nothing in the current code records that
interface resolution specifically failed versus succeeded with a real address.

## Q5. Multi-homed failure mode: can it silently pick the wrong interface?

**VERIFIED (read) for the mechanism; INFERRED for the specific VPN-client behavior, since I did
not have a live multi-homed host to reproduce this against.**

Yes, concretely. The deny-list at `:33` names six specific virtual-adapter prefixes
(`docker`, `br-`, `virbr`, `tailscale`, `vboxnet`, `zeth`) and excludes nothing else by name.
It does not filter common VPN interface names (`tun*`, `utun*`, `wg*`, `ppp*`). The `isPointToPoint`
check at `:31` catches VPN adapters the OS/JVM itself flags as point-to-point, which is common
for classic tunnel-mode VPNs but not guaranteed for every VPN client (a TAP-mode or split-tunnel
client can present as an ordinary interface with a real, non-loopback `Inet4Address`).

Concrete consequence: on a developer machine running a VPN whose interface is up, not loopback,
not point-to-point, and not name-matched by the deny-list, and which is enumerated before the
real LAN adapter, `resolveDevHostIp()` returns the VPN's assigned address (for example a
`10.8.x.x` tunnel address) instead of the developer's actual LAN IP. `BACKEND_BASE_URL` is then
baked into the dev APK as `http://10.8.x.x:8080/` - an address that is real and well-formed, not
obviously wrong, but unreachable from the phone's own LAN, since it is the far end of a private
tunnel the phone has no route into. The failure surfaces identically to Q4's case: a connection
failure with no signal that the wrong host was ever selected, and here the host is not even the
obviously-suspicious `127.0.0.1` - it looks like a plausible real address, making it a harder
failure to recognize than the current loopback fallback.

## Recommendation

Q3 is not a blocker: the path is flavor-gated and cannot reach a `staging` or `prod` build,
which is what "release build" means for anything actually distributed. The `devRelease` nuance
is worth recording (above) but does not change the recommendation.

Recommend proceeding to Phase B as scoped: a log line in the existing catch, naming that
interface resolution failed and what host is being used as a result, so a wrong or fallback
host is never silent to the developer running the build. No change to interface-selection logic,
no change to the "auto" mechanism, no redesign - the multi-homed failure mode in Q5 is a real
residual this narrow fix does not close (a log line at build time only helps someone who reads
Gradle's build output; it does not prevent the wrong interface from being picked), and closing
it fully would mean widening the deny-list or switching to an allow-list, which is a larger,
separate change than what was authorized here.
