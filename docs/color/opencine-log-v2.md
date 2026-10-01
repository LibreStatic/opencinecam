# OpenCine Log v2 capture contract

OCLog2 supersedes the unpromoted OCLog1 planning candidate for live capture. It is a
scene-linear BT.2020 transform whose normalized component curve is:

```text
encode(x) = 0.10 + 0.80 * log(1 + 50x) / log(51)
decode(y) = (51^((y - 0.10) / 0.80) - 1) / 50
```

- Input domain: scene-linear BT.2020, normalized `[0, 1]`.
- Recorded code range: `[0.10, 0.90]`, full-range Main10 surface path.
- `0.10` is normalized scene black and `0.90` is normalized scene white.
- Linear middle gray is `0.18`, encoded as `0.5685019750275356`.
- The normative reference rejects non-finite and out-of-domain inputs. A transport or GPU
  implementation may clamp only when it explicitly reports that boundary behavior.
- Reference evaluation uses 50-digit decimal arithmetic. CPU agreement is `1e-12` absolute;
  GPU and interchange agreement is at most `2e-5` absolute over `[0, 1]`.

The three color components use the same scalar curve. The curve itself performs no gamut
conversion. Source-tier conversion into scene-linear BT.2020 happens first; inverse transforms
return OCLog2 code values to scene-linear BT.2020. OCLog2 version `2.0.0` is identified by
`com.librestatic.opencinecam.color.oclog2` and the machine-readable contract in
[`oclog2-v2/spec.json`](oclog2-v2/spec.json).

## Capture source tiers

The curve and encoded OCLog2 domain are shared, but the camera-source contract is not.
The UI, recording sidecar, and validation evidence must preserve the selected tier:

| Tier | Camera source | Transform into scene-linear BT.2020 | Intended matrix | Claim boundary |
|---|---|---|---|---|
| **HLG-derived 10-bit** | Camera2 `HLG10` + `BT2020_HLG` | Inverse HLG OETF followed by the OCLog2 curve | Regular sessions, currently capability-gated through 4K60 | May claim the requested HLG10/BT.2020 source only after file and device validation |
| **HFR ISP-derived** | Camera2 `STANDARD`; BT.709 transfer/gamut are an explicit working assumption | Inverse Rec.709 OETF, BT.709-to-BT.2020 conversion, then the OCLog2 curve | Constrained-high-speed 120/240 profiles accepted by the camera and Main10 encoder | Must not claim HDR, HLG, RAW, or 10-bit source precision; Main10 describes the output encoder profile only |

The HFR tier is a recoverable, scene-linearized grading representation of a standard-range
ISP signal. It cannot recreate highlight latitude or source precision discarded before the
application receives the surface. It is therefore always labeled **HFR ISP-derived** and is
never presented as equivalent to the HLG-derived tier.

The flat viewfinder is a **monitoring transform**, not a different recording transform. It
decodes OCLog2, converts BT.2020 to the Rec.709 display gamut, applies bounded gamut/saturation
compression, and re-encodes OCLog2. View Assist instead applies the Rec.709 OETF. Neither
monitoring branch is baked into the encoded file.

Every recording sidecar identifies OCLog2, this version, the source tier, requested dynamic
range, assumed/reported color space and transfer, source-precision claim boundary, runtime
dataspace, middle-grey reference and scene gain, and exact shader SHA-256. A changed shader is
a new transform build even when the curve equation remains unchanged.

The container signals full range, BT.2020 primaries and matrix, and an **unspecified**
transfer (H.273 value 2). OCLog2 is not a standard transfer, so the sidecar is the authority for
it, and editors must be told to interpret the clip as OCLog2.

Android has no API that requests an unspecified transfer, and encoders write a concrete one
anyway. Qualcomm `c2.qti.hevc.encoder` wrote ST 2084 (PQ) into the SPS of every BT.2020 OCLog2
recording, whatever buffer dataspace or EGL colorspace it was given. A PQ tag makes decoders treat
the log image as HDR10. The app therefore rewrites `transfer_characteristics` to 2 in every
sequence parameter set before muxing (`HevcVuiTransfer`), both in the codec configuration and in
any keyframe that repeats it, and leaves the transfer out of the track format so the MP4 `colr`
box says unspecified too. Only those 8 bits change, so the coded pictures are untouched.

