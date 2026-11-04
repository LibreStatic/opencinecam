# OpenCine RAW desktop tools

`ocraw_recovery.py` is a dependency-free recovery CLI for OpenCine RAW v1. It
scans complete checksummed chunks, rebuilds an index, verifies final indexes
and END markers, preserves stale/truncated evidence, and can extract PCM16
payloads. A recovered prefix is never reported as finalized.

The file is streamed chunk by chunk, so RAW video takes of many gigabytes are
never loaded whole. For RAW video the report adds `frameCount`,
`frameDimensions` and `frameEncodings` (from each `RAW_FRAME`'s 64-byte frame
header), `frameMetadataCount` (optional `FRAME_METADATA` chunks),
`periodicIndexCount`, the merged static `metadata`, and `invalidFrameHeaders`
and `payloadErrors` for CRC-valid chunks whose contents do not decode. Those
two lists are informational; `valid` covers the container (header, CRCs,
sequence order, final index and END), like the Kotlin readers. The pure-Python
CRC32C runs at about 8 MB/s, about 2 s per full-sensor 4080x3060 RAW10 frame,
so a long take verifies slowly; the Kotlin reader checks the same file at
several GB/s.

On the device and on the JVM, `OcrawStreamReader.recover` in `:media`
truncates a torn tail and appends `FINAL_INDEX` and `END`.

The Kotlin `OcrawDesktopReader` remains the shared Android/JVM reference for
DNG fixture export because it owns the Camera2 RAW metadata types. Both readers
use the wire contract in `docs/formats/opencine-raw-v1.md`.

Example:

```bash
python3 desktop-tools/ocraw_recovery.py clip.ocraw \
  --index-output clip.index.json --pcm16-output clip.pcm
```

## RAW video to DNG

`ocraw_to_dng.py` writes one DNG per RAW frame of a `.ocraw` take. It needs
only the standard library; when numpy imports it unpacks RAW10 and decodes
block-pack frames with numpy, and `--pure` (or `OCRAW_TO_DNG_PURE=1`) forces
the standard-library path. Both paths write byte-identical files.

```bash
python3 desktop-tools/ocraw_to_dng.py clip.ocraw -o clip-dng \
  [--frames 0-99|5,7|10-] [--every N] [--no-crc] [--prefix name] [--pure] \
  [--wav clip.wav [--wav-from-audio]]
python3 desktop-tools/ocraw_to_dng.py clip.ocraw --cinemadng clips [--wav-from-audio] ...
```

- Files are named `<prefix>_<index:06d>.dng`, the prefix defaulting to the
  input's stem and the index being the frame's 0-based position in file order
  (every `RAW_FRAME` chunk counts, so a skipped frame leaves a gap).
- `--cinemadng DIR` writes one clip folder, `DIR/<take>/`, with the DNGs and,
  when the take has sound, `<take>.wav` beside them (the `--wav` rules below).
  `<take>` is `--prefix` or the input's stem. It replaces `-o` and `--wav`.
- `--frames` takes 0-based frame indices in file order (every `RAW_FRAME`
  chunk counts, valid or not); `--every N` then keeps every Nth selected frame,
  starting with the first.
- Every frame encoding is converted: RAW10 (0), LJ92 (1), Bayer
  block-pack (2) and quantised (near-lossless) block-pack (3), whose strip
  container and payload are specified in
  `native-pipeline/include/opencinecam/raw_codecs.h`. Encoding 3 is written
  as the reconstructed DN its payload's table gives back. The summary lists
  frames written, frames skipped as invalid (including a malformed encoded
  frame), frames skipped because their encoding is unknown, the dimensions,
  and any warnings.
- A bad file header exits with status 2. A truncated or damaged tail is not an
  error: every complete, valid frame before it is converted.
