#!/usr/bin/env python3
"""Read the small, dependency-free subset of TIFF/DNG tags used by the gate."""

from __future__ import annotations

import argparse
import json
import struct
from pathlib import Path
from typing import Any

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors


TYPE_SIZES = {1: 1, 2: 1, 3: 2, 4: 4, 5: 8, 7: 1, 9: 4, 10: 8}
TAG_NAMES = {
    256: "ImageWidth",
    257: "ImageLength",
    258: "BitsPerSample",
    259: "Compression",
    262: "PhotometricInterpretation",
    273: "StripOffsets",
    277: "SamplesPerPixel",
    278: "RowsPerStrip",
    279: "StripByteCounts",
    33421: "CFARepeatPatternDim",
    33422: "CFAPattern",
    50706: "DNGVersion",
    50707: "DNGBackwardVersion",
    50714: "BlackLevel",
    50717: "WhiteLevel",
}


def _decode_value(data: bytes, byte_order: str, type_id: int, count: int, offset: int) -> Any:
    size = TYPE_SIZES[type_id] * count
    raw = data[offset : offset + size]
    if type_id == 2:
        return raw.rstrip(b"\0").decode("ascii", errors="replace")
    if type_id in (1, 7):
        return list(raw)
    if type_id == 3:
        return list(struct.unpack(byte_order + f"{count}H", raw))
    if type_id == 4:
        return list(struct.unpack(byte_order + f"{count}I", raw))
    if type_id == 9:
        return list(struct.unpack(byte_order + f"{count}i", raw))
    if type_id in (5, 10):
        fmt = "II" if type_id == 5 else "ii"
        return [struct.unpack(byte_order + fmt, raw[index : index + 8]) for index in range(0, size, 8)]
    raise ValueError(f"unsupported TIFF type {type_id}")


def _summarize(value: Any) -> Any:
    if isinstance(value, list) and len(value) > 16:
        return {"count": len(value), "first": value[:4], "last": value[-4:]}
    return value


def read_dng(path: Path) -> dict[str, Any]:
    data = path.read_bytes()
    if data[:2] == b"II":
        byte_order = "<"
    elif data[:2] == b"MM":
        byte_order = ">"
    else:
        raise ValueError("not a TIFF/DNG byte order")
    if struct.unpack(byte_order + "H", data[2:4])[0] != 42:
        raise ValueError("unsupported TIFF magic")
    ifd_offset = struct.unpack(byte_order + "I", data[4:8])[0]
    count = struct.unpack(byte_order + "H", data[ifd_offset : ifd_offset + 2])[0]
    tags: dict[str, Any] = {}
    for index in range(count):
        entry = ifd_offset + 2 + index * 12
        tag, type_id, value_count = struct.unpack(byte_order + "HHI", data[entry : entry + 8])
        if type_id not in TYPE_SIZES:
            continue
        value_size = TYPE_SIZES[type_id] * value_count
        value_offset = entry + 8 if value_size <= 4 else struct.unpack(byte_order + "I", data[entry + 8 : entry + 12])[0]
        tags[TAG_NAMES.get(tag, f"tag-{tag}")] = _summarize(
            _decode_value(data, byte_order, type_id, value_count, value_offset)
        )
    width = tags.get("ImageWidth", [0])[0]
    height = tags.get("ImageLength", [0])[0]
    strip_bytes = tags.get("StripByteCounts", [0])
    if isinstance(strip_bytes, dict):
        payload_bytes = strip_bytes["count"] * int(strip_bytes["first"][0])
    else:
        payload_bytes = sum(strip_bytes) if isinstance(strip_bytes, list) else int(strip_bytes)
    required = {"ImageWidth", "ImageLength", "BitsPerSample", "StripOffsets", "StripByteCounts", "CFARepeatPatternDim", "CFAPattern", "DNGVersion"}
    missing = sorted(required.difference(tags))
    status = "PASS" if width > 0 and height > 0 and payload_bytes > 0 and not missing else "FAIL"
    return {
        "schema": "opencinecam-dng-validation-v1",
        "status": status,
        "reasonCode": "dng-tags-payload-pass" if status == "PASS" else "dng-tags-or-payload-invalid",
        "file": str(path),
        "fileBytes": len(data),
        "payloadBytes": payload_bytes,
        "missingRequiredTags": missing,
        "tags": tags,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    try:
        report = read_dng(args.input)
    except (OSError, ValueError, struct.error) as error:
        report = {"schema": "opencinecam-dng-validation-v1", "status": "UNKNOWN", "reasonCode": "dng-parse-failed", "safeMessage": str(error)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(report, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
