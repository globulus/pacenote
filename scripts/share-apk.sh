#!/usr/bin/env bash
# A temporary download link for the phone app, for installing it without a
# cable (after that, use Update app in the app). The link only works on your
# tailnet and expires after 20 minutes.
#
#   scripts/share-apk.sh
set -euo pipefail

CAR_HOME="${CLAUDE_CAR_HOME:-$HOME/.claude-car}"
export APK="$CAR_HOME/app/claude-car.apk"
[[ -f "$APK" ]] || { echo "No published app: run scripts/publish-app.sh first." >&2; exit 1; }
export BIND="${CAR_BIND:-100.122.179.88}"
export PORT="${SHARE_PORT:-8789}"
export MINUTES="${SHARE_MINUTES:-20}"
# The APK has the service token inside: an unguessable path, not a public file.
export KEY="$(openssl rand -hex 16)"

echo "On the phone, with Tailscale on, open:"
echo "  http://$BIND:$PORT/$KEY/Claude-Car.apk"
echo "Then open the download and allow installing. The link expires in $MINUTES minutes."

exec bun -e '
const { APK, BIND, PORT, KEY, MINUTES } = process.env
Bun.serve({
  hostname: BIND,
  port: Number(PORT),
  fetch(req, server) {
    if (new URL(req.url).pathname !== `/${KEY}/Claude-Car.apk`) return new Response("Not found", { status: 404 })
    console.log(`download from ${server.requestIP(req)?.address}`)
    return new Response(Bun.file(APK), {
      headers: {
        "content-type": "application/vnd.android.package-archive",
        "content-disposition": "attachment; filename=\"Claude-Car.apk\"",
      },
    })
  },
})
setTimeout(() => { console.log("Link expired."); process.exit(0) }, Number(MINUTES) * 60_000)
'
