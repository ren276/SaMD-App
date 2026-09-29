#!/usr/bin/env bash
# ==============================================================================
# dev-connect.sh — Dynamic Dev Network & Local Docker Connector for SaMDApp
#
# Automatically:
# 1. Detects the host machine's active physical LAN IP (Ethernet / Wi-Fi).
# 2. Checks local Docker backend (port 8080) and classifier (port 8000).
# 3. Sets up ADB reverse tunnels (tcp:8080, tcp:8000, tcp:8090) for connected devices.
# 4. Updates local.properties with the active host IP.
# ==============================================================================

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "=== SaMD Dev Network & Docker Connector ==="

# 1. Detect host active IP
detect_host_ip() {
    # Source address of the default route: the interface traffic to the LAN actually leaves by,
    # so docker0, br-*, virbr0 and VPN interfaces can never be picked.
    local ip
    ip=$(ip -4 route get 1.1.1.1 2>/dev/null | awk '{for (i = 1; i <= NF; i++) if ($i == "src") print $(i + 1)}' | head -n 1) || ip=""
    if [ -z "$ip" ]; then
        echo "WARN: no default route found, falling back to 127.0.0.1" >&2
        ip="127.0.0.1"
    fi
    echo "$ip"
}

HOST_IP=$(detect_host_ip)
echo "→ Detected Host IP: $HOST_IP"

# 2. Check local Docker backend services
echo "→ Checking Docker services..."
BACKEND_OK=0
if curl -s -f -m 2 "http://127.0.0.1:8080/health" > /dev/null 2>&1; then
    echo "  ✓ Backend API (8080): ONLINE"
    BACKEND_OK=1
else
    echo "  ✗ Backend API (8080): OFFLINE or NOT RESPONDING"
fi

CLASSIFIER_OK=0
if curl -s -f -m 2 "http://127.0.0.1:8000/health" > /dev/null 2>&1; then
    echo "  ✓ Classifier ML (8000): ONLINE"
    CLASSIFIER_OK=1
else
    echo "  ✗ Classifier ML (8000): OFFLINE or NOT RESPONDING"
fi

GATEWAY_OK=0
if curl -s -f -m 2 "http://127.0.0.1:8090/health" > /dev/null 2>&1; then
    echo "  ✓ Pi Gateway/Emulator (8090): ONLINE (Docker / Localhost)"
    GATEWAY_OK=1
elif curl -s -f -m 2 "http://kernel-hub.local:8090/health" > /dev/null 2>&1; then
    echo "  ✓ Pi Gateway/Emulator (8090): ONLINE (Physical Pi @ kernel-hub.local)"
    GATEWAY_OK=1
else
    echo "  ✗ Pi Gateway/Emulator (8090): OFFLINE"
fi

if [ $BACKEND_OK -eq 0 ] && [ $CLASSIFIER_OK -eq 0 ]; then
    echo "  ⚠ Warning: Neither backend nor classifier is responding on localhost."
    echo "    Make sure docker compose is running: cd backend && docker compose up -d"
fi

# 3. Update local.properties
LOCAL_PROPS="$REPO_ROOT/local.properties"
if [ -f "$LOCAL_PROPS" ]; then
    # Update or append BACKEND_BASE_URL
    if grep -q "^BACKEND_BASE_URL=" "$LOCAL_PROPS"; then
        sed -i "s|^BACKEND_BASE_URL=.*|BACKEND_BASE_URL=http://$HOST_IP:8080/|" "$LOCAL_PROPS"
    else
        echo "BACKEND_BASE_URL=http://$HOST_IP:8080/" >> "$LOCAL_PROPS"
    fi

    # Update or append KERNEL_BASE_URL
    if grep -q "^KERNEL_BASE_URL=" "$LOCAL_PROPS"; then
        sed -i "s|^KERNEL_BASE_URL=.*|KERNEL_BASE_URL=http://$HOST_IP:8000/|" "$LOCAL_PROPS"
    else
        echo "KERNEL_BASE_URL=http://$HOST_IP:8000/" >> "$LOCAL_PROPS"
    fi
    echo "→ Updated local.properties with host IP: http://$HOST_IP:8080/"
    echo "URLs written to local.properties. Reinstall the dev build to apply. adb reverse tunnels cover localhost:8080/8000/8090 meanwhile."
else
    echo "WARN: local.properties not found at $LOCAL_PROPS, no URLs written" >&2
fi

# 4. Configure ADB reverse if a device is connected
if command -v adb > /dev/null 2>&1; then
    DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | awk '{print $1}')
    if [ -n "$DEVICES" ]; then
        echo "→ Configuring ADB for attached devices: $DEVICES"
        for dev in $DEVICES; do
            echo "  Configuring reverse tunnels on device $dev..."
            adb -s "$dev" reverse tcp:8080 tcp:8080 || echo "WARN: adb reverse tcp:8080 failed on $dev" >&2
            adb -s "$dev" reverse tcp:8000 tcp:8000 || echo "WARN: adb reverse tcp:8000 failed on $dev" >&2
            adb -s "$dev" reverse tcp:8090 tcp:8090 || echo "WARN: adb reverse tcp:8090 failed on $dev" >&2
        done
        echo "  ✓ Reverse tunnels active (phone can access host via 127.0.0.1 or $HOST_IP)"
    else
        echo "→ No ADB devices currently attached via USB."
    fi
else
    echo "→ adb command not found in PATH; skipping ADB reverse setup."
fi

echo "=== Connected and Ready ==="
