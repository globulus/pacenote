#!/usr/bin/env bash
# Installs the Pacenote service on this Mac: a background service (launchd)
# that starts at login and lets the phone talk to any Claude Code session.
# Safe to re-run; re-run it after moving this folder or changing options.
#
# Options (env):
#   PACENOTE_MODEL=sonnet               model for car turns (default: your Claude Code default)
#   PACENOTE_EFFORT=low                 effort for car turns
#   PACENOTE_PERMISSION_MODE=auto       permission mode for car turns (default auto)
#   PACENOTE_PORT=8788                  port on the Mac's Tailscale address
set -euo pipefail

[[ "$(uname)" == Darwin ]] || { echo "The Pacenote service runs on macOS (it uses launchd)." >&2; exit 1; }

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACENOTE_HOME="${PACENOTE_HOME:-$HOME/.pacenote}"
LABEL="com.theglobulus.pacenote"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
PORT="${PACENOTE_PORT:-8788}"
BUN="$(command -v bun || echo "$HOME/.bun/bin/bun")"
CLAUDE="$(command -v claude || true)"
[[ -x "$BUN" ]] || { echo "bun not found: install it from https://bun.sh" >&2; exit 1; }
[[ -n "$CLAUDE" ]] || { echo "claude not found on PATH: install Claude Code and sign in first" >&2; exit 1; }

# The service listens on this Mac's Tailscale address only.
TS_IP=""
TS_CLI=""
for ts in tailscale /Applications/Tailscale.app/Contents/MacOS/Tailscale /opt/homebrew/bin/tailscale /usr/local/bin/tailscale; do
  TS_IP="$("$ts" ip -4 2>/dev/null | head -1 || true)"
  if [[ "$TS_IP" =~ ^100\. ]]; then TS_CLI="$ts"; break; fi
  TS_IP=""
done
[[ -n "$TS_IP" ]] || { echo "Can't find this Mac's Tailscale address: install Tailscale, sign in and connect, then re-run." >&2; exit 1; }
# The phone reaches the Mac by its Tailscale IP: stable, and it doesn't depend on
# the phone's DNS settings (Private DNS can hide MagicDNS names). The name is for display.
TS_NAME="$("$TS_CLI" status --json 2>/dev/null | python3 -c 'import json,sys; print(json.load(sys.stdin).get("Self",{}).get("HostName",""))' 2>/dev/null || true)"
HOST="$TS_IP"

umask 077
mkdir -p "$PACENOTE_HOME"
chmod 700 "$PACENOTE_HOME"
if [[ ! -s "$PACENOTE_HOME/token" ]]; then
  openssl rand -hex 32 > "$PACENOTE_HOME/token"
  echo "Created a new token in $PACENOTE_HOME/token (rebuild the Android app to pick it up)"
fi
chmod 600 "$PACENOTE_HOME/token"
# curl -H @file keeps the token out of `ps` output.
printf 'Authorization: Bearer %s\n' "$(cat "$PACENOTE_HOME/token")" > "$PACENOTE_HOME/auth-header"
chmod 600 "$PACENOTE_HOME/auth-header"
# Where the phone reaches the service; the Android build reads this (and the token).
echo "http://$HOST:$PORT" > "$PACENOTE_HOME/url"
echo "${TS_NAME:-$(scutil --get ComputerName 2>/dev/null || hostname -s)}" > "$PACENOTE_HOME/name"

(cd "$ROOT/bridge" && "$BUN" install --frozen-lockfile --no-summary)

# macOS keeps background services out of ~/Documents (where this repo lives),
# so the service runs from a copy. Re-run setup after changing bridge/src.
SERVICE="$PACENOTE_HOME/service"
mkdir -p "$SERVICE"
rsync -a --delete "$ROOT/bridge/src/" "$SERVICE/src/"
cp "$ROOT/bridge/package.json" "$SERVICE/package.json"

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
    <key>PACENOTE_CLAUDE_BIN</key><string>$(xml "$CLAUDE")</string>
    <key>PACENOTE_PORT</key><string>$(xml "$PORT")</string>
$(env_entry PACENOTE_MODEL "${PACENOTE_MODEL:-}")
$(env_entry PACENOTE_EFFORT "${PACENOTE_EFFORT:-}")
$(env_entry PACENOTE_PERMISSION_MODE "${PACENOTE_PERMISSION_MODE:-}")
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
  <key>ProcessType</key><string>Interactive</string>
  <key>StandardOutPath</key><string>$(xml "$PACENOTE_HOME/service.log")</string>
  <key>StandardErrorPath</key><string>$(xml "$PACENOTE_HOME/service.log")</string>
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
echo "Installed and started the Pacenote service on http://$HOST:$PORT ($PLIST)."
echo "If your projects are in Documents, Desktop or Downloads, macOS may ask to let \"bun\" access them: allow it."
echo "Check it with: $ROOT/scripts/service.sh status"
"$BUN" "$ROOT/bridge/src/pair.ts"
