# ADR-0029: Professional audio formats and controls

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements: OCC-AUDIO-001 through OCC-AUDIO-011
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: ADR-0012
- Superseded by: None

## Context

ADR-0012 fixed production audio at PCM16/AAC-LC 48 kHz. The user requires selectable format, sample rate, bit depth, bitrate, input, and public noise-processing controls, bounded by what the device actually accepts. The current standard video route uses `MediaRecorder`; the OCLog2 route uses a custom video-only Main10 muxer. Android exposes a FLAC encoder but neither `MediaRecorder` nor `MediaMuxer` exposes a raw FLAC output container.

## Decision

1. Probe real `AudioRecord` initialization for 44.1/48/88.2/96/192 kHz, PCM16, packed PCM24, float32, and mono/stereo. Retain correlated tuples; never synthesize combinations from independent lists.
2. Standard MP4 video may embed AAC-LC configured with a codec-supported sample rate, channel count, bitrate, public audio source, and preferred `AudioDeviceInfo`.
3. Lossless recording uses an independently stored PCM WAV sidecar sharing the video basename. Its monotonic start/stop timestamps, requested/effective route, format, frames, bytes, and effect states are written to `.audio.json`.
4. WAV bitrate is displayed as the derived sample-rate × bit-depth × channel count. It is not an independent control.
5. NS, AGC, and AEC controls are available only for WAV/`AudioRecord`, where the app owns an audio session and can report effective state. AAC/`MediaRecorder` does not claim these controls.
6. Public APIs select an input device, not a guaranteed individual built-in microphone capsule. OEM DSP outside public APIs remains Unknown.
7. OCLog2 accepts WAV sidecar audio or deliberate audio-off. It rejects AAC rather than silently producing video-only output.
8. FLAC remains unavailable until OpenCineCam has a streaming container writer that round-trips through its reader and at least FFmpeg plus one independent decoder fixture.
9. Any requested route that disconnects or rejects initialization fails before publication. There is no silent source, format, depth, or channel fallback.

## Alternatives considered

- **MP3:** rejected; not lossless, not exposed by the current MP4 recorder contract, and would reproduce the limitation the user wants to avoid.
- **AAC-only:** rejected because it cannot deliver 24-bit or float lossless capture and is unavailable in the custom OCLog2 muxer.
- **Pretend FLAC support from codec enumeration:** rejected because an encoder does not establish a valid interoperable container.
- **Always disable effects and call audio unprocessed:** rejected because OEM processing is not fully observable and users requested explicit controls.
- **Silently normalize at recording time:** rejected. UI normalization is capability-backed; a changed/disconnected route is an explicit failure.

## Consequences

### Positive

Users can choose honest device-tested quality, including CD-rate/lossless and 24-bit/float paths where initialization succeeds. Standard MP4 remains convenient with embedded AAC. OCLog2 can record professional audio without redesigning the video muxer.

### Negative

WAV is a second media item and must be aligned in post using the paired basename/timestamps. RIFF v1 stops before 4 GiB. AAC effects cannot be controlled truthfully. Storage cost can be high.

### Operational consequences

The microphone permission is separate from camera permission. The settings UI exposes only probed choices and discloses container behavior. Recording foreground service type includes microphone for either AAC or WAV. Sidecar write/finalization failure prevents a requested audio recording from being reported as successful.

## Validation evidence

- Unit tests cover settings normalization and WAV headers.
- Physical-device acceptance requires at least 15 seconds each of embedded AAC and the highest useful WAV tuple accepted by the physical reference foldable, followed by file/sidecar inspection.
- FLAC requires the future gate described in Decision 8.

## Implementation constraints

Use public Android APIs only. Preserve correlated PCM tuples. Use `setPreferredDevice` and reject a false return. Use PCM format code 1 and IEEE-float code 3 in WAV. Record monotonic timestamps and effect enabled states. Never label a source “raw” or “unprocessed” as proof that OEM DSP is absent.

## Affected plans

OCC-PLAN-021, OCC-PLAN-022, OCC-PLAN-023, OCC-PLAN-041

## Revisit conditions

Revisit when the standard video path migrates to the app-owned AudioRecord/MediaCodec muxer, a validated FLAC writer lands, RF64 is required, or Android publishes stronger microphone/effect provenance APIs.

## Amendment (2026-10-19)

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-AUDIO-001 through OCC-AUDIO-011
- Related ADRs: ADR-0010, ADR-0031, ADR-0033

### Context

Three statements of the original decision no longer match the shipped app:

- Context and Decision 8: FLAC is unavailable. `FlacAudioSidecarRecorder` (`app/src/main/java/com/librestatic/opencinecam/storage/FlacAudioSidecarRecorder.kt`) writes a FLAC sidecar, and `ProfessionalAudio.kt` offers FLAC when the device advertises the FLAC encoder. ADR-0033 revision 5 (audio worker retirement) and revision 14 (separate WAV/FLAC source epochs) record the writer, its STREAMINFO and frame-count checks and an FFmpeg decode of five takes, and revision 15 records FLAC in the shared pause.
- Decision 7: OCLog2 accepts only a WAV sidecar or no audio. ADR-0033 revision 15 passes a per-take capture clock from VIDEO and LOG alike to either embedded AAC or a separate WAV/FLAC recorder.
- Context: standard video uses `MediaRecorder`. Normal VIDEO and time-lapse now run on the app-owned GPU to `MediaCodec` graph (`Camera2PreviewEngine.startGpuVideo`), with AAC from an app-owned `AudioRecord` and `MediaCodec` into the shared muxer, as `docs/architecture.md` and ADR-0031 describe. `MediaRecorder` remains only on the constrained high-speed route, where `CaptureService` passes `captureRate` and `startVideo` takes the direct path (`captureRate != null`).

### Decision

- **Decision 7 is replaced.** LOG, like VIDEO, accepts embedded AAC-LC or a separate WAV or FLAC sidecar, or deliberate audio-off, and never records both. The route is selected by the existing audio format intent. A requested route that cannot start still fails before publication (Decision 9), and AAC is still not silently dropped.
- **Decision 8 is amended.** A streaming FLAC writer exists and FLAC is selectable. Its published evidence is the writer's STREAMINFO and frame accounting plus FFmpeg decoding of the retained files (ADR-0033 revision 14). The part of the original gate that asks for a second independent decoder fixture on a published sidecar is not recorded in ADR-0033 and stays pending. FLAC is not described as interoperability-qualified until that fixture is recorded.
- **Context amended.** The standard video path is the GPU graph with an app-owned encoder; `MediaRecorder` is used only for constrained high-speed slow motion (`captureRate != null`).

Decisions 1 to 6 and 9 are unchanged.

### Alternatives considered

- Keep FLAC unavailable until the second decoder fixture exists: rejected because the writer already ships with FFmpeg-verified output, and hiding it would contradict the shipped settings.
- Treat FFmpeg as sufficient qualification: rejected because Decision 8 asked for FFmpeg plus one independent decoder.

### Consequences

- Positive: the ADR matches the shipped audio and video routes.
- Negative: FLAC interoperability stays an open validation item.
- Operational: record the second decoder fixture in the device validation evidence, then drop the pending note.

The original decision above is retained as history; this amendment governs where they differ.
