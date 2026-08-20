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
`0x0031`, and end `0x00ff`. Unknown IDs are preserved for forward readers.

## Payload conventions

- Metadata is a canonical CBOR map of UTF-8 text keys to UTF-8 text values,
  sorted by key.
- RAW frame payloads preserve the advertised public stream's packed bytes,
  stride, CFA, levels, and timestamp metadata; a RAW still capability never
  implies RAW video capability.
- Audio payloads are signed little-endian PCM16 samples. Channel count and
  sample rate are carried in the preceding metadata/index record.
- Index payloads contain sequence, file offset, and timestamp entries. A final
  index is an optimization, never the sole recovery source.

## Recovery and forward rules

Readers validate magic/version, bounds, and CRC32C in order. A truncated header,
payload, invalid length, or CRC mismatch marks stale evidence and returns the
last valid offset; valid earlier chunks remain readable. Unknown chunk IDs are
retained as opaque records and skipped by v1 consumers. Writers use a bounded
append journal with duplicate-command and cancellation outcomes, and close
only after the end chunk has been appended.

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
