#!/usr/bin/env python3
"""Generate and evaluate the OCLog2 independent-editor (DaVinci Resolve) round-trip fixture.

The fixture is a set of deterministic float EXR stills and one synthetic HEVC Main10 clip.
An operator runs them through the editor following docs/color/oclog2-resolve-protocol.md and
renders 32-bit float EXR frames; `compare` measures each rendered frame against the analytic
OCLog2 reference, and `record` folds the case results into one `workflows[]` entry for
tools/qualify_oclog2.py. Nothing here talks to the editor, so a missing case stays NOT_RUN.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
import math
import shutil
import struct
import subprocess
import sys
from array import array
from pathlib import Path
from typing import Callable


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "local-evidence" / "oclog2-resolve"
SPEC_VERSION = "2.0.0"
FIXTURE_SCHEMA = "opencinecam-oclog2-editor-fixture-v1"
CASE_SCHEMA = "opencinecam-oclog2-editor-case-v1"
WIDTH = 1920
HEIGHT = 1080
BLACK = 0.10
WHITE = 0.90
LN51 = math.log(51.0)
TOLERANCE = 2e-5
# Decoder agreement is measured in 10-bit code values (1 LSB = 1/1023), not against 2e-5:
# two YCbCr-to-RGB paths legitimately differ by rounding and chroma upsampling.
LSB10 = 1.0 / 1023.0
SIGNAL_PATCH_TOLERANCE = 0.5 * LSB10
CAMERA_MEDIAN_TOLERANCE = 0.5 * LSB10
CAMERA_P95_TOLERANCE = 2.0 * LSB10
MONOTONIC_SLACK = 1e-7

# Rows of the float stills. Dense: every component sweeps the domain, rotated per channel so
# a channel swap or cross-channel leak cannot pass. Near-floor: neutral log-spaced samples on
# the steep part of the curve. Patches: reference vectors and out-of-domain boundary values.
DENSE_ROWS = (0, 768)
NEAR_FLOOR_ROWS = (768, 960)
PATCH_ROWS = (960, 1080)
VECTOR_INPUTS = (0.0, 0.0009765625, 0.01, 0.18, 0.5, 0.9, 1.0)
FORWARD_BOUNDARY = (-0.25, -0.01, 1.01, 1.5, 4.0)
INVERSE_BOUNDARY = (0.0, 0.05, 0.0999, 0.9001, 0.95, 1.0)

# Synthetic HEVC: 8x3 grid of flat 240x360 patches; (Y, Cb, Cr) 10-bit full-range codes.
# Row 0-1 are neutral steps including sub-black (<0.10) and super-white (>0.90) codes; row 2
# carries chroma so a BT.709/BT.2020 matrix mix-up is visible.
SIGNAL_FPS = 30
SIGNAL_FRAMES = 30
SIGNAL_PATCHES = (
    (0, 512, 512), (32, 512, 512), (64, 512, 512), (102, 512, 512),
    (205, 512, 512), (307, 512, 512), (410, 512, 512), (512, 512, 512),
    (582, 512, 512), (614, 512, 512), (716, 512, 512), (819, 512, 512),
    (921, 512, 512), (960, 512, 512), (1000, 512, 512), (1023, 512, 512),
    (512, 600, 512), (512, 512, 600), (512, 430, 512), (512, 512, 430),
    (400, 560, 560), (600, 470, 470), (700, 512, 560), (300, 560, 512),
)
SIGNAL_COLUMNS = 8
SIGNAL_ROWS = 3
KR, KB = 0.2627, 0.0593  # BT.2020 non-constant luminance
KR709, KB709 = 0.2126, 0.0722

REQUIRED_CASES = (
    "forward-dctl", "inverse-dctl", "roundtrip-dctl",
    "forward-lut1d", "forward-lut3d", "signal-hevc", "camera-clip",
)
TRANSFORM_CASES = {
    "forward-dctl": "forward", "inverse-dctl": "inverse", "roundtrip-dctl": "roundtrip",
    "forward-lut1d": "forward", "forward-lut3d": "forward", "forward-ocio": "forward",
}
OPTIONAL_CASES = ("forward-ocio",)
IMPLEMENTATION_FOR_CASE = {
    "forward-dctl": "dctl", "forward-lut1d": "lut1d", "forward-lut3d": "lut3d", "forward-ocio": "ocio",
}


def encode(x: float) -> float:
    x = min(max(x, 0.0), 1.0)
    return BLACK + (WHITE - BLACK) * math.log1p(50.0 * x) / LN51


def decode(y: float) -> float:
    y = min(max(y, BLACK), WHITE)
    return math.expm1(LN51 * (y - BLACK) / (WHITE - BLACK)) / 50.0


def roundtrip(x: float) -> float:
    return min(max(x, 0.0), 1.0)


EXPECTED: dict[str, Callable[[float], float]] = {"forward": encode, "inverse": decode, "roundtrip": roundtrip}


# ---------------------------------------------------------------------------------------------
# Fixture content


def _patch_values(values: tuple[float, ...]) -> list[float]:
    """Per-column values for a row of equal-width patches; the last patch absorbs remainder."""
    width = WIDTH // len(values)
    return [values[min(x // width, len(values) - 1)] for x in range(WIDTH)]


def still_planes(kind: str) -> tuple[array, array, array]:
    """Scene-linear (`forward`) or OCLog2 code (`inverse`) stills as float32 R, G, B planes."""
    low, high = (0.0, 1.0) if kind == "forward" else (BLACK, WHITE)
    span = high - low
    red, green, blue = array("f"), array("f"), array("f")
    dense = (DENSE_ROWS[1] - DENSE_ROWS[0]) * WIDTH
    third = dense // 3
    for index in range(dense):
        red.append(low + span * index / (dense - 1))
        green.append(low + span * ((index + third) % dense) / (dense - 1))
        blue.append(low + span * ((index + 2 * third) % dense) / (dense - 1))
    floor = (NEAR_FLOOR_ROWS[1] - NEAR_FLOOR_ROWS[0]) * WIDTH
    for index in range(floor):
        value = low + 10.0 ** (-7.0 + 5.0 * index / (floor - 1))
        red.append(value)
        green.append(value)
        blue.append(value)
    if kind == "forward":
        patches = VECTOR_INPUTS + FORWARD_BOUNDARY
    else:
        patches = tuple(encode(value) for value in VECTOR_INPUTS) + INVERSE_BOUNDARY
    row = _patch_values(patches)
    for _ in range(PATCH_ROWS[1] - PATCH_ROWS[0]):
        red.extend(row)
        green.extend(row)
        blue.extend(row)
    return red, green, blue


def signal_patch_index(x: int, y: int) -> int:
    return (y // (HEIGHT // SIGNAL_ROWS)) * SIGNAL_COLUMNS + x // (WIDTH // SIGNAL_COLUMNS)


def ycbcr_to_rgb(y: int, cb: int, cr: int, full: bool = True, bt2020: bool = True) -> tuple[float, float, float]:
    """H.273 10-bit YCbCr codes to normalized non-linear R'G'B'."""
    if full:
        luma, pb, pr = y / 1023.0, (cb - 512) / 1023.0, (cr - 512) / 1023.0
    else:
        luma, pb, pr = (y - 64) / 876.0, (cb - 512) / 896.0, (cr - 512) / 896.0
    kr, kb = (KR, KB) if bt2020 else (KR709, KB709)
    red = luma + 2.0 * (1.0 - kr) * pr
    blue = luma + 2.0 * (1.0 - kb) * pb
    green = (luma - kr * red - kb * blue) / (1.0 - kr - kb)
    return red, green, blue


