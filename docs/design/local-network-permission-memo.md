# F1 ACCESS_LOCAL_NETWORK: Step 1 design memo

Status: gate reviewed 2026-10-15. Operator rulings are section 10; the step 2 design addendum is
section 11 and supersedes sections 5 to 8 where they differ.
Branch: `feat/local-network-permission` from `origin/master` at `1ce3b4a`.
Date: 2026-10-15. Device: Google Pixel 11 Pro, serial 67041FDKX004ZY, Android 17, API 37.
Evidence: `/media/sandesh/extra-ssd/samd-lnp/probe/` on the SSD scratch (file names cited inline).

This file is outside the IEC 62304 controlled set: `docs/design/README.md` says the folder is
outside `docs/quality` and `docs/requirements`, and that new design memos go here directly
(VERIFIED, `docs/design/README.md:7-9,19`).

Tag key: VERIFIED means a file:line, an official doc URL, or a device measurement backs the claim.
INFERRED means it is reasoning and has not been measured or read.

## 0. Summary

- F1 as filed ("never requests `ACCESS_LOCAL_NETWORK`") is half right. The Wi-Fi hub Start control
  already requests it (`CompounderScreen.kt:289-292` through `PermissionAction.kt:54-64`, added in
  `6941d05`) and handles a denial with true copy (VERIFIED, device, `c1-*`, `c2-*`). What has no
  request is everything that talks to the dev backend on the LAN: login, sync, kernel, SLM, ABHA.
  Those calls fail with a 10 s connect timeout and the user is shown a raw exception string at
  login and "could not reach the server" on Home (VERIFIED, device, section 3).
- Staging and prod point at public HTTPS hostnames (VERIFIED, `app/build.gradle.kts:193,200`), and
  the backend PRD places both on AWS Mumbai (VERIFIED, `docs/backend/backend-prd.md:681-682`), so
  as designed today only the dev flavor needs the permission. The intended-use statement says
  "cloud/LAN-hosted" (VERIFIED, `docs/requirements/intended-use-statement.md:62`); that conflict is
  open question Q1.
- Recommendation: option A, a runtime request keyed to "is this target local", with the
  permission state (not the exception) as the discriminator and a new drain-failure kind
  `LOCAL_NETWORK_NOT_ALLOWED`. Section 6.
- Side findings that the probe exposed, not caused by F1: the kernel copy calls any
  `SocketTimeoutException` "The connection was made", which is false for a connect timeout
  (section 4.4); the login screen shows raw exception text including LAN IPs and ports for every
  network failure (section 4.1).

## 1. Platform facts

All from developer.android.com, plus the API 37 platform jar in this repo's SDK.

1.1 Enforcement. Apps targeting API 37 or higher must hold `ACCESS_LOCAL_NETWORK` for LAN
access; in Android 16 it was opt-in. VERIFIED,
https://developer.android.com/about/versions/17/behavior-changes-17 and
https://developer.android.com/privacy-and-security/local-network-permission .
This app targets 37 (VERIFIED, `app/build.gradle.kts:93`), compiles against 37
(`app/build.gradle.kts:86-88`), and declares the permission in the main manifest (VERIFIED,
`app/src/main/AndroidManifest.xml:12`).

1.2 What counts as local network. VERIFIED,
https://developer.android.com/privacy-and-security/local-network-definition :
"an IP network that utilizes a broadcast-capable network interface, such as Wi-Fi or Ethernet, but
excludes cellular (WWAN) or VPN connections". IPv4: 169.254.0.0/16 (link-local), 100.64.0.0/10
(CGNAT), 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16. IPv6: link-local, directly-connected routes,
stub networks such as Thread, multiple subnets (the page gives no IPv6 prefixes). Multicast
224.0.0.0/4 and ff00::/8 and the IPv4 broadcast 255.255.255.255 are local. Resolving a `.local`
name requires the permission (VERIFIED, local-network-permission page). A DNS server on the LAN at
port 53 is exempt (VERIFIED, same page). WebView inherits the host app's state (VERIFIED, same).

1.3 Loopback. The docs do not list 127.0.0.0/8 or ::1 either way (VERIFIED, definition page is
silent). Measured on the Pixel: with the permission revoked, the app uid connects to
127.0.0.1:18080 (an `adb reverse` to the laptop) with rc=0, and fails to connect to the LAN
backend 10.203.2.52:8080 (VERIFIED, device, `raw-socket-loopback.txt`). So loopback is exempt in
practice, which means the `adb reverse` desk setup (`http://127.0.0.1:...`) never needs the
permission (INFERRED from the measurement, not a documented guarantee).

1.4 How a blocked socket fails. "TCP Connections will typically result in a timeout error. UDP
errors and general permission denials will typically result in an EPERM error code" (VERIFIED,
local-network-permission page). Measured: TCP connect from the app uid timed out; it did not fail
fast with EPERM (VERIFIED, device, `raw-socket-lan.txt`, `nc: Timeout` after 8.06 s). The NDK has
`android_getnetworkblockedreason(int sockFd)`; there is no Java equivalent named on the page
(VERIFIED, same page).

1.5 NEARBY_DEVICES group. "If a user has already granted another permission in this group (such as
Bluetooth permissions), they won't be re-prompted for local network access" (VERIFIED,
local-network-permission page). The group constant is
`android.permission-group.NEARBY_DEVICES` (VERIFIED, `javap` on
`$ANDROID_HOME/platforms/android-37.0/android.jar`). The system dialog text on the Pixel is "Allow
SaMDApp to find, connect to and determine the relative position of nearby devices?" (VERIFIED,
device, `c1-start-prompt.txt`). It says nothing about a local network or a server.

1.6 Counter reset. "The platform implements a counter reset strategy ... This mechanism grants
additional opportunities for the app to invoke the requestPermission() API, effectively resetting
the denial count for the ACCESS_LOCAL_NETWORK permission", including after a user revocation
(VERIFIED, local-network-permission page; the page gives no count). Measured: after one "Don't
allow" the flags became `USER_SET|USER_FIXED` and the next request returned denied with no dialog
(VERIFIED, device, `c2-perms.txt`, `c3-second-start.txt`). So the design cannot count on a second
prompt; Settings is the only recovery path that was observed to work.

1.7 System-mediated pickers. `NsdManager` with `DiscoveryRequest.FLAG_SHOW_PICKER` shows a system
dialog to pick one device, and connections to that service's `getHostAddresses()` do not need the
permission (VERIFIED, local-network-permission page; `FLAG_SHOW_PICKER = 2`,
`registerServiceInfoCallback(DiscoveryRequest, Executor, ServiceInfoCallback)` and
`NsdServiceInfo.getHostAddresses()` present in the API 37 jar, VERIFIED `javap`). Output Switcher
covers casting (VERIFIED, same page). How long a picker grant lasts is not stated (VERIFIED,
silent).

1.8 Below target 37. Apps with INTERNET get an implicit grant; "Don't request
ACCESS_LOCAL_NETWORK at runtime prior to targeting SDK 37" (VERIFIED, local-network-permission
page). On an API 37 device the enforcement keys off targetSdk, not the device SDK (VERIFIED, same
page). The existing helper skips the request below SDK_INT 37 (VERIFIED,
`PermissionAction.kt:63`).

1.9 The guard constant. `Build.VERSION_CODES.CINNAMON_BUN = 37` and `BAKLAVA = 36` (VERIFIED,
`javap -constants 'android.os.Build$VERSION_CODES'` on the android-37.0 jar, `source.properties`
`AndroidVersion.ApiLevel=37.0`). The repo currently uses a literal,
`ACCESS_LOCAL_NETWORK_ENFORCED_SDK = 37` (VERIFIED, `LocalNetworkFailure.kt:13`), and a string for
the permission (`LocalNetworkFailure.kt:8`) although `Manifest.permission.ACCESS_LOCAL_NETWORK`
exists at compileSdk 37 (VERIFIED, `javap`). Step 2 can switch both to the SDK constants.

1.10 Revocation kills the process. `pm revoke` produced
`am_kill [0,9524,com.example.samdapp.dev,0,permissions revoked,...]` and a restart of
MainActivity as pid 9778 (VERIFIED, device, `revoke-processdeath.txt`). The docs do not cover
workers or foreground state (VERIFIED, local-network-permission page is silent).

1.11 Testing aid. `adb shell am compat enable RESTRICT_LOCAL_NETWORK <package>` plus a reboot
enables enforcement on Android 16 (VERIFIED, local-network-permission page). Useful for a pre-37
AVD check of the "not enforced" path if wanted.

