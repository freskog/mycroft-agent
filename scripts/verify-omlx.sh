#!/usr/bin/env bash
# verify-omlx.sh — probe the oMLX (OpenAI-compatible) server BEFORE changing any
# harness code, and emit a report of what it actually supports: streaming, usage
# fields, prefix-cache reuse, thinking on/off, where reasoning lands, and long-
# context stability.
#
# Standalone: bash + curl + jq only. Touches no repo code. Reads nothing secret.
#
#   INFERENCE_BASE_URL  (default http://fredriks-mac-mini.gledswood.org:1234/v1)
#   INFERENCE_MODEL     (optional; skip auto-discovery)
#
# Writes scripts/verify-omlx-report.json and a human summary to stdout.
# Exits non-zero if any MVP-REQUIRED capability fails.

set -u

BASE="${INFERENCE_BASE_URL:-http://fredriks-mac-mini.gledswood.org:1234/v1}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPORT="${REPORT_PATH:-$SCRIPT_DIR/verify-omlx-report.json}"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

command -v curl >/dev/null || { echo "FATAL: curl not found" >&2; exit 3; }
command -v jq   >/dev/null || { echo "FATAL: jq not found"   >&2; exit 3; }
command -v python3 >/dev/null || { echo "FATAL: python3 not found (used for ms timing)" >&2; exit 3; }

now_ms() { python3 -c 'import time; print(int(time.time()*1000))'; }

echo '{}' > "$REPORT"
FAILURES=""   # space-separated names of failed REQUIRED checks
WARNINGS=""

note_fail() { FAILURES="$FAILURES $1"; echo "  ✗ REQUIRED FAILED: $1" >&2; }
note_warn() { WARNINGS="$WARNINGS $1"; echo "  ! $1" >&2; }

# report_set <key> <json-value>
report_set() {
  jq --argjson v "$2" ". + {\"$1\": \$v}" "$REPORT" > "$REPORT.tmp" && mv "$REPORT.tmp" "$REPORT"
}
report_set_str() {
  jq --arg v "$2" ". + {\"$1\": \$v}" "$REPORT" > "$REPORT.tmp" && mv "$REPORT.tmp" "$REPORT"
}

# mkbody <model> <system> <user> <max_tokens> [extra-json]
mkbody() {
  local extra='{}'
  [ "$#" -ge 5 ] && [ -n "$5" ] && extra="$5"
  jq -n --arg m "$1" --arg s "$2" --arg u "$3" --argjson mt "$4" --argjson extra "$extra" \
    '{model:$m, messages:[{role:"system",content:$s},{role:"user",content:$u}], max_tokens:$mt} + $extra'
}

# Deterministic filler of ~<tokens> tokens (≈11 tokens per repeated sentence).
make_prefix() {
  python3 -c "import sys; n=max(1,int(${1})//11); sys.stdout.write('You are a deterministic, concise assistant for caching tests. '*n)"
}

# POST a body file (non-stream). Writes resp to $TMP/resp.json.
# Echoes: "<http_code> <time_total_s> <time_starttransfer_s>"
chat_post() {
  curl -s -o "$TMP/resp.json" -w '%{http_code} %{time_total} %{time_starttransfer}' \
    -H 'Content-Type: application/json' --data @"$1" "$BASE/chat/completions" 2>/dev/null
}

jqr() { jq -r "$1" "$2" 2>/dev/null; }   # jqr <filter> <file>, empty on error

echo "== oMLX verification =="
echo "endpoint: $BASE"
report_set_str endpoint "$BASE"
report_set_str started_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

# ─── Probe 1: model discovery ────────────────────────────────────────────────
echo
echo "[1] model discovery"
curl -s "$BASE/models" -o "$TMP/models.json" -w '%{http_code}' > "$TMP/models.code" 2>/dev/null
MCODE="$(cat "$TMP/models.code")"
IDS="$(jqr '.data[].id' "$TMP/models.json")"
report_set models "$(jq '.data // [] | map(.id)' "$TMP/models.json" 2>/dev/null || echo '[]')"

if [ "$MCODE" != "200" ] || [ -z "$IDS" ]; then
  echo "  ✗ could not list models (http $MCODE)"
  note_fail "model_discovery"
  report_set_str model_id ""