- RAW frames are CRC-checked like `ocraw_recovery.py`, at about 2 s per
  full-sensor frame; `--no-crc` skips that, leaving about 0.1 s per
  4080x3060 RAW10 frame (0.05 s of it unpacking, with or without numpy).
  A block-pack frame of that size decodes in about 0.5 s with numpy and
  3.5 s without; LJ92 frames are copied, not decoded.
- `--wav out.wav` also writes the take's sound (its `AUDIO_PCM16` chunks) as
  a 16-bit PCM WAV, at the `audioSampleRate` and `audioChannels` from the
  metadata; given without `-o`, only the WAV is written. Sample zero is the
  first RAW frame's capture time, so the WAV lines up with the DNGs;
  `--wav-from-audio` starts it at the first audio sample instead. Chunks are
  placed by their timestamps: a gap of more than 1 ms, such as audio the
  recorder dropped (`audioDroppedSamples`), becomes silence, and overlapping
  samples are dropped. A take without audio exits with status 2.

Each DNG is little-endian, DNG 1.4 (backward 1.1), with one image IFD holding
the CFA mosaic, and an EXIF IFD. RAW10 and block-pack frames are stored as
uncompressed 16-bit strips (`BitsPerSample` 16). LJ92 frames are stored
without re-encoding: each codec strip, already a lossless JPEG in DNG's CFA
layout, becomes one tile with `Compression` 7, `BitsPerSample` 10 (the JPEG
precision), `TileWidth` the frame width and `TileLength` the first strip's
rows; the last tile's JPEG holds only the rows left in the image. Tiles, not
strips, because LibRaw decodes only the first strip of a multi-strip
lossless JPEG DNG; LibRaw 0.22.3 decodes the tiled files bit-exact. The colour tags
come from the take's static metadata: `CFAPattern` from `cfa` (the names or
Android's integer values), `BlackLevel` (2x2) and `WhiteLevel`,
`ColorMatrix1/2`, `ForwardMatrix1/2`, `CalibrationIlluminant1/2`, and
`model` for `Model` and `UniqueCameraModel`. Each frame's `FRAME_METADATA`
adds `AsShotNeutral` (`neutral`), `ExposureTime` (`exposureNs`) and
`ISOSpeedRatings` (`iso`), and a `blackLevel` or `dynamicBlackLevel` there
overrides the static black level for that frame. Without `colorMatrix1` the
tool writes an identity matrix for D65 and says so, so the files still open
but their colours are not calibrated. Monochrome sensors are not supported.

Every DNG also carries the CinemaDNG tags (PLAN-071 §8): `Make` (271, the
metadata's `make` when present, else `model`), `FrameRate` (51044, SRATIONAL,
`fps`), `TimeCodes` (51043, one 8-byte SMPTE 12M time code, non-drop-frame,
counted from the take's first frame: the frame's sensor timestamp minus the
first readable frame's, times `fps`, rounded to the nearest frame, labelled at
the integer nominal rate; above 30 fps the frames field counts pairs), and
`ReelName` (51081) and `CameraLabel` (51105), both the take name (the input's
stem, whatever `--prefix` says). Without a usable `fps` the tool warns and
writes neither `FrameRate` nor `TimeCodes`.

On the device, `CinemaDngWriter` and `OcrawWavWriter` in `:media` write the
same bytes, with exact sizes up front and byte ranges for the network share;
`tools/regen-cinemadng-golden.sh` regenerates the golden fixtures that check it.

### Import into DaVinci Resolve

1. `python3 desktop-tools/ocraw_to_dng.py A001_C001.ocraw --cinemadng ~/clips`
2. In the Media page, browse to `~/clips` and add the `A001_C001` folder.
   Resolve shows the DNG sequence as one clip and links `A001_C001.wav`
   beside it. If the sound is missing, put the WAV under the clip on the
   timeline at the same start: both begin at the first frame (the WAV has no
   embedded timecode).
3. In the Camera Raw settings, CinemaDNG with *White Balance: As Shot*
   uses each frame's as-shot neutral and the take's colour matrices.
