#!/bin/sh
# Poll-driven projection sync: keep Google Tasks/Calendar in step with the
# substrate. person-service holds the Google credential and does the work; this
# only triggers it on a timer. Commitments are the source of truth for todos,
# events for the calendar mirror — Google is the view.
set -eu

OWNER="${SYNC_OWNER:-fred}"
PERSON_URL="${PERSON_SERVICE_URL:-http://person-service:8080}"
INTERVAL="${SYNC_POLL_INTERVAL_SEC:-300}"

ts() { date -Iseconds 2>/dev/null || date; }

sync_one() { # $1 = endpoint label, $2 = path
  if out="$(curl -sf -X POST "${PERSON_URL}/$2?owner=${OWNER}" -H 'Content-Type: application/json' -d '{}' 2>&1)"; then
    echo "$(ts) $1 ok: ${out}"
  else
    echo "$(ts) $1 sync failed: ${out}"
  fi
}

echo "projection-poll: owner=${OWNER} interval=${INTERVAL}s"
while true; do
  sync_one tasks    "tasks/sync"
  sync_one calendar "calendar/sync"
  sleep "${INTERVAL}"
done
