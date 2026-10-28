# ADR-0036: Lens lock by opening reported physical cameras

- Status: Accepted (2026-10-28: gate G0 of OCC-PLAN-070 passed on the Razr Fold, and lens lock shipped in 1.0.0)
- Date: 2026-10-23
- Related requirements: OCC-CAM-003, OCC-CAM-004, OCC-CAM-014
- Related plan: OCC-PLAN-070
- Extends ADR-0006 and supersedes in part ADR-0035 (its "Opening the hidden physical camera id directly with `openCamera`" rejected alternative).

## Decision

A lens-locked session may open a physical camera id directly with `CameraManager.openCamera` only if that id is listed by the logical camera's `CameraCharacteristics.getPhysicalCameraIds()`. Any other id, including hidden HAL devices that no logical camera reports, is never opened.

The session descriptor of a lens-locked session is built from the physical camera's own `CameraCharacteristics` (zoom range, stream maps, 10-bit and HLG support, stabilization modes, AF range), never from the logical camera's. The app keeps addressing the camera by the logical id: `selectedCameraId`, per-camera zoom, presets, clip profile, device-profile quirks and vendor-key resolution stay keyed by the logical id, carried by a lens-lock binding (logical id, physical id, anchor ratio). The UI keeps the logical zoom scale (UI ratio = anchor ratio x physical ratio), so the telephoto lens still reads 2x to 16x.

If opening the physical camera or configuring its session fails, the engine falls back automatically to a logical session with `CONTROL_ZOOM_RATIO` pinned at the anchor ratio, crops digitally on the GPU (UV scale in the texture matrix) and recrops JPEG/HEIC and RAW (`DefaultCrop`) to match. The fallback is reported to the user, and a warning is shown if the HAL still switches lenses (for example in low light). Lens lock is excluded from direct high-speed sessions and from the night extension.

## Evidence base

The HFR quirk path already opens a reported physical id with `openCamera` on shipped builds (`Camera2PreviewEngine`, around the high-speed session start), and the Razr Fold dump (2026-10-04) lists logical "0" with `physicalIds = [3, 2, 5]`. That precedent keeps the logical descriptor, which breaks the zoom range, cached vendor keys, quirks and DNG matrices; this decision keeps the physical open but fixes the descriptor. Whether the HAL grants third parties a direct physical session with 10-bit, HLG, EIS and the video sizes of the logical camera is unproven and is measured by gate G0 of OCC-PLAN-070 before the lens-lock slice is built.

## Why ADR-0035's rejection no longer holds

ADR-0035 rejected opening "the hidden physical camera id" because the numeric id had no public contract tying it to the logical camera. That is true for ids that only appear in HAL dumps. An id returned by `getPhysicalCameraIds()` of the logical camera is tied to it by the platform's own reported member list, which is the same public enumeration ADR-0006 and ADR-0035 already trust for routing `OutputConfiguration.setPhysicalCameraId`. Restricting direct opens to that list keeps the ADR-0006 rule (identity only from `CameraManager` and `CameraCharacteristics`) and removes the objection. ADR-0035's other decisions and rejected alternatives are unchanged.

## Rejected alternatives

- Pinning the lens only with logical-session request keys: `CONTROL_ZOOM_RATIO` alone leaves the switch to the HAL, and vendor keys (`com.qti.chi.multicamerainfo.MasterCamera`, `org.quic.camera.forceFullFOV.ForceFullFOV`, `com.lenovo.moto.SATFallback.W2UW`, `com.qti.chi.transitionMode.TransitionMode`) are unverified. They are probed in G0 and, if one fixes the lens reliably, may be added as a preferred path through `resolveVendorKey` under the ADR-0006 amendment; direct open stays the fallback-backed route.
- Always cropping a pinned logical zoom on the GPU: loses the optical sensor choice and the physical lens's native resolution, so it is only the fallback.
- Copying the HFR pattern (change only `cameraIdToOpen`, keep the logical descriptor): rejected because it breaks zoom range, vendor keys, quirks and DNG matrices.
- Opening unreported physical ids: no public contract, unchanged from ADR-0035.

## Consequences

- Positive: a user can zoom digitally without the HAL changing lens, colour or exposure mid-shot.
- Negative: the engine owns two descriptor sources (logical and physical) and a fallback path; each device needs the G0 evidence before lens lock is offered.
- Operational: the result is recorded in `docs/device-validation.md`; if G0 shows third parties cannot open the physicals, lens lock ships only as the GPU-crop mode and this ADR is revised.
