#!/bin/sh
# Stands in for `claude -p --output-format stream-json` in tests.
prompt=$(cat)
[ -n "$FAKE_ARGS_FILE" ] && printf '%s\n' "$@" > "$FAKE_ARGS_FILE"
sid="${FAKE_SESSION_ID:-aaaaaaaa-0000-0000-0000-000000000001}"
echo "{\"type\":\"system\",\"subtype\":\"init\",\"session_id\":\"$sid\"}"
[ -n "$FAKE_SLEEP" ] && sleep "$FAKE_SLEEP"
if [ "$FAKE_FAIL" = 1 ]; then
  echo "something broke" >&2
  exit 1
fi
echo "{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"thinking\",\"thinking\":\"hm\"},{\"type\":\"text\",\"text\":\"You said: $prompt\"}]}}"
echo "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"result\":\"You said: $prompt\",\"session_id\":\"$sid\",\"permission_denials\":[{\"tool_name\":\"Bash\"}]}"