## 2. Egress inventory

| # | Destination | Flavors | Host type | Resolution | Source |
|---|---|---|---|---|---|
| E1 | backend/core (auth, sync push, kernel proxy, clinical) | dev | LAN: `http://<build host IPv4>:8080/` by default, or a `local.properties` value | literal IPv4 (no DNS) | `app/build.gradle.kts:27-49,120-136`; `NetworkModule.kt:155` |
| E1 | same | staging | public `https://staging.samd.example.com/backend/` (placeholder) | system DNS | `app/build.gradle.kts:193` |
| E1 | same | prod | public `https://api.samd.example.com/backend/` (placeholder) | system DNS | `app/build.gradle.kts:200` |
| E2 | dev host rewrite of E1 at runtime | dev | LAN: loopback, 10.0.2.2, RFC 1918 only | literal IP | `DevServerConfig.kt:34-41,53-56`; `DevDynamicHostInterceptor.kt:33-54` |
| E3 | ABHA / ABDM (through backend, separate client) | all | same as E1 | same as E1 | `NetworkModule.kt:116-145` (base URL line 138) |
| E4 | SLM read-back (through backend) | all | same as E1 | same as E1 | `SlmNetworkModule.kt:110-123` |
| E5 | WorkManager sync drain (`SyncPushWorker`) | all | same as E1 | same as E1 | `SyncPushWorker.kt:19`; `RetrofitSyncPushService.kt:23-29`; constraint `NetworkType.CONNECTED` at `WorkManagerSyncOutboxScheduler.kt:30` |
| E6 | WorkManager kernel assessment | all | same as E1 | same as E1 | `WorkManagerAssessmentScheduler.kt:35`; `RetrofitKernelSource.kt:89` |
| E7 | Wi-Fi instrument hub `http://kernelhub1.local:8090/` (derived from `PI_HUB_ASSIGNMENT`), or a `local.properties` override | dev only (`src/dev/`) | LAN, mDNS `.local` | `NsdGatewayDns` over `NsdManager`, service `_samd-gw._tcp.`; system DNS for non-`.local` | `app/build.gradle.kts:54,155-166`; `PiGatewayNetworkModule.kt:66-72,89-109`; `NsdGatewayDns.kt:52-76,83` |
| E8 | BLE instrument hub kernelhub2 | dev only | not IP (Bluetooth LE) | CDM association | `app/src/dev/AndroidManifest.xml:21-23`; `BlePrepActivity.kt:88` |
| E9 | Gemini brand lookup `https://generativelanguage.googleapis.com/` | all | public | system DNS | `GeminiNetworkModule.kt:41` |
| E10 | Open-source licence links | all | public, opened in the browser, not app egress | n/a | `OpenSourceLicensesScreen.kt:46,89` |

All of E1, E3, E4, E5, E6 share the one `BACKEND_BASE_URL` (VERIFIED, lines cited). The kernel is
not called directly any more (VERIFIED, `NetworkModule.kt:50-53`). ASR is on-device (INFERRED from
the licence list and `AsrEgressTest`; not re-read for this memo).

The dev default resolves the build host's first non-loopback IPv4 at build time and falls back to
`127.0.0.1` (VERIFIED, `app/build.gradle.kts:27-49`). On the probe build the app called
`10.203.2.52:8080` (VERIFIED, device, `b2-http-failed.txt`). This memo did not read
`local.properties` or `BuildConfig`; the address comes from the device log.

Is the production backend meant for the PHC LAN or the internet? The repo's design is the
internet: "staging | AWS Mumbai (ECS or EC2)", "prod | AWS Mumbai (ECS or EC2)", HTTPS only
(VERIFIED, `docs/backend/backend-prd.md:681-682`, `:402-403`). But the intended-use statement says
"a cloud/LAN-hosted FastAPI + XGBoost inference backend" (VERIFIED,
`docs/requirements/intended-use-statement.md:62`). UNKNOWN for the operator to rule (Q1). Two
traps either way (INFERRED): a public hostname that a PHC's DNS answers with a private address
(split-horizon, an on-site edge box) is local to Android even though the URL looks public; and a
100.64.0.0/10 address is local by the definition unless it is reached over a VPN interface
(the build host here has a tailscale0 100.x address, VERIFIED `ip -4 addr`).

## 3. Measured failure signatures

Build: `assembleDevDebug` at `1ce3b4a` (VERIFIED, `build-sha.txt`, `build.log` EXIT=0), installed
with `adb install -r`, targetSdk 37 (VERIFIED, `dumpsys package`). Setup: Pixel on Wi-Fi
10.203.7.114/20, backend container on the laptop at 10.203.2.52:8080, same /20 (VERIFIED,
`cmd wifi status`, `ip addr`). Both NEARBY_DEVICES members the app declares were revoked:
`ACCESS_LOCAL_NETWORK granted=false`, `BLUETOOTH_CONNECT granted=false` (VERIFIED,
`perms-revoked.txt`). Controls: as the shell uid the phone reaches the backend (`nc -z` rc=0) and
pings the laptop (VERIFIED, `raw-socket-controls.txt`); as the app uid (`run-as`) the same connect
times out, and after the grant is restored it succeeds, rc=0 (VERIFIED, `raw-socket-lan.txt`,
`raw-socket-after-grant.txt`). So the failures below are the permission, not the network.

3.1 (a) Login to the LAN backend.
- Exception: `java.net.SocketTimeoutException: failed to connect to /10.203.2.52 (port 8080) from
  /10.203.7.114 (port 53786) after 10000ms` on `POST /api/v1/auth/login` (VERIFIED, device,
  `a1-login-http.txt`, 12:21:53 to 12:22:03).
- Screen: the login form, with this text under the PIN field, verbatim: "failed to connect to
  /10.203.2.52 (port 8080) from /10.203.7.114 (port 53786) after 10000ms" (VERIFIED, device,
  `a1-login-denied.txt`). Path: `RetrofitAuthService.kt:48-49` puts `e.message` in the failure,
  `BackendAuthSession.kt:50` passes it on, `LoginViewModel.kt:99` shows it as the error. The
  screenshot is black (`a1-login-denied.png`, INFERRED secure window on the login screen); the
  text came from the accessibility tree.
- No permission prompt appeared at any point (VERIFIED, device).
- The probe signed out first. Sign-out took about 12 s and the backend calls in that window all
  failed with the same connect timeout (VERIFIED, device, logcat 12:21:14 to 12:21:18). Sign-out is
  best effort by design (`BackendAuthSession.kt:71`), so under denial the server-side refresh
  chain is not revoked (INFERRED: the failing call in that window is the logout; the grep kept
  only the failure lines).

3.2 (b) Background sync drain.
- Exception, three consecutive WorkManager runs of `SyncPushWorker` with the app open:
  `java.net.SocketTimeoutException: failed to connect to /10.203.2.52 (port 8080) from
  /10.203.7.114 (port 57996) after 10000ms`, then ports 39236 and 58300, each followed by
  `Worker result RETRY` (VERIFIED, device, `b2-http-failed.txt`).
- Exception, a cold run (app process killed, job forced with
  `cmd jobscheduler run -f -n androidx.work.systemjobscheduler`, process started as
  `bound-service`): `java.net.ConnectException: Failed to connect to /10.203.2.52:8080` after
  about 4.9 s, then `Worker result RETRY` (VERIFIED, device, `b3-cold-background.txt`,
  `b3-cold-context.txt`). The cause chain is not in the log. So the same denial produced two
  different exception classes with two different timings. This alone rules out classifying by
  exception.
- Constraint: the job's CONNECTIVITY constraint stayed satisfied while denied (VERIFIED, device,
  `b3-jobs.txt`), so WorkManager keeps retrying on backoff for as long as the denial lasts.
- Drain-failure kind: `IOException` becomes `SyncPushResult.Failure(code = null)`
  (`RetrofitSyncPushService.kt:26-27`), which `drainFailureFor` maps to `NO_CONNECTION`
  (`DrainOutcomeStore.kt:34-36`). VERIFIED by code and by the caption below.