def signal_yuv_frame() -> bytes:
    """One yuv420p10le frame of the flat-patch signal chart."""
    luma = array("H")
    for y in range(HEIGHT):
        for x in range(WIDTH):
            luma.append(SIGNAL_PATCHES[signal_patch_index(x, y)][0])
    cb, cr = array("H"), array("H")
    for y in range(0, HEIGHT, 2):
        for x in range(0, WIDTH, 2):
            patch = SIGNAL_PATCHES[signal_patch_index(x, y)]
            cb.append(patch[1])
            cr.append(patch[2])
    if sys.byteorder != "little":
        for plane in (luma, cb, cr):
            plane.byteswap()
    return luma.tobytes() + cb.tobytes() + cr.tobytes()


# ---------------------------------------------------------------------------------------------
# Minimal OpenEXR scanline I/O (FLOAT/HALF channels; NONE compression natively, others via FFmpeg)


def _attribute(name: str, kind: str, payload: bytes) -> bytes:
    return name.encode() + b"\0" + kind.encode() + b"\0" + struct.pack("<i", len(payload)) + payload


def write_exr(path: Path, red: array, green: array, blue: array, width: int = WIDTH, height: int = HEIGHT) -> None:
    channels = b"".join(name + b"\0" + struct.pack("<iBBBBii", 2, 0, 0, 0, 0, 1, 1) for name in (b"B", b"G", b"R")) + b"\0"
    box = struct.pack("<iiii", 0, 0, width - 1, height - 1)
    header = (
        struct.pack("<ii", 20000630, 2)
        + _attribute("channels", "chlist", channels)
        + _attribute("compression", "compression", b"\0")
        + _attribute("dataWindow", "box2i", box)
        + _attribute("displayWindow", "box2i", box)
        + _attribute("lineOrder", "lineOrder", b"\0")
        + _attribute("pixelAspectRatio", "float", struct.pack("<f", 1.0))
        + _attribute("screenWindowCenter", "v2f", struct.pack("<ff", 0.0, 0.0))
        + _attribute("screenWindowWidth", "float", struct.pack("<f", 1.0))
        + b"\0"
    )
    row_bytes = width * 4 * 3
    offsets_start = len(header)
    first_block = offsets_start + 8 * height
    offsets = b"".join(struct.pack("<Q", first_block + y * (8 + row_bytes)) for y in range(height))
    with path.open("wb") as sink:
        sink.write(header)
        sink.write(offsets)
        for y in range(height):
            sink.write(struct.pack("<ii", y, row_bytes))
            for plane in (blue, green, red):
                row = plane[y * width:(y + 1) * width]
                if sys.byteorder != "little":
                    row = array("f", row)
                    row.byteswap()
                sink.write(row.tobytes())


