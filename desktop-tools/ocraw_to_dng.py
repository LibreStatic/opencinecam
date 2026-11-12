#!/usr/bin/env python3
"""Convert the RAW frames of an OpenCine RAW v1 video take to per-frame DNGs.

Dependency-free: only the standard library is required. When numpy imports,
RAW10 unpacking and block-pack decoding use it; both paths produce
byte-identical samples, and `--pure` (or `OCRAW_TO_DNG_PURE=1`) forces the
standard-library path.

Each DNG is a little-endian TIFF with a single image IFD holding the CFA
mosaic, plus an EXIF IFD for exposure time and ISO. RAW10 (encoding 0),
block-pack (encoding 2) and quantised block-pack (encoding 3, decoded to its
reconstructed DN) frames are decoded and stored as uncompressed 16-bit strips; LJ92 (encoding 1) strips are stored unchanged as lossless JPEG tiles
(Compression 7).
Colour data come from the take's static METADATA chunks and, per frame, from
the FRAME_METADATA chunk whose `sequence` names the RAW_FRAME's chunk sequence.
The wire contract is docs/formats/opencine-raw-v1.md.

Every DNG also carries the CinemaDNG tags (PLAN-071 §8): FrameRate from `fps`,
TimeCodes counted from the take's first frame, and ReelName and CameraLabel set
to the take name (the input's stem). Files are named `<take>_000000.dng` by
their index in file order. `--cinemadng DIR` writes `DIR/<take>/` with the DNGs
and, when the take has sound, `<take>.wav`: one clip with audio for DaVinci
Resolve. `media/.../ocraw/CinemaDngWriter.kt` writes the same bytes on the
device; the golden fixtures in media/src/test/resources/ocraw/cinemadng keep
the two in step.

`--wav out.wav` also (or, without `-o`, only) writes the take's AUDIO_PCM16
chunks as a 16-bit PCM WAV, using the `audioSampleRate` and `audioChannels`
metadata. Sample zero is the first RAW frame's capture time, so the WAV lines up
with the DNG sequence (`--wav-from-audio` starts it at the first audio sample
instead); gaps between chunks, such as audio the recorder dropped, are filled
with silence.
"""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import os
import struct
import sys
import time
from dataclasses import dataclass, field
from fractions import Fraction
from pathlib import Path
from typing import BinaryIO, Callable

sys.path.insert(0, str(Path(__file__).resolve().parent))
import ocraw_recovery as ocraw  # noqa: E402

try:  # Optional acceleration; the pure path is the reference.
    import numpy as _np
except ImportError:  # pragma: no cover - depends on the host
    _np = None

SOFTWARE = "OpenCineCam ocraw_to_dng"
PURE_ENV = "OCRAW_TO_DNG_PURE"

# Android CameraCharacteristics.SENSOR_INFO_COLOR_FILTER_ARRANGEMENT, by name and
# by integer value, mapped to the DNG CFAPattern (0 = red, 1 = green, 2 = blue).
CFA_PATTERNS = {
    "RGGB": (0, 1, 1, 2),
    "GRBG": (1, 0, 2, 1),
    "GBRG": (1, 2, 0, 1),
    "BGGR": (2, 1, 1, 0),
}
ANDROID_CFA_INTS = {"0": "RGGB", "1": "GRBG", "2": "GBRG", "3": "BGGR", "4": "RGB", "5": "MONO", "6": "NIR"}
IDENTITY_MATRIX = [Fraction(1) if row == col else Fraction(0) for row in range(3) for col in range(3)]
D65 = 21

# TIFF field types.
BYTE, ASCII, SHORT, LONG, RATIONAL, UNDEFINED, SRATIONAL = 1, 2, 3, 4, 5, 7, 10
STRIP_TARGET_BYTES = 1 << 20


class UnsupportedEncoding(Exception):
    pass


# ---------------------------------------------------------------------------
# Decoders: encoding -> function(frame header, frame data, use_numpy) returning
# width * height little-endian uint16 samples in raster order, or an Encoded
# payload that the DNG stores without decoding it.
# ---------------------------------------------------------------------------

def _raw10_pure(data: bytes) -> bytes:
    """Unpacks Android RAW10 with C-speed slicing, translate and one big-int OR."""
    groups = len(data) // 5
    msb = [data[k::5] for k in range(4)]
    lsb = data[4::5]
    low_from_msb = bytes(((value << 2) & 0xFF) for value in range(256))
    high_from_msb = bytes((value >> 6) for value in range(256))
    out = bytearray(groups * 8)
    for k in range(4):
        low_bits = lsb.translate(bytes(((value >> (2 * k)) & 3) for value in range(256)))
        shifted = msb[k].translate(low_from_msb)
        # The two sources hold disjoint bits (0-1 and 2-7), so one OR over the
        # whole byte string combines them without a per-pixel Python loop.
        low = (int.from_bytes(shifted, "little") | int.from_bytes(low_bits, "little")).to_bytes(groups, "little")
        out[2 * k::8] = low
        out[2 * k + 1::8] = msb[k].translate(high_from_msb)
    return bytes(out)


def _raw10_numpy(data: bytes) -> bytes:
    packed = _np.frombuffer(data, dtype=_np.uint8).reshape(-1, 5).astype(_np.uint16)
    out = _np.empty((packed.shape[0], 4), dtype="<u2")
    for k in range(4):
        out[:, k] = (packed[:, k] << 2) | ((packed[:, 4] >> (2 * k)) & 3)
    return out.tobytes()


def decode_raw10(header: dict, data: bytes, use_numpy: bool) -> bytes:
    # Rows are tight (rowBytes = width * 5 / 4, width a multiple of 4), so the
    # frame is one continuous run of 5-byte groups with no row boundaries.
    if len(data) != header["rowBytes"] * header["height"]:
        raise ValueError("RAW10 data length disagrees with the frame header")
    return _raw10_numpy(data) if use_numpy and _np is not None else _raw10_pure(data)


@dataclass(frozen=True)
class Encoded:
    """Image data ready to store: one TIFF part (strip or tile) per entry."""
    compression: int
    bits_per_sample: int
    rows_per_part: int
    parts: list[bytes]
    tiled: bool = False  # True: full-width tiles of rows_per_part rows instead of strips


