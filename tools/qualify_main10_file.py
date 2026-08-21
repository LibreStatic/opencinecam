#!/usr/bin/env python3
"""Qualify Main10/HLG file signaling without promoting uncontrolled precision."""

from __future__ import annotations

import argparse
import json
import math
import struct
import subprocess
from pathlib import Path
from typing import Any

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors


def _result(status: str, reason_code: str, safe_message: str, **extra: Any) -> dict[str, Any]:
    return {"status": status, "reasonCode": reason_code, "safeMessage": safe_message, **extra}


def validate_file_signal(stream: dict[str, Any] | None) -> dict[str, Any]:
    if not stream:
        return _result("UNKNOWN", "file-signal-unknown", "No video stream metadata was available")
    required = ("codec_name", "profile", "color_space", "color_transfer", "pix_fmt")
    if any(stream.get(key) in (None, "") for key in required):
        return _result("UNKNOWN", "file-signal-unknown", "Main10/HLG file metadata is incomplete")
    if stream["codec_name"] != "hevc":
        return _result("FAIL", "mime-not-hevc", "File codec is not HEVC")
    if "main 10" not in stream["profile"].lower():
        return _result("FAIL", "profile-not-main10", "File profile is not Main10")
    if not stream["color_space"].lower().startswith("bt2020"):
        return _result("FAIL", "color-standard-mismatch", "File color space is not BT.2020")
    if stream["color_transfer"].lower() != "arib-std-b67":
        return _result("FAIL", "color-transfer-mismatch", "File transfer is not HLG/ARIB STD-B67")
    if stream["pix_fmt"].lower() != "yuv420p10le":
        return _result("FAIL", "pixel-format-not-10-bit", "File pixel format is not 10-bit YUV 4:2:0")
    return _result("PASS", "main10-hlg-signal-pass", "HEVC Main10 BT.2020 HLG signaling is present")


def measure_distinct_bits(samples: list[int] | tuple[int, ...]) -> dict[str, Any]:
    distinct = len(set(samples))
    measured = 0 if distinct <= 1 else math.ceil(math.log2(distinct))
    return {
        "status": "PASS" if measured >= 10 else "FAIL",
        "measuredBits": measured,
        "sampleCount": len(samples),
        "distinctSamples": distinct,
        "reasonCode": "effective-depth-pass" if measured >= 10 else "effective-depth-below-10",
    }


def controlled_gradient() -> dict[str, Any]:
    """Return the deterministic 10-bit fixture used to validate the qualifier."""
    samples = list(range(1024)) + list(range(1023, -1, -1))
    result = measure_distinct_bits(samples)
    result["fixture"] = "monotonic-10-bit-gradient"
    return result


def _probe(path: Path, ffprobe: str) -> dict[str, Any]:
    completed = subprocess.run(
        [ffprobe, "-v", "error", "-of", "json", "-show_streams", "-show_format", str(path)],
        check=True,
        capture_output=True,
        text=True,
        timeout=30,
    )
    return json.loads(completed.stdout)


def _decode_samples(path: Path, ffmpeg: str) -> list[int]:
    completed = subprocess.run(
        [
            ffmpeg,
            "-v",
            "error",
            "-i",
            str(path),
            "-frames:v",
            "2",
            "-f",
            "rawvideo",
            "-pix_fmt",
            "yuv420p10le",
            "pipe:1",
        ],
        check=True,
        capture_output=True,
        timeout=30,
    )
    data = completed.stdout[:4_000_000]
    if len(data) < 2:
        return []
    values = struct.unpack("<" + "H" * (len(data) // 2), data[: len(data) - len(data) % 2])
    return list(values[::16][:65_536])


def qualify(path: Path, ffprobe: str = "ffprobe", ffmpeg: str = "ffmpeg") -> dict[str, Any]:
    metadata = _probe(path, ffprobe)
    stream = next((item for item in metadata.get("streams", []) if item.get("codec_type") == "video"), None)
    file_signal = validate_file_signal(stream)
    decoded = _decode_samples(path, ffmpeg)
    observed = measure_distinct_bits(decoded) if decoded else _result(
        "UNKNOWN", "decoded-samples-unknown", "No decoded 10-bit samples were available"
    )
    # A camera scene is not a controlled gradient. Keep this diagnostic separate
    # and do not use it to promote effective precision.
    effective = _result(
        "UNKNOWN",
        "controlled-gradient-required",
        "Effective precision requires a controlled gradient/test fixture",
        observed=observed,
    )
    return {
        "schema": "opencinecam-main10-qualification-v1",
        "protocolVersion": 1,
        "input": str(path),
        "fileSignal": file_signal,
        "effectivePrecision": effective,
        "controlledGradientFixture": controlled_gradient(),
        "promoted": file_signal["status"] == "PASS" and effective["status"] == "PASS",
        "stream": stream,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--ffprobe", default="ffprobe")
    parser.add_argument("--ffmpeg", default="ffmpeg")
    args = parser.parse_args()
    try:
        report = qualify(args.input, args.ffprobe, args.ffmpeg)
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError, ValueError) as error:
        report = {
            "schema": "opencinecam-main10-qualification-v1",
            "protocolVersion": 1,
            "input": str(args.input),
            "fileSignal": _result("UNKNOWN", "probe-failed", f"Independent probe failed: {error}"),
            "effectivePrecision": _result("UNKNOWN", "probe-failed", "Precision cannot be measured without a valid probe"),
            "controlledGradientFixture": controlled_gradient(),
            "promoted": False,
        }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