- Home, after Sync now: "Syncing…" / "Sending…", then within about 8 s "Could not send: could not
  reach the server. 14 waiting." (VERIFIED, device, `b1-syncnow.txt`), which is
  `sync_caption_could_not_send_no_connection` (`strings.xml:146`, `SyncCaptionCopy.kt:22`). The
  top bar said "Online" throughout (VERIFIED, device), because `AndroidNetworkMonitor` reports
  `NET_CAPABILITY_INTERNET` (`AndroidNetworkMonitor.kt:24-52`). The Sync now snackbar text was not
  captured; by code it is `sync_now_failed_no_connection`, "Could not send: could not reach the
  server." (`strings.xml:180`, `SyncCaptionCopy.kt:51`), INFERRED for this run.
- After a force-stop the caption fell back to "7 records waiting to send." (VERIFIED, device),
  because the last failure is kept in memory only (`DrainOutcomeStore.kt:10-25`). The 7 versus 14
  is records versus records plus activity-log rows (`SyncCaption.kt:56`), not a new defect.

3.3 (c) SpO2 over Wi-Fi to `kernelhub1.local`.
- Start with the permission revoked shows the system dialog "Allow SaMDApp to find, connect to and
  determine the relative position of nearby devices?" with Allow / Don't allow (VERIFIED, device,
  `c1-start-prompt.txt`, `.png`). This is the existing request at `CompounderScreen.kt:289-292`.
- "Don't allow" shows, under Start and Stop, verbatim: "Local network access is off for this app.
  Allow it in system settings to reach the device gateway." (VERIFIED, device, `c2-denied-copy.png`;
  `strings.xml:490`, set by `CompounderViewModel.kt:569-571`). The permission controller logged
  `permission=android.permission.ACCESS_LOCAL_NETWORK ... result=7` (VERIFIED, `c2-logcat.txt`).
- A second Start shows the same copy at once with no dialog (VERIFIED, `c3-second-start.txt`).
- Name resolution and connect while denied: NOT MEASURED, and not reachable through the app. Start
  always requests first, so a denied worker never reaches `NsdGatewayDns` or a socket; and
  revoking while the app runs kills it (1.10). The only way in would be a code change or a
  debugger. kernelhub1 was also powered off today (VERIFIED, `avahi-resolve` timeout from the
  laptop), so a granted-but-unreachable control could not be run either; and the app was signed
  out by then. By the docs, `.local` resolution needs the permission (VERIFIED, 1.2), and the code
  would map whatever `IOException` came back to `RejectReason.UNREACHABLE`
  (`PiGatewayVitalsSource.kt:164-165`), giving "Cannot reach the device gateway. Check it is
  powered on and on the same Wi-Fi." (`strings.xml:489`) (INFERRED). That copy would be wrong
  only if the permission could be lost without a process kill, which 1.10 says it cannot.

Grants restored afterwards: `ACCESS_LOCAL_NETWORK granted=true` with the original flags,
`BLUETOOTH_CONNECT granted=true, USER_SET` as before (VERIFIED, `perms-restored.txt`). The app is
left SIGNED OUT on the Pixel; the operator has to sign in again. The probe never sent a PIN to the
server: the one sign-in attempt used a dummy PIN while the socket was blocked.

Evidence hygiene: this dev build has BODY network logging on (VERIFIED, device: the logcat carried
an `Authorization: Bearer` header and request bodies). The token and bodies were redacted from the
evidence files before they were kept; a grep for `eyJ` and for a PIN field over the evidence finds
nothing (VERIFIED). See Q9.

## 4. Truthfulness analysis

4.1 Login. Current copy: the raw `SocketTimeoutException` message. Not true in the sense that
matters: it blames a timeout to an address when the cause is a setting the worker can change, it
gives no next step, and it puts LAN IPs and ports on a clinical worker's screen. No #76 kind
applies (login is not a drain). Note that the raw-message defect is not specific to F1: every
`IOException` at login reaches the screen the same way (`RetrofitAuthService.kt:48-49`,
`LoginViewModel.kt:99`). Step 2 has to give at least this case a string resource (Q4 asks whether
to fix the general case in the same PR).

4.2 Sync. Today: `NO_CONNECTION`, "Could not send: could not reach the server." That is literally
true and actionably false: the server is up and reachable by the phone, the app is blocked, and
the next step (allow Nearby devices) is nowhere. Nothing else fits: `SIGN_IN`, `SERVER_REFUSED`
and `LOCAL_STORE` are each wrong. A new kind is needed: `LOCAL_NETWORK_NOT_ALLOWED`. The "Online"
label next to it is true (the phone has a network) and is left alone.

4.3 Wi-Fi hub. The existing deny copy is true and names the fix (VERIFIED, 3.3). The one gap is
"in system settings": on this device the setting is labelled "Nearby devices" (VERIFIED, prompt
text 3.3; Settings path "Permissions > Nearby devices" on the local-network-permission page), so
the copy should name it.

4.4 Side finding, kernel. `classifyKernelFailure` maps any `SocketTimeoutException` to `TIMEOUT`
(`KernelApiResult.kt:91-92`) with the comment "only a SocketTimeoutException proves the connection
was actually established first" (`KernelApiResult.kt:94-96`), and `TIMEOUT` reads "The connection
was made but no answer came back. Try again now." (`strings.xml:22-23`). The measured denial
throws `SocketTimeoutException: failed to connect ... after 10000ms` (VERIFIED, 3.1, 3.2): no
connection was made. OkHttp raises a connect timeout as a `SocketTimeoutException` (VERIFIED, by
that message). So the kernel copy is false for every connect timeout, permission or not, including
a powered-off backend. Recommend filing it (Q6); the permission gate in section 5 removes the F1
instance of it.

4.5 Side finding, SLM. The read-back refusal says "Try the read-back again when this phone is back
on Wi-Fi or mobile data." (`strings.xml:376`). Under denial the phone is on Wi-Fi, so that is
false (INFERRED: `RemoteSlmEngine` maps the `IOException` to its unreachable refusal,
`RemoteSlmEngine.kt:191`; not measured).

4.6 Telling a denial apart from a real failure. Two approaches.

- Classify the exception. Rejected. The same denial produced `SocketTimeoutException` after 10 s
  and `ConnectException` after 4.9 s (VERIFIED, 3.1, 3.2); a powered-off host gives the same
  classes; the docs promise only "typically" a timeout or EPERM (1.4); and the repo already rules
  out branching on exception text (`LocalNetworkFailure.kt:19-21`).
- Check the permission, before the call and when classifying. Recommended. The inputs are
  `checkSelfPermission(ACCESS_LOCAL_NETWORK)`, `SDK_INT >= CINNAMON_BUN`, and whether the target
  host is local. If the target is local, enforcement applies and the permission is not granted,
  the result is `LOCAL_NETWORK_NOT_ALLOWED` and the call is not made at all (no 10 s wait, no
  misleading exception). Otherwise the call runs and classifies as today.

Why it is reliable: a revocation kills the process (VERIFIED, 1.10), so the permission cannot go
from granted to denied during a call; the state read at classification time is the state the
socket saw. Failure modes, each INFERRED:
- A grant that lands during an in-flight call (granting does not kill the process) can leave that
  one call classified as a plain network failure. Harmless: the next attempt succeeds.
- The host test must be right. For the dev backend the host is a literal IPv4 (section 2), so a
  pure classifier over the configured host is exact. For a hostname that resolves to a private
  address (split-horizon, Q1) a literal-host test says "public" and the gate never fires; the
  call then fails with a timeout classed `NO_CONNECTION`, which is today's behaviour, not a
  regression. The upgrade, only if Q1 says LAN-hosted prod, is an OkHttp `EventListener` that
  classifies the resolved `InetSocketAddress` at `connectStart`.
- A vendor toggle outside the Android model (the iQOO/vivo case) is invisible to
  `checkSelfPermission`; the existing third state `UNREACHABLE_OR_BLOCKED` covers it below API 37
  and stays (`LocalNetworkFailure.kt:23-30`).
- Loopback, the emulator's 10.0.2.2 and VPN routes: loopback measured exempt (1.3); 10.0.2.2 is
  in 10/8 so a pure classifier calls it local. On an emulator that is the right answer only if the
  emulator enforces; INFERRED harmless, because requesting a grantable permission costs one tap.

Reuse: `classifyLocalNetworkFailure` (`LocalNetworkFailure.kt:31-42`) already has the right shape
(permission and SDK, never the exception). The host test is new; `isLocalDevHost`
(`DevServerConfig.kt:34-41`) is close but lives in `src/dev/`, omits 169.254/16, 100.64/10,
IPv6 and multicast, and treats `localhost`/127.0.0.1 as local, the opposite of the measured
exemption. Step 2 should add one pure `isLocalNetworkHost` in `domain/connectivity` that follows
1.2 and excludes loopback, and leave `isLocalDevHost` alone (it answers a different question: which
hosts the dev rewrite may target).

