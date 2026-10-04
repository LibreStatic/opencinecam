---
plan_id: OCC-PLAN-069
title: "Full-FOV still from public physical cameras"
status: InProgress
revision: 1
milestone: H2
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-015
blocks: []
requirements:
  - OCC-CAM-003
  - OCC-CAM-004
  - OCC-CAM-013
adrs:
  - ADR-0006
  - ADR-0035
risks:
  - RISK-001
estimated_sessions: 2
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-069: Full-FOV still from public physical cameras

## 1. Objective

Offer compressed still capture at the widest size any public physical camera of the active logical camera advertises — on the Razr Fold a 4096x3072 "13 MP" option above the logical 4000x3000 "12 MP" — by routing only the still stream to the owning physical with `OutputConfiguration.setPhysicalCameraId`, with an honest logical-only fallback when a device rejects the routed graph.

## 2. Why This Plan Exists

The Razr Fold's back logical camera ("0") tops at 4000x3000 JPEG while its public physical "5" advertises 4096x3072; the stock Motorola camera's own 12 MP photo is exactly that 4096x3072 sensor view. A probe session on 2026-10-04 (log and JPEG preserved outside the repository) proved a third-party app can configure logical "0" plus a physical "5" still stream in one session and capture a JPEG that decodes to 4096x3072. ADR-0006 already mandates routing with `OutputConfiguration` and auditing the reported active physical; this plan connects that existing architecture to the RES panel's still-size list. The vendor's 50 MP path (private offline reprocess, input 4000x3000, output 8192x6144, quad-CFA keys ignored for third parties) is out of reach by design and is not pursued.

## 3. Prerequisites

- `main` history that carries the photo RES panel (`feat/res-slot`, d4f9f13).
- ADR-0035 accepted; OCC-CAM-013 accepted.
- Razr Fold available over `adb` for the final physical gate (user handles the hardware).
- U0 probe evidence (2026-10-04): session configured and captured REAL 4096x3072 via logical "0" + physical "5".

## 4. Required Reading

ADR-0006 (public enumeration and physical routing), ADR-0035, `docs/architecture.md` "Camera and stream graphs", PLAN-015 (graph negotiation), PLAN-066 E5/E8 (still formats and aspect framing), `docs/device-validation.md`.

## 5. Inputs

- Razr Fold camera dump (2026-10-04): 7 HAL devices, logical "0" with `physicalIds = [3, 2, 5]`; physical "5" publishes JPEG/HEIC/YUV up to 4096x3072 and RAW 4096x3072; physical "2" tops at 4000x3000.
- U0 probe (`~/.cache/claude-tmp/libremagic/qcfa-results/fullfov-u0/`): three-session matrix, all configured, `cam0_p5_4096.jpg` decodes to 4096x3072.

## 6. Deliverables

- `Camera2CameraDescriptor.physicalJpegSizes` / `physicalHeicSizes` maps plus `isPhysicalOnlyStill`.
- `physicalStillRouting` in `StillSizeChoices.kt`.
- Engine: `compressedStillPlan` (size + owning physical), still-output routing, one-shot logical-only retry that retires the still readers first, per-graph `physicalStillFallbackTaken`.
- State/UI: physical sizes merged into `availableStillSizes` inside `PHYSICAL_STILL_ZOOM_WINDOW` (0.95x–1.05x), graph rebuild when the window is crossed while a physical-only size is active.
- Unit tests for routing, merge and labelling; evidence of the physical gate on the Razr Fold.

## 7. In Scope

PHOTO/BURST/BRACKET/LIGHT_TRAIL compressed stills (JPEG and HEIC), the RES panel list, session routing and fallback, the zoom-window gate, tests and the Razr physical gate.

## 8. Out of Scope

RAW full-FOV (RAW_PHOTO stays at the logical readout), the vendor 50 MP remosaic or any vendor tag, HEIC-specific physical quirks beyond the shared fallback, persistence of `targetStillSize` (unchanged: not persisted), and `LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID` result wiring beyond the routing log (follow-up).

## 9. Architecture

```mermaid
flowchart LR
    L[Logical camera "0"<br/>preview + analysis] --> S[Session]
    P[Physical "5" map:<br/>4096x3072 only it advertises] --> R[RES panel 13 MP<br/>above 12 MP]
    R --> S2[compressedStillPlan:<br/>size + owning physical]
    S2 -->|setPhysicalCameraId "5"| S
    S -->|rejected once| F[logical-only retry,<br/>physical routing retired for the graph]
    Z[zoom crosses 0.95x..1.05x] --> G[graph rebuild;<br/>RES list drops physical sizes]
```

The physical size enters only the still output; the graph negotiator, preview and analysis stay untouched. The zoom window exists because a physical still stream is fixed to its sensor and cannot follow logical lens switches.

## 10. Implementation Steps

