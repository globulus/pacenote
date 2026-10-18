#!/usr/bin/env bash
# Manage the claude-car background service.
#
#   scripts/service.sh status | restart | stop | start | logs | uninstall
set -euo pipefail

LABEL="com.theglobulus.claude-car"
PLIST="$HOME/Library/LaunchAgents/$LABEL.plist"
CAR_HOME="${CLAUDE_CAR_HOME:-$HOME/.claude-car}"
DOMAIN="gui/$(id -u)"

case "${1:-status}" in
  status)
    if launchctl print "$DOMAIN/$LABEL" >/dev/null 2>&1; then
      launchctl print "$DOMAIN/$LABEL" | grep -E "^\s*(state|pid|last exit code) =" | sed 's/^\s*//'
      "$(dirname "$0")/smoke.sh" health || true
    else
      echo "not installed (run scripts/setup.sh)"
    fi ;;
  restart) launchctl kickstart -k "$DOMAIN/$LABEL" && echo restarted ;;
  stop) launchctl bootout "$DOMAIN/$LABEL" && echo "stopped (starts again at login; 'start' to start now)" ;;
  start) launchctl bootstrap "$DOMAIN" "$PLIST" && echo started ;;
  logs) tail -n 50 -f "$CAR_HOME/bridge.log" ;;
  uninstall)
    launchctl bootout "$DOMAIN/$LABEL" 2>/dev/null || true
    rm -f "$PLIST"
    echo "uninstalled (your token in $CAR_HOME is kept)" ;;
  *) sed -n '2,4p' "$0"; exit 1 ;;
esac
