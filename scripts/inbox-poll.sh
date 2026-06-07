#!/bin/sh
# Poll Gmail via person-service and trigger mycroft triage when pending messages exist.
set -eu

OWNER="${INBOX_OWNER:-fred}"
PERSON_URL="${PERSON_SERVICE_URL:-http://person-service:8080}"
MYCROFT_URL="${MYCROFT_URL:-http://mycroft:8090}"
CHANNEL="${INBOX_CHANNEL:-$OWNER}"
INTERVAL="${INBOX_POLL_INTERVAL_SEC:-900}"

sync_once() {
  curl -sf -X POST "${PERSON_URL}/gmail/sync?owner=${OWNER}" \
    -H 'Content-Type: application/json' -d '{}'
}

pending_from_sync() {
  # Extract "pending":N from GmailSyncResult JSON without jq.
  echo "$1" | sed -n 's/.*"pending"[[:space:]]*:[[:space:]]*\([0-9][0-9]*\).*/\1/p' | head -1
}

trigger_triage() {
  # Trigger the inbox-triage skill directly; the playbook owns the steps and
  # dedup is a server-side guarantee (propose-by-source).
  curl -sf -X POST "${MYCROFT_URL}/inbound" \
    -H 'Content-Type: application/json' \
    -d "{\"channel\":\"${CHANNEL}\",\"from\":\"${OWNER}\",\"content\":\"Triage the oldest pending inbox messages for ${OWNER}.\",\"skill\":\"inbox-triage\",\"params\":\"{\\\"limit\\\":10,\\\"owner\\\":\\\"${OWNER}\\\"}\"}"
}

echo "inbox-poll: owner=${OWNER} interval=${INTERVAL}s"
while true; do
  if sync_out="$(sync_once 2>&1)"; then
    pending="$(pending_from_sync "$sync_out")"
    echo "$(date -Iseconds 2>/dev/null || date) sync ok pending=${pending:-0}"
    if [ -n "${pending:-}" ] && [ "${pending}" -gt 0 ]; then
      trigger_triage && echo "$(date -Iseconds 2>/dev/null || date) triage triggered" \
        || echo "$(date -Iseconds 2>/dev/null || date) triage trigger failed"
    fi
  else
    echo "$(date -Iseconds 2>/dev/null || date) sync failed: ${sync_out}"
  fi
  sleep "${INTERVAL}"
done
