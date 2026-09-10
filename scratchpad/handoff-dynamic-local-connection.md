# Handoff: Dynamic Local Docker & Network Connection Setup

**Date:** 2026-09-10  
**Git Branch Created:** `feat/dynamic-local-connection`  
**Branched From:** `fix/sync-before-assess-v2` (contains ongoing uncommitted sync/assessment work)  

---

## 1. Problem Addressed
When the development machine switched network interfaces (from Wi-Fi `10.203.3.29` to Ethernet `10.203.2.52`), worker login failed on the phone with `ConnectException` because `BACKEND_BASE_URL` was hardcoded to the old IP. Furthermore:
- Physical devices connected over USB were not leveraging ADB reverse tunnels.
- When network connections change (Ethernet / Wi-Fi / Hotspot), the hardcoded IP broke local testing.
- When ABHA adapter mode is changed from `stub` to `live`, Docker layer caching can leak stale adapter code/wheels into the runtime container.

---

## 2. Changes Made & Files Modified

### A. Git Branch
- Created branch: **`feat/dynamic-local-connection`**
- Working tree contains the previous uncommitted work from `fix/sync-before-assess-v2` (DAO, SyncOutboxDrainer, AssessmentRunner, seed_accounts.py, etc.) untouched.

### B. Build-Time Host IP Auto-Detection
- **File:** `app/build.gradle.kts`
  - Added `resolveDevHostIp()`: scans `NetworkInterface.getNetworkInterfaces()` to find the active physical non-loopback IPv4 address (Ethernet `en*`/`eth*` or Wi-Fi `wl*`), filtering out Docker, bridges, and VPNs.
  - In `dev` product flavor: if `BACKEND_BASE_URL` in `local.properties` is missing or set to `auto`, it dynamically defaults to `http://${resolveDevHostIp()}:8080/` rather than the old hardcoded fallback `10.16.4.182:8080`.
  - Added `unitTests.isReturnDefaultValues = true` in `testOptions` so `android.util.Log` does not throw `Method not mocked` during host JVM unit tests.

### C. Runtime Multi-Host Dynamic Failover (Dev Flavor Only)
- **Files Created:**
  - `app/src/main/java/com/example/samdapp/data/remote/dev/DevServerConfig.kt`:
    - Interface `DevServerConfig` and `@Singleton class RealDevServerConfig`.
    - Manages active dev URL, persists it to `SharedPreferences` (`samd_dev_server_config`).
    - Implements fast health-check probing against `/health` with 1-second timeout across prioritized candidates:
      1. `http://127.0.0.1:8080/` (USB ADB reverse tunnel — prioritized for physical device testing)
      2. `BuildConfig.BACKEND_BASE_URL` (Host LAN IP)
      3. `http://10.0.2.2:8080/` (Android Emulator loopback)
  - `app/src/main/java/com/example/samdapp/data/remote/dev/DevDynamicHostInterceptor.kt`:
    - OkHttp `Interceptor` installed only in dev flavor (`BuildConfig.ENVIRONMENT == "dev"`).
    - Safeguards third-party requests (e.g. Google Gemini API) from being rewritten.
    - Rewrites local backend requests to active dev URL.
    - On `ConnectException` or `SocketTimeoutException`, triggers auto-failover, discovers the responding candidate, updates active URL, and retries the request transparently.
  - `app/src/dev/java/com/example/samdapp/receiver/DevServerReceiver.kt`:
    - `BroadcastReceiver` in `src/dev` registered in `app/src/dev/AndroidManifest.xml`.
    - Accepts `com.example.samdapp.dev.SET_BACKEND_URL` broadcast to update backend URL dynamically on device without app restart:
      ```bash
      adb shell am broadcast -a com.example.samdapp.dev.SET_BACKEND_URL -p com.example.samdapp.dev --es url "http://10.203.2.52:8080/"
      ```
