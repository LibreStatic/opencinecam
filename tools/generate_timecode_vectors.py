#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Generate independent vectors using FFmpeg public libavutil timecode APIs, not a Python timecode formula."""
import argparse
import ctypes as c
import ctypes.util
from pathlib import Path
import random

class Rational(c.Structure):
    _fields_ = [("num", c.c_int), ("den", c.c_int)]

class Timecode(c.Structure):
    _fields_ = [("start", c.c_int), ("flags", c.c_uint32), ("rate", Rational), ("fps", c.c_uint)]

def generate():
    library = ctypes.util.find_library("avutil")
    if library is None:
        raise RuntimeError("FFmpeg libavutil is required for the independent oracle")
    lib = c.CDLL(library)
    lib.avutil_version.restype = c.c_uint
    version = lib.avutil_version()
    if not 58 <= version >> 16 <= 60:
        raise RuntimeError("Qualify the public AVTimecode ABI for this libavutil version first")
    lib.av_timecode_init_from_string.argtypes = [c.POINTER(Timecode), Rational, c.c_char_p, c.c_void_p]
    lib.av_timecode_init_from_string.restype = c.c_int
    lib.av_timecode_make_string.argtypes = [c.POINTER(Timecode), c.c_char_p, c.c_int]
    lib.av_timecode_make_string.restype = c.c_char_p
    lines = ["# Generated via libavutil av_timecode_make_string and av_timecode_init_from_string", "# nominal_fps\tdrop_frame\tinput_frame\tlabel\tparsed_frame"]
    rng = random.Random(660016)
    for fps, drop in [(24, False), (25, False), (30, False), (50, False), (60, False), (30, True), (60, True)]:
        rate = Rational(fps * 1000 if drop else fps, 1001 if drop else 1)
        tc = Timecode()
        assert lib.av_timecode_init_from_string(c.byref(tc), rate, b"00:00:00;00" if drop else b"00:00:00:00", None) == 0
        tc.flags |= 2  # Public AV_TIMECODE_FLAG_24HOURSMAX.
        # Probe broad nominal boundaries; FFmpeg alone supplies labels and inverse frame ordinals.
        inputs = {0, 1, 2_000_000_000}
        for minute in [1, 2, 9, 10, 11, 59, 60, 599, 600, 1439, 1440]:
            inputs.update(range(fps * 60 * minute - fps * 4, fps * 60 * minute + fps * 4, max(1, fps // 15)))
        inputs.update(rng.randrange(0, fps * 86400 * 3) for _ in range(64))
        for frame in sorted(inputs):
            label = c.create_string_buffer(32)
            lib.av_timecode_make_string(c.byref(tc), label, frame)
            parsed = Timecode()
            assert lib.av_timecode_init_from_string(c.byref(parsed), rate, label.value, None) == 0
            lines.append(f"{fps}\t{int(drop)}\t{frame}\t{label.value.decode('ascii')}\t{parsed.start}")
    return "\n".join(lines) + "\n", library, version

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--output", type=Path)
    group.add_argument("--check", type=Path)
    args = parser.parse_args()
    content, library, version = generate()
    target = args.output or args.check
    if args.output:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content)
    else:
        assert target.read_text() == content, "Stored vectors differ from the independent oracle"
    print(f"TIMECODE_ORACLE: {len(content.splitlines()) - 2} vectors; {library}; version {version}; {'written' if args.output else 'byte-identical'}")

if __name__ == "__main__":
    main()