The sidecar records the transfer the encoder wrote (`encoding.encoderVuiTransfer`) and the one the
file carries (`encoding.containerVuiTransfer`). If an encoder's SPS cannot be parsed, its tag is
kept and reported, and `tools/qualify_oclog2.py` fails any clip whose `ffprobe` transfer is not
unspecified (`container-transfer`). Baked-LUT recordings keep their BT.709 SDR tag.

## Middle-grey reference

The two tiers place an 18% grey card at different scene-linear levels. BT.2408 puts HLG
reference grey at 38% signal, and the inverse HLG OETF low branch (`E²/3`) maps that to
`0.38² / 3 = 0.0481333`. The SDR tier inverts the Rec.709 OETF, so a grey card exposed
to Rec.709 18% returns to exactly `0.18`. The tier grey ratio is
`R = 0.18 / (0.38² / 3) = 0.54 / 0.1444 = 3.7396` (about 1.90 stops). The often-quoted
3.76 comes from rounding the Rec.709 grey code to 0.41.

The capture setting **Middle-grey reference** applies one scalar `uSceneGain` to
scene-linear BT.2020, before the OCLog2 curve, in both fragment shaders:

| Mode | HLG-derived gain | HFR ISP-derived gain | Trade-off |
|---|---|---|---|
| `NATIVE` (default) | 1 | 1 | Each tier keeps its own grey; grey sits about 0.22 code apart between tiers |
| `MATCH_HLG` | 1 | 1/R | Lossless: the SDR tier only moves down, and its peak stays inside `[0, 1]` |
| `MATCH_SDR` | R | 1 | HLG signal above about 0.752 (the HLG OETF of `1/R`) clips at scene white 1.0 |

The gain is a runtime uniform, so it does not change the pinned shader SHA-256. The gain is
frozen for each take: changes stay pending while a take records, and monitoring, scopes and
the encoder see the same scaled light. The sidecar `transform` section records
`greyReference` and the exact float `sceneGain`. The qualifier includes both in the
qualification tuple, and fails a sidecar that omits the gain or disagrees with the profile
evidence.

Each shader assumes its tier's source transfer, so the pipeline checks the dataspace of every
recorded frame. The HLG tier accepts only BT.2020 primaries with the HLG transfer, in either
range. The SDR tier rejects BT.2020, HLG and PQ frames. The sidecar records how many frames did not
match and the first unexpected dataspace. `tools/qualify_oclog2.py` fails a bundle with any
mismatched frame and leaves it `NOT_RUN` when the platform did not report a dataspace. The
recording itself is kept, because a lost take is worse than a disclosed mismatch.

## YCbCr decoding

The camera delivers YCbCr. With the default external sampler, the GPU driver converts it to
R'G'B' with a matrix and range of its own choosing. On a Snapdragon 8 Gen 3 (Adreno 750), a P010
buffer tagged BT.2020 HLG full range was decoded as BT.601 limited range, which moved mid-tones by
up to 76 ten-bit codes. The driver conversion is not observable from the app, so it cannot be
qualified.

When the GPU exposes `GL_EXT_YUV_target`, both tier shaders sample through
`__samplerExternal2DY2YEXT` and convert in the shader:
`rgb = clamp(uYcbcrToRgb * (yuv - uYcbcrOffset), 0, 1)`. The matrix and offset come from each
frame's dataspace (`OpenCineLogYcbcrConversion`): BT.601, BT.709 or BT.2020 coefficients, full or
limited range, ten-bit for the HLG tier and eight-bit for the SDR tier. An unspecified standard or
range falls back to BT.601 limited and is labelled `(default)`. The shader variant is derived
textually from the tier shader, so the transform body is identical. It is the pinned
`runtimeShaderSha256`.

