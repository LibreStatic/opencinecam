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
dataspace, and exact shader SHA-256. A changed shader is a new transform build even when the
curve equation remains unchanged.

The HLG and SDR-derived fragment shaders have independent pinned identities. A recording is
not qualified merely because the camera session configures or the HEVC encoder advertises
Main10: the exact size/FPS/source tuple must also sustain recording and produce a parseable
file on physical hardware.

OCLog1 remains immutable for reproducibility of earlier experimental clips; it is not used for
new live recordings.
