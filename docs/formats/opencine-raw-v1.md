# OpenCine RAW v1 (`.ocraw`)

This document is the implementation-owned v1 wire contract. It is append-only:
readers scan complete checksummed chunks and stop at the last valid chunk after
power loss or a truncated write.

## Fixed header (32 bytes, little-endian)

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 8 | ASCII magic `OCRAW\0\1\0` |
| 8 | 2 | Version (`1`) |
| 10 | 2 | Flags |
| 12 | 16 | Clip UUID |
| 28 | 4 | Reserved (`0`) |

The UUID is opaque and stable for the clip. A reader must reject another
version rather than interpreting it as v1.

## Chunk header (44 bytes, little-endian)

Each chunk is `header || payload`; payload length is bounded to 64 MiB.

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 2 | Chunk ID |
| 2 | 2 | Flags |
| 4 | 8 | Monotonic sequence |
| 12 | 8 | Timestamp ticks |
| 20 | 8 | Time-base numerator |
| 28 | 8 | Time-base denominator |
| 36 | 4 | Payload length |
| 40 | 4 | CRC32C of payload |

Time is `timestamp_ticks * numerator / denominator` seconds. Both time-base
values are positive. Core IDs are metadata `0x0001`, RAW frame `0x0010`, video
frame `0x0011`, PCM16 audio `0x0020`, periodic index `0x0030`, final index
`0x0031`, frame metadata `0x0040`, and end `0x00ff`. Unknown IDs are preserved
for forward readers. Sequence numbers rise strictly through the file (the
streaming writer starts at 0); the CRC32C covers the whole payload, including a
RAW frame's 64-byte frame header.

## Payload conventions

- Metadata is a canonical CBOR map of UTF-8 text keys to UTF-8 text values,
  sorted by key.
- Audio payloads are signed little-endian PCM16 samples. Channel count and
  sample rate are carried in the preceding metadata/index record.
  In a RAW video take (PLAN-071 §7.1.1) each chunk holds about 100 ms of
  interleaved samples, and its timestamp is the first sample's capture time
  in the RAW frames' time base. The first METADATA chunk names
  `audioSampleRate`, `audioChannels` and `audioSource` (plus `audioClock`:
  `boottime`, or `monotonic-offset` when the sensor clock is not boot time),
  and comes before any audio chunk. A closing METADATA chunk adds
  `audioDroppedSamples` (sample frames, one sample per channel, lost to a
  full writer queue), `audioTimestampBacked`, `audioMaxResidualNs` when
  AudioRecord timestamps were available, and `audioError` when capture
  stopped early. A take without sound has none of these keys and no audio
  chunks.
- Index payloads contain sequence, file offset, and timestamp entries. A final
  index is an optimization, never the sole recovery source.

## RAW frame payload (`0x0010`)

A RAW frame payload is a 64-byte little-endian frame header followed by
`dataLength` bytes of frame data, so the chunk's payload length is
`64 + dataLength`.

| Offset | Size | Field |
| ---: | ---: | --- |
| 0 | 2 | Frame header version (`1`) |
| 2 | 2 | Encoding |
| 4 | 4 | Width, pixels |
| 8 | 4 | Height, rows |
| 12 | 4 | Row bytes |
| 16 | 4 | Crop left, pixels |
| 20 | 4 | Crop top, rows |
| 24 | 8 | Sensor timestamp, ns (`SENSOR_TIMESTAMP`) |
| 32 | 8 | Frame number: the take-local index, 0 for the first frame |
| 40 | 4 | Data length, bytes |
| 44 | 20 | Reserved, zero |

Encodings:

- `0` RAW10, packed exactly as Android `ImageFormat.RAW10`: each group of four
  pixels is four bytes holding their 8 most significant bits, then one byte
  holding the four 2-bit remainders (pixel 0 in bits 0-1). Rows are tightly
  packed: `rowBytes = width * 5 / 4`, no stride padding, and
  `dataLength = rowBytes * height`. Width is a multiple of 4.
