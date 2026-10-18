#!/usr/bin/env bash
# Builds the phone app and publishes it to the service, so the phone can
# update itself (Claude Car → Update app) without a cable or adb.
#
#   scripts/publish-app.sh
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CAR_HOME="${CLAUDE_CAR_HOME:-$HOME/.claude-car}"
APP_DIR="$CAR_HOME/app"
export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"

(cd "$ROOT/android" && ./gradlew -q :app:assembleDebug)

OUT="$ROOT/android/app/build/outputs/apk/debug"
mkdir -p "$APP_DIR"
chmod 700 "$CAR_HOME" "$APP_DIR"
# Copy first, then swap in the version, so the phone never sees a version without its APK.
cp "$OUT/app-debug.apk" "$APP_DIR/claude-car.apk.tmp"
mv "$APP_DIR/claude-car.apk.tmp" "$APP_DIR/claude-car.apk"
python3 - "$OUT/output-metadata.json" "$APP_DIR/version.json" <<'EOF'
import json, sys
meta = json.load(open(sys.argv[1]))["elements"][0]
json.dump({"version_code": meta["versionCode"], "version_name": meta["versionName"]}, open(sys.argv[2], "w"))
print(f"Published {meta['versionName']} ({meta['versionCode']}). On the phone: Claude Car → Update app.")
EOF
