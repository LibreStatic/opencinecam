#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK_DIR="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
BUILD_TOOLS="$(find "$SDK_DIR/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
ALIGNED="build/OpenCineCam-0.3.3-aligned.apk"
SIGNED="OpenCineCam-0.3.3-verified.apk"
KEYSTORE="${ANDROID_KEYSTORE:-$HOME/.android/debug.keystore}"

rm -f "$UNSIGNED" "$ALIGNED" "$SIGNED" "$SIGNED.idsig" "$SIGNED.sha256"
./gradlew testDebugUnitTest lintRelease :app:assembleRelease --rerun-tasks

test -f "$UNSIGNED"
if find app/src camera/src media/src core/model/src -type f -newer "$UNSIGNED" -print -quit | grep -q .; then
  echo "Refusing to sign: release APK is older than a tracked source file." >&2
  exit 1
fi

mkdir -p build
"$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$SIGNED" \
  "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose "$SIGNED"

"$BUILD_TOOLS/aapt" dump badging "$SIGNED" | grep -q "versionCode='8' versionName='0.3.3'"
unzip -p "$SIGNED" 'classes*.dex' > build/verified-release-classes.dex
if ! grep -aq 'rendererRotationDegrees' build/verified-release-classes.dex; then
  echo "Refusing artifact: new recording geometry contract is missing from the APK." >&2
  exit 1
fi
if ! grep -aq 'AudioLevelMeter' build/verified-release-classes.dex || ! grep -aq 'redHistogram' build/verified-release-classes.dex; then
  echo "Refusing artifact: live audio/scopes contracts are missing from the APK." >&2
  exit 1
fi
sha256sum "$SIGNED" | tee "$SIGNED.sha256"
echo "Verified release: $ROOT/$SIGNED"