- `1` LJ92, lossless JPEG. The frame data is an encoded-frame container (see
  below) whose strips are each one complete ITU-T T.81 lossless JPEG (SOF3)
  stream, SOI to EOI, laid out as DNG stores CFA data: a frame of `X = width / 2`
  columns by the strip's rows, with two components (component 0 the even image
  columns, component 1 the odd ones), precision `P = 10`, point transform 0, no
  restart interval and one Huffman table shared by both components. Any T.81
  predictor 1..7 is allowed; OpenCineCam writes predictor 1 with a per-strip
  optimal Huffman table. Each strip can be stored unchanged as a DNG tile with
  `Compression = 7`, `TileWidth = width` and `TileLength` = the first strip's
  rows.
- `2` Bayer block-pack: the same container, each strip a same-colour delta,
  16-sample block stream. Its exact layout is specified in
  `native-pipeline/include/opencinecam/raw_codecs.h`. OpenCineCam records it
  for compressed ("Save space") takes; takes recorded before 2026-11-03 use
  encoding 1 instead.

For encodings 1 and 2, `rowBytes` is `width * 5 / 4`, the row length of the
decoded RAW10 frame (readers need not use it), `dataLength` is the container
size, width is a multiple of 4 and height is even. Decoding yields exactly the
RAW10 frame encoding 0 would have stored.

The encoded-frame container (little-endian) is `u32 stripCount` (1..64), then
`stripCount` pairs of `u32 rows, u32 byteLength`, then the strip streams
concatenated in that order. Strips are horizontal bands of whole rows, top to
bottom, and their rows add up to the height. Every strip but the last has the
same row count, `ceil(height / strips)` rounded up to a multiple of 16 (or the
whole height when that is taller), so it maps to DNG tiles; the last strip
holds the remaining rows; every strip has an even row count. The byte lengths
consume the frame data exactly. `raw_codecs.h` lists the checks a decoder
applies (an unused high nibble is 0, each stream is consumed exactly, samples
are 0..1023, and so on); a frame that fails them is malformed, but the chunk
stays valid for recovery.

The encoding may differ from frame to frame within one file: a compressed take
stores a frame whose encoded size would not fit its buffer (pathological noise)
as encoding 0, and readers must handle each frame by its own header. The
`encoding` key of the static metadata only describes what the take was
configured for. The same holds for the stream a compressed configuration's
qualification long run writes (`method: compressed-long-run`): it is an
ordinary file whose frames are compressed as a take's would be, with the
encoder's metadata keys, scanned back and then deleted.

The frame number counts the take's frames from 0 and rises by exactly one per
frame, so a reader detects a missing frame as a gap. It is never the Camera2
capture frame number; that one is the `frameNumber` key of the frame's
`FRAME_METADATA`.

The crop origin is the frame's top-left corner within the sensor pixel array
the static metadata describes. Both coordinates are even, so the frame keeps
the sensor's CFA phase; a RAW10 crop left is also a multiple of 4 so packing
groups stay whole. Writers write zero in the reserved bytes and readers
ignore them. A reader rejects a frame header version other than 1 for that
frame only; the chunk itself stays valid for recovery. The chunk timestamp
uses the clip's time base; RAW video takes should use `1/1000000000` with the
sensor timestamp as ticks.

## Frame metadata (`0x0040`)

Optional and skippable. Its payload is the same canonical CBOR text map as
`METADATA`, describing one frame. The `sequence` key holds the decimal chunk
sequence of the `RAW_FRAME` it describes, which precedes it; it is written when
that frame's `TotalCaptureResult` arrives, so it may follow later frames.
Other keys, all optional:

