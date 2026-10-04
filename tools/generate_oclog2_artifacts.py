#!/usr/bin/env python3
"""Generate the normative, deterministic OCLog2 interchange artifact set."""

# SPDX-License-Identifier: Apache-2.0
# Copyright 2026 OpenCineCam contributors

from __future__ import annotations

import argparse
import hashlib
import json
import tempfile
from decimal import Decimal, localcontext
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUTPUT = ROOT / "docs" / "color" / "oclog2-v2"
VERSION = "2.0.0"
BLACK = Decimal("0.10")
WHITE = Decimal("0.90")
BASE = Decimal(50)
LUT_1D_SIZE = 4096
LUT_3D_SIZE = 17
RUNTIME_SHADERS = {
    "HLG10_BT2020": "66ba3a4d5c432c935110f4639e64eb894b3a334a7397ce80fe139f9b787233aa",
    "SDR_BT709_ISP": "25db8970358056bc48d4aa3ed1540979af82764c58bae9a76d56a250cce9a0f8",
}
# Same transforms with the GPU driver's YCbCr conversion (no GL_EXT_YUV_target): never qualified.
DRIVER_SAMPLER_SHADERS = {
    "HLG10_BT2020": "41e87f366a5e89a0e78e5775147d080a0d82e54ec6b2c4afbb46a86e49107ffa",
    "SDR_BT709_ISP": "bdb5d9f3db71d3d6d37a065e9d1a4d7f660e4c8ce98ceb2fa701dc71e10f997b",
}


def encode_decimal(value: Decimal) -> Decimal:
    if value < 0 or value > 1:
        raise ValueError("OCLog2 input must be in [0, 1]")
    with localcontext() as context:
        context.prec = 50
        normalized = (Decimal(1) + BASE * value).ln() / (Decimal(1) + BASE).ln()
        return +(BLACK + (WHITE - BLACK) * normalized)


def decode_decimal(value: Decimal) -> Decimal:
    if value < BLACK or value > WHITE:
        raise ValueError("OCLog2 code must be in [0.10, 0.90]")
    with localcontext() as context:
        context.prec = 50
        normalized = (value - BLACK) / (WHITE - BLACK)
        return +(((Decimal(1) + BASE).ln() * normalized).exp() - Decimal(1)) / BASE


def decimal_text(value: Decimal, digits: int = 15) -> str:
    return format(value, f".{digits}f")


def json_bytes(value: object) -> bytes:
    return (json.dumps(value, indent=2, sort_keys=True) + "\n").encode()


def spec() -> dict[str, object]:
    middle_gray = Decimal("0.18")
    return {
        "schema": "opencinecam-oclog2-spec-v1",
        "id": "com.librestatic.opencinecam.color.oclog2",
        "version": VERSION,
        "status": "normative-host-contract",
        "domain": {
            "encoding": "scene-linear",
            "gamut": "ITU-R BT.2020",
            "minimum": 0,
            "maximum": 1,
            "componentApplication": "independent RGB components",
            "invalidInput": "reject non-finite or out-of-domain values in the normative reference",
        },
        "range": {"container": "full", "blackCode": 0.10, "whiteCode": 0.90},
        "curve": {
            "forward": "0.10 + 0.80 * ln(1 + 50*x) / ln(51)",
            "inverse": "(exp(ln(51) * ((y - 0.10) / 0.80)) - 1) / 50",
            "middleGrayLinear": 0.18,
            "middleGrayCode": float(encode_decimal(middle_gray)),
        },
        "precision": {
            "reference": "decimal-50",
            "rounding": "no normative quantization; generated text uses 15 fractional decimal places",
            "transportBoundary": "GLSL, DCTL, and LUT transports clamp to their declared domains",
            "cpuAbsoluteTolerance": 1e-12,
            "gpuAbsoluteTolerance": 2e-5,
            "lut1dAbsoluteTolerance": 2e-5,
            "lut3dAbsoluteTolerance": 2e-5,
        },
        "sourceTiers": {
            "HLG10_BT2020": {
                "input": "Camera2 HLG10 + BT2020_HLG PRIVATE surface",
                "preTransform": "inverse HLG OETF and BT.709-to-BT.2020 matrix (stream measured with BT.709 primaries despite its BT.2020 tag)",
                "claimBoundary": "exact tuple requires physical qualification",
            },
            "SDR_BT709_ISP": {
                "input": "Camera2 STANDARD PRIVATE surface",
                "preTransform": "assumed inverse BT.709 OETF and BT.709-to-BT.2020 matrix",
                "bt709ToBt2020GlslColumnMajor": [
                    [0.627404, 0.069097, 0.016391],
                    [0.329283, 0.919540, 0.088013],
                    [0.043313, 0.011362, 0.895595],
                ],
                "claimBoundary": "ISP-derived; no source HDR, gamut, or bit-depth claim",
            },
        },
        "middleGreyReference": {
            "application": "runtime scene-linear gain (uniform uSceneGain) applied before OCLog2; recorded in the sidecar transform",
            "hlgGreyLinear": "0.38^2 / 3 (ITU-R BT.2408 18% grey at 38% HLG signal, inverse BT.2100 HLG OETF)",
            "sdrGreyLinear": "0.18 (inverse BT.709 OETF of the 18% grey code)",
            "tierGreyRatio": float(Decimal("0.18") / (Decimal("0.38") * Decimal("0.38") / Decimal(3))),
            "modes": {
                "NATIVE": {"HLG10_BT2020": "1", "SDR_BT709_ISP": "1"},
                "MATCH_HLG": {"HLG10_BT2020": "1", "SDR_BT709_ISP": "1 / tierGreyRatio"},
                "MATCH_SDR": {"HLG10_BT2020": "tierGreyRatio", "SDR_BT709_ISP": "1"},
            },
            "default": "NATIVE",
            "highlightBoundary": "MATCH_SDR clips HLG scene light above 1 / tierGreyRatio (about 0.752 HLG signal)",
        },
        "runtimeShaderSha256": RUNTIME_SHADERS,
        "driverSamplerShaderSha256": DRIVER_SAMPLER_SHADERS,
    }