else
  if [ -n "${INFERENCE_MODEL:-}" ]; then
    MODEL="$INFERENCE_MODEL"
  else
    MODEL="$(printf '%s\n' "$IDS" | grep -iE 'qwen3.*a3b|a3b.*35b|35b.*a3b' | head -1)"
    if [ -z "$MODEL" ]; then
      COUNT="$(printf '%s\n' "$IDS" | grep -c .)"
      if [ "$COUNT" = "1" ]; then
        MODEL="$(printf '%s\n' "$IDS" | head -1)"
      else
        echo "  ✗ ambiguous model selection; available ids:"; printf '      %s\n' $IDS
        note_fail "model_discovery_ambiguous"
        MODEL=""
      fi
    fi
  fi
  echo "  model: ${MODEL:-<none>}"
  report_set_str model_id "${MODEL:-}"
fi

# If we have no usable model, no point continuing the chat probes.
if [ -z "${MODEL:-}" ]; then
  report_set failures "$(jq -n --arg f "$FAILURES" '($f|ltrimstr(" ")|split(" ")|map(select(length>0)))')"
  echo; echo "ABORT: no usable model id." >&2
  exit 2
fi

# ─── Probe 2: non-streaming ──────────────────────────────────────────────────
echo
echo "[2] non-streaming completion"
mkbody "$MODEL" "You are a concise assistant." "Reply with exactly OK." 32 > "$TMP/b2.json"
read -r CODE TT TS <<<"$(chat_post "$TMP/b2.json")"
C2="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
USAGE2="$(jq -c '.usage // {}' "$TMP/resp.json" 2>/dev/null || echo '{}')"
USAGE_KEYS="$(jq -r '.usage // {} | paths(scalars) | join(".")' "$TMP/resp.json" 2>/dev/null | paste -sd, - 2>/dev/null)"
OKVIS=$(printf '%s' "$C2" | grep -qi 'ok' && echo true || echo false)
echo "  http=$CODE  time_total=${TT}s  visible='${C2:0:40}'  usage_keys=${USAGE_KEYS:-none}"
report_set nonstreaming "$(jq -n --arg code "$CODE" --arg c "$C2" --argjson u "$USAGE2" \
  --arg keys "${USAGE_KEYS:-}" --argjson tt "${TT:-0}" --argjson ts "${TS:-0}" \
  '{http:$code, visible:$c, ok:($c|ascii_downcase|test("ok")), usage:$u, usage_keys:$keys, time_total_s:$tt, time_starttransfer_s:$ts}')"
[ "$CODE" = "200" ] && [ "$OKVIS" = "true" ] || note_fail "non_streaming"

# ─── Probe 3: streaming + usage + client timing ──────────────────────────────
echo
echo "[3] streaming + usage"
mkbody "$MODEL" "You are a concise assistant." "Reply with exactly OK." 32 \
  '{"stream":true,"stream_options":{"include_usage":true}}' > "$TMP/b3.json"
: > "$TMP/s3_chunks"; : > "$TMP/s3_first_event"; : > "$TMP/s3_first_tok"
S_START="$(now_ms)"
curl -sN -H 'Content-Type: application/json' --data @"$TMP/b3.json" "$BASE/chat/completions" 2>/dev/null \
| while IFS= read -r line; do
    case "$line" in
      data:*)
        [ -s "$TMP/s3_first_event" ] || now_ms > "$TMP/s3_first_event"
        payload="${line#data: }"; payload="${payload#data:}"
        [ "$payload" = "[DONE]" ] && continue
        printf '%s\n' "$payload" >> "$TMP/s3_chunks"
        d="$(printf '%s' "$payload" | jq -r '.choices[0].delta.content // empty' 2>/dev/null)"
        if [ -n "$d" ] && [ ! -s "$TMP/s3_first_tok" ]; then now_ms > "$TMP/s3_first_tok"; fi
        ;;
    esac
  done
