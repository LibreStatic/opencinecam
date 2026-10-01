# OCLog2 independent-editor protocol: DaVinci Resolve

This protocol produces the `independent-editor` evidence that OCC-PLAN-061 and
`tools/qualify_oclog2.py` require. An operator runs a fixed set of inputs through DaVinci Resolve
and renders 32-bit float EXR frames. `tools/oclog2_editor_fixture.py` measures each frame and folds
the results into one `workflows[]` entry. The tool never drives the editor, so a missing case stays
`NOT_RUN`. A case that runs and misses tolerance is `FAIL`, and a `FAIL` is recorded as-is, never
retried until it passes.

## What it proves

| Case | Input | Resolve operation | Reference | Gate |
|---|---|---|---|---|
| `forward-dctl` | `oclog2-forward-input.exr` | `oclog2-v2.dctl` | `encode(clamp(x, 0, 1))` | max abs ≤ `2e-5`, monotonic |
| `inverse-dctl` | `oclog2-inverse-input.exr` | `oclog2-v2-inverse.dctl` | `decode(clamp(y, 0.10, 0.90))` | max abs ≤ `2e-5`, monotonic |
| `roundtrip-dctl` | `oclog2-forward-input.exr` | forward then inverse DCTL in serial nodes | `clamp(x, 0, 1)` | max abs ≤ `2e-5` |
| `forward-lut1d` | `oclog2-forward-input.exr` | `oclog2-v2-4096.cube` | `encode(clamp(x, 0, 1))` | max abs ≤ `2e-5`, monotonic |
| `forward-lut3d` | `oclog2-forward-input.exr` | `oclog2-v2-17.cube` (Resolve combined format: 4096 shaper + 17³ identity) | `encode(clamp(x, 0, 1))` | max abs ≤ `2e-5`, monotonic |
| `signal-hevc` | `oclog2-signal-main10.mp4` | none (decode only) | H.273 full-range BT.2020 decode of the coded values | patch mean ≤ 0.5 LSB; sub-black and super-white codes preserved |
| `camera-clip` | recorded OCLog2 MP4 | none (decode only) | FFmpeg 10-bit decode + H.273 conversion | median ≤ 0.5 LSB, p95 ≤ 2 LSB |
| `forward-ocio` (optional) | `oclog2-forward-input.exr` | `oclog2-v2.ocio`, scene_linear → oclog2 | `encode(clamp(x, 0, 1))` | max abs ≤ `2e-5` |

The float stills contain three bands:

- **Dense band:** every component sweeps the full domain. Each channel is rotated by a third, so a
  swapped channel or a leak between channels fails.
- **Near-floor band:** neutral, log-spaced samples between `1e-7` and `1e-2`, where the curve is
  steepest.
- **Patch band:** the reference vectors plus out-of-domain values. The out-of-domain values check
  that each transport clamps as `spec.json` declares.

The `maxAbsError` the qualifier compares against `2e-5` is the worst in-domain error across the
transform cases. Decoder agreement in `signal-hevc` and `camera-clip` is reported separately, in
10-bit code values: two YCbCr-to-RGB paths may legitimately differ by rounding and chroma
upsampling.

`signal-hevc` also identifies how Resolve read the clip: full or limited range, and BT.2020 or
BT.709 matrix. Its neutral steps include codes below `0.10` and above `0.90`, so silent clipping
is visible.

## Prerequisites

- DaVinci Resolve. Record the exact version and edition.
  - DCTL cases need an edition and version that can load DCTL from the LUT folder. If DCTL is not
    available, leave those cases `NOT_RUN`, which keeps the editor gate `NOT_RUN`.
  - Main10 HEVC decode depends on the edition, OS, and GPU. If the MP4 does not import or plays as
    media offline, leave `signal-hevc` and `camera-clip` `NOT_RUN`. Never transcode the clip to
    get it in.
- Python 3 and FFmpeg on the machine that runs the comparisons.
- The physical recording from `OpenCineLogSustainedRecordingDeviceTest` for the tuple under
  qualification.

All evidence lives under `local-evidence/`, which is ignored by git. The case and workflow records
keep only file basenames and SHA-256s, never device or personal paths.

## 1. Generate the fixture

```bash
python3 tools/oclog2_editor_fixture.py generate --output local-evidence/oclog2-resolve
```

This writes the following to `local-evidence/oclog2-resolve/`:

- the two 1920×1080 RGB float EXR stills;
- a 1-second, 30 fps, lossless HEVC Main10 clip. Its signaling matches the app's OCLog2
  recordings: full range, BT.2020 primaries and matrix, transfer unspecified (H.273 value 2).
  The container never claims a standard transfer; the OCLog2 sidecar is the authority, so set
  the clip's input transform in the editor;
- `fixture-manifest.json`, which records the generator SHA-256.

A comparison against a fixture built by a different generator revision fails as stale.

Copy these files from `docs/color/oclog2-v2/` into Resolve's LUT folder:

- `oclog2-v2.dctl`
- `oclog2-v2-inverse.dctl`
- `oclog2-v2-4096.cube`
- `oclog2-v2-17.cube`