class ExrImage:
    def __init__(self, width: int, height: int, red: array, green: array, blue: array, pixel_type: str, decoder: str) -> None:
        self.width, self.height = width, height
        self.red, self.green, self.blue = red, green, blue
        self.pixel_type, self.decoder = pixel_type, decoder


def _read_header(data: bytes) -> tuple[dict[str, tuple[str, bytes]], int]:
    if struct.unpack_from("<i", data, 0)[0] != 20000630:
        raise ValueError("not an OpenEXR file")
    if struct.unpack_from("<i", data, 4)[0] & 0x1600:
        raise ValueError("tiled, long-name, or multipart EXR is not supported; render scanline EXR")
    attributes: dict[str, tuple[str, bytes]] = {}
    offset = 8
    while data[offset] != 0:
        name_end = data.index(b"\0", offset)
        kind_end = data.index(b"\0", name_end + 1)
        size = struct.unpack_from("<i", data, kind_end + 1)[0]
        start = kind_end + 5
        attributes[data[offset:name_end].decode()] = (data[name_end + 1:kind_end].decode(), data[start:start + size])
        offset = start + size
    return attributes, offset + 1


def _channels(payload: bytes) -> list[tuple[str, int]]:
    result, offset = [], 0
    while payload[offset] != 0:
        end = payload.index(b"\0", offset)
        pixel_type = struct.unpack_from("<i", payload, end + 1)[0]
        result.append((payload[offset:end].decode(), pixel_type))
        offset = end + 17
    return result


