#!/usr/bin/env bash
# A temporary download link for the phone app, for installing it without a
# cable (after that, use Update app in the app). The link only works on your
# tailnet and expires after 20 minutes.
#
#   scripts/share-apk.sh
set -euo pipefail

PACENOTE_HOME="${PACENOTE_HOME:-$HOME/.pacenote}"
export APK="$PACENOTE_HOME/app/pacenote.apk"
[[ -f "$APK" ]] || { echo "No published app: run scripts/publish-app.sh first." >&2; exit 1; }
SERVICE_URL="$(cat "$PACENOTE_HOME/url" 2>/dev/null || true)"
[[ -n "$SERVICE_URL" ]] || { echo "No $PACENOTE_HOME/url: run scripts/setup.sh first." >&2; exit 1; }
# The same Tailscale address as the service, on another port.
export BIND="${PACENOTE_BIND:-$(sed -E 's#^https?://([^:/]+).*#\1#' <<<"$SERVICE_URL")}"
export PORT="${SHARE_PORT:-8789}"
export MINUTES="${SHARE_MINUTES:-20}"
# The APK has the service token inside: an unguessable path, not a public file.
export KEY="$(openssl rand -hex 16)"

echo "On the phone, with Tailscale on, open:"
echo "  http://$BIND:$PORT/$KEY/Pacenote.apk"
echo "Then open the download and allow installing. The link expires in $MINUTES minutes."

exec bun -e '
const { APK, BIND, PORT, KEY, MINUTES } = process.env
Bun.serve({
  hostname: BIND,
  port: Number(PORT),
  fetch(req, server) {
    if (new URL(req.url).pathname !== `/${KEY}/Pacenote.apk`) return new Response("Not found", { status: 404 })
    console.log(`download from ${server.requestIP(req)?.address}`)
    return new Response(Bun.file(APK), {
      headers: {
        "content-type": "application/vnd.android.package-archive",
        "content-disposition": "attachment; filename=\"Pacenote.apk\"",
      },
    })
  },
})
setTimeout(() => { console.log("Link expired."); process.exit(0) }, Number(MINUTES) * 60_000)
'