## 5. Request design

5.1 When to request. Only when the target is local and `SDK_INT >= CINNAMON_BUN`:
- Login: before the Sign in call, if `isLocalNetworkHost(BACKEND_BASE_URL host)` (in dev, the
  active URL from `DevServerConfig`). Staging and prod hosts are public, so they never prompt
  (VERIFIED for the shipped values, `app/build.gradle.kts:193,200`).
- Wi-Fi hub Start: keep the existing request (`CompounderScreen.kt:289-292`). One refinement: it
  currently requests for every instrument, including BP over BLE. Because the group is shared that
  costs nothing on a grant, but a "Don't allow" there also blocks a BLE-only Start with Wi-Fi copy
  (INFERRED from the code: `onLocalNetworkPermissionDenied` sets
  `acq_reject_permission_denied_wifi` regardless of instrument). Request only when the selected
  instrument routes over Wi-Fi.
- Not at app start and not from a worker (a worker cannot prompt).

5.2 Rationale. The system dialog talks about "nearby devices" and "relative position" (VERIFIED,
3.3), which a worker signing in to a server has no reason to expect. Show an in-app rationale
first, in `strings.xml`, with no promise of an outcome and no "internet" wording. Draft:
- `lnp_rationale_title`: "Allow this phone to reach the clinic server"
- `lnp_rationale_body`: "In this setup the clinic server is on the local network. Android lists
  that under Nearby devices. On the next screen, choose Allow to sign in and send records."
