# OpenCine RAW desktop tools

`ocraw_recovery.py` is a dependency-free recovery CLI for OpenCine RAW v1. It
scans complete checksummed chunks, rebuilds an index, verifies final indexes
and END markers, preserves stale/truncated evidence, and can extract PCM16
payloads. A recovered prefix is never reported as finalized.

The Kotlin `OcrawDesktopReader` remains the shared Android/JVM reference for
DNG fixture export because it owns the Camera2 RAW metadata types. Both readers
use the wire contract in `docs/formats/opencine-raw-v1.md`.

Example:

```bash
python3 desktop-tools/ocraw_recovery.py clip.ocraw \
  --index-output clip.index.json --pcm16-output clip.pcm
```