- **Files Modified:**
  - `app/src/main/java/com/example/samdapp/di/NetworkModule.kt`:
    - Injected `DevDynamicHostInterceptor` into `provideOkHttpClient` and `provideAbhaOkHttpClient` (guarded by `BuildConfig.ENVIRONMENT == "dev"`).
    - Bound `DevServerConfig` to `RealDevServerConfig` in `NetworkModule.Bindings`.
  - `app/src/dev/AndroidManifest.xml`:
    - Registered `DevServerReceiver`.

### D. Unit Tests
- **File Created:** `app/src/test/java/com/example/samdapp/data/remote/dev/DevDynamicHostInterceptorTest.kt`
  - Tests:
    1. Third-party requests (Gemini API) are passed through untouched.
    2. Local backend requests are rewritten to the active dev URL.
    3. `ConnectException` triggers failover probe and automatically retries the request with the recovered URL.
- **Verification:** `./gradlew testDevDebugUnitTest` passes 100% (`BUILD SUCCESSFUL in 3s`).

### E. Developer CLI Tooling
- **File Created:** `tools/dev-connect.sh` (executable `chmod +x`):
  - Automatically:
    1. Detects host's active LAN IP (`10.203.2.52`).
    2. Verifies Docker backend (`8080`) and ML classifier (`8000`) health.
    3. Configures ADB reverse tunnels (`tcp:8080`, `tcp:8000`, `tcp:8090`).
    4. Updates `local.properties` with active IP.
    5. Broadcasts the active IP to the attached Android device.
  - Usage:
    ```bash
    ./tools/dev-connect.sh
    ```

### F. Backend & ABHA Documentation
- **File Modified:** `backend/README.md`
  - Added subsection `### Switching ABHA / ABDM Mode (Stub vs. Live)` detailing that when toggling `ABDM_MODE` between `stub` and `live`, Docker must be rebuilt from scratch without cache:
    ```bash
    cd backend
    docker compose down
    docker compose build --no-cache api
    docker compose up -d --force-recreate
    ```
  - Added healthcheck verification command (`curl -s http://localhost:8080/health | grep abdm_mode`).

### G. Local Configuration
- **File Modified:** `local.properties`
  - Updated to current active Ethernet IP:
    ```properties
    BACKEND_BASE_URL=http://10.203.2.52:8080/
    KERNEL_BASE_URL=http://10.203.2.52:8000/
    ```

---

## 3. Current System Status

- **Host IP:** `10.203.2.52` (interface `enp0s31f6` - Ethernet).
- **Backend API:** Container `backend-api-1` running on port 8080 (`/health` returns `200 OK`).
- **Classifier ML:** Container `samd-classifier` running on port 8000 (`/health` returns `200 OK`).
- **Active ADB Reverse Tunnels:**
  - `tcp:8080 tcp:8080` (Backend API)
  - `tcp:8000 tcp:8000` (Classifier)
  - `tcp:8090 tcp:8090` (Pi Instrument Gateway / local emulator)
- **On-Device Status:**
  - `devDebug` APK installed on device `10BE3A09C700046` (`I2302 - 16`).
  - Verified on-device auto-failover: `shared_prefs/samd_dev_server_config.xml` actively set to `http://127.0.0.1:8080/`.
  - Worker login verified functioning.

---

## 4. Raspberry Pi Instrument Gateway Notes
- Physical Pi was queried: `kernel-hub.local` and previous IP `10.203.2.76` are unreachable because the Pi's Wi-Fi network is down and Ethernet is unplugged.
- To run instrument acquisition without the physical hardware:
  - Local emulator is located in `/media/sandesh/oneTB_SSD/SaMDPi/`. Run `./deploy/run_emulator.sh --port 8090`. Port 8090 is already included in ADB reverse tunnels.
  - Or switch `bindVitalsSource` in `app/src/dev/java/com/example/samdapp/di/DevClinicalMockModule.kt` to `MockVitalsSource` for completely hardware-free mock data.

---

## 5. Next Actions for Continuing Work
1. The developer's ongoing assessment & sync work remains in the working tree on `feat/dynamic-local-connection` (ready for commit/PR when complete).
2. Whenever switching networks, run `./tools/dev-connect.sh` to update tunnels and broadcast the new IP.
