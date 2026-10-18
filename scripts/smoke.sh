#!/usr/bin/env bash
# Poke the service by hand, as the phone would.
#
#   scripts/smoke.sh health
#   scripts/smoke.sh sessions                       # recent sessions as JSON
#   scripts/smoke.sh events [last-event-id]         # stream everything (Ctrl-C to stop)
#   scripts/smoke.sh say hub "list sessions"        # talk to the Claude Car hub
#   scripts/smoke.sh say <session-id> "run the tests"
#   scripts/smoke.sh noauth                         # should print 401
set -euo pipefail

CAR_HOME="${CLAUDE_CAR_HOME:-$HOME/.claude-car}"
URL="${CAR_URL:-http://${CAR_BIND:-100.122.179.88}:${CAR_PORT:-8788}}"
H=(-H @"$CAR_HOME/auth-header")

case "${1:-health}" in
  health) curl -s "${H[@]}" "$URL/health"; echo ;;
  sessions) curl -s "${H[@]}" "$URL/sessions" | python3 -m json.tool ;;
  events)
    extra=()
    [[ -n "${2:-}" ]] && extra=(-H "Last-Event-ID: $2")
    curl -sN "${H[@]}" "${extra[@]}" "$URL/events" ;;
  say)
    body=$(SID="${2:?usage: smoke.sh say <hub|session-id> \"text\"}" MSG="${3:?missing text}" python3 -c '
import json, os, random, time
print(json.dumps({"id": f"smoke-{int(time.time())}-{random.randint(0, 99999)}", "session_id": os.environ["SID"], "text": os.environ["MSG"]}))')
    curl -s "${H[@]}" -H 'Content-Type: application/json' -d "$body" "$URL/message"; echo ;;
  noauth) curl -s -o /dev/null -w '%{http_code}\n' "$URL/health" ;;
  *) sed -n '2,10p' "$0"; exit 1 ;;
esac
