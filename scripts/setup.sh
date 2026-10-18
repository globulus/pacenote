#!/usr/bin/env bash
# Installs the claude-car service on this Mac: a background service (launchd)
# that starts at login and lets the phone talk to any Claude Code session.
# Safe to re-run; re-run it after moving this folder or changing options.
#
# Options (env):
#   CAR_MODEL=sonnet               model for car turns (default: your Claude Code default)
#   CAR_EFFORT=low                 effort for car turns
#   CAR_PERMISSION_MODE=auto       permission mode for car turns (default auto)
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CAR_HOME="${CLAUDE_CAR_HOME:-$HOME/.claude-car}"
LABEL="com.theglobulus.claude-car"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
BUN="$(command -v bun || echo "$HOME/.bun/bin/bun")"
CLAUDE="$(command -v claude || true)"
[[ -x "$BUN" ]] || { echo "bun not found: install it from https://bun.sh" >&2; exit 1; }
[[ -n "$CLAUDE" ]] || { echo "claude not found on PATH" >&2; exit 1; }

umask 077
mkdir -p "$CAR_HOME"
chmod 700 "$CAR_HOME"
if [[ ! -s "$CAR_HOME/token" ]]; then
  openssl rand -hex 32 > "$CAR_HOME/token"
  echo "Created a new token in $CAR_HOME/token (rebuild the Android app to pick it up)"
fi
chmod 600 "$CAR_HOME/token"
# curl -H @file keeps the token out of `ps` output.
printf 'Authorization: Bearer %s\n' "$(cat "$CAR_HOME/token")" > "$CAR_HOME/auth-header"
chmod 600 "$CAR_HOME/auth-header"

(cd "$ROOT/bridge" && "$BUN" install --frozen-lockfile --no-summary)

# macOS keeps background services out of ~/Documents (where this repo lives),
# so the service runs from a copy. Re-run setup after changing bridge/src.
SERVICE="$CAR_HOME/service"
mkdir -p "$SERVICE"
rsync -a --delete "$ROOT/bridge/src/" "$SERVICE/src/"
cp "$ROOT/bridge/package.json" "$SERVICE/package.json"

# Clean up the earlier channel-based version, if it was installed.
claude mcp remove -s user car >/dev/null 2>&1 || true
rm -f "$CAR_HOME/session-settings.json" "$CAR_HOME/mcp.json" "$CAR_HOME/bridge.pid"

xml() { sed -e 's/&/\&amp;/g' -e 's/</\&lt;/g' -e 's/>/\&gt;/g' <<<"$1"; }
env_entry() { [[ -n "${2:-}" ]] && printf '    <key>%s</key><string>%s</string>\n' "$1" "$(xml "$2")"; return 0; }

mkdir -p "$(dirname "$PLIST")"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>$LABEL</string>
  <key>ProgramArguments</key>
  <array>
    <string>$(xml "$BUN")</string>
    <string>$(xml "$SERVICE/src/index.ts")</string>
  </array>
  <key>WorkingDirectory</key><string>$(xml "$SERVICE")</string>
  <key>EnvironmentVariables</key>
  <dict>
    <key>PATH</key><string>$(xml "$(dirname "$CLAUDE"):$(dirname "$BUN"):/opt/homebrew/bin:/usr/local/bin:/usr/bin:/bin:/usr/sbin:/sbin")</string>
    <key>CAR_CLAUDE_BIN</key><string>$(xml "$CLAUDE")</string>
$(env_entry CAR_MODEL "${CAR_MODEL:-}")
$(env_entry CAR_EFFORT "${CAR_EFFORT:-}")
$(env_entry CAR_PERMISSION_MODE "${CAR_PERMISSION_MODE:-}")
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>$(xml "$CAR_HOME/service.log")</string>
  <key>StandardErrorPath</key><string>$(xml "$CAR_HOME/service.log")</string>
</dict>
</plist>
EOF
plutil -lint "$PLIST" >/dev/null

launchctl bootout "gui/$(id -u)/$LABEL" 2>/dev/null || true
# bootout returns before the old service is gone; bootstrapping too early fails with an I/O error.
for _ in $(seq 1 20); do
  launchctl print "gui/$(id -u)/$LABEL" >/dev/null 2>&1 || break
  sleep 0.25
done
launchctl bootstrap "gui/$(id -u)" "$PLIST" || { sleep 2; launchctl bootstrap "gui/$(id -u)" "$PLIST"; }
echo "Installed and started the claude-car service ($PLIST)."
echo "If your projects are in Documents, Desktop or Downloads, macOS may ask to let \"bun\" access them: allow it."
echo "Check it with: $ROOT/scripts/service.sh status"
