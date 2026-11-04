#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors
#
# Regenerates the CinemaDNG golden fixtures (OCC-PLAN-071 §8) under
# media/src/test/resources/ocraw/cinemadng/:
#   1. CinemaDngGoldenTest.buildTake writes the fixture take with OcrawStreamWriter
#      to media/build/cinemadng-golden/A001_C001.ocraw (the test fails while the
#      checked-in copy differs; that is expected here);
#   2. the take is copied beside the goldens;
#   3. desktop-tools/ocraw_to_dng.py --cinemadng writes the expected DNGs and WAV;
#   4. the golden test runs again and must pass.
# Needs JAVA_HOME (JDK 17) and ANDROID_HOME, as any Gradle build here. Set GRADLE_LOCK
# to wrap the Gradle calls in flock (e.g. ~/.cache/claude-tmp/opencinecam/gradle.lock).
set -euo pipefail

root=$(cd "$(dirname "$0")/.." && pwd)
take=A001_C001
golden=$root/media/src/test/resources/ocraw/cinemadng
test_filter='com.librestatic.opencinecam.media.ocraw.CinemaDngGoldenTest'

gradle() {
    local cmd=("$root/gradlew" -p "$root" :media:testDebugUnitTest --tests "$1" --rerun
        -Dorg.gradle.parallel=false --max-workers=2)
    if [[ -n "${GRADLE_LOCK:-}" ]]; then
        flock "$GRADLE_LOCK" "${cmd[@]}"
    else
        "${cmd[@]}"
    fi
}

gradle "$test_filter.builderReproducesTheCheckedInTake" || true
built=$root/media/build/cinemadng-golden/$take.ocraw
[[ -f $built ]] || { echo "the fixture builder did not write $built" >&2; exit 1; }

mkdir -p "$golden"
cp "$built" "$golden/$take.ocraw"
rm -rf "${golden:?}/$take"
python3 "$root/desktop-tools/ocraw_to_dng.py" "$golden/$take.ocraw" --cinemadng "$golden" --pure

gradle "$test_filter"
echo "regenerated $golden"
