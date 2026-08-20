#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

set -euo pipefail

# Dependency-free formatting guard for the repository's checked-in text files.
# It intentionally checks source/docs rather than generated build output.
mapfile -t files < <(
  find app/src core gradle docs tools .github \
    -type d -name build -prune -o -type f \( \
    -name '*.kt' -o -name '*.kts' -o -name '*.xml' -o -name '*.md' \
    -o -name '*.json' -o -name '*.yaml' -o -name '*.yml' -o -name '*.pro' \
    -o -name '*.sh' -o -name '*.toml' \
  \) -print | sort
)
files+=(README.md LICENSE build.gradle.kts settings.gradle.kts .gitignore)

status=0
for file in "${files[@]}"; do
  if [[ ! -f "$file" ]]; then
    echo "format: missing file: $file" >&2
    status=1
    continue
  fi
  if grep -nE '[[:blank:]]+$' "$file"; then
    echo "format: trailing whitespace: $file" >&2
    status=1
  fi
  if [[ -s "$file" ]] && [[ "$(tail -c 1 "$file" | wc -l)" -eq 0 ]]; then
    echo "format: missing final newline: $file" >&2
    status=1
  fi
done

if [[ "$status" -ne 0 ]]; then
  echo "format: checks failed" >&2
  exit "$status"
fi
echo "format: checked ${#files[@]} files"
