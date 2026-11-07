#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

import contextlib
import importlib.util
import io
import math
import os
import random
import shutil
import struct
import subprocess
import sys
import tempfile
import unittest
from fractions import Fraction
from pathlib import Path

ROOT = Path(__file__).parents[2]
FIXTURES = Path(__file__).parent / "fixtures" / "ocraw_codecs"


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    assert spec and spec.loader
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


ocraw = _load("ocraw_recovery", ROOT / "desktop-tools" / "ocraw_recovery.py")
to_dng = _load("ocraw_to_dng", ROOT / "desktop-tools" / "ocraw_to_dng.py")
validate_dng = _load("validate_dng", ROOT / "tools" / "validate_dng.py")

try:
    import numpy  # noqa: F401
    HAS_NUMPY = True
except ImportError:
    HAS_NUMPY = False

WIDTH, HEIGHT = 64, 48
MATRIX1 = "1024/1024 -256/1024 0/1024 -128/1024 1152/1024 -64/1024 0/1024 -512/1024 1536/1024"
MATRIX2 = "900/1000 -100/1000 0/1000 -200/1000 1200/1000 0/1000 0/1000 -300/1000 1300/1000"
FORWARD1 = "7/10 2/10 1/10 3/10 6/10 1/10 0/1 1/10 8/10"
STATIC = {
    "cfa": "RGGB",
    "blackLevel": "64 64 64 64",
    "whiteLevel": "1023",
    "colorMatrix1": MATRIX1,
    "colorMatrix2": MATRIX2,
    "forwardMatrix1": FORWARD1,
    "calibrationIlluminant1": "17",
    "calibrationIlluminant2": "21",
    "sensorWidth": "4080",
    "sensorHeight": "3060",
    "activeArray": "0 0 4080 3060",
    "crop": f"8 4 {WIDTH} {HEIGHT}",
    "fps": "24/1",
    "cameraId": "0",
    "model": "razr 50 ultra",
    "appVersion": "1.0.0",
}


# --------------------------------------------------------------------------
# A spec-exact .ocraw writer (docs/formats/opencine-raw-v1.md).
# --------------------------------------------------------------------------

def cbor_text_map(values: dict[str, str]) -> bytes:
    def head(major: int, length: int) -> bytes:
        if length < 24:
            return bytes([(major << 5) | length])
        if length <= 0xFF:
            return bytes([(major << 5) | 24, length])
        return bytes([(major << 5) | 25]) + length.to_bytes(2, "big")

    out = head(5, len(values))
    for key in sorted(values):
        for text in (key, values[key]):
            encoded = text.encode("utf-8")
            out += head(3, len(encoded)) + encoded
    return out


def pack_raw10(pixels: list[int]) -> bytes:
    out = bytearray()
    for index in range(0, len(pixels), 4):
        group = pixels[index:index + 4]
        out += bytes(value >> 2 for value in group)
        out.append(sum((value & 3) << (2 * k) for k, value in enumerate(group)))
    return bytes(out)


def frame_payload(pixels: list[int], width: int, height: int, frame_number: int, encoding: int = 0,
                  version: int = 1, data: bytes | None = None) -> bytes:
    if data is None:
        data = pack_raw10(pixels) if encoding == 0 else bytes(range(256)) * 3
    row_bytes = width * 5 // 4
    header = struct.pack("<HHIIIIIQQI", version, encoding, width, height, row_bytes, 8, 4,
                         1_000_000_000 + frame_number * 41_666_667, frame_number, len(data))
    return header.ljust(64, b"\0") + data


class TakeWriter:
    def __init__(self):
        self.data = bytearray(ocraw.MAGIC + struct.pack("<HH", 1, 0) + bytes(range(16)) + bytes(4))
        self.sequence = 0
        self.frame_ends: list[int] = []

    def append(self, wire_id: int, payload: bytes, ticks: int = 0) -> int:
        sequence = self.sequence
        self.data += struct.pack("<HHQQQQII", wire_id, 0, sequence, ticks, 1, 1_000_000_000, len(payload),
                                 ocraw.crc32c(payload)) + payload
        self.sequence += 1
        if wire_id == ocraw.RAW_FRAME:
            self.frame_ends.append(len(self.data))
        return sequence


def frame_pixels(seed: int, count: int) -> list[int]:
    rng = random.Random(seed)
    pixels = [rng.randrange(1024) for _ in range(count)]
    pixels[:4] = [0, 1023, 512, 3]  # extremes and both LSB patterns
    return pixels


def build_take(frames: int = 3, encodings: list[int] | None = None, static: dict[str, str] | None = None,
               frame_numbers: list[int] | None = None) -> tuple[bytes, list[list[int]], TakeWriter]:
    writer = TakeWriter()
    writer.append(ocraw.METADATA, cbor_text_map(static if static is not None else STATIC))
    encodings = encodings or [0] * frames
    numbers = frame_numbers or [1000 + index for index in range(len(encodings))]
    pixels_by_frame, pending = [], []
    for index, encoding in enumerate(encodings):
        pixels = frame_pixels(index, WIDTH * HEIGHT)
        pixels_by_frame.append(pixels)
        sequence = writer.append(ocraw.RAW_FRAME, frame_payload(pixels, WIDTH, HEIGHT, numbers[index], encoding))
        pending.append((index, sequence))
        # Frame metadata may follow later frames: emit each one frame late.
        if len(pending) == 2:
            done_index, done_sequence = pending.pop(0)
            writer.append(ocraw.FRAME_METADATA, cbor_text_map(frame_meta(done_index, done_sequence)))
    for done_index, done_sequence in pending:
        writer.append(ocraw.FRAME_METADATA, cbor_text_map(frame_meta(done_index, done_sequence)))
    writer.append(ocraw.END, b"")
    return bytes(writer.data), pixels_by_frame, writer


def frame_meta(index: int, sequence: int) -> dict[str, str]:
    values = {
        "sequence": str(sequence),
        "exposureNs": str(10_000_000 + index),
        "iso": str(400 + index * 100),
        "neutral": "512/1024 1024/1024 768/1024",
    }
    if index == 1:
        values["blackLevel"] = "60 62 63 66"
    return values


# --------------------------------------------------------------------------
# Encoded frame fixtures (encodings 1 and 2), written by the reference codec
# in native-pipeline/src/raw_codecs.cpp through fixtures/ocraw_codecs/make_fixtures.cpp.
# --------------------------------------------------------------------------

# name: (encoding, width, height, seed, strip rows)
CODEC_FIXTURES = {
    "lj92_64x34_s8_seed1": (1, 64, 34, 1, [16, 16, 2]),
    "lj92_128x50_s4_seed2": (1, 128, 50, 2, [16, 16, 16, 2]),
    "lj92_72x26_s1_seed3": (1, 72, 26, 3, [26]),
    "blockpack_64x34_s8_seed1": (2, 64, 34, 1, [16, 16, 2]),
    "blockpack_128x50_s4_seed2": (2, 128, 50, 2, [16, 16, 16, 2]),
    "blockpack_72x26_s1_seed3": (2, 72, 26, 3, [26]),
}

