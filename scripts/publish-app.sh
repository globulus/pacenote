#!/usr/bin/env bash
# Builds the phone app and publishes it to the service, so the phone can
# update itself (Pacenote → Update app) without a cable or adb.
#
#   scripts/publish-app.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
PACENOTE_HOME="${PACENOTE_HOME:-$HOME/.pacenote}"
APP_DIR="$PACENOTE_HOME/app"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

# A release build if you have the signing key (it updates the app from GitHub
# Releases); otherwise a debug build (it updates an app you built yourself).
SIGNING="${PACENOTE_SIGNING:-$HOME/.pacenote-release/signing.properties}"
VARIANT="${PACENOTE_VARIANT:-$([[ -f "$SIGNING" ]] && echo release || echo debug)}"
TASK="assemble$(tr '[:lower:]' '[:upper:]' <<<"${VARIANT:0:1}")${VARIANT:1}"
(cd "$ROOT/android" && ./gradlew -q ":app:$TASK")

OUT="$ROOT/android/app/build/outputs/apk/$VARIANT"
mkdir -p "$APP_DIR"
chmod 700 "$PACENOTE_HOME" "$APP_DIR"
# Copy first, then swap in the version, so the phone never sees a version without its APK.
cp "$OUT/app-$VARIANT.apk" "$APP_DIR/pacenote.apk.tmp"
mv "$APP_DIR/pacenote.apk.tmp" "$APP_DIR/pacenote.apk"
python3 - "$OUT/output-metadata.json" "$APP_DIR/version.json" <<'EOF'
import json, sys
meta = json.load(open(sys.argv[1]))["elements"][0]
json.dump({"version_code": meta["versionCode"], "version_name": meta["versionName"]}, open(sys.argv[2], "w"))
print(f"Published {meta['versionName']} ({meta['versionCode']}). On the phone: Pacenote → Update app.")
EOF
