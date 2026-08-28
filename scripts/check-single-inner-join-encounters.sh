#!/usr/bin/env bash
# Mechanical enforcement of REQ-ROS-02: exactly one query on the Android DAO layer may be an
# encounter-required (INNER JOIN) roster read - PatientDao.observePatientsWithEncounterBetween,
# the query Home's day-scoped work queue is built on. Every other patient-list read must either
# be scoped to a single already-known encounter/patient id, or use LEFT JOIN if it needs to
# include patients without an encounter (see PatientDao.observeRegisteredOrSeenBetween, the
# Patients tab's directory read).
#
# A second INNER JOIN encounters site is exactly how the day-scoping data-minimisation control
# (H-04) would regress silently: each individual query looks reasonable, and only counting them
# together catches the drift. This script is that count.
set -euo pipefail

cd "$(dirname "$0")/.."

ALLOWED_FILE="app/src/main/java/com/example/samdapp/data/local/dao/PatientDao.kt"

# Matches the SQL string literal itself (leading quote, real join condition), not KDoc prose
# that merely mentions "INNER JOIN encounters" while explaining the one allowed site.
PATTERN='"INNER JOIN encounters'
matches=$(grep -rn "$PATTERN" app/src/main/java/com/example/samdapp/data/local/dao/ || true)
count=$(printf '%s\n' "$matches" | grep -c "$PATTERN" || true)

if [ "$count" -eq 0 ]; then
    echo "check-single-inner-join-encounters: found zero 'INNER JOIN encounters' sites, expected exactly one in $ALLOWED_FILE."
    echo "If observePatientsWithEncounterBetween was renamed or removed, update this script and REQ-ROS-01 alongside it."
    exit 1
fi

if [ "$count" -gt 1 ]; then
    echo "check-single-inner-join-encounters: found $count 'INNER JOIN encounters' sites, expected exactly one."
    echo "$matches"
    echo ""
    echo "REQ-ROS-02 / H-04 requires the encounter-required roster join to exist in exactly one place"
    echo "(Home's $ALLOWED_FILE). If a new query needs patients without an encounter too, use LEFT JOIN"
    echo "with the window predicate in HAVING (see observeRegisteredOrSeenBetween), not a second INNER JOIN."
    exit 1
fi

if ! printf '%s\n' "$matches" | grep -q "^$ALLOWED_FILE:"; then
    echo "check-single-inner-join-encounters: the one 'INNER JOIN encounters' site is not in $ALLOWED_FILE."
    echo "$matches"
    echo "That query moved, or a new site replaced it in the wrong place. Confirm this is intentional"
    echo "and update ALLOWED_FILE in this script to match before merging."
    exit 1
fi

echo "check-single-inner-join-encounters: OK - exactly one site, in $ALLOWED_FILE."
