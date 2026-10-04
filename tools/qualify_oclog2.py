#!/usr/bin/env python3
"""Evaluate an exact OCLog2 artifact/device/profile qualification bundle."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
import math
import re
from dataclasses import dataclass
from pathlib import Path
from typing import Any


SPEC_VERSION = "2.0.0"
EXPECTED_SHADERS = {
    "HLG10_BT2020": "66ba3a4d5c432c935110f4639e64eb894b3a334a7397ce80fe139f9b787233aa",
    "SDR_BT709_ISP": "25db8970358056bc48d4aa3ed1540979af82764c58bae9a76d56a250cce9a0f8",
}
# Same transforms with the GPU driver's YCbCr conversion (no GL_EXT_YUV_target): never qualified.
DRIVER_SAMPLER_SHADERS = {
    "HLG10_BT2020": "41e87f366a5e89a0e78e5775147d080a0d82e54ec6b2c4afbb46a86e49107ffa",
    "SDR_BT709_ISP": "bdb5d9f3db71d3d6d37a065e9d1a4d7f660e4c8ce98ceb2fa701dc71e10f997b",
}
# Middle-grey reference between source tiers (OpenCineLogGreyReference): BT.2408 places 18% grey
# at 38% HLG signal, which the inverse HLG OETF maps to 0.38^2/3; the SDR tier's inverse BT.709
# returns 0.18. The scene-linear gain is a runtime shader parameter, so it is part of the tuple.
TIER_GREY_RATIO = 0.18 / (0.38 * 0.38 / 3.0)
EXPECTED_SCENE_GAINS = {
    ("NATIVE", "HLG10_BT2020"): 1.0,
    ("NATIVE", "SDR_BT709_ISP"): 1.0,
    ("MATCH_HLG", "HLG10_BT2020"): 1.0,
    ("MATCH_HLG", "SDR_BT709_ISP"): 1.0 / TIER_GREY_RATIO,
    ("MATCH_SDR", "HLG10_BT2020"): TIER_GREY_RATIO,
    ("MATCH_SDR", "SDR_BT709_ISP"): 1.0,
}
# The app records a 32-bit float gain; compare relatively, far tighter than any mode difference.
SCENE_GAIN_RELATIVE_TOLERANCE = 1e-6
REQUIRED_IMPLEMENTATIONS = {"cpu", "gpu", "lut1d", "lut3d", "ocio", "dctl"}
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MIN_DURATION_SECONDS = 30


@dataclass
class Check:
    name: str
    status: str
    reason: str

    def as_json(self) -> dict[str, str]:
        return {"name": self.name, "status": self.status, "reason": self.reason}


def file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def resolve_file(root: Path, value: object) -> Path | None:
    if not isinstance(value, str) or not value.strip():
        return None
    path = Path(value)
    return path if path.is_absolute() else root / path


def check_identity(name: str, value: object, checks: list[Check]) -> None:
    passed = isinstance(value, str) and SHA256.fullmatch(value) is not None
    checks.append(Check(name, "PASS" if passed else "NOT_RUN", "sha256-present" if passed else "sha256-missing-or-invalid"))


def check_hashed_file(root: Path, name: str, record: object, checks: list[Check]) -> Path | None:
    if not isinstance(record, dict):
        checks.append(Check(name, "NOT_RUN", "file-record-missing"))
        return None
    path = resolve_file(root, record.get("path"))
    expected = record.get("sha256")
    if path is None or not path.is_file() or not isinstance(expected, str) or not SHA256.fullmatch(expected):
        checks.append(Check(name, "NOT_RUN", "file-or-hash-missing"))
        return None
    actual = file_sha256(path)
    checks.append(Check(name, "PASS" if actual == expected else "FAIL", "hash-match" if actual == expected else "hash-mismatch"))
    return path


def load_json_file(path: Path | None, name: str, checks: list[Check]) -> dict[str, Any] | None:
    if path is None:
        return None
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        checks.append(Check(name, "FAIL", "invalid-json"))
        return None
    if not isinstance(value, dict):
        checks.append(Check(name, "FAIL", "json-root-not-object"))
        return None
    return value


DATASPACE_STANDARD_MASK = 63 << 16
DATASPACE_STANDARD_BT2020 = 6 << 16
DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE = 7 << 16
DATASPACE_TRANSFER_MASK = 31 << 22
DATASPACE_TRANSFER_ST2084 = 7 << 22
DATASPACE_TRANSFER_HLG = 8 << 22


def dataspace_matches(source_path: object, dataspace: int) -> bool:
    """Mirror of OpenCineLogSourcePath.acceptsDataSpace: can this tier's shader decode the frames?"""
    standard = dataspace & DATASPACE_STANDARD_MASK
    transfer = dataspace & DATASPACE_TRANSFER_MASK
    if source_path == "HLG10_BT2020":
        return standard == DATASPACE_STANDARD_BT2020 and transfer == DATASPACE_TRANSFER_HLG
    if source_path == "SDR_BT709_ISP":
        return (
            standard not in {DATASPACE_STANDARD_BT2020, DATASPACE_STANDARD_BT2020_CONSTANT_LUMINANCE}
            and transfer not in {DATASPACE_TRANSFER_HLG, DATASPACE_TRANSFER_ST2084}
        )
    return False


