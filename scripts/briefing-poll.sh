#!/bin/sh
# Daily-briefing scheduler. This only *checks in* — person-service owns the
# schedule: `runDailyBriefing` fires once per local day, after the configured
# local hour (using the owner's timezone, DST-correct), and is idempotent. So we
# just poll periodically; the server decides when 7am has arrived. It also retries
# delivery of any pending briefing. This script never composes or sends.
set -eu

OWNER="${BRIEFING_OWNER:-fred}"
PERSON_URL="${PERSON_SERVICE_URL:-http://person-service:8080}"
CHECK_INTERVAL="${BRIEFING_CHECK_INTERVAL_SEC:-600}"

ts() { date -Iseconds 2>/dev/null || date; }

echo "briefing-poll: owner=${OWNER} check=${CHECK_INTERVAL}s (server gates the time)"
while true; do
  curl -sf -X POST "${PERSON_URL}/briefing/run?owner=${OWNER}"     -H 'Content-Type: application/json' -d '{}' >/dev/null 2>&1 \
    && echo "$(ts) checked in" || echo "$(ts) run check failed"
  curl -sf -X POST "${PERSON_URL}/briefing/deliver?owner=${OWNER}" -H 'Content-Type: application/json' -d '{}' >/dev/null 2>&1 || true
  sleep "${CHECK_INTERVAL}"
done
