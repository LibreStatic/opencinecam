#!/usr/bin/env python3
# SPDX-License-Identifier: Apache-2.0
"""Compare known PCM to independently decoded AAC; packet count alone never proves fidelity."""
import argparse
import array
import hashlib
import json
import math
from pathlib import Path
import sys


def read_pcm(path, channels):
    data = Path(path).read_bytes()
    if channels not in (1, 2) or len(data) % (2 * channels):
        raise ValueError("PCM must contain whole mono/stereo s16le frames")
    samples = array.array("h")
    samples.frombytes(data)
    if sys.byteorder != "little":
        samples.byteswap()
    return [samples[channel::channels] for channel in range(channels)]


def correlation(source, decoded, start, count, lag, step=1):
    end = min(start + count, len(source), len(decoded) - lag)
    start = max(start, -lag)
    if end - start < 64:
        return -1.0
    aa = bb = ab = 0
    for index in range(start, end, step):
        x, y = source[index], decoded[index + lag]
        aa += x * x
        bb += y * y
        ab += x * y
    return ab / math.sqrt(aa * bb) if aa and bb else 0.0


def analyze_channel(source, decoded, max_lag=8192, threshold=0.98):
    if len(source) < 12288 or len(decoded) < 12288:
        raise ValueError("This swept-signal probe needs at least 12288 frames per channel")
    # Sparse correlation locates the lag; full-rate checks then validate the entire available signal.
    lag = max(range(-max_lag, max_lag + 1),
              key=lambda value: correlation(source, decoded, 8192, 2048, value, 8))
    first = max(0, -lag)
    end = max(first, min(len(source), len(decoded) - lag))
    scores = {
        "start": correlation(source, decoded, 0, 2048, lag),
        "middle": correlation(source, decoded, len(source) // 2, 2048, lag),
        "lastAvailable": correlation(source, decoded, max(first, end - 2048), 2048, lag),
        "allAvailable": correlation(source, decoded, first, end - first, lag),
    }
    matched = min(scores.values()) >= threshold
    complete = matched and first == 0 and end == len(source)
    return {
        "measuredLagFrames": lag,
        "missingInputStartFrames": first,
        "missingInputTailFrames": len(source) - end,
        "trailingDecodedFrames": max(0, len(decoded) - lag - len(source)),
        "correlations": scores,
        "threshold": threshold,
        "signalMatched": matched,
        "sourceCoverageVerified": complete,
        "gaplessTrimVerified": False,
    }


def analyze_record(directory, record):
    stem = record["stem"]
    if Path(stem).name != stem or stem in ("", ".", ".."):
        raise ValueError("Probe stem must be a basename")
    source = read_pcm(directory / (stem + ".input.pcm"), record["channels"])
    decoded = read_pcm(directory / (stem + ".decoded.pcm"), record["channels"])
    if len(source[0]) != record["inputFrames"]:
        raise ValueError("Source frame count does not match the recorded encoder input")
    channels = [analyze_channel(a, b) for a, b in zip(source, decoded)]
    lags = {item["measuredLagFrames"] for item in channels}
    complete = all(item["sourceCoverageVerified"] for item in channels) and len(lags) == 1
    return {
        "stem": stem, "encoder": record["encoder"], "eosOnData": record["eosOnData"],
        "sampleRate": record["rate"], "channels": record["channels"],
        "inputFrames": len(source[0]), "decodedFrames": len(decoded[0]),
        "experimentalPaddingFrames": record.get("experimentalPaddingFrames", 0),
        "encodedPackets": len(record["packets"]), "channelResults": channels,
        "sourceCoverageVerified": complete,
        "status": "SOURCE_PRESENT_NOT_GAPLESS" if complete else "SOURCE_INCOMPLETE_OR_UNMATCHED",
        "mp4Sha256": hashlib.sha256((directory / (stem + ".m4a")).read_bytes()).hexdigest(),
        "inputSha256": hashlib.sha256((directory / (stem + ".input.pcm")).read_bytes()).hexdigest(),
        "decodedSha256": hashlib.sha256((directory / (stem + ".decoded.pcm")).read_bytes()).hexdigest(),
    }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    parser.add_argument("--require-source-coverage", action="store_true")
    args = parser.parse_args()
    records = json.loads((args.directory / "aac-tail-probe.json").read_text())
    if not records:
        raise ValueError("Probe manifest is empty")
    results = [analyze_record(args.directory, record) for record in records]
    (args.directory / "probe-analysis.json").write_text(json.dumps(results, indent=2) + "\n")
    for result in results:
        print(json.dumps(result))
    # Diagnostics can collect a known failure, but the explicit qualification gate must fail it.
    return 2 if args.require_source_coverage and not all(r["sourceCoverageVerified"] for r in results) else 0


if __name__ == "__main__":
    sys.exit(main())