Without the extension, or if the variant fails to link, the pipeline uses the driver sampler. The
sidecar then records `transform.ycbcrConversion: null` and the driver-sampler shader hash, listed
as `driverSamplerShaderSha256` in the spec. `tools/qualify_oclog2.py` fails that hash by name
(`driver-ycbcr-conversion`). It also checks the recorded conversion against the tier, and leaves a
`(default)` conversion `NOT_RUN`.

The inverse HLG step clamps its input to [0, 1] first. Without the clamp, a below-black code from
the sampler would square to positive light.

The HLG and SDR-derived fragment shaders have independent pinned identities. A recording is
not qualified merely because the camera session configures or the HEVC encoder advertises
Main10: the exact size/FPS/source tuple must also sustain recording and produce a parseable
file on physical hardware.

## Versioned interchange package

`docs/color/oclog2-v2/manifest.json` pins every generated byte and SHA-256. The package contains
independent decimal vectors, a 4096-entry 1D `.cube`, a combined 4096-entry shaper plus 17³
identity `.cube`, an OCIO v2 configuration, forward GLSL, forward/inverse DCTL, and the normative
JSON specification. The dense shaper is required because a bare 17³ sampling exceeds the
declared tolerance near black.

The two `.cube` files use different dialects. The Adobe Cube LUT Specification 1.0 allows one 1D
or one 3D table per file, so only `oclog2-v2-4096.cube` is an Adobe Cube file (`TITLE`,
`LUT_1D_SIZE`, `DOMAIN_MIN`/`DOMAIN_MAX`). `oclog2-v2-17.cube` uses DaVinci Resolve's combined
shaper format instead: leading `#` comments, then `LUT_1D_SIZE`, `LUT_1D_INPUT_RANGE`,
`LUT_3D_SIZE`, `LUT_3D_INPUT_RANGE`, the shaper rows, and the lattice rows. It has no `TITLE` or
`DOMAIN_*` keywords. OpenColorIO reads it with its `resolve_cube` reader as a 1D LUT followed by a
3D LUT. Adobe-only readers cannot load it, and they should use the 1D file.

```bash
python3 tools/generate_oclog2_artifacts.py
python3 tools/generate_oclog2_artifacts.py --check
python3 -m unittest tools.tests.test_oclog2_artifacts
```

The generator is deterministic. A spec, generator, shader, or generated-byte change requires a
new manifest identity and review; an edited artifact must never be accepted by merely updating
its hash.

## Qualification boundary

Capability enumeration does not qualify a profile. Production status is scoped to the exact
artifact SHA-256, source digest, build fingerprint, camera ID, width, height, FPS, source tier,
spec version, and runtime shader SHA-256. `tools/qualify_oclog2.py` emits:

- `QUALIFIED` only when every numerical, physical, file, provenance, sustained-recording,
  FFmpeg, and independent-editor check passes;
- `NOT_RUN` when required evidence is absent or the target is not physical;
- `FAILED` when supplied evidence contradicts the contract, is stale, or exceeds tolerance.

Required implementation names are `cpu`, `gpu`, `lut1d`, `lut3d`, `ocio`, and `dctl`. Recorded
clip, sidecar, and `ffprobe` JSON are hash-bound. A successful editor entry must be independent
of OpenCineCam and FFmpeg. The app records profile stage, reason, and evidence ID in the sidecar;
no profile is `VERIFIED` without a non-empty exact evidence ID. The serialized contract is
validated by `docs/schemas/oclog2-sidecar-v2.schema.json`.

```bash
python3 tools/qualify_oclog2.py \
  --input evidence/plan-061/qualification-input.json \
  --output evidence/plan-061/qualification-result.json
```

The independent-editor evidence follows [oclog2-resolve-protocol.md](oclog2-resolve-protocol.md),
whose `tools/oclog2_editor_fixture.py record` output is the `workflows[]` editor entry.

An emulator is useful for contracts but is never a physical qualification target. Missing
hardware or editor access remains `NOT_RUN`, not promoted or treated as fabricated success.

OCLog1 remains immutable for reproducibility of earlier experimental clips; it is not used for
new live recordings.
