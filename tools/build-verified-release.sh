#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

SDK_DIR="${ANDROID_SDK_ROOT:-$(sed -n 's/^sdk.dir=//p' local.properties)}"
BUILD_TOOLS="$(find "$SDK_DIR/build-tools" -mindepth 1 -maxdepth 1 -type d | sort -V | tail -1)"
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -1)"
VERSION_CODE="$(sed -n 's/^[[:space:]]*versionCode = \([0-9][0-9]*\)/\1/p' app/build.gradle.kts | head -1)"
UNSIGNED="app/build/outputs/apk/release/app-release-unsigned.apk"
OUTPUT_DIR="${RELEASE_OUTPUT_DIR:-build/release}"
ALIGNED="$OUTPUT_DIR/OpenCineCam-$VERSION_NAME-arm64-aligned.apk"
SIGNED="$OUTPUT_DIR/OpenCineCam-$VERSION_NAME-arm64-release.apk"
KEYSTORE="${ANDROID_RELEASE_KEYSTORE:-}"

: "${KEYSTORE:?ANDROID_RELEASE_KEYSTORE must point to the release keystore}"
: "${ANDROID_RELEASE_KEYSTORE_PASSWORD:?ANDROID_RELEASE_KEYSTORE_PASSWORD is required}"
: "${ANDROID_RELEASE_KEY_ALIAS:?ANDROID_RELEASE_KEY_ALIAS is required}"
: "${ANDROID_RELEASE_KEY_PASSWORD:?ANDROID_RELEASE_KEY_PASSWORD is required}"
test -n "$SDK_DIR" && test -d "$BUILD_TOOLS"
test -n "$VERSION_NAME" && test -n "$VERSION_CODE"

if [[ -n "${RELEASE_TAG:-}" && "${RELEASE_TAG#v}" != "$VERSION_NAME" ]]; then
    echo "Release tag $RELEASE_TAG does not match Android versionName $VERSION_NAME." >&2
    exit 1
fi

mkdir -p "$OUTPUT_DIR"
rm -f "$UNSIGNED" "$ALIGNED" "$SIGNED" "$SIGNED.idsig" "$SIGNED.sha256"
./gradlew --no-daemon --dependency-verification=strict --console=plain \
    testDebugUnitTest lintRelease :app:assembleRelease --rerun-tasks

test -f "$UNSIGNED"
if find app/src camera/src media/src core/model/src -type f -newer "$UNSIGNED" -print -quit | grep -q .; then
  echo "Refusing to sign: release APK is older than a tracked source file." >&2
  exit 1
fi

mkdir -p build
"$BUILD_TOOLS/zipalign" -f -p 4 "$UNSIGNED" "$ALIGNED"
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$ANDROID_RELEASE_KEY_ALIAS" \
    --ks-pass env:ANDROID_RELEASE_KEYSTORE_PASSWORD \
    --key-pass env:ANDROID_RELEASE_KEY_PASSWORD \
    --out "$SIGNED" \
    "$ALIGNED"
"$BUILD_TOOLS/apksigner" verify --verbose --print-certs "$SIGNED"

"$BUILD_TOOLS/aapt" dump badging "$SIGNED" | grep -q "versionCode='$VERSION_CODE' versionName='$VERSION_NAME'"
unzip -p "$SIGNED" 'classes*.dex' > build/verified-release-classes.dex
if ! grep -aq 'rendererRotationDegrees' build/verified-release-classes.dex; then
  echo "Refusing artifact: new recording geometry contract is missing from the APK." >&2
  exit 1
fi
if ! grep -aq 'AudioLevelMeter' build/verified-release-classes.dex || ! grep -aq 'redHistogram' build/verified-release-classes.dex; then
  echo "Refusing artifact: live audio/scopes contracts are missing from the APK." >&2
  exit 1
fi
sha256sum "$SIGNED" > "$SIGNED.sha256"
echo "Verified release: $ROOT/$SIGNED"
