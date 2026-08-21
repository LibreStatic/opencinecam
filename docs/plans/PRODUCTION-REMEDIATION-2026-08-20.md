# Production camera remediation record — 2026-08-20

This bounded execution record closes the gap between isolated qualification fixtures and the
launcher-reachable product. It does not renumber the canonical 60-plan graph.

| Workstream | Production reachability requirement | Status | Evidence |
|---|---|---|---|
| PR-01 Viewfinder | Launcher → bound capture service → public Camera2 → repeating `SurfaceView` preview | Done | Release screenshot and live result HUD |
| PR-02 Photo | Primary shutter writes a full-resolution JPEG through pending MediaStore ownership | Done | 4000×3000 release JPEG in `DCIM/OpenCineCam` |
| PR-03 Video/audio | Primary record control produces a finalized standard H.264/AAC MP4 and restores preview | Done | 1920×1080 30 fps MP4 with 48 kHz AAC |
| PR-04 Manual/HUD | ISO, shutter, focus, and WB controls update requests; reported metadata stays visible | Done | Physical ISO 201 request/result pass |
| PR-05 RAW/specialty | DNG, bracket, long exposure, and timelapse are launcher-reachable; unsafe branches remain gated | Done | 4000×3000 DNG, 3-frame bracket, 1 s JPEG, 2→30 fps MP4 |
| PR-06 Monitoring | Live histogram, zebra, and focus analysis use a bounded public YUV stream | Done | Physical four-output session and live histogram |
| PR-07 Media/adaptive UI | Graphite/amber bilingual UI and local gallery work on the unfolded target | Done | Release gallery lists JPEG/DNG/MP4 thumbnails |
| PR-08 Experimental truth | Log stays Candidate while provenance is Unknown; APV is Unsupported; RAW video is Failed | Done | `evidence/plan-047/provenance-gate.json`, `evidence/plan-051/apv-capability.json` |

## Release gate

- Host/unit/lint/release build: passed.
- Physical package: `com.librestatic.opencinecam`, version `0.1.0`.
- APK: `app/build/outputs/apk/release/OpenCineCam-0.1.0-release.apk`.
- APK SHA-256: `ad3860bd152dd1bff1a87abc5c41d6424293e2137391a057a5238d45111ad834`.
- Signing: development/test identity only; store signing remains outside this remediation record.

## Remaining conditional work

- OpenCine Log requires an accepted scene-linear RAW/P010/ISP provenance fixture and external numerical/editor qualification before capture can be enabled.
- APV may be reconsidered only on a fingerprint exposing a qualifying hardware encoder.
- RAW video remains disabled because the sustained throughput gate failed.
- High-speed slow motion remains Candidate until a constrained high-speed Camera2 session passes exact device validation.