S_END="$(now_ms)"
S_CONTENT="$(jq -rs 'map(.choices[0].delta.content // empty) | join("")' "$TMP/s3_chunks" 2>/dev/null)"
S_USAGE="$(jq -cs 'map(select(.usage)) | last.usage // {}' "$TMP/s3_chunks" 2>/dev/null || echo '{}')"
S_NCHUNKS="$(grep -c . "$TMP/s3_chunks" 2>/dev/null || echo 0)"
FE="$(cat "$TMP/s3_first_event" 2>/dev/null)"; FT="$(cat "$TMP/s3_first_tok" 2>/dev/null)"
TTFE=$([ -n "$FE" ] && echo $((FE - S_START)) || echo -1)
TTFT=$([ -n "$FT" ] && echo $((FT - S_START)) || echo -1)
TOTAL=$((S_END - S_START))
STREAM_OK=$([ "$S_NCHUNKS" -gt 0 ] && printf '%s' "$S_CONTENT" | grep -qi 'ok' && echo true || echo false)
echo "  chunks=$S_NCHUNKS  ttf_event=${TTFE}ms  ttf_token=${TTFT}ms  total=${TOTAL}ms  usage=$(printf '%s' "$S_USAGE" | head -c 60)"
report_set streaming "$(jq -n --argjson nc "${S_NCHUNKS:-0}" --arg c "$S_CONTENT" --argjson u "$S_USAGE" \
  --argjson tte "$TTFE" --argjson ttt "$TTFT" --argjson tot "$TOTAL" \
  '{chunks:$nc, visible:$c, final_usage:$u, ttf_event_ms:$tte, ttf_token_ms:$ttt, total_ms:$tot, usage_in_stream:($u|length>0)}')"
[ "$STREAM_OK" = "true" ] || note_fail "streaming"
[ "$(printf '%s' "$S_USAGE" | jq 'length' 2>/dev/null || echo 0)" -gt 0 ] || note_warn "streaming_usage_absent (client timing is the fallback signal)"

# ─── Probe 4: prefix-cache reuse ─────────────────────────────────────────────
echo
echo "[4] prefix-cache reuse (stable ~3000-token system prefix)"
PREFIX="$(make_prefix 3000)"
cached_of() { jq -r '(.usage.prompt_tokens_details.cached_tokens // .usage.cached_tokens // "null")' "$1" 2>/dev/null; }
prompt_of() { jq -r '(.usage.prompt_tokens // .usage.input_tokens // "null")' "$1" 2>/dev/null; }
run_cached() { # run_cached <user> -> echoes "time_total cached prompt"
  mkbody "$MODEL" "$PREFIX" "$1" 8 > "$TMP/bc.json"
  read -r _c t _s <<<"$(chat_post "$TMP/bc.json")"
  echo "$t $(cached_of "$TMP/resp.json") $(prompt_of "$TMP/resp.json")"
}
read -r A1T A1C A1P <<<"$(run_cached 'Reply with exactly A.')"
read -r A2T A2C A2P <<<"$(run_cached 'Reply with exactly A.')"
read -r BT  BC  BP  <<<"$(run_cached 'Reply with exactly B.')"
CACHE_FIELD=$([ "$A2C" != "null" ] && echo true || echo false)
echo "  A1: ${A1T}s cached=$A1C prompt=$A1P | A2: ${A2T}s cached=$A2C | B: ${BT}s cached=$BC"
report_set prefix_cache "$(jq -n \
  --argjson a1t "${A1T:-0}" --arg a1c "$A1C" --arg a1p "$A1P" \
  --argjson a2t "${A2T:-0}" --arg a2c "$A2C" \
  --argjson bt "${BT:-0}"  --arg bc "$BC" \
  --argjson cf "$CACHE_FIELD" \
  '{cached_token_field_exposed:$cf,
    a1:{time_s:$a1t, cached:$a1c, prompt:$a1p},
    a2:{time_s:$a2t, cached:$a2c},
    b:{time_s:$bt, cached:$bc},
    latency_fallback_A2_faster_than_A1: ($a2t < $a1t)}')"
[ "$CACHE_FIELD" = "true" ] || note_warn "cached_tokens not exposed — using latency as cache signal (A2<A1: $(python3 -c "print($A2T<$A1T)" 2>/dev/null))"

# ─── Probe 5: skill-branch cache ─────────────────────────────────────────────
echo
echo "[5] skill-branch cache reuse"
CORE="$(make_prefix 1500)"
SK_EMAIL="$CORE
SKILL=process-email. Extract obligations and dates from the message below."
SK_RESEARCH="$CORE
SKILL=private-research. Synthesise an answer from the notes below."
branch() { mkbody "$MODEL" "$1" "$2" 8 > "$TMP/bb.json"; read -r _ t _ <<<"$(chat_post "$TMP/bb.json")"; echo "$t $(cached_of "$TMP/resp.json")"; }
read -r E1T E1C <<<"$(branch "$SK_EMAIL" 'Input A: nothing due.')"
read -r E2T E2C <<<"$(branch "$SK_EMAIL" 'Input B: pay fees Friday.')"
read -r R1T R1C <<<"$(branch "$SK_RESEARCH" 'Input C: compare two options.')"
echo "  email#1=${E1T}s c=$E1C  email#2=${E2T}s c=$E2C  research=${R1T}s c=$R1C"
report_set skill_branch_cache "$(jq -n --argjson e1t "${E1T:-0}" --arg e1c "$E1C" --argjson e2t "${E2T:-0}" --arg e2c "$E2C" --argjson r1t "${R1T:-0}" --arg r1c "$R1C" \
  '{email_1:{time_s:$e1t,cached:$e1c}, email_2:{time_s:$e2t,cached:$e2c}, research:{time_s:$r1t,cached:$r1c}, email2_reuses_branch:($e2t<=$e1t)}')"