def read_exr(path: Path) -> ExrImage:
    data = path.read_bytes()
    attributes, offset = _read_header(data)
    x0, y0, x1, y1 = struct.unpack("<iiii", attributes["dataWindow"][1])
    width, height = x1 - x0 + 1, y1 - y0 + 1
    channels = _channels(attributes["channels"][1])
    types = {name: kind for name, kind in channels}
    if not {"R", "G", "B"} <= types.keys():
        raise ValueError("EXR must contain R, G and B channels")
    pixel_type = {0: "uint", 1: "half", 2: "float"}.get(types["R"], "unknown")
    if attributes["compression"][1][0] != 0:
        return _read_exr_ffmpeg(path, width, height, pixel_type)
    sizes = {0: 4, 1: 2, 2: 4}
    formats = {0: "I", 1: "e", 2: "f"}
    planes: dict[str, array] = {name: array("f") for name in ("R", "G", "B")}
    cursor = offset + 8 * height
    for _ in range(height):
        _, size = struct.unpack_from("<ii", data, cursor)
        cursor += 8
        block = cursor
        for name, kind in channels:
            count = width * sizes[kind]
            if name in planes:
                values = struct.unpack_from(f"<{width}{formats[kind]}", data, block)
                planes[name].extend(float(value) for value in values)
            block += count
        cursor += size
    return ExrImage(width, height, planes["R"], planes["G"], planes["B"], pixel_type, "native")


def _read_exr_ffmpeg(path: Path, width: int, height: int, pixel_type: str) -> ExrImage:
    """Compressed EXR (ZIP/PIZ/...) is decoded by FFmpeg's EXR reader with no transfer applied."""
    if shutil.which("ffmpeg") is None:
        raise ValueError("compressed EXR needs ffmpeg; render 'RGB Float (No Compression)' instead")
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(path), "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "gbrpf32le", "-"],
        check=True, capture_output=True,
    ).stdout
    return _planar_gbr(raw, width, height, pixel_type, "ffmpeg-exr")


def _planar_gbr(raw: bytes, width: int, height: int, pixel_type: str, decoder: str) -> ExrImage:
    count = width * height
    if len(raw) != count * 12:
        raise ValueError(f"decoded frame has {len(raw)} bytes, expected {count * 12}")
    planes = []
    for index in range(3):
        plane = array("f")
        plane.frombytes(raw[index * count * 4:(index + 1) * count * 4])
        if sys.byteorder != "little":
            plane.byteswap()
        planes.append(plane)
    green, blue, red = planes
    return ExrImage(width, height, red, green, blue, pixel_type, decoder)


