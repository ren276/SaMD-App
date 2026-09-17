#!/usr/bin/env bash
# Process-death regression checks. Two modes, with OPPOSITE expectations about `am kill`.
#
#   capture   (default, H-18 Build 3b Option A)
#             With the CameraX viewfinder open the process must NEVER be a low-memory-killer
#             candidate, because it never leaves the foreground. Asserts oom_score_adj stays 0 and
#             that a mid-window `am kill` is REFUSED.
#
#   navstack  (nav back-stack restore, phases 1 and 2)
#             Asserts the opposite, on purpose: with the app backgrounded, `am kill` must SUCCEED,
#             and on relaunch the worker must land back on the screen they were on rather than at
#             Home. This is the only check that proves onSaveInstanceState actually routes through
#             the saver under a real kill. Every unit and instrumented test drives the Saver
#             directly, which cannot prove the platform ever calls it.
#
# The two modes are not variants of one flow. capture proves a kill cannot happen; navstack needs
# the kill to happen. They share only the device plumbing and the cleanup trap below, which is why
# this is a mode switch rather than a flag on a single code path.
#
# NEITHER mode drives the app to the screen under test. Reaching either needs a signed-in session
# and, for the clinical flow, the backend up. Get the app where it needs to be BEFORE running this.
#
# Usage: scripts/process_death_check.sh [mode] [serial] [duration_seconds]
#   mode              capture (default) or navstack.
#   serial            adb device serial. Defaults to the first attached device.
#   duration_seconds  capture mode: how long to sample oom_score_adj. Default 15. Ignored by
#                     navstack mode.
#
# The older two-argument form (serial, duration) still works: the mode is only consumed when the
# first argument is literally "capture" or "navstack".

set -u

MODE="capture"
if [ "${1:-}" = "capture" ] || [ "${1:-}" = "navstack" ]; then
    MODE="$1"
    shift
fi

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
# Check the serial is real before anything else runs. Without this, every subsequent adb call fails
# individually with "device not found" and the script grinds on, and a mistyped MODE is the most
# likely way to get here: an unrecognised first argument is treated as a serial (see the usage
# note above), so "navstck" silently becomes a device name.
if ! adb devices | awk 'NR>1 && $2=="device" {print $1}' | grep -qx "$SERIAL"; then
    echo "STOP: '$SERIAL' is not an attached device." >&2
    echo "If you meant it as a mode, the only valid values are 'capture' and 'navstack'." >&2
    echo "Attached devices:" >&2
    adb devices | awk 'NR>1 && $2=="device" {print "  " $1}' >&2
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

echo "Mode: $MODE"
echo "Device: $SERIAL"
echo "always_finish_activities before this script touched anything: $($ADB shell settings get global always_finish_activities)"

require_running() {
    PID=$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')
    if [ -z "$PID" ]; then
        echo "STOP: $PACKAGE is not running. $1" >&2
        exit 1
    fi
    echo "PID: $PID"
}

# A fingerprint of what is on screen: every text= value in the current window, sorted. Used by
# navstack mode to compare the screen before the kill with the screen after the relaunch. Sorted
# because a restored screen may lay out in a different order without being a different screen.
screen_fingerprint() {
    $ADB shell uiautomator dump /sdcard/pdc_dump.xml >/dev/null 2>&1
    $ADB shell cat /sdcard/pdc_dump.xml 2>/dev/null \
        | tr '>' '\n' \
        | grep -o 'text="[^"]*"' \
        | grep -v 'text=""' \
        | sort -u
}

run_capture_mode() {
    require_running "Launch it and open the document-capture screen (with the camera viewfinder visible) before running this script."

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
    return "$FAIL"
}

