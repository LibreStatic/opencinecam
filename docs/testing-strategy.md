# Testing and Evidence Strategy

## Pyramid

Pure JVM tests cover models, schema parity, policy graphs, request composer, timestamp math, container parser/recovery, and test vectors. Android instrumentation covers permissions, service lifecycle, MediaStore/SAF, surface replacement, and fakes. Physical-device suites alone establish Camera2, codec, audio, storage, thermal, foldable, RAW, and APV behavior. Emulators are orchestration evidence only.

## Evidence levels

| Level | Required evidence |
|---|---|
| Advertised | Public API value captured with fingerprint and report version |
| SessionCreated | Exact graph configuration callback succeeds |
| FirstFrameReceived | Matching result/image/codec output and timestamps arrive |
| Recorded | Graceful EOS and file finalization complete |
| FileVerified | Android extractor plus independent parser confirm required properties |
| Sustained | Encoded: 10 minutes; RAW: 60 seconds; no prohibited loss/pressure/error and storage margin passes |
| EmpiricallyVerified | Three cold-start sustained passes on exact fingerprint/protocol |
| Certified | 30-minute record, lifecycle/fold/fault/low-space/thermal suites, security/privacy/accessibility and release gates |

Runtime frame loss uses sensor/output timestamp intervals greater than 1.5× the expected period. Controlled repeat tests use unique frame fixtures; identical scene content is not proof. HLG effective precision uses a controlled gradient protocol and remains separate from Main10 signaling.

## Evidence storage

Each device run records command, app/build fingerprint, graph ID, environment, start/end, structured log, capability report, sidecar, produced-file metadata, SHA-256 where practical, and validation JSON. Evidence is local and manually exported. Failed evidence remains visible and never promotes a profile.
