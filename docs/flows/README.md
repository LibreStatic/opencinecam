# Runtime flows

These pages explain the flows that no single source file shows whole: who calls whom, in what order, and what happens when something fails. Each one names the files it describes, draws the flow with Mermaid diagrams, and ends with failure cases, invariants and links to the ADRs and plans.

The pages describe the code as it is. Where the code and an ADR disagree, the page says so under "Related".

| Flow | What it covers |
|---|---|
| [Startup](startup.md) | Launcher tap to the first viewfinder frame: camera pre-open, splash release, first run and permissions, themes, baseline profile. |
| [Session negotiation](session-negotiation.md) | Choosing and opening a camera session: routing, graph fallbacks, high-speed, night and lens-lock sessions, recovery. |
| [Recording lifecycle](recording-lifecycle.md) | Record to finalized take: ownership, the capture state machine, pause, stop, sidecars, MediaStore, incomplete takes, Strict and Adaptive. |
| [GPU frame pipeline](gpu-frame-pipeline.md) | Camera frames through the GL pipeline to the viewfinder, encoder, analysis and cover display, with LOG/HLG transforms and HDR metadata. |
| [FPS LOCK cadence hold](fps-lock-cadence-hold.md) | Holding the shutter at 1/fps: native, vendor and emulated holds, the software ISO loop and lens switches. |
| [Lens calibration and lens lock](lens-calibration-and-lock.md) | Measuring each lens, applying the correction, and keeping a take on one physical lens with its crop fallback. |
| [Fold displays](fold-displays.md) | Foldable posture, cover display sessions and roles, subject modes, split preview and self capture. |
| [WebDAV transfers](webdav-transfers.md) | The opt-in uploads: enrollment, the outbox, the HTTP exchange, automatic upload under WorkManager. |

## Diagrams

GitHub renders the diagrams with Mermaid 12.2.0. Keep new diagrams to `flowchart`, `sequenceDiagram` and `stateDiagram-v2`, quote labels that contain punctuation, avoid `;` inside sequence messages, and set no theme or colours so they read in dark mode. Check every diagram renders against that version before committing.