MAX_STRIPS = 64  # kMaxStrips in native-pipeline/include/opencinecam/raw_codecs.h
LJ92_PRECISION = 10


def parse_strips(data: bytes, height: int) -> list[tuple[int, bytes]]:
    """Splits an encoding 1 or 2 frame container into (rows, strip stream) pairs.

    The container (raw_codecs.h) is u32 stripCount, stripCount x {u32 rows,
    u32 byteLength}, then the streams; the rows add up to the frame height.
    """
    if len(data) < 4:
        raise ValueError("encoded frame is truncated")
    count = struct.unpack_from("<I", data)[0]
    if not 1 <= count <= MAX_STRIPS:
        raise ValueError(f"encoded frame has {count} strips (1..{MAX_STRIPS})")
    offset = 4 + 8 * count
    if len(data) < offset:
        raise ValueError("encoded frame strip table is truncated")
    strips, total = [], 0
    for index in range(count):
        rows, length = struct.unpack_from("<II", data, 4 + 8 * index)
        if rows == 0 or rows % 2 or length > len(data) - offset:
            raise ValueError(f"encoded frame strip {index} is invalid")
        strips.append((rows, data[offset:offset + length]))
        offset += length
        total += rows
    if total != height or offset != len(data):
        raise ValueError("encoded frame strips disagree with the frame height or data length")
    return strips


def decode_lj92(header: dict, data: bytes, use_numpy: bool) -> Encoded:
    """Passes the LJ92 strips through unchanged as DNG Compression 7 tiles.

    Each strip is a complete SOF3 stream in DNG CFA layout (width / 2 columns,
    two components), which is how DNG stores lossless JPEG, so no pixel is
    decoded here. Each strip becomes one full-width tile: LibRaw reads
    multi-part lossless JPEG DNGs only from tiles (with strips it decodes the
    first one alone). TileLength is the first strip's rows; the last tile's
    JPEG holds only the rows left in the image, the rest of the tile being
    outside it. BitsPerSample is the JPEG precision, 10.
    """
    if header["width"] % 2:
        raise ValueError("LJ92 frame width must be even")
    strips = parse_strips(data, int(header["height"]))
    rows_per_strip = strips[0][0]
    if any(rows != rows_per_strip for rows, _ in strips[:-1]) or strips[-1][0] > rows_per_strip:
        raise ValueError("LJ92 strips do not have a uniform height")
    for index, (rows, stream) in enumerate(strips):
        _check_sof3(stream, rows, int(header["width"]), index)
    return Encoded(compression=7, bits_per_sample=LJ92_PRECISION, rows_per_part=rows_per_strip,
                   parts=[stream for _, stream in strips], tiled=True)


def _check_sof3(stream: bytes, rows: int, width: int, index: int) -> None:
    """Checks that a strip is a lossless JPEG whose frame matches the strip geometry."""
    if stream[:2] != b"\xff\xd8" or stream[-2:] != b"\xff\xd9":
        raise ValueError(f"LJ92 strip {index} is not a JPEG stream")
    at = 2
    while at + 4 <= len(stream):
        if stream[at] != 0xFF:
            break
        marker = stream[at + 1]
        length = struct.unpack_from(">H", stream, at + 2)[0]
        if marker == 0xC3:
            if at + 10 > len(stream):
                break
            precision, lines, columns, components = struct.unpack_from(">BHHB", stream, at + 4)
            if (precision, lines, columns * components, components) != (LJ92_PRECISION, rows, width, 2):
                raise ValueError(f"LJ92 strip {index} frame is {columns}x{lines}x{components} P={precision}, "
                                 f"expected {width // 2}x{rows}x2 P={LJ92_PRECISION}")
            return
        if marker == 0xDA:
            break
        at += 2 + length
    raise ValueError(f"LJ92 strip {index} has no SOF3 frame header")


BLOCK = 16
SAMPLE_MAX = 1023