run_navstack_mode() {
    require_running "Sign in and navigate several screens deep (for example Patients -> a patient -> Consent -> Compounder) before running this script."

    echo
    echo "Capturing the screen fingerprint before the kill..."
    BEFORE=$(screen_fingerprint)
    if [ -z "$BEFORE" ]; then
        echo "STOP: could not read the current screen with uiautomator. Nothing to compare against." >&2
        exit 1
    fi
    echo "$BEFORE" | head -8 | sed 's/^/  /'
    BEFORE_COUNT=$(echo "$BEFORE" | wc -l | tr -d ' ')
    echo "  ($BEFORE_COUNT distinct text values)"

    if echo "$BEFORE" | grep -qi 'text="PHC Patient Care"'; then
        echo "STOP: the app appears to be on Home already. A restore check from Home proves nothing," >&2
        echo "because Home is where a FAILED restore also lands. Navigate into a case first." >&2
        exit 1
    fi

    echo
    echo "Backgrounding the app so onSaveInstanceState runs..."
    $ADB shell input keyevent KEYCODE_HOME
    sleep 2

    ADJ=$($ADB shell cat /proc/"$PID"/oom_score_adj 2>/dev/null | tr -d '\r')
    echo "oom_score_adj while backgrounded: ${ADJ:-<process gone>}"
    if [ -n "$ADJ" ] && [ "$ADJ" -eq 0 ]; then
        echo "WARNING: still 0 (foreground). The app may not have actually backgrounded, in which" >&2
        echo "case the kill below may be refused and this run will be inconclusive." >&2
    fi

    echo "Killing the process (this one is EXPECTED to succeed, unlike capture mode)..."
    $ADB shell am kill "$PACKAGE" >/dev/null 2>&1
    sleep 2
    PID_AFTER_KILL=$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')
    if [ -n "$PID_AFTER_KILL" ] && [ "$PID_AFTER_KILL" = "$PID" ]; then
        echo "FAIL: 'am kill' did not kill the process (pid still $PID). Nothing was tested: the" >&2
        echo "app never died, so no restore happened. Make sure it is really backgrounded." >&2
        return 1
    fi
    echo "  process gone (was $PID)."

    echo "Relaunching..."
    $ADB shell monkey -p "$PACKAGE" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
    sleep 5
    PID_NEW=$($ADB shell pidof "$PACKAGE" 2>/dev/null | tr -d '\r')
    if [ -z "$PID_NEW" ]; then
        echo "FAIL: the app did not come back up after the kill." >&2
        return 1
    fi
    echo "  new PID: $PID_NEW"

    echo
    echo "Capturing the screen fingerprint after the relaunch..."
    AFTER=$(screen_fingerprint)
    echo "$AFTER" | head -8 | sed 's/^/  /'

    if echo "$AFTER" | grep -qi 'text="Enter PIN"\|text="Unlock"'; then
        echo "INCONCLUSIVE: the app came back to a PIN or lock screen, so the restored stack is not" >&2
        echo "visible yet. Unlock and re-read the screen manually, or re-run with the idle lock off." >&2
        return 2
    fi

    if echo "$AFTER" | grep -qi 'text="PHC Patient Care"'; then
        echo "FAIL: the app came back to Home. The back stack was NOT restored." >&2
        echo "Check logcat for a nav_stack_restore_discarded audit row: a discard is reported with a" >&2
        echo "reason (corrupt, too_large, session_changed) and would explain this." >&2
        return 1
    fi

    OVERLAP=$(comm -12 <(echo "$BEFORE") <(echo "$AFTER") | wc -l | tr -d ' ')
    echo "  $OVERLAP of $BEFORE_COUNT pre-kill text values are still on screen."
    if [ "$OVERLAP" -eq 0 ]; then
        echo "FAIL: nothing from the pre-kill screen survived. The worker did not land back where" >&2
        echo "they were." >&2
        return 1
    fi

    echo
    echo "PASS: the process was really killed (pid $PID -> $PID_NEW), the app relaunched, and it"
    echo "came back to a screen sharing $OVERLAP text values with the one it died on rather than to"
    echo "Home. onSaveInstanceState routed through the back-stack saver."
    echo
    echo "NOTE: this compares what is on screen, not the stack itself. Fields held only in ViewModel"
    echo "memory (a typed form, an in-flight acquisition) are expected to come back empty; that is"
    echo "RR-03, not a restore failure. What is being asserted here is the ROUTE, not its contents."
    return 0
}

case "$MODE" in
    capture) run_capture_mode ;;
    navstack) run_navstack_mode ;;
    *)
        echo "STOP: unknown mode '$MODE'. Use 'capture' or 'navstack'." >&2
        exit 1
        ;;
esac
exit "$?"
