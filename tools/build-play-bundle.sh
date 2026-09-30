#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors
#
# Builds the release App Bundle for Google Play and signs it with the Play upload key.
# The upload key is separate from the GitHub/F-Droid release key (tools/build-verified-release.sh).
# Credentials come from OPENCINECAM_UPLOAD_* env vars (CI) or ~/.android/opencinecam/keystore.properties.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

PROPS="${OPENCINECAM_UPLOAD_PROPERTIES:-$HOME/.android/opencinecam/keystore.properties}"
prop() { [[ -f "$PROPS" ]] && sed -n "s/^$1=//p" "$PROPS" | head -1; }
export OPENCINECAM_UPLOAD_KEYSTORE="${OPENCINECAM_UPLOAD_KEYSTORE:-$(prop storeFile)}"
export OPENCINECAM_UPLOAD_KEYSTORE_PASSWORD="${OPENCINECAM_UPLOAD_KEYSTORE_PASSWORD:-$(prop storePassword)}"
export OPENCINECAM_UPLOAD_KEY_ALIAS="${OPENCINECAM_UPLOAD_KEY_ALIAS:-$(prop keyAlias)}"
export OPENCINECAM_UPLOAD_KEY_PASSWORD="${OPENCINECAM_UPLOAD_KEY_PASSWORD:-$(prop keyPassword)}"

: "${OPENCINECAM_UPLOAD_KEYSTORE:?upload keystore path is required}"
: "${OPENCINECAM_UPLOAD_KEYSTORE_PASSWORD:?upload keystore password is required}"
: "${OPENCINECAM_UPLOAD_KEY_ALIAS:?upload key alias is required}"
: "${OPENCINECAM_UPLOAD_KEY_PASSWORD:?upload key password is required}"
test -f "$OPENCINECAM_UPLOAD_KEYSTORE"

VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName = "\([^"]*\)"/\1/p' app/build.gradle.kts | head -1)"
if [[ -n "${GITHUB_RUN_NUMBER:-}" ]]; then
    VERSION_CODE=$((GITHUB_RUN_NUMBER + 100))
else
    VERSION_CODE="$(sed -n 's/^[[:space:]]*?: \([0-9][0-9]*\)$/\1/p' app/build.gradle.kts | head -1)"
fi
test -n "$VERSION_NAME" && test -n "$VERSION_CODE"

OUTPUT_DIR="${RELEASE_OUTPUT_DIR:-build/release}"
UNSIGNED="app/build/outputs/bundle/release/app-release.aab"
SIGNED="$OUTPUT_DIR/OpenCineCam-$VERSION_NAME-$VERSION_CODE-play.aab"

mkdir -p "$OUTPUT_DIR"
rm -f "$UNSIGNED" "$SIGNED"
./gradlew --no-daemon --dependency-verification=strict --console=plain :app:bundleRelease
test -f "$UNSIGNED"

cp "$UNSIGNED" "$SIGNED"
jarsigner -sigalg SHA256withRSA -digestalg SHA-256 \
    -keystore "$OPENCINECAM_UPLOAD_KEYSTORE" \
    -storepass:env OPENCINECAM_UPLOAD_KEYSTORE_PASSWORD \
    -keypass:env OPENCINECAM_UPLOAD_KEY_PASSWORD \
    "$SIGNED" "$OPENCINECAM_UPLOAD_KEY_ALIAS" >/dev/null
# Not -strict: a self-signed upload certificate without a timestamp is expected for Play.
jarsigner -verify "$SIGNED" | grep '^jar verified\.' >/dev/null
keytool -printcert -jarfile "$SIGNED" | grep -m1 'SHA256:'

# Refuse a bundle whose manifest or native libraries drifted from the Play contract.
unzip -l "$SIGNED" | grep -q ' base/manifest/AndroidManifest.xml$'
abis="$(unzip -Z1 "$SIGNED" | awk -F/ '$1 == "base" && $2 == "lib" && NF > 3 {print $3}' | sort -u | paste -sd' ' -)"
[[ -z "$abis" || "$abis" == "arm64-v8a" ]] || { echo "Unexpected ABIs in $SIGNED: $abis" >&2; exit 1; }

sha256sum "$SIGNED" > "$SIGNED.sha256"
echo "Signed Play bundle: $ROOT/$SIGNED (versionName $VERSION_NAME, versionCode $VERSION_CODE)"
