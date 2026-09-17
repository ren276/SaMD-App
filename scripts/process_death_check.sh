#!/usr/bin/env bash
# H-18, Build 3b, Option A: process-death regression check.
#
# Verifies the actual claim Option A makes (scratchpad/capture-process-death-memo.md section 1):
# with the CameraX viewfinder open, this process is never a low-memory-killer candidate, because
# it never leaves the foreground. Phase A measured the OLD TakePicture hand-off backgrounding the
# app (oom_score_adj 0 -> gone) the instant the external camera activity took focus, and separately
# measured that `am kill` against a FOREGROUND process is refused outright - Option A's whole fix
# is to keep the process foreground for the entire capture loop, so this script asserts both
# halves: oom_score_adj stays 0 for the sampled window, and an `am kill` attempt mid-window is
# refused rather than tearing the process down.
#
# This script does NOT drive the app to the document-capture screen itself: reaching it needs a
# signed-in session and the Consent -> Compounder -> ... -> Consultation flow, which needs the
# backend up and is out of scope for a repro script. Get the app onto the capture screen with the
# viewfinder live BEFORE running this.
#
# Usage: scripts/process_death_check.sh [serial] [duration_seconds]
#   serial            adb device serial. Defaults to the first attached device.
#   duration_seconds  how long to sample oom_score_adj for. Default 15.

set -u

SERIAL="${1:-}"
DURATION="${2:-15}"
PACKAGE="com.example.samdapp.dev"

if [ -z "$SERIAL" ]; then
    SERIAL=$(adb devices | awk 'NR>1 && $2=="device" {print $1; exit}')
fi
if [ -z "$SERIAL" ]; then
    echo "STOP: adb devices is empty. No device to run against." >&2
    exit 1
fi

ADB="adb -s $SERIAL"

# Reset unconditionally on every exit path (pass, fail, or Ctrl-C) - a script that only resets on
# its own success path is exactly the trap that leaves a handset in developer-hostile state after
# an aborted run.
cleanup() {
    $ADB shell settings put global always_finish_activities 0 >/dev/null 2>&1
    echo "always_finish_activities reset to 0 (read back: $($ADB shell settings get global always_finish_activities))"
}
trap cleanup EXIT

echo "Device: $SERIAL"
echo "always_finish_activities before this script touched anything: $($ADB shell settings get global always_finish_activities)"

PID=$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')
if [ -z "$PID" ]; then
    echo "STOP: $PACKAGE is not running. Launch it and open the document-capture screen (with the" >&2
    echo "camera viewfinder visible) before running this script." >&2
    exit 1
fi
echo "PID: $PID"

TOP=$($ADB shell dumpsys activity activities 2>/dev/null | grep -m1 "topResumedActivity")
echo "Top resumed activity: $TOP"
if ! echo "$TOP" | grep -q "$PACKAGE"; then
    echo "WARNING: $PACKAGE does not appear to be the foreground app right now. This check is only" >&2
    echo "meaningful with the capture screen's viewfinder open and visible." >&2
fi

FAIL=0
HALFWAY=$((DURATION / 2))
if [ "$HALFWAY" -lt 1 ]; then HALFWAY=1; fi

echo "Sampling oom_score_adj every 1s for ${DURATION}s..."
for i in $(seq 1 "$DURATION"); do
    ADJ=$($ADB shell cat /proc/"$PID"/oom_score_adj 2>/dev/null | tr -d '\r')
    if [ -z "$ADJ" ]; then
        echo "t=${i}s oom_score_adj=<process gone>"
        echo "FAIL: the process died during the capture window (t=${i}s)." >&2
        FAIL=1
        break
    fi
    echo "t=${i}s oom_score_adj=$ADJ"
    if [ "$ADJ" -ne 0 ]; then
        echo "FAIL: oom_score_adj=$ADJ (expected 0, foreground) at t=${i}s - the process left the foreground." >&2
        FAIL=1
        break
    fi

    if [ "$i" -eq "$HALFWAY" ]; then
        echo "t=${i}s: attempting 'am kill' mid-capture (expected to be refused - the process is foreground)"
        $ADB shell am kill "$PACKAGE" >/dev/null 2>&1
        sleep 1
        PID_AFTER=$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')
        if [ "$PID_AFTER" != "$PID" ]; then
            echo "FAIL: 'am kill' succeeded against the foreground process (pid $PID -> '$PID_AFTER')." >&2
            echo "The capture screen is not actually keeping the process foreground." >&2
            FAIL=1
            break
        fi
        echo "  am kill refused as expected; pid unchanged ($PID)."
    fi

    sleep 1
done

if [ "$FAIL" -eq 0 ]; then
    echo "PASS: oom_score_adj stayed 0 (foreground) for the full ${DURATION}s window, and a" \
        "mid-window 'am kill' attempt was refused. This process was never a low-memory-killer" \
        "candidate during the capture."
fi
exit "$FAIL"
