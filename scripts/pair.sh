#!/usr/bin/env bash
# Shows the QR code that pairs the Pacenote app with this Mac.
#
#   scripts/pair.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
BUN="$(command -v bun || echo "$HOME/.bun/bin/bun")"
exec "$BUN" "$ROOT/bridge/src/pair.ts"
