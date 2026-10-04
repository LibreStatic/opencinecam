# ADR-0035: Full-FOV still from public physical streams

- Status: Accepted
- Date: 2026-10-04
- Related requirements: OCC-CAM-003, OCC-CAM-004, OCC-CAM-005, OCC-CAM-013
- Related plan: OCC-PLAN-069
- Extends ADR-0006 without changing public enumeration or the preview graph.

## Decision

Enumerate the compressed still sizes of every physical camera the active logical camera reports through `CameraCharacteristics.getPhysicalCameraIds()`, and offer in the RES panel the sizes that only a physical advertises, merged above the logical's own list. The megapixel-label dedup keeps them distinguishable (4096x3072 reads "13 MP" next to the logical 4000x3000 "12 MP").

Route only the still output stream to the owning physical with `OutputConfiguration.setPhysicalCameraId`; preview and analysis keep flowing from the logical device in the same session. A physical-only size is offered and routed inside a base zoom window around 1.0x only, and crossing that window rebuilds the graph, because a physical still stream is fixed to its sensor and cannot follow logical lens switches.

A graph whose physical routing is rejected retries exactly once with logical-only still sizes — the still readers are retired and rebuilt first — and never routes physically again for that graph. A degraded graph keeps the viewfinder alive; only the wider size is lost. A physical whose characteristics cannot be read costs only that physical's extra sizes.

## Evidence base

On the Razr Fold the logical back camera ("0") tops at 4000x3000 JPEG while its public physical "5" advertises 4096x3072; the stock Motorola camera's own 12 MP photo is 4096x3072 from that sensor view. A third-party probe session on 2026-10-04 configured logical "0" plus a `setPhysicalCameraId("5")` still stream and captured a JPEG that decodes to 4096x3072 (probe log and capture preserved outside the repository). The vendor's 50 MP output is produced by a private offline reprocess pipeline (input 4000x3000, output 8192x6144) and its quad-CFA vendor keys are not honored for third-party requests; neither is pursued here.

## Rejected alternatives

- Opening the hidden physical camera id directly with `openCamera`: the numeric id has no public contract tying it to the logical, unlike the ids the logical itself reports.
- Vendor remosaic or client-identity keys (`com.lenovo.moto.quadra_cfa.*`, `com.lenovo.moto.clientapp.*`): the HAL ignores or degrades them for unlisted third-party packages.
- Upscaling the logical 4000x3000 frame to 4096x3072 in-app: fabricates pixels the sensor does deliver through the public physical stream.