def ffmpeg_decode_frame(clip: Path, frame: int, width: int, height: int) -> ExrImage:
    """Reference decode of a full-range BT.2020 YCbCr clip to non-linear R'G'B' floats.

    FFmpeg only decodes to 10-bit YCbCr; the H.273 conversion happens here because swscale
    normalizes full-range 10-bit luma by 1024 rather than 1023 (up to ~1 LSB low at white).
    Chroma is upsampled by 2x2 replication.
    """
    raw = subprocess.run(
        ["ffmpeg", "-v", "error", "-i", str(clip), "-vf", f"select=eq(n\\,{frame})",
         "-frames:v", "1", "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-"],
        check=True, capture_output=True,
    ).stdout
    count = width * height
    if len(raw) != count * 3:
        raise ValueError(f"decoded frame has {len(raw)} bytes, expected {count * 3}; check --frame and resolution")
    codes = array("H")
    codes.frombytes(raw)
    if sys.byteorder != "little":
        codes.byteswap()
    chroma_width = width // 2
    cb_base, cr_base = count, count + count // 4
    red, green, blue = array("f"), array("f"), array("f")
    for y in range(height):
        chroma_row = (y // 2) * chroma_width
        for x in range(width):
            chroma = chroma_row + x // 2
            r, g, b = ycbcr_to_rgb(codes[y * width + x], codes[cb_base + chroma], codes[cr_base + chroma])
            red.append(r)
            green.append(g)
            blue.append(b)
    return ExrImage(width, height, red, green, blue, "float", "ffmpeg-yuv+h273")


# ---------------------------------------------------------------------------------------------
# Generation


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def generator_sha256() -> str:
    return hashlib.sha256(Path(__file__).read_bytes()).hexdigest()


def encode_signal_clip(path: Path) -> None:
    if shutil.which("ffmpeg") is None:
        raise SystemExit("ffmpeg with libx265 is required to build the signal clip")
    frame = signal_yuv_frame()
    # Lossless x265 keeps every flat patch bit-exact. VUI mirrors the app's OCLog2 recordings:
    # full range, BT.2020 primaries/matrix, transfer left unspecified (H.273 value 2, x265
    # "unknown"); the curve is in the sidecar.
    command = [
        "ffmpeg", "-v", "error", "-y",
        "-f", "rawvideo", "-pix_fmt", "yuv420p10le", "-s", f"{WIDTH}x{HEIGHT}", "-r", str(SIGNAL_FPS),
        "-color_range", "pc", "-color_primaries", "bt2020", "-color_trc", "unspecified", "-colorspace", "bt2020nc",
        "-i", "-",
        "-c:v", "libx265", "-pix_fmt", "yuv420p10le", "-profile:v", "main10",
        "-x265-params", "lossless=1:range=full:colorprim=bt2020:transfer=unknown:colormatrix=bt2020nc:log-level=error",
        "-color_range", "pc", "-color_primaries", "bt2020", "-color_trc", "unspecified", "-colorspace", "bt2020nc",
        "-tag:v", "hvc1", "-movflags", "+faststart", "-map_metadata", "-1", "-fflags", "+bitexact",
        str(path),
    ]
    subprocess.run(command, input=frame * SIGNAL_FRAMES, check=True)


def generate(output: Path) -> dict[str, object]:
    output.mkdir(parents=True, exist_ok=True)
    files = {
        "oclog2-forward-input.exr": lambda path: write_exr(path, *still_planes("forward")),
        "oclog2-inverse-input.exr": lambda path: write_exr(path, *still_planes("inverse")),
        "oclog2-signal-main10.mp4": encode_signal_clip,
    }
    for name, build in files.items():
        build(output / name)
    artifacts = ROOT / "docs" / "color" / "oclog2-v2"
    manifest = {
        "schema": FIXTURE_SCHEMA,
        "specVersion": SPEC_VERSION,
        "generator": "tools/oclog2_editor_fixture.py",
        "generatorSha256": generator_sha256(),
        "artifactManifestSha256": sha256_file(artifacts / "manifest.json"),
        "width": WIDTH,
        "height": HEIGHT,
        "files": {name: {"sha256": sha256_file(output / name), "bytes": (output / name).stat().st_size} for name in sorted(files)},
    }
    (output / "fixture-manifest.json").write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


# ---------------------------------------------------------------------------------------------
# Comparison


def _percentile(sorted_values: list[float], fraction: float) -> float:
    if not sorted_values:
        return math.nan
    return sorted_values[min(len(sorted_values) - 1, int(fraction * (len(sorted_values) - 1) + 0.5))]


def compare_transform(case: str, image: ExrImage) -> dict[str, object]:
    mode = TRANSFORM_CASES[case]
    source = "inverse" if mode == "inverse" else "forward"
    expected_of = EXPECTED[mode]
    inputs = still_planes(source)
    outputs = (image.red, image.green, image.blue)
    regions = {"dense": DENSE_ROWS, "nearFloor": NEAR_FLOOR_ROWS, "patches": PATCH_ROWS}
    patch_inputs = (VECTOR_INPUTS + FORWARD_BOUNDARY if source == "forward"
                    else tuple(encode(v) for v in VECTOR_INPUTS) + INVERSE_BOUNDARY)
    domain = (0.0, 1.0) if source == "forward" else (BLACK, WHITE)
    in_domain_max = 0.0
    boundary_max = 0.0
    region_max = {name: 0.0 for name in regions}
    worst: dict[str, object] = {}
    for name, (first, last) in regions.items():
        for index in range(first * WIDTH, last * WIDTH):
            for channel, (plane_in, plane_out) in enumerate(zip(inputs, outputs)):
                value = plane_in[index]
                error = abs(plane_out[index] - expected_of(value))
                if not math.isfinite(error):
                    error = math.inf
                region_max[name] = max(region_max[name], error)
                if domain[0] <= value <= domain[1]:
                    if error > in_domain_max:
                        in_domain_max = error
                        worst = {"x": index % WIDTH, "y": index // WIDTH, "channel": "RGB"[channel],
                                 "input": value, "expected": expected_of(value), "actual": plane_out[index]}
                else:
                    boundary_max = max(boundary_max, error)
    # Monotonicity on paths whose input increases with pixel order.
    violations = 0
    for first, last, plane in ((DENSE_ROWS[0], DENSE_ROWS[1], 0), (NEAR_FLOOR_ROWS[0], NEAR_FLOOR_ROWS[1], 1)):
        out = outputs[plane]
        for index in range(first * WIDTH, last * WIDTH - 1):
            if out[index + 1] < out[index] - MONOTONIC_SLACK:
                violations += 1
    patch_width = WIDTH // len(patch_inputs)
    center_row = (PATCH_ROWS[0] + PATCH_ROWS[1]) // 2 * WIDTH
    patch_report = []
    for patch in range(len(patch_inputs)):
        center = center_row + patch * patch_width + patch_width // 2
        value = inputs[0][center]
        patch_report.append({"input": value, "expected": expected_of(value), "actual": outputs[0][center]})
    reasons = []
    if in_domain_max > TOLERANCE:
        reasons.append("in-domain-error-above-tolerance")
    if boundary_max > TOLERANCE:
        reasons.append("boundary-clamp-mismatch")
    if violations:
        reasons.append("non-monotonic-output")
    return {
        "metric": "absolute error vs analytic OCLog2 reference (float64)",
        "tolerance": TOLERANCE,
        "maxAbsError": in_domain_max,
        "boundaryMaxAbsError": boundary_max,
        "regionMaxAbsError": region_max,
        "monotonicViolations": violations,
        "worstSample": worst,
        "patches": patch_report,
        "status": "FAIL" if reasons else "PASS",
        "reasons": reasons,
    }


def _patch_mean(image: ExrImage, patch: int) -> tuple[float, float, float]:
    patch_width, patch_height = WIDTH // SIGNAL_COLUMNS, HEIGHT // SIGNAL_ROWS
    x0 = (patch % SIGNAL_COLUMNS) * patch_width
    y0 = (patch // SIGNAL_COLUMNS) * patch_height
    # Inner 60%: chroma upsampling and any deblocking only touch patch edges.
    xs = range(x0 + patch_width // 5, x0 + patch_width - patch_width // 5)
    ys = range(y0 + patch_height // 5, y0 + patch_height - patch_height // 5)
    sums = [0.0, 0.0, 0.0]
    for y in ys:
        row = y * image.width
        for x in xs:
            sums[0] += image.red[row + x]
            sums[1] += image.green[row + x]
            sums[2] += image.blue[row + x]
    count = len(xs) * len(ys)
    return sums[0] / count, sums[1] / count, sums[2] / count


def compare_signal(image: ExrImage) -> dict[str, object]:
    interpretations = {
        "full-bt2020": (True, True), "limited-bt2020": (False, True),
        "full-bt709": (True, False), "limited-bt709": (False, False),
    }
    measured = [_patch_mean(image, index) for index in range(len(SIGNAL_PATCHES))]
    fits = {}
    for name, (full, bt2020) in interpretations.items():
        error = 0.0
        for patch, actual in zip(SIGNAL_PATCHES, measured):
            expected = ycbcr_to_rgb(*patch, full=full, bt2020=bt2020)
            if not full:  # a limited-range reading clips sub-black/super-white codes
                expected = tuple(min(max(v, 0.0), 1.0) for v in expected)
            error = max(error, max(abs(a - e) for a, e in zip(actual, expected)))
        fits[name] = error
    best = min(fits, key=fits.get)
    max_error = fits["full-bt2020"]
    neutral = [measured[index][1] for index in range(16)]
    sub_black = neutral[0] < neutral[1] < neutral[2] < neutral[3]
    super_white = neutral[12] < neutral[13] < neutral[14] < neutral[15]
    reasons = []
    if max_error > SIGNAL_PATCH_TOLERANCE:
        reasons.append(f"decoded-as-{best}" if best != "full-bt2020" else "patch-error-above-tolerance")
    if not sub_black:
        reasons.append("sub-black-codes-clipped")
    if not super_white:
        reasons.append("super-white-codes-clipped")
    return {
        "metric": "patch mean (inner 60%) vs H.273 full-range BT.2020 decode of the coded values",
        "tolerance": SIGNAL_PATCH_TOLERANCE,
        "maxAbsError": max_error,
        "maxAbsErrorLsb10": max_error / LSB10,
        "interpretationFit": fits,
        "bestInterpretation": best,
        "subBlackPreserved": sub_black,
        "superWhitePreserved": super_white,
        "patches": [
            {"ycbcr": list(patch), "expected": list(ycbcr_to_rgb(*patch)), "actual": list(actual)}
            for patch, actual in zip(SIGNAL_PATCHES, measured)
        ],
        "status": "FAIL" if reasons else "PASS",
        "reasons": reasons,
    }


def compare_camera(image: ExrImage, reference: ExrImage) -> dict[str, object]:
    errors: list[float] = []
    clipped = {"editorAtOrBelowBlack": 0, "editorAtOrAboveWhite": 0, "ffmpegAtOrBelowBlack": 0, "ffmpegAtOrAboveWhite": 0}
    for ours, theirs in ((image.red, reference.red), (image.green, reference.green), (image.blue, reference.blue)):
        for actual, expected in zip(ours, theirs):
            errors.append(abs(actual - expected))
            clipped["editorAtOrBelowBlack"] += actual <= BLACK
            clipped["editorAtOrAboveWhite"] += actual >= WHITE
            clipped["ffmpegAtOrBelowBlack"] += expected <= BLACK
            clipped["ffmpegAtOrAboveWhite"] += expected >= WHITE
    total = len(errors)
    errors.sort()
    median, p95 = _percentile(errors, 0.5), _percentile(errors, 0.95)
    minimum = min(min(image.green), min(image.red), min(image.blue))
    maximum = max(max(image.green), max(image.red), max(image.blue))
    reasons = []
    if median > CAMERA_MEDIAN_TOLERANCE:
        reasons.append("median-decoder-disagreement")
    if p95 > CAMERA_P95_TOLERANCE:
        reasons.append("p95-decoder-disagreement")
    return {
        "metric": "per-component absolute difference, editor render vs FFmpeg full-range BT.2020 decode",
        "medianTolerance": CAMERA_MEDIAN_TOLERANCE,
        "p95Tolerance": CAMERA_P95_TOLERANCE,
        "medianAbsError": median,
        "p95AbsError": p95,
        "maxAbsError": errors[-1] if errors else math.nan,
        "medianLsb10": median / LSB10,
        "p95Lsb10": p95 / LSB10,
        "editorRange": [minimum, maximum],
        "clippingFractions": {key: value / total for key, value in clipped.items()},
        "status": "FAIL" if reasons else "PASS",
        "reasons": reasons,
    }


def run_compare(case: str, render: Path, clip: Path | None, frame: int, fixture_dir: Path) -> dict[str, object]:
    image = read_exr(render)
    result: dict[str, object] = {
        "schema": CASE_SCHEMA,
        "case": case,
        "specVersion": SPEC_VERSION,
        "generatorSha256": generator_sha256(),
        "render": {"name": render.name, "sha256": sha256_file(render), "pixelType": image.pixel_type,
                   "decoder": image.decoder, "width": image.width, "height": image.height},
    }
    stale = _fixture_staleness(fixture_dir)
    reasons: list[str] = []
    if stale:
        reasons.append(stale)
    if image.pixel_type != "float":
        reasons.append("render-not-32-bit-float")
    if (image.width, image.height) != (WIDTH, HEIGHT) and case != "camera-clip":
        reasons.append("render-resolution-mismatch")
    if reasons:
        result.update(status="FAIL", reasons=reasons)
        return result
    if case in TRANSFORM_CASES:
        result.update(compare_transform(case, image))
    elif case == "signal-hevc":
        result.update(compare_signal(image))
    elif case == "camera-clip":
        if clip is None:
            raise SystemExit("--clip is required for camera-clip")
        reference = ffmpeg_decode_frame(clip, frame, image.width, image.height)
        result["clip"] = {"name": clip.name, "sha256": sha256_file(clip), "frame": frame}
        result.update(compare_camera(image, reference))
    else:
        raise SystemExit(f"unknown case {case}")
    return result


def _fixture_staleness(fixture_dir: Path) -> str | None:
    manifest_path = fixture_dir / "fixture-manifest.json"
    if not manifest_path.is_file():
        return "fixture-manifest-missing"
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("generatorSha256") != generator_sha256():
        return "fixture-generated-by-other-generator"
    return None


# ---------------------------------------------------------------------------------------------
# Workflow record for tools/qualify_oclog2.py


def build_record(case_paths: list[Path], editor: str, version: str, edition: str) -> dict[str, object]:
    cases: dict[str, dict[str, object]] = {}
    for path in case_paths:
        value = json.loads(path.read_text(encoding="utf-8"))
        if value.get("schema") != CASE_SCHEMA or not isinstance(value.get("case"), str):
            raise SystemExit(f"{path.name} is not an editor case result")
        if value["case"] in cases:
            raise SystemExit(f"duplicate case {value['case']}")
        cases[value["case"]] = value
    statuses = {name: cases[name]["status"] if name in cases else "NOT_RUN" for name in REQUIRED_CASES + OPTIONAL_CASES}
    required = [statuses[name] for name in REQUIRED_CASES]
    status = "FAIL" if "FAIL" in required else "NOT_RUN" if "NOT_RUN" in required else "PASS"
    transform_errors = [cases[name]["maxAbsError"] for name in TRANSFORM_CASES if name in cases and "maxAbsError" in cases[name]]
    record: dict[str, object] = {
        "name": editor,
        "version": version,
        "edition": edition,
        "independent": True,
        "status": status,
        "caseStatus": statuses,
        "cases": {
            name: {key: result.get(key) for key in (
                "status", "reasons", "maxAbsError", "boundaryMaxAbsError", "monotonicViolations",
                "bestInterpretation", "subBlackPreserved", "superWhitePreserved",
                "medianLsb10", "p95Lsb10", "clippingFractions", "render", "clip",
            ) if key in result}
            for name, result in sorted(cases.items())
        },
        "implementations": {
            implementation: {"status": cases[case]["status"], "maxAbsError": cases[case].get("maxAbsError"), "via": editor}
            for case, implementation in IMPLEMENTATION_FOR_CASE.items() if case in cases
        },
    }
    if transform_errors:
        # The qualifier's editor gate reads maxAbsError against 2e-5; only transform cases share
        # that metric. Decoder agreement is reported per case in 10-bit LSBs.
        record["maxAbsError"] = max(transform_errors)
    return record


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    gen = commands.add_parser("generate", help="write the fixture set and fixture-manifest.json")
    gen.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    cmp_ = commands.add_parser("compare", help="measure one rendered EXR frame")
    cmp_.add_argument("--case", required=True, choices=sorted(set(REQUIRED_CASES) | set(OPTIONAL_CASES)))
    cmp_.add_argument("--render", type=Path, required=True, help="32-bit float EXR rendered by the editor")
    cmp_.add_argument("--clip", type=Path, help="camera-clip only: the recorded OCLog2 MP4")
    cmp_.add_argument("--frame", type=int, default=0, help="camera-clip only: source frame the render shows")
    cmp_.add_argument("--fixtures", type=Path, default=DEFAULT_OUTPUT)
    cmp_.add_argument("--output", type=Path, required=True)
    rec = commands.add_parser("record", help="fold case results into a qualify_oclog2 workflows[] entry")
    rec.add_argument("cases", type=Path, nargs="+")
    rec.add_argument("--editor", default="davinci-resolve")
    rec.add_argument("--editor-version", required=True)
    rec.add_argument("--edition", required=True, choices=("free", "studio"))
    rec.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    if args.command == "generate":
        print(json.dumps(generate(args.output), indent=2, sort_keys=True))
        return 0
    if args.command == "compare":
        result = run_compare(args.case, args.render, args.clip, args.frame, args.fixtures)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        summary = {key: result.get(key) for key in ("case", "status", "reasons", "maxAbsError")}
        print(json.dumps(summary, sort_keys=True))
        return 0 if result["status"] == "PASS" else 2
    record = build_record(args.cases, args.editor, args.editor_version, args.edition)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(record, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps({key: record[key] for key in ("status", "caseStatus", "maxAbsError") if key in record}, sort_keys=True))
    return 0 if record["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