- Buttons: "Continue" (launches the request) and "Not now".
These avoid every banned pattern in `UserFacingPromiseCopyTest.kt` ("automatically", "will
review", "syncs when", and the rest). Wording is Q5.

5.3 Denied. Login stays on the form with
- `lnp_denied_login`: "Cannot sign in: this app is not allowed to use the local network. Allow
  Nearby devices for this app in Settings, then sign in again."
- and an "Open settings" button that fires `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` for the
  package. Same button on the hub copy, whose text becomes "... Allow Nearby devices for this app
  in Settings ...".

5.4 Permanently denied. After one "Don't allow" the Pixel marked the permission `USER_FIXED` and
stopped prompting (VERIFIED, 1.6). So: never rely on the counter reset; treat
`shouldShowRequestPermissionRationale == false` after a denial as permanent and lead with the
Settings button. If the platform does grant another chance, the request simply shows a dialog
again, which is fine.

5.5 Background worker. A worker cannot prompt. The drain checks the gate first and, when blocked,
records `DrainFailure.LOCAL_NETWORK_NOT_ALLOWED` without opening a socket, and returns retry so the
queue is kept. Home shows
- `sync_caption_could_not_send_local_network`: "Could not send: this app is not allowed to use the
  local network. Allow Nearby devices for this app in Settings. %1$d waiting."
- `sync_now_failed_local_network`: "Could not send: this app is not allowed to use the local
  network."
Because the outcome store is memory-only (`DrainOutcomeStore.kt:10-25`) and a revocation kills the
process, the caption after a cold start reads the plain "waiting" text until the next drain. That
is still true. Optional and cheap: Home can compute the gate directly (permission plus host) instead
of waiting for a drain; recommend it, so the caption is right on first frame. When the grant comes
back (the app resumes and the check passes), enqueue one sync, because nothing else wakes the
backoff early (INFERRED: WorkManager does not observe permissions; the CONNECTIVITY constraint was
already satisfied while denied, VERIFIED 3.2).

5.6 Process death on revocation. Verified (1.10). After the kill the system restarted
MainActivity, and the app came back with its "Resume in-progress consultation?" dialog (VERIFIED,
device). That is H-26's restore path; F1 adds a new trigger for it, not a new behaviour. The new
login rationale state must survive this too (it is on the login screen, which has no clinical
state, so INFERRED low risk).

5.7 Ordering with BLUETOOTH_CONNECT. Same group (VERIFIED, 1.5; PR-B2 P14 on this Pixel).
- Local first, then BLE: granting at login means the later BLE request is granted with no dialog
  (INFERRED from the docs; P14 saw the converse).
- BLE first, then local: same, the login request is granted with no dialog (INFERRED).
- A "Don't allow" on one: whether it marks the other `USER_FIXED` is not measured (Q8, device row
  D7 in section 7).
Consequence: the rationale screen may precede a request that the system then grants silently.
Show the rationale only when `checkSelfPermission` is denied and no group member is granted, or
accept one rationale screen that is followed by no dialog (Q5).

## 6. Options

A. Runtime request as designed in section 5. RECOMMENDED.
- Fixes F1 on the real path (login and sync in dev) and keeps the hub path.
- Staging and prod, with public hosts, never prompt and pay nothing (VERIFIED host values).
- Second-order effects: a fourth `DrainFailure` kind means two exhaustive `when`s grow
  (`SyncCaptionCopy.kt:21-26,50-55`) and their tests; the gate short-circuits the 10 s connect
  timeout for denied local calls, so the kernel TIMEOUT mislabel (4.4) stops firing in this case;
  the dev worker sees one extra screen on a fresh install; a hostname that resolves to a private
  address is not covered until the `EventListener` upgrade (4.6).

B. Lower targetSdk to 36 as a stopgap.
- History: `targetSdk = 37` and `compileSdk release(37)` have been there since the first commit,
  `a587e06` on 2026-07-14 (VERIFIED, `git log -S "targetSdk = 37"` and `-S "release(37)"`).
- Play: new apps and updates must target API 36 or higher from 2026-08-31 (extension to
  2026-11-01) (VERIFIED, https://support.google.com/googleplay/android-developer/answer/11926878
  and https://developer.android.com/google/play/requirements/target-sdk). So 36 is accepted today.
  The policy moves yearly to "within one year of the latest major release" (VERIFIED,
  https://support.google.com/googleplay/android-developer/answer/16561298), so 37 becomes
  mandatory at the next yearly step (INFERRED: about August 2027). Private apps restricted to one
  organization are exempt (VERIFIED, target-sdk page).
- Sideload or MDM: no target floor that matters here (INFERRED). An MDM can pre-grant the
  permission with `setPermissionGrantState()` (VERIFIED,
  https://developer.android.com/work/versions/android-17), which helps option A too.
- Costs: it drops every other API 37 behaviour the app was built and tested against for three
  months, and it changes nothing about the copy defects (4.1, 4.4). It defers F1, it does not fix
  it. Not recommended, except as an emergency rollback if a pilot is blocked before step 2 lands.

C. System picker for the Wi-Fi hub (`FLAG_SHOW_PICKER`).
- Covers only E7. Login and sync (the actual failures) are untouched, so A is needed anyway.
- The hub path already works with a request. A picker per session or per lease (duration not
  stated, 1.7) adds a dialog in front of a patient and changes `NsdGatewayDns`, which is dev-only.
- Not recommended now. Worth revisiting if the hub path ever ships beyond dev, because a picker
  avoids the broad permission.

## 7. Test plan

JVM (host), each with a mutation row that must go RED:

| Test | Asserts | Mutation |
|---|---|---|
| `isLocalNetworkHost` table | 10/8, 172.16/12 edges (172.15 and 172.32 false), 192.168/16, 169.254/16, 100.64/10 edges (100.63, 100.128 false), 224.0.0.0/4, 255.255.255.255, fe80::/10, ff00::/8, `*.local` true; 127.0.0.1, ::1, `localhost`, 8.8.8.8, a public hostname false | drop the 100.64 rule; make loopback local; turn `.local` off |
| gate | `(sdk, granted, local)`: blocked only for `(>=37, false, true)` | flip `>=` to `>`; drop the `local` term |
| drain kind | a push failing with `LocalNetworkNotAllowedException` gives `LOCAL_NETWORK_NOT_ALLOWED`; no socket is opened (section 11.1 moves the gate into the client, so the push service is called and fails before connect) | map the typed cause to `NO_CONNECTION` |
| caption copy | each `DrainFailure` maps to its own string id, new kind included | map new kind to `NO_CONNECTION` string |
| login state | a blocked gate gives the denied state and no `login` call; a `code = null` failure gives a string id, not `e.message` | restore `errorMessage = result.message` |
| hub request scope | BLE-routed instrument does not request; Wi-Fi-routed does | always request |
| promise copy | existing `UserFacingPromiseCopyTest` passes with the new strings | add "automatically" to a new string |

Device, Pixel 11 Pro (API 37), dev build, mock data:
- D1 Fresh install, Allow at login: rationale, dialog, sign-in succeeds; the later BP BLE Start
  shows no dialog.
- D2 Fresh install, Don't allow at login: denied copy plus Settings button; no backend call in
  logcat; `USER_FIXED` state recorded.
- D3 Permanent deny: second Sign in shows no dialog, Settings button opens app details; grant
  there, return, sign in works.
- D4 Group revoke in Settings while signed in: process killed (expect `am_kill ... permissions
  revoked`), relaunch restores per H-26; Home caption shows the local-network text without a
  drain.
- D5 Background sync while denied: forced job opens no socket (no `--> POST` in logcat) and records
  the new kind; after a grant one sync is enqueued and drains.
- D6 BLE first then local: grant Nearby devices on a BP Start, sign out, sign in: no dialog.
- D7 Local first then BLE, and the deny cross-effect: deny at login, then BP Start: record whether
  BLUETOOTH_CONNECT prompts or is `USER_FIXED` too.
- D8 Hub path: SpO2 Start with kernelhub1 powered and the permission denied, then granted; and the
  hub-off control (granted, kernelhub1 off) to record the UnknownHost or timeout signature that
  could not be measured today (3.3).

Pre-37: one AVD at API 36 (BAKLAVA), image and AVD under `/media/sandesh/extra-ssd/`, dev build:
no rationale, no dialog at login or Start, sign-in works over the LAN (proves `PermissionAction.kt:63`
and the new gate skip below 37). Optionally a second run with
`am compat enable RESTRICT_LOCAL_NETWORK` to see the not-enforced copy path (1.11).

## 8. Risk

Draft hazard row, PROPOSED, AWAITING OPERATOR SIGN-OFF, not written to `docs/quality`. Its id is
H-46. H-30 and H-31 are reserved and H-37 to H-45 are reserved for PR-C (VERIFIED,
`docs/design/samdpi-two-hub-memo.md:1813-1829`, section 14.6, "starts at H-37 ... H-30 and H-31 are
reserved"; PR-C transcribes H-37 to H-45, `docs/design/samdpi-pr-b-client-memo.md:949`). The first
free id after that block is H-46. The earlier draft of this section said H-37, which collides.

> **PROPOSED, AWAITING OPERATOR SIGN-OFF. NOT APPROVED. Drafted 2026-10-15, F1 local-network
> permission track (`docs/design/local-network-permission-memo.md`).** H-46 | On Android 17 or
> later, with targetSdk 37, the app is not granted local network access while its backend or
> instrument hub is on the local network, so sign-in, record upload, kernel assessment and SLM
> read-back fail, and the worker is told the server cannot be reached (or shown raw exception
> text) instead of the setting that blocks it. | Harm: delayed upload of clinical records and
> delayed decision support while the worker troubleshoots the wrong cause; records stay on the
> phone longer than expected. | Controls: request the permission only when the target host is
> local (rationale, request, Settings path); a permission-state gate before every local call,
> never exception classification; a dedicated drain-failure kind and caption; string-resource
> copy instead of exception text at login; host classification and gate covered by JVM tests with
> mutation rows; device checks D1 to D8. | Residual: a public hostname that resolves to a private
> address is not gated (classified as no connection, today's behaviour) until the resolved-address
> upgrade; vendor toggles outside the Android permission model stay "unreachable or blocked".

Interactions:
- H-26 nav restore: revocation is a new process-death trigger (VERIFIED, 1.10). No new state is
  added to a clinical route, so the restore contract is unchanged (INFERRED); D4 checks it.
- #76 captions: one new `DrainFailure` kind, two new strings, two exhaustive `when`s; the
  "memory only, true when unknown" rule holds, and the optional direct gate read on Home keeps it
  true after a cold start (5.5).
- Banned-phrase contract: every new string in 5.2 to 5.5 avoids the banned patterns; the test
  reads `strings.xml` and presentation literals so it covers them with no change
  (`UserFacingPromiseCopyTest.kt`).
- Kernel TIMEOUT (4.4) and SLM read-back (4.5): pre-existing truthfulness defects exposed here,
  filed separately unless Q6 says otherwise.

## 9. Open questions for the operator

1. Is the production backend internet-hosted (AWS Mumbai, `backend-prd.md` 7.1) or can it be
   PHC-LAN-hosted (`intended-use-statement.md` h, "cloud/LAN-hosted")? If LAN is possible, step 2
   needs the resolved-address check (4.6) and the intended-use text should say which.
2. Approve option A, with B held only as an emergency rollback?
3. Scope the hub Start request to Wi-Fi-routed instruments only (5.1)?
4. Fix the raw-exception login copy for all network failures in this PR, or only the
   local-network case (4.1)?
5. Approve or edit the rationale, denied and caption wording (5.2 to 5.5), and whether the
   rationale shows when a group member is already granted (5.7).
6. File the kernel connect-timeout mislabel (4.4) and the SLM "back on Wi-Fi" copy (4.5) as
   separate findings, or fold them into step 2?
7. Should Home read the gate directly so the caption is right before any drain (5.5)?
8. OK to run D7 (deny at login, then a BLE Start) on the Pixel, which leaves the NEARBY_DEVICES
   group `USER_FIXED` until reset from Settings or by `pm`?
9. This dev build logs bearer tokens and request bodies (BODY network logging from a
   `local.properties` flag). Turn it off on the test device for the step 2 live checks, since
   sign-in bodies carry the PIN?
10. The Pixel is signed out after the probe. Sign in again before step 2, and power kernelhub1 for
    D8?

## 10. Operator rulings 2026-10-15

Q1 A LAN-hosted production backend is possible; the intended-use
   wording stays. The gate must classify resolved addresses, not only
   the URL host.
Q2 Option A approved; option B (targetSdk 36) only as emergency
   rollback.
Q3 The hub Start request applies only to Wi-Fi-routed instruments.
Q4 Login maps every IOException to string resources, never e.message.
Q5 Wording approved as drafted in memo 5.2 to 5.5. Show the rationale
   only when no NEARBY_DEVICES member is granted; if one is granted,
   request directly (the grant is silent); if the denial is permanent,
   skip the rationale and lead with Settings.
Q6 The kernel connect-timeout mislabel and the SLM "back on Wi-Fi"
   copy are filed separately, not in this PR.
Q7 Home reads the gate directly and shows the local-network caption
   only when something is waiting, with an "Open settings" action.
Q8 D7 approved, run last; reset with pm clear-permission-flags per
   permission. NEVER pm reset-permissions (it resets every app).
Q9 Network logging always redacts Authorization and Cookie headers
   and never logs bodies on /auth/ paths, in its own commit.
Q10 (new) Refresh semantics: a token refresh that fails with
   LocalNetworkNotAllowedException or any other IOException means
   "cannot refresh now", never "session invalid". Only a definitive
   server answer (401 or invalid_grant on the refresh endpoint) may end
   the session. The user must never be sent to login because of the
   permission.
Hazard ID: H-37 to H-45 are reserved for PR-C. Find where the
reservation is recorded (cite it); the F1 row takes the next ID after
that block. Fix memo section 8 to match.

Addendum, same date (overrides the above where they conflict):

E1 The enforcement interceptor is an application interceptor (`addInterceptor`), never a network
   interceptor. The scan guard asserts `addInterceptor` specifically, with a positive control that
   fails on `addNetworkInterceptor`.
E2 Verify in the OkHttp sources what happens to an IOException thrown from `Dns.lookup` inside
   route selection and retry recovery; the caller must always receive
   `LocalNetworkNotAllowedException` itself. Test at the Retrofit call site with
   `retryOnConnectionFailure` true and false, asserting the exact type and zero connects.
E3 Record how refresh failures are classified today before changing them; state whether current
   behaviour could explain the next-morning forced logout. Fix nothing beyond Q10.
E4 Enumerate every network path outside the app's OkHttp clients; list an ungated local path
   under YOUR CALL, do not gate it in this PR.
E5 Confirm `Build.VERSION_CODES.CINNAMON_BUN` and `Manifest.permission.ACCESS_LOCAL_NETWORK` in the
   compileSdk jar before using them.
E8 (Q11) The Q9 redaction commit also suppresses request and response bodies on the SLM read-back
   path (read-back prompts carry patient data; closes audit finding A-02 for the remote engine).

Hazard id resolved in section 8: H-46.

Second batch, 2026-10-15:

Q10a (supersedes the 403 part of Q10): a refresh ends the session only on 401, invalid_grant, or
   403 whose app error code is 1006 (account disabled) or 1007 (device mismatch). Every other 403,
   5xx, empty body, LocalNetworkNotAllowedException or other IOException keeps the session.
Q11 (= E8) SLM read-back bodies never logged.
Q12 Phase 2 adds B6: PinChangeViewModel maps every IOException to string resources, never
   e.message (same rule as Q4).
Q13 Phase 2 classifies IPv6 ULA fc00::/7 as local, with a test and mutation row; Phase 3 adds one
   device check of a ULA or documents why it cannot be produced.
Q14 Approved copy: kernel_failure_local_network_title, readback_refusal_local_network_title,
   readback_refusal_local_network_body, login_failed_no_connection, login_failed_secure_connection
   as reported at STOP L1. kernel_failure_local_network_body is HELD pending the item 3 finding.

R1 (operator ruling, 2026-10-28): kernel_failure_local_network_body is APPROVED; prod continues via the acknowledgement box. Supersedes the HELD status in Q14.

Phase 2 rulings, 2026-10-29:

P2-R1 Timing, memo 5.1 stands. Login checks before the Sign in call, after the biometric step. The
   check calls the gate's single host decision function, blocksHost(host): the interceptor's
   expression gate.blocks(isLocalNetworkHost(host)) extracted into one function that the
   interceptor, login and Home (P2-R5) all call. Login applies it to the active backend URL host
   (DevServerConfig's active URL in dev). No other copy of that expression may exist. Safety net: if
   signIn still returns LOCAL_NETWORK_NOT_ALLOWED (for example a LAN hostname that only the Dns
   wrapper's isLocalNetworkAddress check catches, or a permission revoked between check and call),
   show the 5.3/5.4 denied state; no automatic re-submit.
P2-R2 Rationale, Q5 stands.
   - Show the in-app rationale only when no NEARBY_DEVICES permission that the app declares (in the
     merged manifest of the running flavor) is granted.
   - If one is granted, launch the request directly.
   - After a denial in this session with shouldShowRequestPermissionRationale false, skip the
     rationale and lead with Open settings.
   - No persisted denial flag.
   - Read the rationale through LocalActivity.current (activity-compose 1.13.0). If it is null, show
     the rationale.
   - "Not now" returns to the form with no error and no request, unless the memo rules otherwise
     (report it).
P2-R2a The NEARBY_DEVICES member set is a Hilt Set<String> multibinding. src/main contributes
   NEARBY_WIFI_DEVICES and UWB_RANGING. src/dev contributes BLUETOOTH_SCAN, BLUETOOTH_CONNECT and
   BLUETOOTH_ADVERTISE through @ElementsIntoSet in a dev-only module. Staging and prod contribute
   nothing beyond main. The set is intersected with the running package's requested permissions;
   ACCESS_LOCAL_NETWORK never counts. No Bluetooth token may appear in src/main, src/staging or
   src/prod (G-B11b unchanged).
P2-R3 Denied, memo 5.3 stands. Every denial shows lnp_denied_login plus an Open settings button:
   Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName,
   null)). No ask-again strings.
P2-R4 The Home action is Open settings (Q7). No allow action, no request launched from Home.
P2-R5 Home reads the gate (Q7).
   - The local-network caption derives from the gate's decision for the backend host (blocksHost,
     the same function as P2-R1), never from checkSelfPermission alone. A public backend host never
     shows it, whatever the permission state.
   - It shows only when something is waiting.
   - F1-M1: the persisted OWNER_MISMATCH hold outranks it.
   - Memo 5.5: on ON_RESUME, if the gate no longer blocks the backend host and something is
     waiting, enqueue exactly one sync through the EXISTING unique work name and policy.
P2-R6 LoginUiState.
   - One sealed LoginError (ServerDetail, Worded, Refusal, Biometric) replaces errorMessage,
     errorRes and refusal.
   - LoginScreen renders it with one exhaustive when.
   - Existing tests change accessors only, expected values byte-identical. No derived get-only
     properties added for tests.
   - Report whether the PIN is ever held in rememberSaveable or SavedStateHandle; if it is, STOP.
P2-R7 The A1 docs commit also fixes the PROGRESS.md FU-1 split (move FU-1's last two lines back
   under its first line) and adds the missing blank line between the R1 paragraph and "Filed, not
   in F1:" in the memo.
P2-R8 SyncPushWorker returns Result.failure() for LOCAL_NETWORK_NOT_ALLOWED, as it does for
   OWNER_MISMATCH. Outbox rows stay PENDING. The periodic job resumes at its next period with the
   attempt count reset, so recovery after a grant is bounded by the period with no user action. The
   resume enqueue (5.5, through syncNow, REPLACE) and Sync now still wake a drain at once.
   Supersedes the "returns retry" clause of memo 5.5 because a periodic job in backoff runs at the
   backoff delay (up to 5 h), not its interval.
P2-R9 Home observes the OwnerMismatchHold (a Flow, combined into Home's state), never a one-shot
   read. While the hold is set, Home shows no local-network caption and no local-network Open
   settings, cold start included.
P2-R10 Hub Start requests ACCESS_LOCAL_NETWORK only when HubLocalNetworkNeed says so. src/main
   declares the interface (needsRequest(instrument): Boolean) and an @BindsOptionalOf for it. Only
   src/dev binds an implementation: the instrument's HubAssignment route is Wi-Fi AND
   gate.blocksHost(gateway host), with the gateway host parsed from PI_GATEWAY_BASE_URL through
   HttpUrl. Staging and prod bind nothing; an absent binding means no request. The decision is
   evaluated when Start is clicked, never cached at composition. The hub rejection
   acq_reject_permission_denied_wifi carries an Open settings button (lnp_open_settings,
   ACTION_APPLICATION_DETAILS_SETTINGS), per memo 5.3. The hub path launches the request directly;
   the login rationale (5.2) does not apply.
P2-R10a Approved copy (hub rejection): acq_reject_permission_denied_wifi = "Local network access is
   off for this app. Allow Nearby devices for this app in Settings to reach the device gateway."
P2-R11 PIN change follows Q4 and Q12. ChangePinResult.Failure carries a reason from the existing
   signInFailureReasonFor(cause), with no second classifier. Transport failures map to
   pin_change_failed_* resources, a server refusal keeps the server detail, and no path renders
   e.message. The session is kept on every failure (Q10a). The local-network case carries an Open
   settings button (lnp_open_settings, ACTION_APPLICATION_DETAILS_SETTINGS), as on login, Home and
   the hub (5.3).
P2-R11a Approved copy:
   pin_change_failed_local_network = "Cannot change PIN: this app is not allowed to use the local
     network. Allow Nearby devices for this app in Settings, then try again."
   pin_change_failed_no_connection = "Cannot change PIN: could not reach the server. Check this
     phone's connection, then try again."
   pin_change_failed_secure_connection = "Cannot change PIN: this phone could not make a safe
     connection to the server. Tell your supervisor."
Standing: F1-M1, R1, Q4, Q10a, Q12, and the facility order (local-network check, server accepts the
   PIN, facility gate, saveLogin only on CLAIM, LEGACY_CLAIM or CONTINUE; BLOCK stays BLOCK).

Filed, not in F1: single refresh coordinator PR (one @Singleton authenticator with a Mutex shared
   by backend and ABHA clients; DataStore read error distinct from missing token); ABHA fallback
   copy to strings.xml.

## 11. Step 2 design addendum

Written before any step 2 code. Supersedes sections 4.6, 5.5 (the drain gate placement) and the
drain row of section 7 where they differ. Tags as in the header.

### 11.1 Single enforcement point

One gate, two hooks, installed on every `OkHttpClient` the app builds. The gate is pure:
`isLocalNetworkBlocked(sdkInt, granted, local) = sdkInt >= CINNAMON_BUN && !granted && local`.

- Hook 1, an application interceptor (`LocalNetworkGateInterceptor`). It classifies
  `request.url.host` with `isLocalNetworkHost` and, when blocked, throws
  `LocalNetworkNotAllowedException` (an `IOException`) before `chain.proceed`. Application
  interceptors run before `RetryAndFollowUpInterceptor` and `ConnectInterceptor` (VERIFIED,
  OkHttp 4.12.0 sources, `okhttp3/internal/connection/RealCall.kt:175-186`), so no route is
  selected, no name is resolved and no socket is opened. It covers IP literals and `.local` names.
- Hook 2, a `Dns` wrapper (`LocalNetworkGateDns(delegate, gate)`). It calls the client's existing
  `Dns` (system, or `NsdGatewayDns` for the hub), drops every resolved address that
  `isLocalNetworkAddress` calls local when the gate is blocked, and throws the typed exception when
  none is left. This is the Q1 control: a public-looking hostname that resolves to a private
  address is caught before connect. A mixed answer keeps its public addresses, so a split-horizon
  name with a public fallback still works.

Why both (Q1, VERIFIED): OkHttp does not call `Dns.lookup` for an IP literal. `RouteSelector`
does `if (socketHost.canParseAsIpAddress()) listOf(InetAddress.getByName(socketHost)) else
address.dns.lookup(socketHost)` (`okhttp3/internal/connection/RouteSelector.kt:163-169`). The dev
backend is a literal IPv4 (section 2), so the `Dns` hook alone would miss it; the interceptor
alone would miss split-horizon names. Sources: `okhttp-4.12.0-sources.jar` sha256
`d91a769a4140e542cddbac4e67fcf279299614e8bfd53bd23b85e60c2861341c`, from the version catalog
`okhttp = "4.12.0"` (`gradle/libs.versions.toml:27`), extracted to
`/media/sandesh/extra-ssd/samd-lnp/okhttp-4.12.0-src/`.

Order. The dev host rewrite (`DevDynamicHostInterceptor.kt:33-48`) changes the target host, so the
gate interceptor is added after `devInterceptors` and sees the real target. `NsdGatewayDns` is never
reached for a blocked `.local` host, because hook 1 fires first; `NsdManager` discovery therefore
does not start while denied.

E2, exception surfacing (VERIFIED, sources cited, plus the call-site test below):
- Thrown from the application interceptor: `RealInterceptorChain.proceed` does not wrap it;
  `getResponseWithInterceptorChain` rethrows it through `noMoreExchanges(e)`
  (`RealCall.kt:207-209`), which only replaces it with `InterruptedIOException("timeout")` when the
  call timeout has already fired (`RealCall.kt:394-401`). The gate throws in microseconds, so no.
  The async path hands the same `IOException` to the callback (`RealCall.kt:520-525`); only a
  non-`IOException` is replaced (`RealCall.kt:527-531`).
- Thrown from `Dns.lookup`: `RouteSelector.next` propagates it, `ExchangeFinder.find` records it
  with `trackFailure` and wraps it as `RouteException(e)` (`ExchangeFinder.kt:83-89`).
  `RetryAndFollowUpInterceptor` catches the `RouteException` and calls `recover`
  (`RetryAndFollowUpInterceptor.kt:78-86`). With `retryOnConnectionFailure = false`, `recover`
  returns false at once (`:151`) and `e.firstConnectException` is thrown, which is the typed
  exception itself, unwrapped. With `true`, `isRecoverable` is true for a plain `IOException`
  (`:172-200`), then `retryAfterFailure` (`ExchangeFinder.kt:274-298`) finds no retry route
  (`otherFailureCount > 0`, `:306`), no current route selection (it is assigned only after
  `next()` returns, `ExchangeFinder.kt:205-206`), and a route selector whose single proxy index
  has already advanced (`RouteSelector.kt:121,130`), so it returns false and the same
  `firstConnectException` is thrown. With several system proxies OkHttp would retry the next proxy,
  calling the gated `Dns` again; the last attempt's exception is the typed one, so the type still
  holds (INFERRED; the test runs the single-proxy case).
- Retrofit 2.11.0 passes the OkHttp `IOException` to the suspend caller unchanged. Not verified
  from sources (no 2.11.0 sources jar in the Gradle cache); verified instead by the call-site test,
  which asserts the exact class at a Retrofit suspend call with both retry settings and zero
  `connectStart` events.

### 11.2 Client inventory

| Client | Built at | Existing Dns | Gate wiring |
|---|---|---|---|
| backend (auth, kernel, evaluate, sync, refresh) | `NetworkModule.kt:96-104` | system | interceptor after dev interceptors; `LocalNetworkGateDns(Dns.SYSTEM)` |
| ABHA | `NetworkModule.kt:124-131` | system | same |
| SLM | `SlmNetworkModule.kt:110-116`, `backendClient.newBuilder()` | inherited | inherits both hooks; adds nothing (the scan guard forbids a `.dns(` or a new builder there) |
| Wi-Fi hub (dev) | `PiGatewayNetworkModule.kt:89-96` | `NsdGatewayDns` (`:71`) | interceptor first; `LocalNetworkGateDns(nsdGatewayDns)` |
| Gemini | `GeminiNetworkModule.kt:46-52` | system | same as backend; public host, never blocks in practice |

No other `OkHttpClient.Builder()` or `OkHttpClient()` exists under `app/src` outside tests
(VERIFIED, grep).

Scan guard (`LocalNetworkGateCoverageTest`): reads every `.kt` under `app/src/{main,dev,staging,prod}`;
for every `OkHttpClient.Builder()` it takes the builder chain up to `.build()` and requires
`.addInterceptor(LocalNetworkGateInterceptor(` and `.dns(LocalNetworkGateDns(`; it fails on any
`addNetworkInterceptor(LocalNetworkGateInterceptor`, on a `.dns(` argument that is not a
`LocalNetworkGateDns`, and on a gate interceptor added before `devInterceptors`. Positive controls
run the same checker on inline snippets (a builder without the gate, one using
`addNetworkInterceptor`, one with a bare `.dns(Dns.SYSTEM)`) and assert each is rejected. Inputs:
the source tree is already a declared input of the unit-test task (`app/build.gradle.kts:382-384`),
so an edit to any client file reruns the guard.

### 11.3 Classifiers

`domain/connectivity`, pure, JVM tested:
- `isLocalNetworkAddress(InetAddress)`: IPv4 10/8, 172.16/12, 192.168/16, 169.254/16, 100.64/10,
  224/4, 255.255.255.255; IPv6 link-local fe80::/10 and multicast ff00::/8. Loopback and the
  wildcard address are not local (section 1.3). IPv4-mapped IPv6 arrives as IPv4 from
  `InetAddress`, so it is covered. ULA fc00::/7 is not listed by the definition page and is not
  classified local (INFERRED gap, YOUR CALL list).
- `isLocalNetworkHost(String)`: `*.local` true; an IPv4 or IPv6 literal goes to
  `isLocalNetworkAddress` (parsed without DNS: IPv4 by a strict dotted-quad check, IPv6 only when the
  host contains `:`); every other name false (hook 2 handles names).
- Constants (E5, VERIFIED): `javap -constants` on
  `/media/sandesh/extra-ssd/Android/Sdk/platforms/android-37.0/android.jar` (sha256
  `bf1b4387cc7ca94fc6ef684f040d9d16fbf16248e181819f020736ea2053f177`, `AndroidVersion.ApiLevel=37.0`)
  prints `public static final int CINNAMON_BUN = 37;` in `android.os.Build$VERSION_CODES` and
  `public static final java.lang.String ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK";`
  in `android.Manifest$permission`. compileSdk is 37 (`app/build.gradle.kts:86-88`). Both are
  compile-time constants, so they inline into JVM tests.

### 11.4 Consumer map

Every place an `IOException` from these clients is classified, and what the typed exception shows.

| Consumer | Classified today | Typed exception becomes | Copy |
|---|---|---|---|
| Login | `RetrofitAuthService.kt:45-50` puts `e.message` in `AuthApiResult.Failure`; `BackendAuthSession.kt:50`; `LoginViewModel.kt:98-99` | `SignInResult.Failure` with a reason; `LoginViewModel` picks a string id | `lnp_denied_login` (5.3); any other `IOException`: `login_failed_no_connection`; `SSLException`: `login_failed_secure_connection` (both new, YOUR CALL) |
| Token refresh | `TokenAuthenticator.kt:66-81`, see 11.5 | rethrown from `authenticate`, so the original call fails with it and its own consumer classifies it; session kept | that consumer's copy |
| Sync drain | `RetrofitSyncPushService.kt:24-28` (code null), `SyncOutboxDrainer.kt:210-215`, `drainFailureFor` `DrainOutcomeStore.kt:34-36` gives `NO_CONNECTION` | `SyncPushResult.Failure` carries the cause; `drainFailureFor` gives `LOCAL_NETWORK_NOT_ALLOWED`; the worker still returns retry (`SyncPushWorker.kt:32`), queue kept | `sync_caption_could_not_send_local_network`, `sync_now_failed_local_network` (5.5) |
| Kernel assess | `RetrofitKernelSource.kt:89-93` `Unreachable(e)`; `classifyKernelFailure` `KernelApiResult.kt:91-98` (`TIMEOUT` or `OFFLINE`) | new `KernelFailure.LOCAL_NETWORK_NOT_ALLOWED` (RETRY_NOW: Try again works once allowed). Added to `FALLBACK_ELIGIBLE` (`GenerateKernelReportUseCase.kt:77`) because today the same denial lands in `OFFLINE`/`TIMEOUT`, both eligible; behaviour kept. Persisted by name in Room only, never synced (`KernelReportEntity.kt:42-43`) | `kernel_failure_local_network_title`, `_body` (new, YOUR CALL) |
| Evaluate | `GenerateEvaluateReportUseCase.kt:80-86` saves the exception class name | unchanged; the marker text is cause-agnostic, "EVALUATION FAILED, no AI treatment recommendation was generated" (`ReportCanvasRenderer.kt:61-62`) | documented true fallback |
| SLM read-back | `RemoteSlmEngine.kt:191-198`, `transportErrorFor` `:287-311` gives `UNREACHABLE` | new `SlmEngineError.LOCAL_NETWORK_NOT_ALLOWED`, mapped first, to new `SlmRefusal.LOCAL_NETWORK_NOT_ALLOWED` (`engineRefusalFor`, `SlmReadbackUseCase.kt:514-524`) | `readback_refusal_local_network_title`, `_body` (new, YOUR CALL). Q6 leaves the existing UNREACHABLE copy alone |
| ABHA | `RetrofitAbhaSource.kt:124-125` (code null); `AbhaEnrolResult.kt:54-58` | unchanged: "No connection to the SaMD server. Check the network and try again." | documented fallback: literally true, not actionable. ABHA copy is inline Kotlin in `domain/abha`, not `strings.xml`; converting it is a convention change (YOUR CALL). Home's caption (Q7) names the cause meanwhile |
| Wi-Fi hub (dev) | `PiGatewayVitalsSource.kt:161-167` gives `UNREACHABLE` | `RejectReason.PERMISSION_DENIED`, matched before `IOException` | `acq_reject_permission_denied_wifi` (existing; B2 rewords it) |
| Sign-out | `BackendAuthSession.kt:71` `runCatching` | ignored, local clear proceeds | unchanged, true |
| PIN change | `RetrofitAuthService` then `PinChangeViewModel.kt:84` shows `result.message` | unchanged (Q4 names login only) | raw text remains here; YOUR CALL |
| Gemini | `GeminiBrandLookupSource.kt:63`, never throws | unchanged; public host | none shown |

### 11.5 Refresh path, evidence before change (Q10, E3)

Where it runs: OkHttp calls `TokenAuthenticator.authenticate` on a 401, from
`RetryAndFollowUpInterceptor.followUpRequest`. The refresh is a Retrofit call through
`RetrofitAuthService.refresh` (`RetrofitAuthService.kt:34-35`) on the shared backend client
(`NetworkModule.kt:153-163`), so it passes through the gate like any other call.

Today, verbatim:
- A refresh `IOException`. `RetrofitAuthService.kt:46-50`:
  `} catch (e: IOException) { return AuthApiResult.Failure(code = null, message = e.message ?: "Network error.") }`.
  Then `TokenAuthenticator.kt:74-81`: `is AuthApiResult.Failure -> { // SAMD-AUTH-1004 (reuse
  detected, chain revoked) or any other refresh failure. Clearing here is what routes the app to
  sign-in ... tokenStore.clear() null }`. So any transport failure of the refresh ends the session.
- A refresh 401. The same branch: `RetrofitAuthService.kt:57-63` builds `Failure(code =
  problem?.code, ...)` with no HTTP status kept, and `TokenAuthenticator.kt:79` clears. The backend
  answers 401 for 1002 token expired, 1003 invalid, 1004 revoked or reuse
  (`backend/core/app/errors.py:131-133`) and 403 for 1006 disabled and 1007 device mismatch
  (`:135-136`). A 5xx, an empty body and a non-problem body are cleared the same way.
- Concurrent refreshes. `TokenAuthenticator.kt:37,50-58`: a `Mutex`, and inside it, if the stored
  access token differs from the one that failed, retry with the stored one instead of refreshing.
  That makes refresh single-flight per `TokenAuthenticator` instance. The class has no scope
  annotation (`TokenAuthenticator.kt:32`), and `provideOkHttpClient` (`NetworkModule.kt:93`) and
  `provideAbhaOkHttpClient` (`:121`) each receive their own instance; SLM shares the backend one
  through `newBuilder()`. So the backend and ABHA clients do not serialise against each other.
- A missing refresh token clears too (`TokenAuthenticator.kt:60-63`), and `snapshot()` reads an
  empty store on a DataStore `IOException` (`DataStoreAuthTokenStore.kt:65-70`).

Q10 change, and only this: `AuthApiResult.Failure` gains the HTTP status and the transport cause.
In `authenticate`: a transport failure is rethrown (session kept, the original call fails with
that exception, so a blocked refresh surfaces as the local-network copy of whoever called);
a 401, or a problem code `invalid_grant`, clears the session as today; every other answer (403,
5xx, empty or unreadable body) keeps the session and returns null, so the original 401 reaches its
caller. Not changed: the mutex, the missing-token branch, the scoping.

### 11.6 Non-OkHttp egress (E4)

| Path | Where | Can reach a local address on API 37 | Gated |
|---|---|---|---|
| `NsdManager` discovery and resolve (mDNS multicast) | `NsdGatewayDns.kt:107-160`, dev only | yes, multicast 224.0.0.251 | indirectly: reached only from the hub client's `Dns`, behind hook 1 for `.local`. The Start request (`CompounderScreen.kt:289-292`) also precedes it |
| `HttpURLConnection` | `PiGatewayVitalsSource.kt:14,146-148` | no: status constants only | n/a |
| `URL.openStream`, raw `Socket`/`DatagramSocket`, `WebView`, `DownloadManager`, image loaders (Coil, Glide), Ktor, Volley, Cronet, Firebase | none in `app/src/{main,dev,staging,prod}` (VERIFIED, grep) | n/a | n/a |
| Licence links | `OpenSourceLicensesScreen.kt:138`, `ACTION_VIEW` | browser's egress, not this app's | n/a |
| BLE hub | `BleHubManager`, CDM | not IP | n/a |
| sherpa-onnx ASR (`libs/sherpa-onnx-1.13.7.aar`) | on-device | none measured (H-15 L3.2, `txDelta=0B`) | n/a |

No ungated local IP path outside the OkHttp clients.

### 11.7 Logging (Q9, E8)

The one `HttpLoggingInterceptor` is on the backend client (`NetworkModule.kt:76-86,103`) and so on
the SLM client too (`SlmNetworkModule.kt:110-116`). ABHA has none. Change: redact `Authorization`
and `Cookie` (and `Set-Cookie`, the response-side cookie) always, and select headers-only logging
for any path under `/api/v1/auth/` and for `/api/v1/slm/readback`, whatever
`ENABLE_NETWORK_LOGGING` says. Two `HttpLoggingInterceptor` instances behind one small selector, so
no shared mutable `level` is flipped per request.

### 11.8 Test rows added to section 7

| Test | Asserts | Mutation |
|---|---|---|
| interceptor | blocked local literal throws the typed exception with zero `connectStart` | proceed before the check |
| Dns wrapper | a name resolving to 10.x is blocked; a mixed answer keeps the public address; granted passes all | drop the filter |
| call site (E2) | Retrofit suspend call, literal and hostname cases, `retryOnConnectionFailure` true and false: exact class, zero connects | wrap the exception |
| consumers | each row of 11.4 maps the typed exception as stated | map it to the old kind |
| refresh (Q10) | typed exception and plain `IOException` keep the session and are rethrown; 401 clears; 503 keeps | restore clear-on-any-failure |
| scan guard | every builder gated, application interceptor, after dev interceptors; positive controls | remove the gate from one client |
| logging | Authorization, Cookie redacted; no body on `/auth/` and on read-back | drop each rule |

### 11.9 Conflicts with the rulings

None found. Every client can be gated before connect (11.1, 11.2).