def vectors() -> dict[str, object]:
    inputs = [Decimal("0"), Decimal("0.0009765625"), Decimal("0.01"), Decimal("0.18"), Decimal("0.5"), Decimal("0.9"), Decimal("1")]
    return {
        "schema": "opencinecam-oclog2-vectors-v1",
        "specVersion": VERSION,
        "reference": {
            "method": "Python Decimal analytic equation",
            "precision": 50,
            "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        },
        "vectors": [
            {
                "sceneLinear": float(value),
                "oclog2": float(encode_decimal(value)),
                "inverseSceneLinear": float(decode_decimal(encode_decimal(value))),
            }
            for value in inputs
        ],
    }


def cube_1d() -> bytes:
    # Adobe Cube LUT Specification 1.0, section 6: one LUT_1D_SIZE table with optional TITLE and
    # DOMAIN_MIN/DOMAIN_MAX. Readers clamp to the declared [0, 1] domain.
    lines = [
        'TITLE "OpenCineCam OCLog2 v2.0.0 scene-linear BT.2020 to OCLog2"',
        f"LUT_1D_SIZE {LUT_1D_SIZE}",
        "DOMAIN_MIN 0.0 0.0 0.0",
        "DOMAIN_MAX 1.0 1.0 1.0",
    ]
    for index in range(LUT_1D_SIZE):
        value = encode_decimal(Decimal(index) / Decimal(LUT_1D_SIZE - 1))
        encoded = decimal_text(value)
        lines.append(f"{encoded} {encoded} {encoded}")
    return ("\n".join(lines) + "\n").encode()


def cube_3d() -> bytes:
    # The Adobe Cube 1.0 format allows either one 1D or one 3D table per file, so a combined
    # shaper plus lattice must use the DaVinci Resolve .cube variant: LUT_1D_SIZE,
    # LUT_1D_INPUT_RANGE, LUT_3D_SIZE, LUT_3D_INPUT_RANGE, then the 1D rows, then the 3D rows.
    # That variant has no TITLE or DOMAIN_* keywords (OpenColorIO's resolve_cube reader rejects
    # TITLE), and comments may only precede the header.
    lines = [
        f"# OpenCineCam OCLog2 v{VERSION} {LUT_1D_SIZE}-entry shaper plus {LUT_3D_SIZE}-point identity cube",
        "# DaVinci Resolve combined 1D shaper + 3D .cube; not an Adobe Cube 1.0 file.",
        f"LUT_1D_SIZE {LUT_1D_SIZE}",
        "LUT_1D_INPUT_RANGE 0.0 1.0",
        f"LUT_3D_SIZE {LUT_3D_SIZE}",
        "LUT_3D_INPUT_RANGE 0.0 1.0",
    ]
    # A bare 17^3 sampling of this steep curve misses the 2e-5 contract near black. The shaper
    # applies the dense component curve first; its [0.10, 0.90] output stays inside the 3D input
    # range, and the following 17^3 identity lattice makes the artifact a real combined 1D+3D
    # transform without adding interpolation error under trilinear or tetrahedral lookup.
    for index in range(LUT_1D_SIZE):
        value = encode_decimal(Decimal(index) / Decimal(LUT_1D_SIZE - 1))
        encoded = decimal_text(value)
        lines.append(f"{encoded} {encoded} {encoded}")
    values = [Decimal(index) / Decimal(LUT_3D_SIZE - 1) for index in range(LUT_3D_SIZE)]
    # .cube ordering: red changes fastest, then green, then blue.
    for blue in values:
        for green in values:
            for red in values:
                lines.append(f"{decimal_text(red)} {decimal_text(green)} {decimal_text(blue)}")
    return ("\n".join(lines) + "\n").encode()


def glsl() -> bytes:
    return b"""// SPDX-License-Identifier: Apache-2.0
// Normative OCLog2 component reference; source tier decoding occurs before this transform.
const float OCLOG2_BASE = 50.0;
const float OCLOG2_BLACK = 0.10;
const float OCLOG2_WHITE = 0.90;

float oclog2_encode(float sceneLinear) {
    float x = clamp(sceneLinear, 0.0, 1.0);
    return OCLOG2_BLACK + (OCLOG2_WHITE - OCLOG2_BLACK) * log(1.0 + OCLOG2_BASE * x) / log(1.0 + OCLOG2_BASE);
}

float oclog2_decode(float code) {
    float y = clamp(code, OCLOG2_BLACK, OCLOG2_WHITE);
    float normalized = (y - OCLOG2_BLACK) / (OCLOG2_WHITE - OCLOG2_BLACK);
    return (pow(1.0 + OCLOG2_BASE, normalized) - 1.0) / OCLOG2_BASE;
}
"""


def dctl(inverse: bool) -> bytes:
    function = "oclog2_decode" if inverse else "oclog2_encode"
    source = f"""// SPDX-License-Identifier: Apache-2.0
// OpenCineCam OCLog2 v{VERSION} {'inverse' if inverse else 'forward'} transform.
__DEVICE__ float oclog2_encode(float x) {{
    x = _clampf(x, 0.0f, 1.0f);
    return 0.10f + 0.80f * _logf(1.0f + 50.0f * x) / _logf(51.0f);
}}
__DEVICE__ float oclog2_decode(float y) {{
    y = _clampf(y, 0.10f, 0.90f);
    return (_powf(51.0f, (y - 0.10f) / 0.80f) - 1.0f) / 50.0f;
}}
__DEVICE__ float3 transform(int width, int height, int x, int y, float r, float g, float b) {{
    return make_float3({function}(r), {function}(g), {function}(b));
}}
"""
    return source.encode()


def ocio() -> bytes:
    # Profile version 2 keeps the config loadable by every OCIO 2.x host; it uses no later
    # feature. OCIO v2 refuses to load a config without a Default file rule (or a default role)
    # and fails validation without at least one display.
    return b"""ocio_profile_version: 2
name: OpenCineCam OCLog2 v2.0.0
description: Normative scene-linear BT.2020 to OCLog2 interchange config
search_path: .
strictparsing: true
roles:
  scene_linear: scene_linear_bt2020
  reference: scene_linear_bt2020
file_rules:
  - !<Rule> {name: Default, colorspace: scene_linear_bt2020}
displays:
  OCLog2:
    - !<View> {name: OCLog2 code values, colorspace: oclog2_bt2020}
colorspaces:
  - !<ColorSpace>
    name: scene_linear_bt2020
    family: OpenCineCam
    bitdepth: 32f
    isdata: false
    allocation: lg2
  - !<ColorSpace>
    name: oclog2_bt2020
    family: OpenCineCam
    bitdepth: 32f
    isdata: false
    allocation: uniform
    to_scene_reference: !<FileTransform> {src: oclog2-v2-4096.cube, interpolation: linear, direction: inverse}
    from_scene_reference: !<FileTransform> {src: oclog2-v2-4096.cube, interpolation: linear, direction: forward}
"""


def artifact_payloads() -> dict[str, bytes]:
    return {
        "spec.json": json_bytes(spec()),
        "vectors.json": json_bytes(vectors()),
        "oclog2-v2-4096.cube": cube_1d(),
        "oclog2-v2-17.cube": cube_3d(),
        "oclog2-v2.ocio": ocio(),
        "oclog2-v2.glsl": glsl(),
        "oclog2-v2.dctl": dctl(False),
        "oclog2-v2-inverse.dctl": dctl(True),
    }


def generate(output: Path) -> dict[str, object]:
    output.mkdir(parents=True, exist_ok=True)
    payloads = artifact_payloads()
    for name, payload in payloads.items():
        (output / name).write_bytes(payload)
    manifest = {
        "schema": "opencinecam-oclog2-artifacts-v1",
        "specVersion": VERSION,
        "generator": "tools/generate_oclog2_artifacts.py",
        "generatorSha256": hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        "artifacts": {
            name: {"sha256": hashlib.sha256(payload).hexdigest(), "bytes": len(payload)}
            for name, payload in sorted(payloads.items())
        },
    }
    (output / "manifest.json").write_bytes(json_bytes(manifest))
    return manifest


def check(output: Path) -> bool:
    with tempfile.TemporaryDirectory() as temporary:
        generated = Path(temporary)
        generate(generated)
        expected = sorted(path.name for path in generated.iterdir())
        actual = sorted(path.name for path in output.iterdir()) if output.is_dir() else []
        return expected == actual and all((generated / name).read_bytes() == (output / name).read_bytes() for name in expected)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    if args.check:
        passed = check(args.output)
        print(json.dumps({"output": str(args.output), "status": "PASS" if passed else "MISMATCH"}, sort_keys=True))
        return 0 if passed else 1
    print(json.dumps(generate(args.output), sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