| Key | Value |
| --- | --- |
| `frameNumber` | `TotalCaptureResult.getFrameNumber()`, integer |
| `exposureNs` | `SENSOR_EXPOSURE_TIME`, integer ns |
| `iso` | `SENSOR_SENSITIVITY`, integer |
| `frameDurationNs` | `SENSOR_FRAME_DURATION` as the HAL reports it, integer ns; diagnostic only, since some HALs report the declared limit rather than the real rate (the take's rate is `fps`, its timing the frame timestamps) |
| `neutral` | `SENSOR_NEUTRAL_COLOR_POINT`, 3 rationals |
| `ccGains` | `COLOR_CORRECTION_GAINS`, 4 decimals (R, Gr, Gb, B) |
| `focusDistance` | `LENS_FOCUS_DISTANCE`, decimal diopters |
| `dynamicBlackLevel` | `SENSOR_DYNAMIC_BLACK_LEVEL`, 4 decimals in CFA order, space-separated; overrides the static `blackLevel` for this frame |
| `whiteLevel` | `SENSOR_DYNAMIC_WHITE_LEVEL`, integer; overrides the static `whiteLevel` for this frame |

A frame with no `FRAME_METADATA` (older takes lost the last frame's) inherits only its
colour values from the nearest earlier frame that has them, else the nearest later one:
`neutral`, the black level (`dynamicBlackLevel` or `blackLevel`) and `whiteLevel`, each taken
whole from one record. Exposure, ISO and the other keys stay absent. Readers that develop or
convert frames (the app's review and CinemaDNG export, `ocraw_to_dng.py`) apply this rule.

## Static metadata keys

The first `METADATA` chunk, written before any frame, carries what a DNG
converter needs. Later `METADATA` chunks may add keys; a later value wins.

| Key | Value text |
| --- | --- |
| `cfa` | `RGGB`, `GRBG`, `GBRG`, `BGGR` or `MONO` |
| `blackLevel` | 4 integers in CFA order, space-separated |
| `whiteLevel` | integer |
| `colorMatrix1`, `colorMatrix2` | 9 rationals, row-major |
| `forwardMatrix1`, `forwardMatrix2` | 9 rationals, row-major |
| `calibrationIlluminant1`, `calibrationIlluminant2` | EXIF LightSource integer |
| `sensorWidth`, `sensorHeight` | pixel array size, integers |
| `activeArray` | `left top right bottom`, integers |
| `crop` | `left top width height` of every frame, integers |
| `fps` | rational |
| `cameraId` | Camera2 id |
| `model` | `Build.MODEL` |
| `appVersion` | app version name |
| `encoding` | configured frame encoding: `raw10` or `lj92`; informative, each frame header's encoding is authoritative |
| `lj92Predictor` | LJ92 takes only: the T.81 predictor the writer uses, integer 1..7 |
| `lj92Strips` | LJ92 takes only: the strip count the writer requests, integer (short frames may get fewer) |

Rationals are written `n/d` with integer `n` and positive `d` (for example
`1024/1024`); lists separate values with one space; decimals use `.` and no
exponent. Unknown keys are kept and ignored.

## Indexes and closing

Every periodic and final index entry lists one chunk other than an index or
END, in file order. The streaming writer appends a `PERIODIC_INDEX` at least
every 1 s of media time and every 32 RAW frames, holding the entries written
since the previous periodic index (or since the file header); the older
in-memory journal repeats every entry so far, so readers treat periodic
indexes as hints and rebuild by scanning when in doubt. A cleanly closed
file ends with a `FINAL_INDEX` listing every such chunk, then an `END` chunk
with an empty payload. When the final index would exceed the 64 MiB payload
bound (about 2.8 million chunks) the writer writes `END` alone. Recovery
truncates a torn tail and may append its own `FINAL_INDEX` and `END`, which
continue the sequence and reuse the last chunk's timestamp and time base.

## Recovery and forward rules

Readers validate magic/version, bounds, and CRC32C in order. A truncated header,
payload, invalid length, or CRC mismatch marks stale evidence and returns the
last valid offset; valid earlier chunks remain readable. Unknown chunk IDs are
retained as opaque records and skipped by v1 consumers. Writers use a bounded
append journal with duplicate-command and cancellation outcomes, and close
only after the end chunk has been appended.

The streaming writer, reader and recovery for RAW video are
`OcrawStreamWriter.kt`, `OcrawStreamReader.kt` and `OcrawFrameHeader.kt`,
with CRC32C in `Crc32c.kt`; the frame ring between the camera callback and the
writer thread is `media/src/main/java/com/librestatic/opencinecam/media/raw/RawVideoRing.kt`.

The Kotlin reference codec and deterministic fixtures live in
`media/src/main/java/com/librestatic/opencinecam/media/ocraw/OpenCineRaw.kt` and
`media/src/test/java/com/librestatic/opencinecam/media/ocraw/OpenCineRawTest.kt`.
The append journal, periodic/final index encoder, and interruption result are
in `OcrawJournal.kt`; the host reader/recovery/PCM/DNG facade is in
`OcrawDesktop.kt`; and the truncation, bit-flip, unknown-chunk, large-file, and
writer-reader interoperability matrix is in `OcrawInterop.kt`.
The dependency-free desktop recovery CLI is `desktop-tools/ocraw_recovery.py`.

Camera-side RAW10/RAW12/RAW_SENSOR unpacking, DNG tag/payload fixtures, and a
byte-bounded no-drop ownership ring are implemented in
`camera/src/main/java/com/librestatic/opencinecam/camera/RawCapture.kt`.
Physical Camera2 RAW capability and destination-throughput promotion remains a
ConditionalReady gate; host fixtures do not claim RAW-video support.

## Encoding 3: quantised block-pack (added 2026-11-04)

This section extends the encoding list above; the rules there for encodings 1
and 2 apply unchanged.

- `3` quantised (near-lossless) Bayer block-pack. Before block-pack coding,
  each RAW10 sample is replaced by a code from a monotonic table built from the
  sensor's noise model, so neighbouring values closer than about `k` noise
  sigmas share a code. The frame data is self-contained, little-endian, with
  no padding:

  | Offset | Size | Field |
  | ---: | ---: | --- |
  | 0 | 1 | `quantVersion` (`1`) |
  | 1 | 1 | Reserved: writers write 0, readers ignore it |
  | 2 | 2 | `tableCount` N, 1..1024 |
  | 4 | 4 | `isoUsed`: the ISO the table was built for (informative) |
  | 8 | 2 | `kMilli`: the step `k` in noise sigmas, times 1000 (informative) |
  | 10 | 2 | `blackLevel` of the noise model (informative) |
  | 12 | 2N | `inverse[N]`: code to reconstructed sample, each 0..1023 |
  | 12 + 2N | rest | the encoded-frame container of the codes, as encoding 2 |

  A reader decodes the container exactly as encoding 2, which yields codes
  instead of samples, and replaces each code `c` with `inverse[c]`; it needs no
  noise model. `rowBytes`, width and height follow the rules of encodings 1
  and 2. Besides the encoding 2 checks, a frame is malformed when
  `quantVersion` is not 1, N is outside 1..1024, the data is shorter than
  `12 + 2N`, an `inverse` entry exceeds 1023, or a decoded code is N or more.
  Decoding yields the reconstructed RAW10 frame, not the samples the sensor
  delivered. The table construction, the codes and the native layout are in
  `native-pipeline/include/opencinecam/raw_codecs.h` and
  `media/src/main/java/com/librestatic/opencinecam/media/raw/RawQuantisation.kt`.

  Encoding 3 has its own id so that a reader that knows only encoding 2 fails
  on the frame instead of returning codes as samples. As with the other
  encodings, a frame that does not fit is stored as encoding 0, and each
  frame header's encoding is authoritative.

## Near-lossless takes: static metadata (added 2026-11-04)

This section extends the static metadata keys above.

- `encoding` may also be `bp` (a block-pack take: frames of encoding 2) or
  `bpq` (a near-lossless take: a block-pack configuration recorded with
  encoding 3). A `bpq` take's frames are encoding 3, or encoding 2 until the
  writer knows the sensitivity of the frames (the capture results trail the
  frames), or encoding 0 when a frame does not fit. As before, the value is
  informative and each frame header's encoding is authoritative.
- `blockPackStrips`: `bp` and `bpq` takes, the strip count the writer
  requests, integer.
- `quantK`: `bpq` takes only, the step `k` in noise sigmas the tables were
  built with, decimal (informative; each frame's preamble carries `kMilli`).
- `quantNoiseProfile`: `bpq` takes only, the noise profile the tables came
  from as `device/cameraId` (informative).

A writer builds each frame's table from the most recent ISO it knows, which
is the frame's own when its capture result has arrived; the preamble's
`isoUsed` names that ISO and may differ from the frame's `iso` in
`FRAME_METADATA`.