def _block_pack_rows(stream: bytes, width: int, rows: int) -> tuple[list[int], list[list[int]]]:
    """Returns each row's payload offset and block widths, checking the stream's length."""
    blocks = (width + BLOCK - 1) // BLOCK
    nibble_bytes = (blocks + 1) // 2
    starts, widths, at = [], [], 0
    for _ in range(rows):
        nibbles = stream[at:at + nibble_bytes]
        if len(nibbles) < nibble_bytes:
            raise ValueError("block-pack strip is truncated")
        row = [nibbles[b // 2] >> (4 * (b & 1)) & 0xF for b in range(blocks)]
        if blocks & 1 and nibbles[-1] >> 4:
            raise ValueError("block-pack row has a non-zero unused width nibble")
        if max(row) > 11:
            raise ValueError("block-pack block width exceeds 11 bits")
        at += nibble_bytes
        starts.append(at)
        widths.append(row)
        at += 2 * sum(row)
    if at != len(stream):
        raise ValueError("block-pack strip length disagrees with its rows")
    return starts, widths


def _block_pack_strip_pure(stream: bytes, width: int, rows: int) -> list[int]:
    starts, widths = _block_pack_rows(stream, width, rows)
    out: list[int] = []
    first = [[0, 0], [0, 0]]  # first two samples of the last even / odd row
    for y in range(rows):
        residuals: list[int] = []
        at = starts[y]
        for w in widths[y]:
            if w == 0:
                residuals.extend([0] * BLOCK)
                continue
            bits = int.from_bytes(stream[at:at + 2 * w], "little")
            at += 2 * w
            mask = (1 << w) - 1
            for k in range(BLOCK):
                z = (bits >> (k * w)) & mask
                residuals.append((z >> 1) ^ -(z & 1))
        row = [first[y & 1][0], first[y & 1][1]] if y >= 2 else [0, 0]
        for i in range(width):
            value = row[i] + residuals[i]
            if not 0 <= value <= SAMPLE_MAX:
                raise ValueError("block-pack sample out of range")
            row.append(value)
        first[y & 1] = row[2:4]
        out.extend(row[2:])
    return out


def _block_pack_strip_numpy(stream: bytes, width: int, rows: int):
    np = _np
    starts, widths = _block_pack_rows(stream, width, rows)
    w = np.array(widths, dtype=np.int64)                       # rows x blocks
    block_start = np.array(starts, dtype=np.int64)[:, None] + 2 * (np.cumsum(w, axis=1) - w)
    k = np.arange(BLOCK, dtype=np.int64)
    bit = block_start[:, :, None] * 8 + k * w[:, :, None]      # rows x blocks x 16
    byte = bit >> 3
    padded = np.frombuffer(stream + b"\0\0\0", dtype=np.uint8).astype(np.int64)
    word = padded[byte] | padded[byte + 1] << 8 | padded[byte + 2] << 16
    z = (word >> (bit & 7)) & ((1 << w[:, :, None]) - 1)
    r = ((z >> 1) ^ -(z & 1)).reshape(rows, -1)[:, :width]
    # x[i] = x[i - 2] + r[i] along a row, and the first two samples follow the
    # same rule down the rows of one parity: both are cumulative sums.
    r = r.copy()
    for parity in (0, 1):
        r[parity::2, :2] = np.cumsum(r[parity::2, :2], axis=0)
    x = np.empty_like(r)
    x[:, 0::2] = np.cumsum(r[:, 0::2], axis=1)
    x[:, 1::2] = np.cumsum(r[:, 1::2], axis=1)
    # Every sample is an intermediate prediction too, so this range check
    # matches the reference decoder's per-sample check.
    if x.size and (x.min() < 0 or x.max() > SAMPLE_MAX):
        raise ValueError("block-pack sample out of range")
    return x.astype("<u2")


def decode_block_pack(header: dict, data: bytes, use_numpy: bool) -> bytes:
    width, height = int(header["width"]), int(header["height"])
    if width % 2:
        raise ValueError("block-pack frame width must be even")
    strips = parse_strips(data, height)
    if use_numpy and _np is not None:
        return b"".join(_block_pack_strip_numpy(stream, width, rows).tobytes() for rows, stream in strips)
    samples: list[int] = []
    for rows, stream in strips:
        samples.extend(_block_pack_strip_pure(stream, width, rows))
    return struct.pack(f"<{len(samples)}H", *samples)


QUANT_PREAMBLE_BYTES = 12  # kQuantPreambleBytes in raw_codecs.h
QUANT_VERSION = 1
QUANT_LUT_SIZE = 1024


def decode_block_pack_quantised(header: dict, data: bytes, use_numpy: bool) -> bytes:
    """Decodes encoding 3: block-pack codes mapped through the payload's inverse table.

    The payload (raw_codecs.h) is u8 quantVersion (1), u8 reserved, u16 tableCount
    N (1..1024), u32 isoUsed, u16 kMilli, u16 blackLevel, N x u16 inverse (code ->
    DN, 0..1023), then the block-pack container of the codes; a code >= N is corrupt.
    """
    if len(data) < QUANT_PREAMBLE_BYTES:
        raise ValueError("quantised frame preamble is truncated")
    version, _, count = struct.unpack_from("<BBH", data)
    if version != QUANT_VERSION:
        raise ValueError(f"quantised frame version {version} is not {QUANT_VERSION}")
    if not 1 <= count <= QUANT_LUT_SIZE:
        raise ValueError(f"quantised frame table has {count} entries (1..{QUANT_LUT_SIZE})")
    start = QUANT_PREAMBLE_BYTES + 2 * count
    if len(data) < start:
        raise ValueError("quantised frame table is truncated")
    inverse = struct.unpack_from(f"<{count}H", data, QUANT_PREAMBLE_BYTES)
    if max(inverse) > SAMPLE_MAX:
        raise ValueError("quantised frame table entry exceeds 1023")
    codes = decode_block_pack(header, data[start:], use_numpy)
    if use_numpy and _np is not None:
        values = _np.frombuffer(codes, dtype="<u2")
        if values.size and int(values.max()) >= count:
            raise ValueError("quantised frame code exceeds its table")
        return _np.asarray(inverse, dtype="<u2")[values].tobytes()
    values = struct.unpack(f"<{len(codes) // 2}H", codes)
    if values and max(values) >= count:
        raise ValueError("quantised frame code exceeds its table")
    return struct.pack(f"<{len(values)}H", *(inverse[v] for v in values))


DECODERS: dict[int, Callable[[dict, bytes, bool], "bytes | Encoded"]] = {
    0: decode_raw10,
    # LJ92 strips are already DNG lossless JPEG: stored as Compression 7 as is.
    1: decode_lj92,
    # Block-pack is decoded to 16-bit samples for the uncompressed path.
    2: decode_block_pack,
    # Quantised block-pack is decoded to its reconstructed DN, then stored the same way.
    3: decode_block_pack_quantised,
}


# ---------------------------------------------------------------------------
# Metadata parsing.
# ---------------------------------------------------------------------------

def parse_number(text: str) -> Fraction:
    if "/" in text:
        numerator, denominator = text.split("/", 1)
        value = Fraction(int(numerator), int(denominator))
        if int(denominator) <= 0:
            raise ValueError(f"non-positive denominator in {text!r}")
        return value
    return Fraction(text)


def parse_numbers(text: str | None, count: int) -> list[Fraction] | None:
    if text is None:
        return None
    values = [parse_number(part) for part in text.split()]
    if len(values) != count:
        raise ValueError(f"expected {count} values, found {len(values)}")
    return values


def cfa_name(value: str | None) -> str | None:
    if value is None:
        return None
    value = value.strip().upper()
    return ANDROID_CFA_INTS.get(value, value)


@dataclass
class Conversion:
    output: Path
    prefix: str
    use_numpy: bool
    take: str = ""  # ReelName and CameraLabel; the prefix when empty
    written: list[Path] = field(default_factory=list)
    invalid: int = 0
    unsupported: dict[int, int] = field(default_factory=dict)
    dimensions: dict[str, int] = field(default_factory=dict)
    warnings: list[str] = field(default_factory=list)

    def warn(self, message: str) -> None:
        if message not in self.warnings:
            self.warnings.append(message)


@dataclass(frozen=True)
class StaticColour:
    cfa: tuple[int, int, int, int]
    black: list[Fraction]
    white: int
    color_matrix1: list[Fraction]
    color_matrix2: list[Fraction] | None
    forward_matrix1: list[Fraction] | None
    forward_matrix2: list[Fraction] | None
    illuminant1: int | None
    illuminant2: int | None
    model: str
    make: str


def static_colour(metadata: dict[str, str], job: Conversion) -> StaticColour:
    name = cfa_name(metadata.get("cfa"))
    if name is None:
        job.warn("metadata has no cfa; assuming RGGB")
        name = "RGGB"
    if name not in CFA_PATTERNS:
        raise ValueError(f"CFA arrangement {name} is not supported (only RGGB, GRBG, GBRG and BGGR)")
    white = int(metadata.get("whiteLevel", "0") or 0)
    if white <= 0:
        job.warn("metadata has no whiteLevel; using 1023")
        white = 1023
    black = parse_numbers(metadata.get("blackLevel"), 4)
    if black is None:
        job.warn("metadata has no blackLevel; using 0")
        black = [Fraction(0)] * 4
    matrix1 = parse_numbers(metadata.get("colorMatrix1"), 9)
    illuminant1 = int(metadata["calibrationIlluminant1"]) if "calibrationIlluminant1" in metadata else None
    if matrix1 is None:
        job.warn("metadata has no colorMatrix1; wrote an identity ColorMatrix1 (D65), colours are not calibrated")
        matrix1 = IDENTITY_MATRIX
        illuminant1 = D65
    matrix2 = parse_numbers(metadata.get("colorMatrix2"), 9)
    illuminant2 = int(metadata["calibrationIlluminant2"]) if "calibrationIlluminant2" in metadata else None
    model = metadata.get("model") or f"OpenCineCam camera {metadata.get('cameraId', '?')}"
    return StaticColour(
        cfa=CFA_PATTERNS[name],
        black=black,
        white=white,
        color_matrix1=matrix1,
        color_matrix2=matrix2,
        forward_matrix1=parse_numbers(metadata.get("forwardMatrix1"), 9),
        forward_matrix2=parse_numbers(metadata.get("forwardMatrix2"), 9),
        illuminant1=illuminant1,
        illuminant2=illuminant2,
        model=model,
        # The take records Build.MODEL only; a `make` key (none is written yet) wins when present.
        make=metadata.get("make") or model,
    )


# ---------------------------------------------------------------------------
# TIFF/DNG writer.
# ---------------------------------------------------------------------------

def _clamp_fraction(value: Fraction, signed: bool) -> tuple[int, int]:
    limit = 0x7FFFFFFF if signed else 0xFFFFFFFF
    if not signed and value < 0:
        raise ValueError("negative value in an unsigned RATIONAL")
    value = value.limit_denominator(limit)
    if abs(value.numerator) > limit:
        return (limit if value > 0 else -limit), 1
    return value.numerator, value.denominator


def _encode(type_id: int, values) -> tuple[int, bytes]:
    if type_id == ASCII:
        raw = values.encode("ascii", errors="replace") + b"\0"
        return len(raw), raw
    if type_id in (BYTE, UNDEFINED):
        raw = bytes(values)
        return len(raw), raw
    if type_id == SHORT:
        return len(values), struct.pack(f"<{len(values)}H", *values)
    if type_id == LONG:
        return len(values), struct.pack(f"<{len(values)}I", *values)
    if type_id in (RATIONAL, SRATIONAL):
        signed = type_id == SRATIONAL
        pairs = [_clamp_fraction(Fraction(value), signed) for value in values]
        fmt = "<ii" if signed else "<II"
        return len(values), b"".join(struct.pack(fmt, *pair) for pair in pairs)
    raise ValueError(f"unsupported TIFF type {type_id}")


def _build_ifd(entries: list[tuple[int, int, object]], offset: int) -> bytes:
    """Encodes one IFD at `offset` followed by its out-of-line values."""
    entries = sorted(entries, key=lambda entry: entry[0])
    overflow_at = offset + 2 + 12 * len(entries) + 4
    table = bytearray(struct.pack("<H", len(entries)))
    overflow = bytearray()
    for tag, type_id, values in entries:
        count, raw = _encode(type_id, values)
        if len(raw) <= 4:
            table += struct.pack("<HHI", tag, type_id, count) + raw.ljust(4, b"\0")
        else:
            table += struct.pack("<HHII", tag, type_id, count, overflow_at + len(overflow))
            overflow += raw
            if len(overflow) % 2:
                overflow += b"\0"
    table += struct.pack("<I", 0)
    return bytes(table + overflow)


def _black_level_entry(black: list[Fraction]) -> tuple[int, int, object]:
    if all(value.denominator == 1 and 0 <= value <= 0xFFFFFFFF for value in black):
        return 50714, LONG, [int(value) for value in black]
    return 50714, RATIONAL, black


def uncompressed(width: int, height: int, samples: bytes) -> Encoded:
    """Splits 16-bit raster samples into uncompressed strips of about 1 MiB."""
    rows_per_strip = max(1, min(height, STRIP_TARGET_BYTES // (width * 2)))
    step = rows_per_strip * width * 2
    return Encoded(compression=1, bits_per_sample=16, rows_per_part=rows_per_strip,
                   parts=[samples[at:at + step] for at in range(0, len(samples), step)])


# ---------------------------------------------------------------------------
# CinemaDNG tags (DNG 1.4 spec, "CinemaDNG" tags; PLAN-071 §8).
# ---------------------------------------------------------------------------

TAG_TIME_CODES, TAG_FRAME_RATE, TAG_REEL_NAME, TAG_CAMERA_LABEL = 51043, 51044, 51081, 51105
SMPTE_MAX_FRAME_DIGITS = 30  # the frames field holds 0..39 (2 tens bits); counts above 30 fps go in pairs


def parse_fps(text: str | None) -> Fraction | None:
    """The take's nominal frame rate, or None when `fps` is missing, unreadable or not positive."""
    if not text:
        return None
    try:
        value = parse_number(text.strip())
    except (ValueError, ZeroDivisionError):
        return None
    return value if value > 0 else None


def timecode_frames(timestamp_ns: int, origin_ns: int, fps: Fraction) -> int:
    """Frames from the take start: the sensor-timestamp offset at [fps], rounded half to even, never negative."""
    return max(0, round(Fraction(timestamp_ns - origin_ns, 1_000_000_000) * fps))


def smpte_timecode(frames: int, fps: Fraction) -> bytes:
    """One 8-byte SMPTE 12M time code, the DNG `TimeCodes` layout, non-drop-frame.

    Byte 0: bit 7 drop-frame flag, bit 6 colour-frame flag, bits 5-4 tens of
    frames, bits 3-0 units. Bytes 1 and 2: bits 6-4 tens and 3-0 units of the
    seconds and minutes (bit 7 a flag). Byte 3: bits 5-4 tens and 3-0 units of
    the hours. Bytes 4-7 (binary groups) are zero. The labels count at the
    integer nominal rate (29.97 labels as 30); hours wrap at 24. Above 30 fps
    the frames field counts frame pairs (SMPTE ST 12-1 for 50/60p) and the
    field flag marks the second frame of a pair: byte 3 bit 7 at 50 fps, byte 1
    bit 7 otherwise.
    """
    base = max(1, round(fps))
    divisor = (base + SMPTE_MAX_FRAME_DIGITS - 1) // SMPTE_MAX_FRAME_DIGITS
    seconds, frame = divmod(frames, base)
    label = frame // divisor

    def bcd(value: int) -> int:
        return (value // 10) << 4 | value % 10

    code = bytearray([bcd(label), bcd(seconds % 60), bcd(seconds // 60 % 60), bcd(seconds // 3600 % 24), 0, 0, 0, 0])
    if divisor == 2 and frame % 2:
        if base % 25 == 0:
            code[3] |= 0x80
        else:
            code[1] |= 0x80
    return bytes(code)


@dataclass(frozen=True)
class CineTags:
    """Per-take CinemaDNG values; [fps] and [origin_ns] are None when the take lacks them."""
    take: str
    fps: Fraction | None
    origin_ns: int | None


TIFF_ORIENTATION = {0: 1, 90: 6, 180: 3, 270: 8}


def orientation_degrees(metadata: dict[str, str]) -> int:
    """The take's `orientation` (clockwise degrees to show the stored frames upright): 0, 90, 180
    or 270, and 0 when the key is absent or invalid, as in takes recorded before it."""
    value = (metadata.get("orientation") or "").strip()
    return int(value) if value in ("90", "180", "270") else 0


def tiff_orientation(degrees: int) -> int:
    """The TIFF Orientation (tag 274) value for [degrees]: 1, 6, 3 or 8."""
    return TIFF_ORIENTATION.get(degrees, 1)


def frame_origin_ns(raw_chunks: list[ocraw.Chunk]) -> int | None:
    """The sensor timestamp of the first RAW frame whose header is readable: the take start."""
    for chunk in raw_chunks:
        try:
            header = ocraw.decode_frame_header(chunk.payload)
        except ValueError:
            continue
        if header["dataLength"] == chunk.payload_length - ocraw.FRAME_HEADER_BYTES:
            return int(header["sensorTimestampNs"])
    return None


def cine_entries(cine: CineTags, timestamp_ns: int) -> list[tuple[int, int, object]]:
    entries: list[tuple[int, int, object]] = [(TAG_REEL_NAME, ASCII, cine.take), (TAG_CAMERA_LABEL, ASCII, cine.take)]
    if cine.fps is not None:
        entries.append((TAG_FRAME_RATE, SRATIONAL, [cine.fps]))
        if cine.origin_ns is not None:
            frames = timecode_frames(timestamp_ns, cine.origin_ns, cine.fps)
            entries.append((TAG_TIME_CODES, BYTE, list(smpte_timecode(frames, cine.fps))))
    return entries


def write_dng(path: Path, width: int, height: int, image: bytes | Encoded, colour: StaticColour,
              frame_meta: dict[str, str], job: Conversion, cine: CineTags | None = None,
              timestamp_ns: int = 0, orientation: int = 1) -> None:
    if not isinstance(image, Encoded):
        image = uncompressed(width, height, image)
    rows_per_strip = image.rows_per_part
    strip_count = len(image.parts)
    strip_bytes = [len(part) for part in image.parts]

    black = colour.black
    dynamic_black = frame_meta.get("dynamicBlackLevel") or frame_meta.get("blackLevel")
    if dynamic_black:
        try:
            black = parse_numbers(dynamic_black, 4) or black
        except ValueError as error:
            job.warn(f"ignored a per-frame black level: {error}")
    white = colour.white
    if frame_meta.get("whiteLevel"):
        try:
            white = int(frame_meta["whiteLevel"])
        except ValueError as error:
            job.warn(f"ignored a per-frame white level: {error}")

    def ifd0(strip_offsets: list[int], exif_offset: int) -> list[tuple[int, int, object]]:
        entries: list[tuple[int, int, object]] = [
            (254, LONG, [0]),
            (256, LONG, [width]),
            (257, LONG, [height]),
            (258, SHORT, [image.bits_per_sample]),
            (259, SHORT, [image.compression]),
            (262, SHORT, [32803]),
            (271, ASCII, colour.make),
            (272, ASCII, colour.model),
            (274, SHORT, [orientation]),
            (277, SHORT, [1]),
            (284, SHORT, [1]),
            (305, ASCII, SOFTWARE),
            (33421, SHORT, [2, 2]),
            (33422, BYTE, list(colour.cfa)),
            (34665, LONG, [exif_offset]),
            (50706, BYTE, [1, 4, 0, 0]),
            (50707, BYTE, [1, 1, 0, 0]),
            (50708, ASCII, colour.model),
            (50710, BYTE, [0, 1, 2]),
            (50711, SHORT, [1]),
            (50713, SHORT, [2, 2]),
            _black_level_entry(black),
            (50717, LONG, [white]),
            (50719, LONG, [0, 0]),
            (50720, LONG, [width, height]),
            (50721, SRATIONAL, colour.color_matrix1),
        ]
        if image.tiled:
            entries += [(322, LONG, [width]), (323, LONG, [rows_per_strip]),
                        (324, LONG, strip_offsets), (325, LONG, strip_bytes)]
        else:
            entries += [(273, LONG, strip_offsets), (278, LONG, [rows_per_strip]), (279, LONG, strip_bytes)]
        if colour.color_matrix2 is not None:
            entries.append((50722, SRATIONAL, colour.color_matrix2))
        if colour.illuminant1 is not None:
            entries.append((50778, SHORT, [colour.illuminant1]))
        if colour.illuminant2 is not None:
            entries.append((50779, SHORT, [colour.illuminant2]))
        if colour.forward_matrix1 is not None:
            entries.append((50964, SRATIONAL, colour.forward_matrix1))
        if colour.forward_matrix2 is not None:
            entries.append((50965, SRATIONAL, colour.forward_matrix2))
        if cine is not None:
            entries += cine_entries(cine, timestamp_ns)
        neutral = frame_meta.get("neutral")
        if neutral:
            try:
                entries.append((50728, RATIONAL, parse_numbers(neutral, 3)))
            except ValueError as error:
                job.warn(f"ignored a per-frame neutral: {error}")
        return entries

    exif: list[tuple[int, int, object]] = [(36864, UNDEFINED, b"0230")]
    if frame_meta.get("exposureNs"):
        try:
            exif.append((33434, RATIONAL, [Fraction(int(frame_meta["exposureNs"]), 1_000_000_000)]))
        except ValueError as error:
            job.warn(f"ignored a per-frame exposure: {error}")
    if frame_meta.get("iso"):
        try:
            exif.append((34855, SHORT, [max(0, min(0xFFFF, int(frame_meta["iso"])))]))
        except ValueError as error:
            job.warn(f"ignored a per-frame ISO: {error}")

    # IFD sizes do not depend on the offset values, so lay out with placeholders first.
    first = _build_ifd(ifd0([0] * strip_count, 0), 8)
    exif_offset = 8 + len(first)
    exif_bytes = _build_ifd(exif, exif_offset)
    data_offset = exif_offset + len(exif_bytes)
    if data_offset % 2:
        data_offset += 1
    offsets, cursor = [], data_offset
    for size in strip_bytes:
        offsets.append(cursor)
        cursor += size
    first = _build_ifd(ifd0(offsets, exif_offset), 8)
    with path.open("wb") as out:
        out.write(b"II" + struct.pack("<HI", 42, 8))
        out.write(first)
        out.write(exif_bytes)
        out.write(b"\0" * (data_offset - exif_offset - len(exif_bytes)))
        for part in image.parts:
            out.write(part)


# ---------------------------------------------------------------------------
# Selection and conversion.
# ---------------------------------------------------------------------------

# Colour values a frame without a FRAME_METADATA record inherits, as OcrawFrameIndex.effectiveFrameMetadata;
# each group is taken whole from one record.
INHERITED_COLOUR_GROUPS: tuple[tuple[str, ...], ...] = (("neutral",), ("dynamicBlackLevel", "blackLevel"), ("whiteLevel",))


def inherit_colour(sequences: list[int], frame_meta: dict[int, dict[str, str]]) -> dict[int, dict[str, str]]:
    """For each frame (by chunk sequence, in file order) without a FRAME_METADATA record, the colour values it
    inherits: each group from the nearest earlier frame whose record carries it, else the nearest later one.
    Takes recorded before the engine kept the last capture result lack the final frame's record; exposure, ISO
    and the rest are never inherited."""
    records = [frame_meta.get(sequence) for sequence in sequences]
    if all(record is not None for record in records) or all(record is None for record in records):
        return {}
    count = len(records)
    sources: list[list[int]] = []
    for group in INHERITED_COLOUR_GROUPS:
        has = [record is not None and any(record.get(name) for name in group) for record in records]
        source = [-1] * count
        last = -1
        for i in range(count):
            if has[i]:
                last = i
            source[i] = last
        following = -1
        for i in range(count - 1, -1, -1):
            if has[i]:
                following = i
            if source[i] < 0:
                source[i] = following
        sources.append(source)
    inherited: dict[int, dict[str, str]] = {}
    for i, record in enumerate(records):
        if record is not None:
            continue
        values: dict[str, str] = {}
        for group, source in zip(INHERITED_COLOUR_GROUPS, sources):
            if source[i] >= 0:
                values.update({name: records[source[i]][name] for name in group if records[source[i]].get(name)})
        if values:
            inherited[sequences[i]] = values
    return inherited


def parse_frames(spec: str | None) -> Callable[[int], bool]:
    if not spec:
        return lambda index: True
    ranges: list[tuple[int, int]] = []
    for part in spec.split(","):
        part = part.strip()
        if not part:
            continue
        if "-" in part:
            start, end = part.split("-", 1)
            low, high = int(start), int(end) if end else sys.maxsize
        else:
            low = high = int(part)
        if low < 0 or high < low:
            raise ValueError(f"invalid frame range {part!r}")
        ranges.append((low, high))
    return lambda index: any(low <= index <= high for low, high in ranges)


def frame_file_name(prefix: str, index: int) -> str:
    """`<take>_000000.dng`: the frame's 0-based index in file order (every RAW_FRAME counts), 6 digits."""
    return f"{prefix}_{index:06d}.dng"


def _read_frame_data(stream: BinaryIO, chunk: ocraw.Chunk, length: int) -> bytes:
    stream.seek(chunk.offset + ocraw.CHUNK_HEADER_BYTES + ocraw.FRAME_HEADER_BYTES)
    data = stream.read(length)
    if len(data) != length:
        raise ValueError("frame data is truncated")
    return data


def convert(path: Path, job: Conversion, frames: str | None = None, every: int = 1,
            verify_crc: bool = True) -> dict[str, object]:
    """Converts the selected frames; raises ValueError when the file header is invalid."""
    selected = parse_frames(frames)
    if every < 1:
        raise ValueError("--every must be at least 1")
    with path.open("rb") as stream:
        result = ocraw.scan_stream(stream, verify_raw_crc=verify_crc)
        if result.header is None:
            raise ValueError("invalid or truncated OpenCine RAW v1 file header")
        metadata: dict[str, str] = {}
        frame_meta: dict[int, dict[str, str]] = {}
        for chunk in result.chunks:
            if chunk.wire_id not in (ocraw.METADATA, ocraw.FRAME_METADATA):
                continue
            try:
                values = ocraw.decode_text_map(chunk.payload)
            except (ValueError, UnicodeDecodeError) as error:
                job.warn(f"skipped unreadable metadata chunk {chunk.sequence}: {error}")
                continue
            if chunk.wire_id == ocraw.METADATA:
                metadata.update(values)
            elif values.get("sequence", "").isdigit():
                frame_meta[int(values["sequence"])] = values
        colour = static_colour(metadata, job)
        job.output.mkdir(parents=True, exist_ok=True)
        raw_chunks = [chunk for chunk in result.chunks if chunk.wire_id == ocraw.RAW_FRAME]
        cine = CineTags(take=job.take or job.prefix, fps=parse_fps(metadata.get("fps")),
                        origin_ns=frame_origin_ns(raw_chunks))
        if cine.fps is None:
            job.warn("metadata has no usable fps; wrote no FrameRate or TimeCodes")
        orientation = tiff_orientation(orientation_degrees(metadata))
        inherited = inherit_colour([chunk.sequence for chunk in raw_chunks], frame_meta)
        picked = [index for index in range(len(raw_chunks)) if selected(index)][::every]
        for index in picked:
            chunk = raw_chunks[index]
            try:
                header = ocraw.decode_frame_header(chunk.payload)
                if header["dataLength"] != chunk.payload_length - ocraw.FRAME_HEADER_BYTES:
                    raise ValueError("frame data length disagrees with the chunk")
            except ValueError as error:
                job.invalid += 1
                job.warn(f"skipped frame {index} (chunk {chunk.sequence}): {error}")
                continue
            encoding = struct.unpack_from("<H", chunk.payload, 2)[0]
            decoder = DECODERS.get(encoding)
            try:
                if decoder is None:
                    raise UnsupportedEncoding(f"unknown-{encoding}")
                image = decoder(header, _read_frame_data(stream, chunk, int(header["dataLength"])), job.use_numpy)
            except UnsupportedEncoding:
                job.unsupported[encoding] = job.unsupported.get(encoding, 0) + 1
                continue
            except ValueError as error:
                job.invalid += 1
                job.warn(f"skipped frame {index} (chunk {chunk.sequence}): {error}")
                continue
            width, height = int(header["width"]), int(header["height"])
            target = job.output / frame_file_name(job.prefix, index)
            meta = frame_meta[chunk.sequence] if chunk.sequence in frame_meta else inherited.get(chunk.sequence, {})
            write_dng(target, width, height, image, colour, meta, job, cine,
                      int(header["sensorTimestampNs"]), orientation)
            job.written.append(target)
            key = f"{width}x{height}"
            job.dimensions[key] = job.dimensions.get(key, 0) + 1
    return {
        "frames": len(raw_chunks),
        "selected": len(picked),
        "staleTail": result.stale_evidence,
        "lastValidOffset": result.last_valid_offset,
    }


WAV_HEADER_BYTES = 44
WAV_MAX_DATA = 0xFFFFFFFF - (WAV_HEADER_BYTES - 8)


def _ticks_seconds(chunk: ocraw.Chunk) -> Fraction:
    if chunk.time_base_denominator <= 0 or chunk.time_base_numerator <= 0:
        raise ValueError(f"chunk {chunk.sequence} has an invalid time base")
    return Fraction(chunk.timestamp_ticks * chunk.time_base_numerator, chunk.time_base_denominator)


def _positive_int(metadata: dict[str, str], key: str) -> int:
    text = metadata.get(key, "")
    if not text.isdigit() or int(text) <= 0:
        raise ValueError(f"take has no usable {key} metadata (audio was not recorded?)")
    return int(text)


def export_wav(path: Path, target: Path, align_to_video: bool = True) -> dict[str, object]:
    """Writes the take's PCM16 audio to [target] as a WAV; raises ValueError when the take has none.

    Chunks are placed by their timestamps: a gap of more than a millisecond is filled with silence,
    and samples that overlap what is already written (or precede the origin) are dropped.
    """
    with path.open("rb") as stream:
        # RAW frame CRCs are not needed for audio; every other chunk's CRC is still checked.
        result = ocraw.scan_stream(stream, verify_raw_crc=False)
    if result.header is None:
        raise ValueError("invalid or truncated OpenCine RAW v1 file header")
    metadata: dict[str, str] = {}
    for chunk in result.chunks:
        if chunk.wire_id == ocraw.METADATA:
            try:
                metadata.update(ocraw.decode_text_map(chunk.payload))
            except (ValueError, UnicodeDecodeError):
                continue
    rate = _positive_int(metadata, "audioSampleRate")
    channels = _positive_int(metadata, "audioChannels")
    frame_bytes = 2 * channels
    audio = [chunk for chunk in result.chunks if chunk.wire_id == ocraw.AUDIO_PCM16]
    if not audio:
        raise ValueError("take has no audio chunks")
    audio.sort(key=_ticks_seconds)  # stable: chunks with equal timestamps keep file order
    first_frame = next((chunk for chunk in result.chunks if chunk.wire_id == ocraw.RAW_FRAME), None)
    origin = _ticks_seconds(first_frame) if align_to_video and first_frame is not None else _ticks_seconds(audio[0])
    tolerance = rate // 1000
    data = bytearray()
    cursor = 0  # sample frames written so far
    padded = trimmed = skipped = 0
    for chunk in audio:
        payload = chunk.payload
        if len(payload) % frame_bytes:
            skipped += 1
            continue
        start = round((_ticks_seconds(chunk) - origin) * rate)
        if start > cursor + tolerance:
            data += bytes((start - cursor) * frame_bytes)
            padded += start - cursor
            cursor = start
        elif start < cursor - tolerance:
            drop = min(cursor - start, len(payload) // frame_bytes)
            payload = payload[drop * frame_bytes:]
            trimmed += drop
        data += payload
        cursor += len(payload) // frame_bytes
    if len(data) > WAV_MAX_DATA:
        raise ValueError("audio is too long for a WAV file (4 GiB)")
    header = struct.pack("<4sI4s4sIHHIIHH4sI", b"RIFF", WAV_HEADER_BYTES - 8 + len(data), b"WAVE", b"fmt ", 16, 1,
                         channels, rate, rate * frame_bytes, frame_bytes, 16, b"data", len(data))
    target.parent.mkdir(parents=True, exist_ok=True)
    with target.open("wb") as out:
        out.write(header)
        out.write(data)
    return {
        "sampleRate": rate,
        "channels": channels,
        "frames": cursor,
        "chunks": len(audio) - skipped,
        "skippedChunks": skipped,
        "paddedFrames": padded,
        "trimmedFrames": trimmed,
        "droppedSamples": metadata.get("audioDroppedSamples"),
        "staleTail": result.stale_evidence,
    }


def use_numpy_by_default() -> bool:
    return _np is not None and os.environ.get(PURE_ENV, "") in ("", "0")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Convert OpenCine RAW v1 video frames to DNG files.")
    parser.add_argument("input", type=Path)
    parser.add_argument("-o", "--output", type=Path, help="output directory for the DNGs")
    parser.add_argument("--cinemadng", type=Path, metavar="DIR",
                        help="write a CinemaDNG clip folder DIR/<take>/: the DNGs plus <take>.wav when the take "
                             "has sound")
    parser.add_argument("--wav", type=Path, help="also write the take's audio as a 16-bit PCM WAV")
    parser.add_argument("--wav-from-audio", action="store_true",
                        help="start the WAV at the first audio sample instead of the first RAW frame")
    parser.add_argument("--frames", help="0-based frame indices in file order, e.g. 0-99 or 5,7 or 10-")
    parser.add_argument("--every", type=int, default=1, help="keep every Nth selected frame")
    parser.add_argument("--no-crc", action="store_true", help="skip CRC32C verification of RAW frames")
    parser.add_argument("--prefix", help="file name prefix (default: the input's stem)")
    parser.add_argument("--pure", action="store_true", help=f"never use numpy (also {PURE_ENV}=1)")
    args = parser.parse_args(argv)
    if not args.input.is_file():
        parser.error("input must be a file")
    if args.cinemadng is not None:
        if args.output is not None or args.wav is not None:
            parser.error("--cinemadng writes its own DNGs and WAV; drop -o/--output and --wav")
        take = args.prefix or args.input.stem
        args.output = args.cinemadng / take
        args.prefix = take
    if args.output is None and args.wav is None:
        parser.error("give -o/--output, --wav, both, or --cinemadng")
    if args.output is None:
        return _main_wav(args)
    job = Conversion(output=args.output, prefix=args.prefix or args.input.stem,
                     use_numpy=not args.pure and use_numpy_by_default(), take=args.input.stem)
    started = time.monotonic()
    try:
        report = convert(args.input, job, frames=args.frames, every=args.every, verify_crc=not args.no_crc)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    elapsed = time.monotonic() - started
    dims = ", ".join(f"{key} ({count})" for key, count in sorted(job.dimensions.items())) or "none"
    print(f"frames written: {len(job.written)} of {report['selected']} selected ({report['frames']} in file)")
    print(f"skipped invalid: {job.invalid}")
    unsupported = ", ".join(
        f"encoding {wire} ({ocraw.ENCODINGS.get(wire, 'unknown')}) not supported: {count}"
        for wire, count in sorted(job.unsupported.items()))
    print(f"skipped unsupported encoding: {sum(job.unsupported.values())}" + (f" ({unsupported})" if unsupported else ""))
    print(f"dimensions: {dims}")
    print(f"unpacking: {'numpy' if job.use_numpy else 'pure Python'}; "
          f"CRC32C: {'skipped' if args.no_crc else 'verified'}; {elapsed:.2f} s")
    if report["staleTail"]:
        print(f"truncated or damaged tail after byte {report['lastValidOffset']}: converted the complete frames before it")
    for warning in job.warnings:
        print(f"warning: {warning}")
    if args.cinemadng is not None:
        return _main_cinemadng_wav(args)
    return _main_wav(args) if args.wav is not None else 0


def _main_cinemadng_wav(args: argparse.Namespace) -> int:
    """The clip folder's `<take>.wav`, written only when the take has sound."""
    if not take_has_audio(args.input):
        print("audio: none (the take has no sound)")
        return 0
    args.wav = args.output / f"{args.prefix}.wav"
    return _main_wav(args)


def take_has_audio(path: Path) -> bool:
    """True when the take names its audio format and holds at least one AUDIO_PCM16 chunk."""
    with path.open("rb") as stream:
        result = ocraw.scan_stream(stream, verify_raw_crc=False)
    metadata: dict[str, str] = {}
    for chunk in result.chunks:
        if chunk.wire_id == ocraw.METADATA:
            try:
                metadata.update(ocraw.decode_text_map(chunk.payload))
            except (ValueError, UnicodeDecodeError):
                continue
    try:
        _positive_int(metadata, "audioSampleRate")
        _positive_int(metadata, "audioChannels")
    except ValueError:
        return False
    return any(chunk.wire_id == ocraw.AUDIO_PCM16 for chunk in result.chunks)


def _main_wav(args: argparse.Namespace) -> int:
    try:
        report = export_wav(args.input, args.wav, align_to_video=not args.wav_from_audio)
    except ValueError as error:
        print(f"error: {error}", file=sys.stderr)
        return 2
    seconds = report["frames"] / report["sampleRate"]
    print(f"audio written: {args.wav} ({report['sampleRate']} Hz, {report['channels']} ch, "
          f"{seconds:.3f} s from {report['chunks']} chunks)")
    print(f"silence padded: {report['paddedFrames']} frames; overlap trimmed: {report['trimmedFrames']} frames")
    if report["skippedChunks"]:
        print(f"warning: skipped {report['skippedChunks']} audio chunks with a partial sample frame")
    if report["droppedSamples"] not in (None, "0"):
        print(f"warning: the recorder dropped {report['droppedSamples']} sample frames (filled with silence)")
    if report["staleTail"]:
        print("truncated or damaged tail: wrote the complete audio chunks before it")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
