# Versioned Schemas

JSON Schema 2020-12 files here are canonical external contracts. Runtime models require golden parity tests.

- `schemaVersion` is semantic: additive optional fields increment minor; incompatible changes increment major.
- Unknown is `{ "knowledge": "unknown" }`, never false, zero, or empty text.
- Readers ignore additive unknown fields and reject unsupported major versions.
- Media time is normalized microseconds; hardware observations retain declared nanosecond time bases.
- Fingerprints, models, file names, URIs, and diagnostics are privacy-sensitive and redacted by default.

## Clip sidecar

`clip-sidecar.schema.json` describes `<videoStem>.clip.json`, the canonical sidecar of one video take (ADR-0011 amendment 2026-10-22). `ClipSidecarBuilder` (`app/.../storage/`) writes it pending and publishes it before the video; failing to build or write it never fails or deletes the take. An opt-in WebDAV bundle uploads it as the `CLIP_METADATA` artifact (ADR-0034 amendment 2026-10-22). Main fields:

- `schemaVersion`, `recordingId`, `createdAt`.
- `intent`: what the operator asked for when the take started (recording policy, capture mode, requested size and fps, project rate, codec and bitrate preference, audio, LUT, timecode).
- `resolvedGraph`: what actually ran, read when the take stopped (route, recorder, video encoder and encoded size, color kind/transfer/gamut/profile, audio storage, device profile and applied quirks, and `camera`: the camera settings sampled while the take recorded, such as lens, exposure mode, ISO and shutter ranges, white balance, focus, zoom, stabilization and processing modes). A field the service did not know is written as unknown, never guessed.
- `events`: take start, pause/resume windows, warnings and take stop, each with `timeUs`, `code` and `severity`.
- `validation`: the `Recorded` and `FileVerified` evidence steps (`pass`, `fail` or `notRun`).
- `media`: display name, duration, encoded frames, and the take's other sidecars and audio files.
- `takeStatus: "incomplete"` and `failureReasons` appear only on incomplete takes, with one `incomplete:<reason>` event per reason.
- `continuation` appears only on a take that continues on the phone a take whose USB drive was lost mid-take (OCC-PLAN-072 Delivery 4): `of` is the drive take's `recordingId`, `previousName` its video file name, `index` 1 for the first continuation, and `reason` `drive-disconnected` or `drive-write-failed`. The drive take's own sidecar is not rewritten. With `resolvedGraph.camera`, it makes the sidecar `1.1.0`: both are optional additions, so a `1.0.0` sidecar stays valid and the schema accepts either version.