- U1 Descriptor: enumerate per-physical extra compressed sizes via `getPhysicalCameraIds` + each physical's public map; failures cost only that physical. Done 2026-10-04.
- U2 Engine: `compressedStillPlan` picks the active size and its owning physical (`physicalStillRouting`, deterministic smallest-id tie-break); the still `OutputConfiguration` routes when not already fallen back; a rejected graph retries once with logical-only sizes after retiring the still/raw/analysis readers. Done 2026-10-04.
- U3 State/UI: `availableStillSizes` merges physical sizes inside `PHYSICAL_STILL_ZOOM_WINDOW`; `setZoomRatio` rebuilds the graph when the window is crossed while a physical-only size is active. Done 2026-10-04.
- U4 Tests: camera routing tests, app merge/label test, full host suites. Done 2026-10-04.
- U5 Physical gate on the Razr Fold: install with `adb -s`, confirm the panel lists 13 MP above 12 MP at 1.0x, capture, and verify the file decodes to 4096x3072; confirm the 4000x3000 path still captures. Open until run with the user.

## 11. State and Data

No persisted settings change. New in-memory state: `physicalJpegSizes`/`physicalHeicSizes` in the descriptor (rebuilt on attach), `physicalStillFallbackTaken` in the engine (reset per graph), and the derived panel list which now depends on `zoomRatio`.

## 12. Failure Handling

A physical whose characteristics read fails contributes no sizes. A graph rejected with a routed still retries exactly once logical-only (readers retired first) and never routes physically again for that graph; the viewfinder survives and only the wider size is lost. Zoom outside the base window hides physical sizes and the next attach degrades the session on its own.

## 13. Tests

- `StillSizeChoicesTest`: physical routing picks the owning physical, prefers the logical map, breaks ties by smallest id.
- `ResolutionGroupsTest`: the merged Razr list keeps 4096x3072 above 4000x3000 and labels it "13 MP".
- Full host suites: `:camera:testDebugUnitTest` (722), `:app:testDebugUnitTest` (1404), `:media:testDebugUnitTest`, `:core:model:test`.
- Instrumented emulator coverage is unchanged by this plan; the physical behaviour gate runs on the Razr Fold (U5).

## 14. Documentation Updates

This plan, ADR-0035, OCC-CAM-013 in `docs/requirements.md`, the ADR index, `docs/plans/manifest.yaml`, `docs/plans/README.md`, `docs/plans/TRACEABILITY.md`, `docs/plans/GOAL-PROGRESS.md`.

## 15. Commands to Run

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk
flock ~/.cache/claude-tmp/opencinecam/gradle.lock ./gradlew --no-daemon -Dorg.gradle.parallel=false --max-workers=2 -Dorg.gradle.caching=false :camera:testDebugUnitTest :app:testDebugUnitTest :media:testDebugUnitTest :core:model:test :app:lintDebug
./tools/check_format.sh
python3 tools/validate_plan_system.py
adb -s <razr-serial> install -r app/build/outputs/apk/debug/app-debug.apk
```

Note: default-parameter changes to `Camera2CameraDescriptor`/`CameraUiState` can serve stale test classes from the local build cache after switching branches; `-Dorg.gradle.caching=false` plus removing the module `build/` directory gives a clean verdict.

## 16. Acceptance Criteria

- [x] The descriptor exposes per-public-physical compressed still sizes that only those physicals advertise.
- [x] The still stream routes to the owning physical through `OutputConfiguration.setPhysicalCameraId` while preview and analysis stay on the logical device.
- [x] A rejected routed graph retries exactly once with logical-only sizes and keeps the viewfinder alive.
- [x] The RES panel lists 4096x3072 as "13 MP" above "12 MP" while zoom is in the base window, and drops physical sizes outside it.
- [x] Host suites pass: camera 722, app 1404, media, core; lint and format clean.
- [ ] On the Razr Fold, OpenCineCam captures a JPEG that decodes to 4096x3072 from the 13 MP option.
- [ ] On the Razr Fold, the 4000x3000 "12 MP" path still captures unchanged.

## 17. Evidence to Record

U0 probe log and capture summary (already preserved outside the repository, referenced from section 5), the U5 device photo EXIF/dimensions, and the U4 suite exit codes in the execution record.

## 18. Rollback and Recovery

Revert the single implementation commit (six files: engine, choices, two tests, state, service); no persisted data or schema is touched, and the panel returns to logical-only sizes.

## 19. Risks and Mitigations

- HAL variance: a vendor rejecting mixed routed graphs — mitigated by the one-shot logical-only retry.
- Zoom-crossing rebuild latency: bounded to one re-attach per window crossing.
- Misleading panel on devices whose physical extras duplicate logical sizes — the label dedup already collapses them.
- Local stale-cache false failures during development — documented command flag in section 15.

## 20. Completion Update

Open. U1–U4 landed on `feat/plan-069-full-fov-still`; U5 physical gate pending a Razr session with the user.

## 21. Execution Record

2026-10-04 (Claude Code, main session): U0 probe on the Razr Fold confirmed logical "0" + physical "5" routing (session configured, JPEG decoded 4096x3072). U1–U4 implemented and verified: `:camera:testDebugUnitTest` 722 passed, `:app:testDebugUnitTest` 1404 passed, `:media:testDebugUnitTest` and `:core:model:test` passed, `:app:lintDebug` and `./tools/check_format.sh` clean (all with `-Dorg.gradle.caching=false` after removing module build directories). U5 remains open; the plan stays InProgress until the physical gate records its evidence.