def ycbcr_conversion_matches(source_path: object, conversion: str) -> bool:
    """The HLG tier is decoded as ten-bit BT.2020; the SDR tier as eight-bit non-BT.2020."""
    parts = conversion.split("/")
    if len(parts) != 3 or parts[1] not in {"full", "limited"}:
        return False
    if source_path == "HLG10_BT2020":
        return parts[0] == "BT2020" and parts[2] == "10-bit"
    if source_path == "SDR_BT709_ISP":
        return parts[0] in {"BT601", "BT709"} and parts[2] == "8-bit"
    return False


def is_gain(value: object) -> bool:
    return isinstance(value, (int, float)) and not isinstance(value, bool) and math.isfinite(value) and value > 0


def same_gain(left: object, right: object) -> bool:
    return is_gain(left) and is_gain(right) and math.isclose(left, right, rel_tol=SCENE_GAIN_RELATIVE_TOLERANCE)


def evaluate(manifest: dict[str, Any], root: Path) -> dict[str, Any]:
    checks: list[Check] = []
    if manifest.get("schema") != "opencinecam-oclog2-qualification-input-v1":
        checks.append(Check("schema", "NOT_RUN", "unsupported-or-missing-schema"))
    else:
        checks.append(Check("schema", "PASS", "supported-schema"))

    artifact = manifest.get("artifact") if isinstance(manifest.get("artifact"), dict) else {}
    check_identity("artifact-sha256", artifact.get("sha256"), checks)
    check_identity("source-digest", artifact.get("sourceDigest"), checks)
    if not artifact.get("id"):
        checks.append(Check("artifact-id", "NOT_RUN", "artifact-id-missing"))
    else:
        checks.append(Check("artifact-id", "PASS", "artifact-id-present"))
    artifact_path = check_hashed_file(root, "artifact-file", artifact, checks)
    if artifact_path is not None:
        artifact_manifest = load_json_file(artifact_path, "artifact-manifest-json", checks)
        manifest_valid = bool(
            artifact_manifest
            and artifact_manifest.get("schema") == "opencinecam-oclog2-artifacts-v1"
            and artifact_manifest.get("specVersion") == SPEC_VERSION
        )
        checks.append(Check(
            "artifact-manifest-contract",
            "PASS" if manifest_valid else "FAIL",
            "artifact-manifest-match" if manifest_valid else "artifact-manifest-mismatch",
        ))

    target = manifest.get("target") if isinstance(manifest.get("target"), dict) else {}
    target_valid = (
        isinstance(target.get("fingerprint"), str) and bool(target["fingerprint"].strip())
        and isinstance(target.get("cameraId"), str) and bool(target["cameraId"].strip())
        and isinstance(target.get("apiLevel"), int) and target["apiLevel"] >= 33
        and target.get("physical") is True
    )
    checks.append(Check("physical-target", "PASS" if target_valid else "NOT_RUN", "exact-physical-target" if target_valid else "physical-target-missing"))

    profile = manifest.get("profile") if isinstance(manifest.get("profile"), dict) else {}
    source_path = profile.get("sourcePath")
    spec_version = profile.get("specVersion")
    shader = profile.get("shaderSha256")
    dimensions_valid = all(isinstance(profile.get(key), int) and profile[key] > 0 for key in ("width", "height", "fps"))
    checks.append(Check("profile-dimensions", "PASS" if dimensions_valid else "NOT_RUN", "exact-tuple" if dimensions_valid else "profile-tuple-missing"))
    checks.append(Check("spec-version", "PASS" if spec_version == SPEC_VERSION else "FAIL", "spec-match" if spec_version == SPEC_VERSION else "stale-or-unknown-spec"))
    expected_shader = EXPECTED_SHADERS.get(str(source_path))
    shader_valid = expected_shader is not None and shader == expected_shader
    if shader_valid:
        checks.append(Check("runtime-shader", "PASS", "shader-match"))
    elif shader is not None and shader == DRIVER_SAMPLER_SHADERS.get(str(source_path)):
        # The fallback lets the GPU driver pick the YCbCr matrix and range, which on tested
        # hardware ignored the buffer dataspace; such a file cannot vouch for its code values.
        checks.append(Check("runtime-shader", "FAIL", "driver-ycbcr-conversion"))
    else:
        checks.append(Check("runtime-shader", "FAIL", "source-or-shader-mismatch"))
    grey_reference = profile.get("greyReference")
    scene_gain = profile.get("sceneGain")
    if grey_reference is None and scene_gain is None:
        checks.append(Check("scene-gain", "NOT_RUN", "scene-gain-missing"))
    else:
        expected_gain = EXPECTED_SCENE_GAINS.get((str(grey_reference), str(source_path)))
        gain_valid = expected_gain is not None and same_gain(scene_gain, expected_gain)
        checks.append(Check("scene-gain", "PASS" if gain_valid else "FAIL", "gain-match" if gain_valid else "reference-or-gain-mismatch"))

    provenance = manifest.get("provenance") if isinstance(manifest.get("provenance"), dict) else {}
    if source_path == "HLG10_BT2020":
        provenance_valid = (
            provenance.get("dynamicRange") == "HLG10"
            and provenance.get("colorSpace") == "BT2020_HLG"
            and provenance.get("sourcePrecisionClaim") == "camera-hlg10-profile"
        )
    elif source_path == "SDR_BT709_ISP":
        provenance_valid = (
            provenance.get("dynamicRange") == "STANDARD"
            and provenance.get("colorSpace") == "BT709_ASSUMED"
            and provenance.get("sourcePrecisionClaim") == "not-claimed"
        )
    else:
        provenance_valid = False
    checks.append(Check("source-provenance", "PASS" if provenance_valid else "FAIL", "tier-disclosed" if provenance_valid else "tier-provenance-mismatch"))

    implementation_records = manifest.get("implementations") if isinstance(manifest.get("implementations"), dict) else {}
    tolerance = manifest.get("implementationTolerance", 2e-5)
    tolerance_valid = isinstance(tolerance, (int, float)) and 0 < tolerance <= 2e-5
    checks.append(Check("implementation-tolerance", "PASS" if tolerance_valid else "FAIL", "bounded-tolerance" if tolerance_valid else "tolerance-too-wide-or-invalid"))
    for implementation in sorted(REQUIRED_IMPLEMENTATIONS):
        record = implementation_records.get(implementation)
        present = isinstance(record, dict)
        error = record.get("maxAbsError") if present else None
        passed = present and record.get("status") == "PASS" and isinstance(error, (int, float)) and tolerance_valid and error <= tolerance
        checks.append(Check(f"implementation-{implementation}", "PASS" if passed else "NOT_RUN" if not present else "FAIL", "agreement-pass" if passed else "agreement-missing-or-failed"))

    sustained = manifest.get("sustained") if isinstance(manifest.get("sustained"), dict) else {}
    duration = sustained.get("durationSeconds")
    frames = sustained.get("encodedFrames")
    fps = profile.get("fps")
    sustained_valid = (
        isinstance(duration, (int, float)) and duration >= MIN_DURATION_SECONDS
        and isinstance(frames, int) and isinstance(fps, int) and frames >= duration * fps * 0.98
        and sustained.get("droppedFrames") == 0
        and sustained.get("status") == "PASS"
    )
    checks.append(Check("sustained-recording", "PASS" if sustained_valid else "NOT_RUN" if not sustained else "FAIL", "sustained-pass" if sustained_valid else "sustained-missing-or-failed"))

    files = manifest.get("files") if isinstance(manifest.get("files"), dict) else {}
    clip_path = check_hashed_file(root, "clip-file", files.get("clip"), checks)
    sidecar_path = check_hashed_file(root, "sidecar-file", files.get("sidecar"), checks)
    ffprobe_path = check_hashed_file(root, "ffprobe-file", files.get("ffprobe"), checks)
    sidecar = load_json_file(sidecar_path, "sidecar-json", checks)
    ffprobe = load_json_file(ffprobe_path, "ffprobe-json", checks)

    sidecar_valid = False
    if sidecar is not None:
        source = sidecar.get("source") if isinstance(sidecar.get("source"), dict) else {}
        transform = sidecar.get("transform") if isinstance(sidecar.get("transform"), dict) else {}
        sidecar_valid = (
            sidecar.get("cameraId") == target.get("cameraId")
            and source.get("path") == source_path
            and transform.get("curve") == "OCLog2"
            and transform.get("version") in {"2.0", SPEC_VERSION}
            and transform.get("shaderSha256") == shader
            # A sidecar that does not declare the applied gain cannot vouch for the code values.
            and transform.get("greyReference") == grey_reference
            and same_gain(transform.get("sceneGain"), scene_gain)
        )
    checks.append(Check("sidecar-contract", "PASS" if sidecar_valid else "NOT_RUN" if sidecar is None else "FAIL", "sidecar-match" if sidecar_valid else "sidecar-missing-or-mismatch"))

    transform = sidecar.get("transform") if sidecar is not None and isinstance(sidecar.get("transform"), dict) else {}
    conversion = transform.get("ycbcrConversion")
    if not isinstance(conversion, str) or not conversion:
        checks.append(Check("ycbcr-conversion", "NOT_RUN", "ycbcr-conversion-unreported"))
    elif conversion.endswith("(default)"):
        checks.append(Check("ycbcr-conversion", "NOT_RUN", "ycbcr-conversion-assumed"))
    elif ycbcr_conversion_matches(source_path, conversion):
        checks.append(Check("ycbcr-conversion", "PASS", "ycbcr-conversion-from-dataspace"))
    else:
        checks.append(Check("ycbcr-conversion", "FAIL", "ycbcr-conversion-mismatch"))

    # The shader assumes the tier's transfer; a frame in another dataspace is decoded wrongly even
    # though every hash and timing check passes, so the runtime dataspace is evidence in its own right.
    source = sidecar.get("source") if sidecar is not None and isinstance(sidecar.get("source"), dict) else {}
    dataspace = source.get("androidDataSpace")
    mismatched = source.get("dataSpaceMismatchedFrames")
    if not isinstance(dataspace, int) or isinstance(dataspace, bool) or not isinstance(mismatched, int) or isinstance(mismatched, bool):
        checks.append(Check("source-dataspace", "NOT_RUN", "dataspace-unreported"))
    elif mismatched == 0 and dataspace_matches(source_path, dataspace):
        checks.append(Check("source-dataspace", "PASS", "dataspace-matches-tier"))
    else:
        checks.append(Check("source-dataspace", "FAIL", "dataspace-mismatch"))

    ffprobe_valid = False
    if ffprobe is not None:
        streams = ffprobe.get("streams") if isinstance(ffprobe.get("streams"), list) else []
        video = next((stream for stream in streams if isinstance(stream, dict) and stream.get("codec_type") == "video"), None)
        ffprobe_valid = bool(
            video and video.get("codec_name") in {"hevc", "h265"}
            and "10" in str(video.get("profile", ""))
            and video.get("width") == profile.get("width")
            and video.get("height") == profile.get("height")
        )
    checks.append(Check("ffprobe-contract", "PASS" if ffprobe_valid else "NOT_RUN" if ffprobe is None else "FAIL", "file-metadata-match" if ffprobe_valid else "file-metadata-missing-or-mismatch"))
    # OCLog2 claims no standard transfer. The app clears the encoder's VUI tag to H.273 unspecified,
    # which ffprobe omits or prints as "unknown"; an HDR or linear tag would mislead every player.
    if ffprobe is None:
        checks.append(Check("container-transfer", "NOT_RUN", "ffprobe-missing"))
    else:
        streams = ffprobe.get("streams") if isinstance(ffprobe.get("streams"), list) else []
        video = next((stream for stream in streams if isinstance(stream, dict) and stream.get("codec_type") == "video"), None)
        transfer = video.get("color_transfer", "unknown") if video else None
        if transfer == "unknown":
            checks.append(Check("container-transfer", "PASS", "container-transfer-unspecified"))
        else:
            checks.append(Check("container-transfer", "FAIL", f"container-transfer-tagged:{transfer}"))

    workflows = manifest.get("workflows") if isinstance(manifest.get("workflows"), list) else []
    ffmpeg_pass = any(isinstance(item, dict) and str(item.get("name", "")).lower() == "ffmpeg" and item.get("status") == "PASS" for item in workflows)
    editor_pass = any(
        isinstance(item, dict)
        and str(item.get("name", "")).lower() not in {"", "ffmpeg", "opencinecam"}
        and item.get("independent") is True
        and item.get("status") == "PASS"
        and isinstance(item.get("maxAbsError"), (int, float))
        and item["maxAbsError"] <= 2e-5
        for item in workflows
    )
    checks.append(Check("ffmpeg-workflow", "PASS" if ffmpeg_pass else "NOT_RUN", "ffmpeg-pass" if ffmpeg_pass else "ffmpeg-not-run"))
    checks.append(Check("independent-editor", "PASS" if editor_pass else "NOT_RUN", "editor-pass" if editor_pass else "editor-not-run"))

    if clip_path is None:
        # The clip is deliberately consumed only for identity here; ffprobe and sidecar own semantics.
        pass
    statuses = {check.status for check in checks}
    status = "FAILED" if "FAIL" in statuses else "NOT_RUN" if "NOT_RUN" in statuses else "QUALIFIED"
    tuple_id = {
        "artifactId": artifact.get("id"),
        "artifactSha256": artifact.get("sha256"),
        "fingerprint": target.get("fingerprint"),
        "cameraId": target.get("cameraId"),
        "width": profile.get("width"),
        "height": profile.get("height"),
        "fps": profile.get("fps"),
        "sourcePath": source_path,
        "specVersion": spec_version,
        "shaderSha256": shader,
        "greyReference": grey_reference,
        "sceneGain": scene_gain,
    }
    return {
        "schema": "opencinecam-oclog2-qualification-result-v1",
        "status": status,
        "tuple": tuple_id,
        "checks": [check.as_json() for check in checks],
        "passed": sum(check.status == "PASS" for check in checks),
        "failed": [check.name for check in checks if check.status == "FAIL"],
        "notRun": [check.name for check in checks if check.status == "NOT_RUN"],
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--input", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if not args.input.is_file():
        parser.error("input manifest must exist")
    try:
        manifest = json.loads(args.input.read_text(encoding="utf-8"))
    except json.JSONDecodeError as failure:
        parser.error(f"input manifest is invalid JSON: {failure}")
    if not isinstance(manifest, dict):
        parser.error("input manifest root must be an object")
    result = evaluate(manifest, args.input.parent)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "QUALIFIED" else 2


if __name__ == "__main__":
    raise SystemExit(main())