# Encoding 3 (quantised block-pack) fixtures from the same generator, with the
# Razr's ISO 25600 noise model: a = 0.6, b = 6.8974, black 64, k = 0.5.
QUANT_FIXTURES = {
    "blockpackq_64x34_s8_seed1": (3, 64, 34, 1, [16, 16, 2]),
    "blockpackq_72x26_s1_seed3": (3, 72, 26, 3, [26]),
}
QUANT_MODEL = (0.6, 6.8974, 64, 0.5)


def quant_tables(a: float, b: float, black: int, k: float) -> tuple[list[int], list[int]]:
    """Forward (DN -> code) and inverse (code -> DN) tables, as RawQuantisation.build."""
    forward, lo, hi, t = [], [1 << 30] * 1024, [-1] * 1024, 0.0
    for i in range(1024):
        c = math.floor(t + 0.5)  # lround for t >= 0
        forward.append(c)
        lo[c], hi[c] = min(lo[c], i), max(hi[c], i)
        sigma = math.sqrt(max(0.0, a + b * max(0, i - black)))
        t += 1.0 if sigma == 0 else min(1.0, 1.0 / (k * sigma))
    return forward, [c if hi[c] < 0 else (lo[c] + hi[c] + 1) // 2 for c in range(forward[-1] + 1)]


def fixture_samples(width: int, height: int, seed: int) -> list[int]:
    """The source samples of a fixture, as fixture_samples() in make_fixtures.cpp."""
    out, state = [], seed
    for y in range(height):
        for x in range(width):
            state ^= (state << 13) & 0xFFFFFFFF
            state ^= state >> 17
            state ^= (state << 5) & 0xFFFFFFFF
            out.append(max(0, min(1023, 64 + x * 600 // width + y * 200 // height + state % 33 - 16)))
    out[:4] = [0, 1023, 512, 3]
    out[(height - 1) * width + width - 1] = 1023
    out[(height - 1) * width + width - 2] = 0
    out[(height // 2) * width + width // 2] = 1023
    return out


def fixture(name: str) -> tuple[bytes, int, int, int, list[int], list[int]]:
    encoding, width, height, seed, rows = CODEC_FIXTURES.get(name) or QUANT_FIXTURES[name]
    return (FIXTURES / f"{name}.bin").read_bytes(), encoding, width, height, rows, fixture_samples(width, height, seed)


def strip_streams(data: bytes) -> list[bytes]:
    count = struct.unpack_from("<I", data)[0]
    offset, streams = 4 + 8 * count, []
    for index in range(count):
        length = struct.unpack_from("<I", data, 8 + 8 * index)[0]
        streams.append(data[offset:offset + length])
        offset += length
    return streams


def encoded_take(data: bytes, encoding: int, width: int, height: int, frame_number: int = 7) -> bytes:
    writer = TakeWriter()
    writer.append(ocraw.METADATA, cbor_text_map(STATIC))
    writer.append(ocraw.RAW_FRAME, frame_payload([], width, height, frame_number, encoding, data=data))
    writer.append(ocraw.END, b"")
    return bytes(writer.data)


# --------------------------------------------------------------------------
# A minimal TIFF reader.
# --------------------------------------------------------------------------

TYPES = {1: ("B", 1), 2: ("s", 1), 3: ("H", 2), 4: ("I", 4), 5: ("II", 8), 7: ("B", 1), 10: ("ii", 8)}


def read_ifd(data: bytes, offset: int) -> dict[int, object]:
    count = struct.unpack_from("<H", data, offset)[0]
    tags = {}
    for index in range(count):
        entry = offset + 2 + 12 * index
        tag, type_id, value_count = struct.unpack_from("<HHI", data, entry)
        fmt, size = TYPES[type_id]
        total = size * value_count
        at = entry + 8 if total <= 4 else struct.unpack_from("<I", data, entry + 8)[0]
        raw = data[at:at + total]
        if type_id == 2:
            value = raw.rstrip(b"\0").decode("ascii")
        elif type_id in (5, 10):
            value = [Fraction(*struct.unpack_from("<" + fmt, raw, k * 8)) for k in range(value_count)]
        else:
            value = list(struct.unpack(f"<{value_count}{fmt}", raw))
        tags[tag] = value
    tags["_next"] = struct.unpack_from("<I", data, offset + 2 + 12 * count)[0]
    tags["_order"] = [struct.unpack_from("<H", data, offset + 2 + 12 * k)[0] for k in range(count)]
    return tags


def read_parts(path: Path) -> list[bytes]:
    """The strips or tiles of IFD 0, as stored."""
    data = path.read_bytes()
    ifd0 = read_ifd(data, struct.unpack_from("<I", data, 4)[0])
    offsets, sizes = (ifd0[324], ifd0[325]) if 324 in ifd0 else (ifd0[273], ifd0[279])
    return [data[offset:offset + size] for offset, size in zip(offsets, sizes)]


def read_dng(path: Path) -> tuple[dict, dict, list[int]]:
    data = path.read_bytes()
    assert data[:4] == b"II*\0"
    ifd0 = read_ifd(data, struct.unpack_from("<I", data, 4)[0])
    exif = read_ifd(data, ifd0[34665][0])
    samples = []
    if ifd0[259] == [1]:
        pixels = b"".join(read_parts(path))
        samples = list(struct.unpack(f"<{len(pixels) // 2}H", pixels))
    return ifd0, exif, samples


def run_cli(args: list[str], pure: bool = False) -> tuple[int, str]:
    out = io.StringIO()
    previous = os.environ.get(to_dng.PURE_ENV)
    if pure:
        os.environ[to_dng.PURE_ENV] = "1"
    try:
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(io.StringIO()):
            code = to_dng.main(args)
    finally:
        if previous is None:
            os.environ.pop(to_dng.PURE_ENV, None)
        else:
            os.environ[to_dng.PURE_ENV] = previous
    return code, out.getvalue()


def ratios(text: str) -> list[Fraction]:
    return [Fraction(int(part.split("/")[0]), int(part.split("/")[1])) for part in text.split()]


class OcrawToDngTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.tmp = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, data: bytes, name: str = "take.ocraw") -> Path:
        path = self.tmp / name
        path.write_bytes(data)
        return path

    def test_unpack_matches_the_packed_values(self):
        pixels = frame_pixels(9, 4 * 50)
        expected = struct.pack(f"<{len(pixels)}H", *pixels)
        self.assertEqual(expected, to_dng._raw10_pure(pack_raw10(pixels)))
        if HAS_NUMPY:
            self.assertEqual(expected, to_dng._raw10_numpy(pack_raw10(pixels)))

    def test_converts_every_frame_with_samples_and_tags(self):
        data, pixels, _ = build_take()
        code, summary = run_cli([str(self.write(data)), "-o", str(self.tmp / "out"), "--pure"])
        self.assertEqual(0, code, summary)
        self.assertIn("frames written: 3", summary)
        self.assertIn(f"{WIDTH}x{HEIGHT} (3)", summary)
        files = sorted((self.tmp / "out").glob("*.dng"))
        self.assertEqual(["take_000000.dng", "take_000001.dng", "take_000002.dng"], [f.name for f in files])
        for index, path in enumerate(files):
            ifd0, exif, samples = read_dng(path)
            self.assertEqual(pixels[index], samples, path.name)
            self.assertEqual(sorted(ifd0["_order"]), ifd0["_order"], "tags must ascend")
            self.assertEqual(0, ifd0["_next"], "single image IFD")
            self.assertEqual([0], ifd0[254])
            self.assertEqual(([WIDTH], [HEIGHT]), (ifd0[256], ifd0[257]))
            self.assertEqual(([16], [1], [32803], [1]), (ifd0[258], ifd0[259], ifd0[262], ifd0[277]))
            self.assertEqual([1, 4, 0, 0], ifd0[50706])
            self.assertEqual([1, 1, 0, 0], ifd0[50707])
            self.assertEqual([2, 2], ifd0[33421])
            self.assertEqual([0, 1, 1, 2], ifd0[33422])
            self.assertEqual([0, 1, 2], ifd0[50710])
            self.assertEqual([1], ifd0[50711])
            self.assertEqual([2, 2], ifd0[50713])
            self.assertEqual([60, 62, 63, 66] if index == 1 else [64] * 4, ifd0[50714])
            self.assertEqual([1023], ifd0[50717])
            self.assertEqual(ratios(MATRIX1), ifd0[50721])
            self.assertEqual(ratios(MATRIX2), ifd0[50722])
            self.assertEqual(ratios(FORWARD1), ifd0[50964])
            self.assertNotIn(50965, ifd0)
            self.assertEqual(([17], [21]), (ifd0[50778], ifd0[50779]))
            self.assertEqual([Fraction(1, 2), Fraction(1), Fraction(3, 4)], ifd0[50728])
            self.assertEqual("razr 50 ultra", ifd0[50708])
            self.assertEqual("razr 50 ultra", ifd0[272])
            self.assertEqual(([0, 0], [WIDTH, HEIGHT]), (ifd0[50719], ifd0[50720]))
            self.assertEqual("OpenCineCam ocraw_to_dng", ifd0[305])
            self.assertEqual([Fraction(10_000_000 + index, 1_000_000_000)], exif[33434])
            self.assertEqual([400 + index * 100], exif[34855])
            report = validate_dng.read_dng(path)
            self.assertEqual("PASS", report["status"], report)
            self.assertEqual(WIDTH * HEIGHT * 2, report["payloadBytes"])

    @unittest.skipUnless(HAS_NUMPY, "numpy is not installed")
    def test_numpy_and_pure_outputs_are_identical(self):
        path = self.write(build_take()[0])
        self.assertEqual(0, run_cli([str(path), "-o", str(self.tmp / "pure"), "--pure"])[0])
        self.assertEqual(0, run_cli([str(path), "-o", str(self.tmp / "env")], pure=True)[0])
        code, summary = run_cli([str(path), "-o", str(self.tmp / "np")])
        self.assertEqual(0, code)
        self.assertIn("unpacking: numpy", summary)
        for name in ("take_000000.dng", "take_000001.dng", "take_000002.dng"):
            pure = (self.tmp / "pure" / name).read_bytes()
            self.assertEqual(pure, (self.tmp / "np" / name).read_bytes())
            self.assertEqual(pure, (self.tmp / "env" / name).read_bytes())

    def test_env_forces_the_pure_path(self):
        code, summary = run_cli([str(self.write(build_take(1)[0])), "-o", str(self.tmp / "out")], pure=True)
        self.assertEqual(0, code)
        self.assertIn("unpacking: pure Python", summary)

    def test_truncated_tail_converts_the_complete_frames(self):
        data, pixels, writer = build_take(frames=3)
        for cut, expected in ((writer.frame_ends[1] + 10, 2), (writer.frame_ends[0] - 1, 0),
                              (writer.frame_ends[2] - 7, 2)):
            out = self.tmp / f"cut{cut}"
            code, summary = run_cli([str(self.write(data[:cut], f"cut{cut}.ocraw")), "-o", str(out),
                                     "--prefix", "c", "--no-crc"])
            self.assertEqual(0, code, summary)
            self.assertIn("truncated or damaged tail", summary)
            files = sorted(out.glob("*.dng"))
            self.assertEqual(expected, len(files), summary)
            for index, path in enumerate(files):
                self.assertEqual(pixels[index], read_dng(path)[2])

    def test_corrupt_frame_stops_at_the_last_valid_chunk(self):
        data, _, writer = build_take(frames=3)
        corrupt = bytearray(data)
        corrupt[writer.frame_ends[1] - 5] ^= 0x10
        out = self.tmp / "out"
        code, summary = run_cli([str(self.write(bytes(corrupt))), "-o", str(out)])
        self.assertEqual(0, code)
        self.assertEqual(1, len(list(out.glob("*.dng"))), summary)

    def test_no_crc_skips_raw_frame_verification(self):
        data, _, writer = build_take(frames=3)
        corrupt = bytearray(data)
        corrupt[writer.frame_ends[1] - 5] ^= 0x10
        out = self.tmp / "out"
        code, summary = run_cli([str(self.write(bytes(corrupt))), "-o", str(out), "--no-crc"])
        self.assertEqual(0, code)
        self.assertIn("CRC32C: skipped", summary)
        self.assertEqual(3, len(list(out.glob("*.dng"))), summary)

    def test_frame_selection_and_every(self):
        path = self.write(build_take(frames=6)[0])
        cases = {
            # Names carry the frame's index in file order, not its frame number (1000 + index).
            ("--frames", "1-3"): [1, 2, 3],
            ("--frames", "0,5"): [0, 5],
            ("--every", "2"): [0, 2, 4],
            ("--frames", "1-", "--every", "2"): [1, 3, 5],
        }
        for args, numbers in cases.items():
            out = self.tmp / "-".join(args)
            code, summary = run_cli([str(path), "-o", str(out), "--prefix", "s", *args])
            self.assertEqual(0, code, summary)
            self.assertEqual([f"s_{n:06d}.dng" for n in numbers], sorted(p.name for p in out.glob("*.dng")))

    def test_unknown_encodings_are_skipped(self):
        # Encoding 3 is the quantised block-pack now; 4 is the first unassigned id.
        path = self.write(build_take(encodings=[0, 4, 9, 4])[0])
        code, summary = run_cli([str(path), "-o", str(self.tmp / "out")])
        self.assertEqual(0, code)
        self.assertIn("frames written: 1", summary)
        self.assertIn("skipped unsupported encoding: 3", summary)
        self.assertIn("encoding 4 (unknown) not supported: 2", summary)
        self.assertIn("encoding 9 (unknown) not supported: 1", summary)

    def test_invalid_frame_header_is_skipped(self):
        writer = TakeWriter()
        writer.append(ocraw.METADATA, cbor_text_map(STATIC))
        writer.append(ocraw.RAW_FRAME, frame_payload(frame_pixels(0, WIDTH * HEIGHT), WIDTH, HEIGHT, 1, version=7))
        writer.append(ocraw.RAW_FRAME, frame_payload(frame_pixels(1, WIDTH * HEIGHT), WIDTH, HEIGHT, 2))
        code, summary = run_cli([str(self.write(bytes(writer.data))), "-o", str(self.tmp / "out")])
        self.assertEqual(0, code)
        self.assertIn("frames written: 1", summary)
        self.assertIn("skipped invalid: 1", summary)

    def test_bad_file_header_exits_non_zero(self):
        data = bytearray(build_take(1)[0])
        data[8] = 2  # version 2
        code, _ = run_cli([str(self.write(bytes(data))), "-o", str(self.tmp / "out")])
        self.assertNotEqual(0, code)
        code, _ = run_cli([str(self.write(b"not an ocraw file at all, sorry")), "-o", str(self.tmp / "out")])
        self.assertNotEqual(0, code)

    def test_missing_matrices_fall_back_to_identity_with_a_warning(self):
        static = {key: value for key, value in STATIC.items() if not key.startswith(("color", "forward", "calib"))}
        static["cfa"] = "3"  # Android BGGR as its integer value
        code, summary = run_cli([str(self.write(build_take(2, static=static)[0])), "-o", str(self.tmp / "out")])
        self.assertEqual(0, code)
        self.assertEqual(1, summary.count("identity ColorMatrix1"), summary)
        ifd0, _, _ = read_dng(sorted((self.tmp / "out").glob("*.dng"))[0])
        self.assertEqual([Fraction(int(r == c)) for r in range(3) for c in range(3)], ifd0[50721])
        self.assertEqual([21], ifd0[50778])
        self.assertEqual([2, 1, 1, 0], ifd0[33422])
        for tag in (50722, 50779, 50964):
            self.assertNotIn(tag, ifd0)

    def test_cfa_names(self):
        expected = {"RGGB": [0, 1, 1, 2], "GRBG": [1, 0, 2, 1], "GBRG": [1, 2, 0, 1], "BGGR": [2, 1, 1, 0],
                    "0": [0, 1, 1, 2], "1": [1, 0, 2, 1], "2": [1, 2, 0, 1]}
        for name, pattern in expected.items():
            job = to_dng.Conversion(output=self.tmp, prefix="x", use_numpy=False)
            self.assertEqual(tuple(pattern), to_dng.static_colour({**STATIC, "cfa": name}, job).cfa, name)
        with self.assertRaises(ValueError):
            to_dng.static_colour({**STATIC, "cfa": "MONO"}, to_dng.Conversion(self.tmp, "x", False))

    def test_frame_without_metadata_omits_neutral_and_exif_values(self):
        writer = TakeWriter()
        writer.append(ocraw.METADATA, cbor_text_map(STATIC))
        pixels = frame_pixels(3, WIDTH * HEIGHT)
        writer.append(ocraw.RAW_FRAME, frame_payload(pixels, WIDTH, HEIGHT, 7))
        code, _ = run_cli([str(self.write(bytes(writer.data))), "-o", str(self.tmp / "out")])
        self.assertEqual(0, code)
        ifd0, exif, samples = read_dng(self.tmp / "out" / "take_000000.dng")
        self.assertEqual(pixels, samples)
        self.assertNotIn(50728, ifd0)
        self.assertNotIn(33434, exif)
        self.assertNotIn(34855, exif)

    def convert_partial(self, metas: list[dict[str, str] | None]) -> list[tuple[dict, dict]]:
        """A take with one frame per entry, a None entry having no FRAME_METADATA record; returns each DNG's IFDs."""
        writer = TakeWriter()
        writer.append(ocraw.METADATA, cbor_text_map(STATIC))
        for index, meta in enumerate(metas):
            sequence = writer.append(ocraw.RAW_FRAME, frame_payload(frame_pixels(index, WIDTH * HEIGHT), WIDTH,
                                                                    HEIGHT, 1000 + index))
            if meta is not None:
                writer.append(ocraw.FRAME_METADATA, cbor_text_map({"sequence": str(sequence), **meta}))
        writer.append(ocraw.END, b"")
        code, _ = run_cli([str(self.write(bytes(writer.data))), "-o", str(self.tmp / "out")])
        self.assertEqual(0, code)
        return [read_dng(self.tmp / "out" / f"take_{index:06d}.dng")[:2] for index in range(len(metas))]

    def test_last_frame_without_metadata_inherits_the_previous_colour(self):
        first = {"exposureNs": "10000000", "iso": "400", "neutral": "1/2 1/1 3/4",
                 "dynamicBlackLevel": "64.5 64 64 64", "whiteLevel": "1000"}
        second = {"exposureNs": "20000000", "iso": "800", "neutral": "1/3 1/1 2/3",
                  "dynamicBlackLevel": "63 63 65 65", "whiteLevel": "1010"}
        dngs = self.convert_partial([first, second, None])
        ifd0, exif = dngs[2]
        self.assertEqual(dngs[1][0][50728], ifd0[50728])
        self.assertEqual(ratios("1/3 1/1 2/3"), ifd0[50728])
        self.assertEqual(dngs[1][0][50714], ifd0[50714])
        self.assertEqual([1010], ifd0[50717])
        self.assertNotIn(33434, exif)
        self.assertNotIn(34855, exif)

    def test_first_frame_without_metadata_inherits_the_next_colour(self):
        later = {"exposureNs": "10000000", "iso": "400", "neutral": "1/2 1/1 3/4", "blackLevel": "60 62 63 66"}
        dngs = self.convert_partial([None, later, {"neutral": "1/3 1/1 2/3"}])
        ifd0, exif = dngs[0]
        self.assertEqual(ratios("1/2 1/1 3/4"), ifd0[50728])
        self.assertEqual(dngs[1][0][50714], ifd0[50714])
        self.assertEqual([1023], ifd0[50717])
        self.assertNotIn(33434, exif)
        self.assertNotIn(34855, exif)

    def test_take_without_any_frame_metadata_is_unchanged(self):
        dngs = self.convert_partial([None, None])
        for ifd0, exif in dngs:
            self.assertNotIn(50728, ifd0)
            self.assertEqual([1023], ifd0[50717])
            self.assertNotIn(33434, exif)

    def test_inherit_colour_takes_each_group_from_its_nearest_record(self):
        meta = {
            10: {"neutral": "1/2 1/1 3/4", "iso": "100"},
            11: {"dynamicBlackLevel": "64 64 64 64", "whiteLevel": "1000", "neutral": ""},
            14: {"neutral": "1/3 1/1 2/3", "blackLevel": "60 60 60 60"},
        }
        inherited = to_dng.inherit_colour([9, 10, 11, 12, 13, 14], meta)
        self.assertEqual({9, 12, 13}, set(inherited))
        self.assertEqual({"neutral": "1/2 1/1 3/4", "dynamicBlackLevel": "64 64 64 64", "whiteLevel": "1000"},
                         inherited[9])
        self.assertEqual({"neutral": "1/2 1/1 3/4", "dynamicBlackLevel": "64 64 64 64", "whiteLevel": "1000"},
                         inherited[12])
        self.assertEqual(inherited[12], inherited[13])
        self.assertEqual({}, to_dng.inherit_colour([1, 2], {}))
        self.assertEqual({}, to_dng.inherit_colour([1], {1: {"neutral": "1/1 1/1 1/1"}}))


class EncodedFramesTest(unittest.TestCase):
    """Encodings 1 (LJ92) and 2 (block-pack), with fixtures from the reference C++ codec."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.tmp = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def convert(self, name: str, *args: str, data: bytes | None = None) -> tuple[int, str, Path]:
        encoded, encoding, width, height, _, _ = fixture(name)
        take = self.tmp / f"{name}.ocraw"
        take.write_bytes(encoded_take(encoded if data is None else data, encoding, width, height))
        out = self.tmp / f"out-{name}-{'-'.join(args)}"
        code, summary = run_cli([str(take), "-o", str(out), "--prefix", "f", *args])
        return code, summary, out

    def test_fixture_strip_layout_matches_the_codec_rule(self):
        for name, (_, _, height, _, rows) in CODEC_FIXTURES.items():
            data = fixture(name)[0]
            self.assertEqual([(r, len(s)) for r, s in zip(rows, strip_streams(data))],
                             [(r, len(s)) for r, s in to_dng.parse_strips(data, height)], name)

    def test_block_pack_decodes_to_the_source_samples(self):
        for name in (n for n in CODEC_FIXTURES if n.startswith("blockpack")):
            data, _, width, height, _, source = fixture(name)
            header = {"width": width, "height": height}
            expected = struct.pack(f"<{len(source)}H", *source)
            self.assertEqual(expected, to_dng.decode_block_pack(header, data, use_numpy=False), name)
            if HAS_NUMPY:
                self.assertEqual(expected, to_dng.decode_block_pack(header, data, use_numpy=True), name)

    def test_block_pack_take_writes_an_uncompressed_dng(self):
        for name in (n for n in CODEC_FIXTURES if n.startswith("blockpack")):
            _, _, width, height, _, source = fixture(name)
            for args in ((), ("--pure",), ("--no-crc",)):
                code, summary, out = self.convert(name, *args)
                self.assertEqual(0, code, summary)
                self.assertIn("frames written: 1 of 1", summary)
                path = out / "f_000000.dng"
                ifd0, _, samples = read_dng(path)
                self.assertEqual(source, samples, (name, args))
                self.assertEqual(([16], [1], [width], [height]), (ifd0[258], ifd0[259], ifd0[256], ifd0[257]))
                report = validate_dng.read_dng(path)
                self.assertEqual("PASS", report["status"], report)
                self.assertEqual(width * height * 2, report["payloadBytes"])

    @unittest.skipUnless(HAS_NUMPY, "numpy is not installed")
    def test_block_pack_numpy_and_pure_files_are_identical(self):
        name = "blockpack_128x50_s4_seed2"
        _, _, numpy_out = self.convert(name)
        _, _, pure_out = self.convert(name, "--pure")
        self.assertEqual((pure_out / "f_000000.dng").read_bytes(), (numpy_out / "f_000000.dng").read_bytes())

    def test_lj92_strips_are_stored_unchanged_as_tiles(self):
        for name in (n for n in CODEC_FIXTURES if n.startswith("lj92")):
            data, _, width, height, rows, _ = fixture(name)
            for args in ((), ("--pure",)):
                code, summary, out = self.convert(name, *args)
                self.assertEqual(0, code, summary)
                self.assertIn("frames written: 1 of 1", summary)
                path = out / "f_000000.dng"
                ifd0, _, _ = read_dng(path)
                self.assertEqual(sorted(ifd0["_order"]), ifd0["_order"], "tags must ascend")
                self.assertEqual(([10], [7], [32803], [1]), (ifd0[258], ifd0[259], ifd0[262], ifd0[277]))
                self.assertEqual(([width], [height]), (ifd0[256], ifd0[257]))
                self.assertEqual(([width], [rows[0]]), (ifd0[322], ifd0[323]))
                self.assertEqual(len(rows), len(ifd0[324]))
                self.assertEqual(-(-height // rows[0]), len(ifd0[324]), "TIFF tile count rule")
                for tag in (273, 278, 279):
                    self.assertNotIn(tag, ifd0)
                streams = strip_streams(data)
                self.assertEqual([len(s) for s in streams], ifd0[325])
                self.assertEqual(streams, read_parts(path))
                self.assertEqual([0, 1, 1, 2], ifd0[33422])
                self.assertEqual(([64] * 4, [1023]), (ifd0[50714], ifd0[50717]))
                report = validate_dng.read_dng(path)
                self.assertEqual("PASS", report["status"], report)
                self.assertEqual(sum(ifd0[325]), report["payloadBytes"])

    @unittest.skipUnless(shutil.which("unprocessed_raw"), "LibRaw unprocessed_raw is not installed")
    def test_libraw_decodes_both_encodings_bit_exact(self):
        for name in CODEC_FIXTURES:
            _, _, width, height, _, source = fixture(name)
            code, summary, out = self.convert(name)
            self.assertEqual(0, code, summary)
            path = out / "f_000000.dng"
            result = subprocess.run(["unprocessed_raw", "-q", str(path)], capture_output=True, text=True,
                                    timeout=60)
            self.assertEqual(0, result.returncode, result.stderr)
            pgm = Path(f"{path}.pgm").read_bytes()
            magic, size, maximum, pixels = pgm.split(b"\n", 3)
            self.assertEqual((b"P5", f"{width} {height}".encode()), (magic, size), name)
            self.assertEqual(source, list(struct.unpack(f">{width * height}H", pixels[:width * height * 2])), name)

    def test_quantised_block_pack_decodes_to_the_reconstructed_samples(self):
        forward, inverse = quant_tables(*QUANT_MODEL)
        for name in QUANT_FIXTURES:
            data, _, width, height, _, source = fixture(name)
            self.assertEqual((1, 0, len(inverse), 25600, 500, 64), struct.unpack_from("<BBHIHH", data), name)
            self.assertEqual(list(inverse), list(struct.unpack_from(f"<{len(inverse)}H", data, 12)), name)
            header = {"width": width, "height": height}
            expected = struct.pack(f"<{len(source)}H", *(inverse[forward[v]] for v in source))
            self.assertNotEqual(struct.pack(f"<{len(source)}H", *source), expected, "the fixture is lossy")
            for use_numpy in (False, True) if HAS_NUMPY else (False,):
                self.assertEqual(expected, to_dng.decode_block_pack_quantised(header, data, use_numpy), (name, use_numpy))
                # A reader of encoding 2 alone rejects the payload instead of returning codes.
                with self.assertRaises(ValueError):
                    to_dng.decode_block_pack(header, data, use_numpy)

    def test_quantised_block_pack_take_writes_an_uncompressed_dng(self):
        forward, inverse = quant_tables(*QUANT_MODEL)
        for name in QUANT_FIXTURES:
            _, _, width, height, _, source = fixture(name)
            for args in ((), ("--pure",)):
                code, summary, out = self.convert(name, *args)
                self.assertEqual(0, code, summary)
                self.assertIn("frames written: 1 of 1", summary)
                ifd0, _, samples = read_dng(out / "f_000000.dng")
                self.assertEqual([inverse[forward[v]] for v in source], samples, (name, args))
                self.assertEqual(([16], [1], [width], [height]), (ifd0[258], ifd0[259], ifd0[256], ifd0[257]))

    def test_quantised_block_pack_rejects_bad_payloads(self):
        data, _, width, height, _, _ = fixture("blockpackq_64x34_s8_seed1")
        count = struct.unpack_from("<H", data, 2)[0]
        start = 12 + 2 * count

        def patched(offset: int, fmt: str, value: int) -> bytes:
            out = bytearray(data)
            struct.pack_into(fmt, out, offset, value)
            return bytes(out)

        # The lossless container of the source samples behind a four-entry table.
        lossless = fixture("blockpack_64x34_s8_seed1")[0]
        cases = {
            "truncated preamble": data[:11],
            "truncated table": data[:start - 1],
            "truncated container": data[:-1],
            "version 2": patched(0, "<B", 2),
            "no table": patched(2, "<H", 0),
            "1025 entries": patched(2, "<H", 1025),
            "inverse above 1023": patched(12, "<H", 1024),
            "code beyond the table": struct.pack("<BBHIHH4H", 1, 0, 4, 0, 0, 0, 0, 1, 2, 3) + lossless,
        }
        header = {"width": width, "height": height}
        for label, payload in cases.items():
            for use_numpy in (False, True) if HAS_NUMPY else (False,):
                with self.assertRaises(ValueError, msg=(label, use_numpy)):
                    to_dng.decode_block_pack_quantised(header, payload, use_numpy)
            code, summary, out = self.convert("blockpackq_64x34_s8_seed1", data=payload)
            self.assertEqual(0, code, (label, summary))
            self.assertIn("skipped invalid: 1", summary, label)
        # The reserved byte and the informative fields are not checked.
        relaxed = patched(1, "<B", 9)
        relaxed = relaxed[:4] + struct.pack("<IHH", 7, 1, 2) + relaxed[12:]
        self.assertEqual(to_dng.decode_block_pack_quantised(header, data, False),
                         to_dng.decode_block_pack_quantised(header, relaxed, False))

    def test_block_pack_rejects_bad_rows(self):
        def frame(*rows: bytes) -> bytes:
            stream = b"".join(rows)
            return struct.pack("<III", 1, len(rows), len(stream)) + stream

        header = {"width": 16, "height": 2}
        zero_row = b"\x00"  # one block of width 0
        cases = {
            # Width 1, z = 1 for the first sample: residual -1 from a prediction of 0.
            "sample below 0": frame(b"\x01\x01\x00", zero_row),
            "unused nibble set": frame(b"\x10", zero_row),
            "row payload truncated": frame(b"\x02\x00\x00\x00", zero_row),
            "trailing bytes": frame(zero_row, zero_row + b"\x00"),
        }
        self.assertEqual(bytes(64), to_dng.decode_block_pack(header, frame(zero_row, zero_row), False))
        for label, data in cases.items():
            for use_numpy in (False, True) if HAS_NUMPY else (False,):
                with self.assertRaises(ValueError, msg=(label, use_numpy)):
                    to_dng.decode_block_pack(header, data, use_numpy)

    def test_malformed_containers_are_skipped_as_invalid(self):
        lj92 = fixture("lj92_64x34_s8_seed1")[0]
        block = fixture("blockpack_64x34_s8_seed1")[0]

        def with_rows(data: bytes, rows: list[int]) -> bytes:
            out = bytearray(data)
            for index, value in enumerate(rows):
                struct.pack_into("<I", out, 4 + 8 * index, value)
            return bytes(out)

        first_stream = 4 + 8 * 3
        wide_nibble = bytearray(block)
        wide_nibble[first_stream] = (wide_nibble[first_stream] & 0xF0) | 12
        corrupt_sof = bytearray(lj92)
        corrupt_sof[first_stream:first_stream + 2] = b"\0\0"
        cases = {
            "truncated": ("blockpack_64x34_s8_seed1", block[:-1]),
            "empty": ("lj92_64x34_s8_seed1", b""),
            "zero strips": ("lj92_64x34_s8_seed1", bytes(4)),
            "rows short of height": ("blockpack_64x34_s8_seed1", with_rows(block, [16, 16, 0])),
            "odd rows": ("blockpack_64x34_s8_seed1", with_rows(block, [16, 15, 3])),
            "non-uniform lj92": ("lj92_64x34_s8_seed1", with_rows(lj92, [16, 14, 4])),
            "block width 12": ("blockpack_64x34_s8_seed1", bytes(wide_nibble)),
            "lj92 without SOI": ("lj92_64x34_s8_seed1", bytes(corrupt_sof)),
            "block rows disagree": ("blockpack_64x34_s8_seed1", with_rows(block, [14, 18, 2])),
        }
        for label, (name, data) in cases.items():
            for args in ((), ("--pure",)):
                code, summary, out = self.convert(name, *args, data=data)
                self.assertEqual(0, code, (label, summary))
                self.assertIn("frames written: 0 of 1", summary, label)
                self.assertIn("skipped invalid: 1", summary, label)
                self.assertFalse(list(out.glob("*.dng")), label)


class CinemaDngTest(unittest.TestCase):
    """CinemaDNG tags, naming and the `--cinemadng` clip folder (PLAN-071 §8)."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.tmp = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, data: bytes, name: str = "A001_C002.ocraw") -> Path:
        path = self.tmp / name
        path.write_bytes(data)
        return path

    def test_smpte_timecode_bcd_layout(self):
        tc = to_dng.smpte_timecode
        self.assertEqual(bytes(8), tc(0, Fraction(24)))
        # 1 h 2 min 3 s 4 frames at 24 fps.
        frames = ((1 * 60 + 2) * 60 + 3) * 24 + 4
        self.assertEqual(bytes([0x04, 0x03, 0x02, 0x01, 0, 0, 0, 0]), tc(frames, Fraction(24)))
        # 23:59:59:23, then the hours wrap at 24.
        last = 24 * 3600 * 24 - 1
        self.assertEqual(bytes([0x23, 0x59, 0x59, 0x23, 0, 0, 0, 0]), tc(last, Fraction(24)))
        self.assertEqual(bytes(8), tc(last + 1, Fraction(24)))
        # 29.97 labels at the nominal 30, non-drop: frame 29 then 00:00:01:00.
        self.assertEqual(bytes([0x29, 0, 0, 0, 0, 0, 0, 0]), tc(29, Fraction(30000, 1001)))
        self.assertEqual(bytes([0x00, 0x01, 0, 0, 0, 0, 0, 0]), tc(30, Fraction(30000, 1001)))
        # 15 fps: 00:00:10:14.
        self.assertEqual(bytes([0x14, 0x10, 0, 0, 0, 0, 0, 0]), tc(164, Fraction(15)))
        # 60 fps counts frame pairs; the second of a pair sets byte 1 bit 7 (byte 3 bit 7 at 50 fps).
        self.assertEqual(bytes([0x29, 0x00, 0, 0, 0, 0, 0, 0]), tc(58, Fraction(60)))
        self.assertEqual(bytes([0x29, 0x80, 0, 0, 0, 0, 0, 0]), tc(59, Fraction(60)))
        self.assertEqual(bytes([0x24, 0x00, 0, 0x80, 0, 0, 0, 0]), tc(49, Fraction(50)))

    def test_timecode_frames_round_from_the_first_frame(self):
        frames = to_dng.timecode_frames
        self.assertEqual(0, frames(5_000_000_000, 5_000_000_000, Fraction(24)))
        self.assertEqual(1, frames(5_041_000_000, 5_000_000_000, Fraction(24)))   # 0.984 frames
        self.assertEqual(3, frames(5_100_100_000, 5_000_000_000, Fraction(30)))   # 3.003 frames
        self.assertEqual(2, frames(5_062_500_000, 5_000_000_000, Fraction(24)))   # 1.5: half to even
        self.assertEqual(0, frames(4_000_000_000, 5_000_000_000, Fraction(24)))   # never negative

    def test_every_dng_carries_the_cinemadng_tags(self):
        data, _, _ = build_take(frames=3)
        code, summary = run_cli([str(self.write(data)), "-o", str(self.tmp / "out"), "--pure"])
        self.assertEqual(0, code, summary)
        files = sorted((self.tmp / "out").glob("*.dng"))
        self.assertEqual(["A001_C002_000000.dng", "A001_C002_000001.dng", "A001_C002_000002.dng"],
                         [f.name for f in files])
        for index, path in enumerate(files):
            ifd0, _, _ = read_dng(path)
            self.assertEqual([Fraction(24)], ifd0[51044])
            raw = path.read_bytes()
            self.assertEqual([index, 0, 0, 0, 0, 0, 0, 0], ifd0[51043], path.name)
            self.assertEqual("A001_C002", ifd0[51081])
            self.assertEqual("A001_C002", ifd0[51105])
            self.assertEqual("razr 50 ultra", ifd0[271])
            self.assertEqual("razr 50 ultra", ifd0[272])
            self.assertEqual("razr 50 ultra", ifd0[50708])
            self.assertEqual(sorted(ifd0["_order"]), ifd0["_order"])
            # Types: TimeCodes BYTE x 8, FrameRate SRATIONAL x 1, the names ASCII.
            types = self.entry_types(raw)
            self.assertEqual((1, 8), types[51043])
            self.assertEqual((10, 1), types[51044])
            self.assertEqual((2, len("A001_C002") + 1), types[51081])
            self.assertEqual((2, len("A001_C002") + 1), types[51105])
            self.assertEqual("PASS", validate_dng.read_dng(path)["status"])

    def test_prefix_renames_files_but_the_reel_stays_the_take(self):
        data, _, _ = build_take(frames=1)
        code, _ = run_cli([str(self.write(data)), "-o", str(self.tmp / "out"), "--prefix", "x", "--pure"])
        self.assertEqual(0, code)
        ifd0, _, _ = read_dng(self.tmp / "out" / "x_000000.dng")
        self.assertEqual("A001_C002", ifd0[51081])

    def test_make_key_and_fractional_fps(self):
        static = dict(STATIC, make="motorola", fps="30000/1001")
        data, _, _ = build_take(frames=3, static=static)
        code, _ = run_cli([str(self.write(data)), "-o", str(self.tmp / "out"), "--pure"])
        self.assertEqual(0, code)
        ifd0, _, _ = read_dng(self.tmp / "out" / "A001_C002_000002.dng")
        self.assertEqual("motorola", ifd0[271])
        self.assertEqual("razr 50 ultra", ifd0[272])
        self.assertEqual([Fraction(30000, 1001)], ifd0[51044])
        # 2 x 41.67 ms at 29.97 fps is 2.498 frames.
        self.assertEqual([2, 0, 0, 0, 0, 0, 0, 0], ifd0[51043])

    def test_without_fps_the_rate_and_timecode_are_omitted(self):
        static = {key: value for key, value in STATIC.items() if key != "fps"}
        data, _, _ = build_take(frames=1, static=static)
        code, summary = run_cli([str(self.write(data)), "-o", str(self.tmp / "out"), "--pure"])
        self.assertEqual(0, code)
        self.assertIn("no usable fps", summary)
        ifd0, _, _ = read_dng(self.tmp / "out" / "A001_C002_000000.dng")
        self.assertNotIn(51044, ifd0)
        self.assertNotIn(51043, ifd0)
        self.assertEqual("A001_C002", ifd0[51081])

    def test_cinemadng_folder_with_audio(self):
        chunks = [(1_000_000_000, pcm_block(100, 2, 0))]
        path = self.write(build_audio_take(chunks, [1_000_000_000, 1_041_666_667]))
        code, summary = run_cli([str(path), "--cinemadng", str(self.tmp / "clips"), "--pure"])
        self.assertEqual(0, code, summary)
        folder = self.tmp / "clips" / "A001_C002"
        self.assertEqual(["A001_C002.wav", "A001_C002_000000.dng", "A001_C002_000001.dng"],
                         sorted(p.name for p in folder.iterdir()))
        self.assertEqual(["A001_C002"], [p.name for p in (self.tmp / "clips").iterdir()])
        self.assertEqual(pcm_block(100, 2, 0), read_wav(folder / "A001_C002.wav")[1])
        self.assertEqual("A001_C002", read_dng(folder / "A001_C002_000001.dng")[0][51081])

    def test_cinemadng_folder_without_audio(self):
        path = self.write(build_take(frames=2)[0])
        code, summary = run_cli([str(path), "--cinemadng", str(self.tmp / "clips"), "--pure"])
        self.assertEqual(0, code, summary)
        self.assertIn("audio: none", summary)
        folder = self.tmp / "clips" / "A001_C002"
        self.assertEqual(["A001_C002_000000.dng", "A001_C002_000001.dng"], sorted(p.name for p in folder.iterdir()))
        # Audio keys without any chunk are silent too.
        path = self.write(build_audio_take([], [1_000_000_000]), "silent.ocraw")
        code, summary = run_cli([str(path), "--cinemadng", str(self.tmp / "clips"), "--pure"])
        self.assertEqual(0, code, summary)
        self.assertEqual(["silent_000000.dng"], [p.name for p in (self.tmp / "clips" / "silent").iterdir()])

    def test_cinemadng_excludes_output_and_wav(self):
        path = self.write(build_take(frames=1)[0])
        for extra in (["-o", str(self.tmp / "out")], ["--wav", str(self.tmp / "x.wav")]):
            with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
                to_dng.main([str(path), "--cinemadng", str(self.tmp / "clips"), *extra])

    @staticmethod
    def entry_types(data: bytes) -> dict[int, tuple[int, int]]:
        offset = struct.unpack_from("<I", data, 4)[0]
        count = struct.unpack_from("<H", data, offset)[0]
        out = {}
        for index in range(count):
            tag, type_id, value_count = struct.unpack_from("<HHI", data, offset + 2 + 12 * index)
            out[tag] = (type_id, value_count)
        return out


def pcm_block(frames: int, channels: int, first: int) -> bytes:
    """Interleaved PCM16 LE samples numbered from [first], so placement is checkable."""
    return b"".join(struct.pack("<h", (first + index) % 30000) for index in range(frames * channels))


def build_audio_take(audio: list[tuple[int, bytes]], frame_ticks: list[int], rate: int = 1000,
                     channels: int = 2, keys: bool = True, closing: dict[str, str] | None = None) -> bytes:
    """A take with RAW frames at [frame_ticks] (ns) and AUDIO_PCM16 chunks interleaved after the first frame."""
    writer = TakeWriter()
    static = dict(STATIC)
    if keys:
        static.update({"audioSampleRate": str(rate), "audioChannels": str(channels), "audioSource": "UNPROCESSED"})
    writer.append(ocraw.METADATA, cbor_text_map(static), ticks=frame_ticks[0])
    for index, ticks in enumerate(frame_ticks):
        writer.append(ocraw.RAW_FRAME, frame_payload(frame_pixels(index, WIDTH * HEIGHT), WIDTH, HEIGHT, index), ticks)
        if index == 0:
            for chunk_ticks, payload in audio:
                writer.append(ocraw.AUDIO_PCM16, payload, chunk_ticks)
    if closing:
        writer.append(ocraw.METADATA, cbor_text_map(closing))
    writer.append(ocraw.END, b"")
    return bytes(writer.data)


def read_wav(path: Path) -> tuple[tuple, bytes]:
    data = path.read_bytes()
    header = struct.unpack_from("<4sI4s4sIHHIIHH4sI", data)
    return header, data[44:]


class OcrawWavExportTest(unittest.TestCase):
    """`--wav`: the AUDIO_PCM16 chunks of a RAW take with sound as a WAV (PLAN-071 §7.1.1)."""

    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.tmp = Path(self.directory.name)

    def tearDown(self):
        self.directory.cleanup()

    def write(self, data: bytes) -> Path:
        path = self.tmp / "take.ocraw"
        path.write_bytes(data)
        return path

    def test_contiguous_chunks_from_the_first_frame_make_a_valid_wav(self):
        # 1 kHz stereo: 100-frame chunks every 100 ms, starting with the first frame at 5 s.
        chunks = [(5_000_000_000 + index * 100_000_000, pcm_block(100, 2, index * 200)) for index in range(3)]
        path = self.write(build_audio_take(chunks, [5_000_000_000, 5_041_666_667]))
        wav = self.tmp / "out" / "take.wav"
        code, summary = run_cli([str(path), "--wav", str(wav)])
        self.assertEqual(0, code, summary)
        header, data = read_wav(wav)
        self.assertEqual((b"RIFF", 36 + 1200, b"WAVE", b"fmt ", 16, 1, 2, 1000, 4000, 4, 16, b"data", 1200), header)
        self.assertEqual(pcm_block(300, 2, 0), data)
        self.assertIn("1000 Hz, 2 ch, 0.300 s from 3 chunks", summary)
        self.assertEqual([wav], list((self.tmp / "out").iterdir()))

    def test_gaps_become_silence_and_the_wav_starts_at_the_first_frame(self):
        # Audio begins 50 ms after the first frame; the recorder dropped the chunk at 5.15 s.
        chunks = [(5_050_000_000, pcm_block(100, 1, 0)), (5_250_000_000, pcm_block(100, 1, 100))]
        path = self.write(build_audio_take(chunks, [5_000_000_000], channels=1,
                                           closing={"audioDroppedSamples": "100"}))
        wav = self.tmp / "take.wav"
        code, summary = run_cli([str(path), "--wav", str(wav)])
        self.assertEqual(0, code, summary)
        _, data = read_wav(wav)
        self.assertEqual(bytes(50 * 2) + pcm_block(100, 1, 0) + bytes(100 * 2) + pcm_block(100, 1, 100), data)
        self.assertIn("silence padded: 150 frames", summary)
        self.assertIn("dropped 100 sample frames", summary)

        code, summary = run_cli([str(path), "--wav", str(wav), "--wav-from-audio"])
        self.assertEqual(0, code, summary)
        _, data = read_wav(wav)
        self.assertEqual(pcm_block(100, 1, 0) + bytes(100 * 2) + pcm_block(100, 1, 100), data)

    def test_audio_before_the_first_frame_and_overlaps_are_trimmed(self):
        # The first chunk starts 20 ms before the first frame; the next overlaps it by 30 ms.
        chunks = [(980_000_000, pcm_block(100, 1, 0)), (1_050_000_000, pcm_block(100, 1, 1000))]
        path = self.write(build_audio_take(chunks, [1_000_000_000], channels=1))
        wav = self.tmp / "take.wav"
        report = to_dng.export_wav(path, wav)
        _, data = read_wav(wav)
        self.assertEqual(pcm_block(80, 1, 20) + pcm_block(70, 1, 1030), data)
        self.assertEqual(20 + 30, report["trimmedFrames"])
        self.assertEqual(150, report["frames"])

    def test_dngs_and_the_wav_in_one_run(self):
        chunks = [(1_000_000_000, pcm_block(100, 2, 0))]
        path = self.write(build_audio_take(chunks, [1_000_000_000, 1_041_666_667]))
        out, wav = self.tmp / "dng", self.tmp / "take.wav"
        code, summary = run_cli([str(path), "-o", str(out), "--wav", str(wav)])
        self.assertEqual(0, code, summary)
        self.assertEqual(2, len(list(out.glob("*.dng"))))
        self.assertEqual(pcm_block(100, 2, 0), read_wav(wav)[1])

    def test_a_take_without_audio_is_an_error(self):
        wav = self.tmp / "take.wav"
        silent = self.write(build_audio_take([], [1_000_000_000], keys=False))
        code, _ = run_cli([str(silent), "--wav", str(wav)])
        self.assertEqual(2, code)
        self.assertFalse(wav.exists())
        with self.assertRaisesRegex(ValueError, "audioSampleRate"):
            to_dng.export_wav(silent, wav)
        no_chunks = self.write(build_audio_take([], [1_000_000_000]))
        with self.assertRaisesRegex(ValueError, "no audio chunks"):
            to_dng.export_wav(no_chunks, wav)

    def test_an_output_is_required(self):
        path = self.write(build_audio_take([], [1_000_000_000]))
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            to_dng.main([str(path)])


if __name__ == "__main__":
    unittest.main()