# ─── Probe 6: thinking disabled ──────────────────────────────────────────────
echo
echo "[6] thinking disabled (enable_thinking=false)"
mkbody "$MODEL" "You are a concise assistant." "Reply with exactly OK." 64 \
  '{"chat_template_kwargs":{"enable_thinking":false}}' > "$TMP/b6.json"
read -r C6CODE _ _ <<<"$(chat_post "$TMP/b6.json")"
V6="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
R6="$(jqr '.choices[0].message.reasoning_content // empty' "$TMP/resp.json")"
echo "  http=$C6CODE visible='${V6:0:40}' reasoning_present=$([ -n "$R6" ] && echo yes || echo no)"
report_set thinking_disabled "$(jq -n --arg code "$C6CODE" --arg v "$V6" --arg r "$R6" \
  '{http:$code, visible:$v, ok:($v|ascii_downcase|test("ok")), reasoning_present:($r|length>0)}')"
{ [ "$C6CODE" = "200" ] && printf '%s' "$V6" | grep -qi 'ok'; } || note_fail "thinking_disabled"

# ─── Probe 7: thinking enabled ───────────────────────────────────────────────
echo
echo "[7] thinking enabled (enable_thinking=true)"
mkbody "$MODEL" "You are a concise assistant." "Think briefly, then reply with exactly OK." 256 \
  '{"chat_template_kwargs":{"enable_thinking":true}}' > "$TMP/b7.json"
read -r C7CODE _ _ <<<"$(chat_post "$TMP/b7.json")"
V7="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
R7="$(jqr '.choices[0].message.reasoning_content // empty' "$TMP/resp.json")"
# Where did reasoning land?
RLOC="none"
[ -n "$R7" ] && RLOC="message.reasoning_content"
[ "$RLOC" = "none" ] && printf '%s' "$V7" | grep -q '<think>' && RLOC="content(<think>)"
[ "$RLOC" = "none" ] && [ -n "$(jqr '.choices[0].message.reasoning // empty' "$TMP/resp.json")" ] && RLOC="message.reasoning"
V7_VISIBLE_OK=$([ -n "$V7" ] && printf '%s' "$V7" | grep -qi 'ok' && echo true || echo false)
echo "  http=$C7CODE visible='${V7:0:40}' reasoning_location=$RLOC"
report_set thinking_enabled "$(jq -n --arg code "$C7CODE" --arg v "$V7" --arg loc "$RLOC" --arg r "$R7" \
  '{http:$code, visible:$v, visible_answer_present:($v|length>0), reasoning_location:$loc, reasoning_sample:($r[0:160])}')"
{ [ "$C7CODE" = "200" ] && [ -n "$V7" ]; } || note_fail "thinking_enabled"

# ─── Probe 8: preserve thinking (best-effort, only if 7 produced reasoning) ───
echo
echo "[8] preserve thinking (best-effort)"
PT_VERDICT="not_tested"
if [ "$RLOC" != "none" ] && [ "$C7CODE" = "200" ]; then
  mkbody "$MODEL" "You are a precise assistant." "Generate two random 20-digit numbers. Validate both internally. Only show the FIRST number." 512 \
    '{"chat_template_kwargs":{"enable_thinking":true}}' > "$TMP/b8a.json"
  chat_post "$TMP/b8a.json" >/dev/null
  A8V="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
  A8R="$(jqr '.choices[0].message.reasoning_content // empty' "$TMP/resp.json")"
  # Round-trip the prior assistant turn (incl. reasoning_content) and ask for #2.
  jq -n --arg m "$MODEL" --arg p "Generate two random 20-digit numbers. Validate both internally. Only show the FIRST number." \
        --arg av "$A8V" --arg ar "$A8R" \
    '{model:$m,
      messages:[
        {role:"user",content:$p},
        ({role:"assistant",content:$av} + (if ($ar|length>0) then {reasoning_content:$ar} else {} end)),
        {role:"user",content:"What was the SECOND number?"}],
      max_tokens:512,
      chat_template_kwargs:{enable_thinking:true, preserve_thinking:true}}' > "$TMP/b8b.json"
  read -r C8CODE _ _ <<<"$(chat_post "$TMP/b8b.json")"
  A8B="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
  if [ "$C8CODE" != "200" ]; then PT_VERDICT="not_supported"
  elif printf '%s' "$A8B" | grep -qE '[0-9]{18,}'; then PT_VERDICT="supported"
  else PT_VERDICT="unreliable"; fi
  echo "  http=$C8CODE second_answer='${A8B:0:50}' verdict=$PT_VERDICT"
