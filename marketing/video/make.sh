#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Builds the soundtrack, renders both cuts and muxes them: out/opencinecam-{landscape,portrait}.mp4
set -euo pipefail
cd "$(dirname "$0")"
formats=("${@:-landscape portrait}")
node music.mjs
for format in ${formats[@]}; do
  node render.mjs --format "$format"
  ffmpeg -y -loglevel error -i "out/opencinecam-$format-video.mp4" -i out/music.wav \
    -map 0:v -map 1:a -c:v copy -c:a aac -b:a 256k -shortest -movflags +faststart \
    "out/opencinecam-$format.mp4"
  echo "wrote out/opencinecam-$format.mp4"
done