`oclog2-v2-4096.cube` is an Adobe Cube 1.0 1D file. `oclog2-v2-17.cube` uses Resolve's combined
shaper layout (`LUT_1D_SIZE`, `LUT_1D_INPUT_RANGE`, `LUT_3D_SIZE`, `LUT_3D_INPUT_RANGE`), so
Resolve applies the 1D shaper first and then the 3D lattice. The lattice is an identity, so the
3D interpolation setting cannot change the result. Note whether Resolve lists and loads the file
without an error. If Resolve rejects it, leave `forward-lut3d` `NOT_RUN` and report the error
text as a defect in the artifact package.

To find the folder, open Project Settings → Color Management → Lookup Tables → Open LUT Folder.
Then click Update Lists. Check that the copies are byte-identical to the hash-pinned files:

```bash
python3 tools/generate_oclog2_artifacts.py --check
sha256sum docs/color/oclog2-v2/*.dctl docs/color/oclog2-v2/*.cube
```

## 2. Project settings (one project for every case)

- **Color science:** DaVinci YRGB, not color managed. Set no input, output, or timeline LUT and no
  color-space transform.
- **Timeline resolution:** 1920×1080 at 30 fps.
- **Image Scaling:** set mismatched resolution to "Center crop with no resizing", so a mismatch
  shows up as a resolution failure instead of being resampled silently.
- Leave every node unrelated to the case untouched: no grades, noise reduction, sharpening, or
  ResolveFX.

Record any setting you had to change from these values in the workflow notes.

## 3. Run each case

For each case:

1. Put its input on its own timeline.
2. On the Color page, apply the operation from the table to node 1. For `roundtrip-dctl`, use
   node 1 for the forward DCTL and a serial node 2 for the inverse DCTL.
3. Set the In and Out marks to the first frame.
4. On the Deliver page, render one frame as EXR with 32-bit RGB float at the timeline resolution.
   Use no compression when Resolve offers that option. The comparator reads compressed float EXR
   through FFmpeg, but uncompressed is read natively.

Half-float renders are rejected: their 1e-3 precision cannot test a 2e-5 tolerance.

For the two MP4 cases, open Clip Attributes and record what **Data Levels: Auto** shows. Render
once with Auto and keep that result as a finding. Then set Data Levels to **Full** and render the
case that is recorded:

```bash
python3 tools/oclog2_editor_fixture.py compare --case signal-hevc \
  --render <auto-render>.exr --output local-evidence/oclog2-resolve/findings/signal-hevc-auto.json
```

Only the run with Full data levels goes into the workflow record. The Auto result is a finding
about Resolve's default handling, not a gate.

Compare every rendered frame:

```bash
R=local-evidence/oclog2-resolve
python3 tools/oclog2_editor_fixture.py compare --case forward-dctl   --render $R/renders/forward-dctl.exr   --output $R/cases/forward-dctl.json
python3 tools/oclog2_editor_fixture.py compare --case inverse-dctl   --render $R/renders/inverse-dctl.exr   --output $R/cases/inverse-dctl.json
python3 tools/oclog2_editor_fixture.py compare --case roundtrip-dctl --render $R/renders/roundtrip-dctl.exr --output $R/cases/roundtrip-dctl.json
python3 tools/oclog2_editor_fixture.py compare --case forward-lut1d  --render $R/renders/forward-lut1d.exr  --output $R/cases/forward-lut1d.json
python3 tools/oclog2_editor_fixture.py compare --case forward-lut3d  --render $R/renders/forward-lut3d.exr  --output $R/cases/forward-lut3d.json
python3 tools/oclog2_editor_fixture.py compare --case signal-hevc    --render $R/renders/signal-hevc.exr    --output $R/cases/signal-hevc.json
python3 tools/oclog2_editor_fixture.py compare --case camera-clip    --render $R/renders/camera-clip.exr \
  --clip $R/camera/<recording>.mp4 --frame 0 --output $R/cases/camera-clip.json
```

Each comparison takes a few seconds and exits with 0 for PASS and 2 for FAIL. The JSON contains:

- the worst sample (pixel, channel, input, expected and actual values);
- error per band;
- the measured values for each patch;
- for the decode cases, which range and matrix interpretation fit best.

If a case fails, keep the case JSON. That failure is the evidence. Do not change the settings to
chase a pass unless the setting was wrong according to this protocol. In that case, record the
correction.

`forward-ocio` is optional. Run it only if your Resolve version can load
`docs/color/oclog2-v2/oclog2-v2.ocio` as a config and apply the
`scene_linear_bt2020 → oclog2_bt2020` transform. Otherwise leave it out, and it stays `NOT_RUN`.

## 4. Fold into the qualification bundle

```bash
python3 tools/oclog2_editor_fixture.py record local-evidence/oclog2-resolve/cases/*.json \
  --editor-version "<exact Resolve version>" --edition studio \
  --output local-evidence/oclog2-resolve/workflow-resolve.json
```

The command produces one record whose status is:

- `PASS` only if all seven required cases pass;
- `FAIL` if any required case failed;
- `NOT_RUN` if any required case is missing.

Copy the record into the `workflows` array of the OCC-PLAN-061 qualification input, next to the
`ffmpeg` entry. Its `implementations` map gives the `dctl`, `lut1d`, `lut3d`, and, when run, `ocio`
results measured in Resolve. Merge them into the input's `implementations` only for paths that
passed. Then run `tools/qualify_oclog2.py`, as described in [opencine-log-v2.md](opencine-log-v2.md).

A passing Resolve record closes only the independent-editor check, and only for the exact clip,
artifact manifest, and Resolve version it names. It does not qualify any other tuple, source tier,
or editor version.