else
  echo "  skipped (no reasoning content in probe 7)"
fi
report_set_str preserve_thinking "$PT_VERDICT"

# ─── Probe 9: long-context stability (8k / 16k / 32k) ────────────────────────
echo
echo "[9] long-context stability"
stability() { # stability <tokens>
  local p; p="$(make_prefix "$1")"
  mkbody "$MODEL" "$p" "Reply with exactly OK." 16 > "$TMP/b9.json"
  read -r code t _ <<<"$(chat_post "$TMP/b9.json")"
  local v; v="$(jqr '.choices[0].message.content // empty' "$TMP/resp.json")"
  local okk; okk=$([ "$code" = "200" ] && [ -n "$v" ] && echo true || echo false)
  echo "$code $t $okk"
  jq -n --argjson tk "$1" --arg code "$code" --argjson t "${t:-0}" --argjson ok "$okk" --arg v "${v:0:24}" \
    '{tokens:$tk, http:$code, time_s:$t, ok:$ok, visible:$v}' > "$TMP/stab_$1.json"
}
read -r S8C  S8T  S8OK  <<<"$(stability 8000)";  echo "   8k:  http=$S8C  ${S8T}s ok=$S8OK"
read -r S16C S16T S16OK <<<"$(stability 16000)"; echo "  16k:  http=$S16C ${S16T}s ok=$S16OK"
if [ "$S16OK" = "true" ]; then
  read -r S32C S32T S32OK <<<"$(stability 32000)"; echo "  32k:  http=$S32C ${S32T}s ok=$S32OK"
else
  echo "  32k:  skipped (16k unstable)"; echo '{"tokens":32000,"skipped":true}' > "$TMP/stab_32000.json"
fi
report_set stability "$(jq -n \
  --argjson k8 "$(cat "$TMP/stab_8000.json")" \
  --argjson k16 "$(cat "$TMP/stab_16000.json")" \
  --argjson k32 "$(cat "$TMP/stab_32000.json" 2>/dev/null || echo '{}')" \
  '{"8k":$k8, "16k":$k16, "32k":$k32}')"
[ "$S16OK" = "true" ] || note_fail "stability_16k"

# ─── Assemble + verdict ──────────────────────────────────────────────────────
report_set warnings  "$(jq -n --arg w "$WARNINGS" '($w|split(" ")|map(select(length>0)))')"
report_set failures  "$(jq -n --arg f "$FAILURES" '($f|split(" ")|map(select(length>0)))')"
report_set_str finished_at "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

echo
echo "== summary =="
echo "report: $REPORT"
jq -r '
  "model: \(.model_id)",
  "non-stream OK: \(.nonstreaming.ok)   stream OK: \(.streaming.visible|ascii_downcase|test("ok"))",
  "thinking off: \(.thinking_disabled.ok)   thinking on: \(.thinking_enabled.visible_answer_present)   reasoning@ \(.thinking_enabled.reasoning_location)",
  "cached_tokens exposed: \(.prefix_cache.cached_token_field_exposed)   preserve_thinking: \(.preserve_thinking)",
  "stability 8k/16k/32k: \(.stability."8k".ok)/\(.stability."16k".ok)/\(.stability."32k".ok // "skipped")"
' "$REPORT"

if [ -n "$(printf '%s' "$FAILURES" | tr -d ' ')" ]; then
  echo
  echo "RESULT: FAIL — required capabilities missing:$FAILURES" >&2
  exit 1
fi
echo
echo "RESULT: PASS (MVP-required capabilities present). Review the report before any code changes."
exit 0
