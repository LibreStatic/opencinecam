---
plan_id: OCC-PLAN-064
title: "Fold display sessions, roles and continuity"
status: InProgress
revision: 6
milestone: H2
intended_executor: Codex
execution_mode: implementation
depends_on:
  - OCC-PLAN-015
blocks: []
requirements:
  - OCC-PRO-003
  - OCC-PRO-004
adrs:
  - ADR-0004
  - ADR-0031
risks:
  - RISK-004
estimated_sessions: 3
expected_repo_state: buildable
created_by: Codex
---
# OCC-PLAN-064: Fold display sessions, roles and continuity

## 1. Objective

Implement public foldable display discovery, explicitly owned exterior sessions, subject status/prompter, rear-screen transfer and hinge-aware layouts. Continue toward complete H2 without promoting unmeasured double-camera preview.

## 2. Why This Plan Exists

The approved program places foldables after settings/light. H1 source tests passed; physical lighting qualification remains independent and open. Existing display continuity is retained rather than replaced.

## 3. Prerequisites

OCC-PLAN-015; H1 shared-settings implementation from OCC-PLAN-063. Physical H1 qualification is not a prerequisite to discovering public display capabilities. Existing source is buildable.

## 4. Required Reading

ADR-0004, ADR-0030, ADR-0031, proposal FOLD-01 through FOLD-05, service preview attach/detach and GPU pipeline render ownership.

## 5. Inputs

Preserved H1 workspace under build/implementation-h2/BASELINE.tar; WindowManager 1.5.1; Android SDK; API 30 emulator; physical device requested from the user.

## 6. Deliverables

FoldSessionStateMachine, FoldDisplayCoordinator, subject status/prompter content, searchable display preferences, transfer/return actions, hinge panes and close-policy detector. Tests and reversible source evidence accompany the unit.

## 7. In Scope

FOLD-01 discovery; FOLD-03 transfer foundation; FOLD-04 layouts/close policy; FOLD-05 STATUS and TELEPROMPTER. Live preview now has a concrete GPU/service/window integration; physical simultaneous-screen qualification remains open.

## 8. Out of Scope

This unit does not complete all H2 acceptance. Reference/review modes, live multiview rendering and the remaining H3–H5 work stay in the active goal. No final APK release before the entire program is complete.

## 9. Architecture

ADR-0031. Windows own presentation; CaptureService owns capture. Activity transfer and dual-screen presentation use distinct capability gates. Late callbacks carry a generation and never affect a later session.

## 10. Implementation Steps

1. Preserve/test the H1 baseline.
2. Add pure state/pane/close-edge models and tests.
3. Integrate WindowAreaController and FoldingFeature lifecycle observation.
4. Add subject content and operator settings.
5. Preserve recording finalization and close policy.
6. Test on host/emulator and collect device evidence.
7. Implement/qualify live multiview next.

## 11. State and Data

Subject preferences are persistent; active sessions are not. Scripts/cues are local and bounded. Requested brightness is not a measured panel luminance. Capture finalization has an explicit pending flag.

## 12. Failure Handling

Unavailable capabilities disable start actions. Cancelled starts reject and close late sessions. Stale end/visibility callbacks are ignored. A disappearing presentation never issues capture stop. Hinge sensor absence does not imply a closed phone.

## 13. Tests

FoldDisplayTest; FoldDisplayUiTest; SettingsPersistenceTest; existing SettingsHubTest and CaptureAdaptiveUiTest. Device probe records public capability separately from hardware certification.

## 14. Documentation Updates

Requirements OCC-PRO-003/004, ADR-0031, manifest/index/traceability, approved proposal execution status and GOAL-PROGRESS.md. Preserve prior H1 evidence unchanged.

## 15. Commands to Run

```bash
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:testDebugUnitTest :camera:testDebugUnitTest :app:lintDebug
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.librestatic.opencinecam.FoldDisplayUiTest,com.librestatic.opencinecam.SettingsHubTest,com.librestatic.opencinecam.SettingsPersistenceTest,com.librestatic.opencinecam.CaptureAdaptiveUiTest
rtk proxy ./tools/check_format.sh
```

## 16. Acceptance Criteria

- [x] Pure state, pane, policy and preference tests pass.
- [x] Subject roles have no capture/settings actions and report finalizing distinctly.
- [x] UI and lint pass for the final source snapshot.
- [x] Rollback restores the independently tested H1 source (initial H2 evidence).
- [x] Bounded GPU backend passes host lease tests and seven synthetic GLES output/lifecycle tests.
- [ ] Physical device confirms transfer/presentation or an explicit unsupported branch.
- [ ] Live preview distributor passes independent-output/frame-age/recording-continuity tests.
- [ ] Physical hinge, closure, orientation, light and 20-cycle recording tests pass.

## 17. Evidence to Record

Source hashes, exact commands/exits, test reports, WindowManager capabilities, session events, physical device/firmware, output roles and camera/encoder continuity. Development snapshots are under build/implementation-h2.

## 18. Rollback and Recovery

Restore only the changed paths from the H1 baseline on a separate copy, verifying current hashes before any writes. Leave the active implementation modified. Display-session teardown releases windows without deleting media or issuing capture stop.

## 19. Risks and Mitigations

Public exterior APIs vary by firmware. A handset with two screens may advertise no usable WindowArea capability. Physical absence is not a global goal blocker while meaningful implementation/testing remains possible. Avoid introducing encoder stalls through unbounded auxiliary outputs.

## 20. Completion Update

Keep InProgress until full acceptance. Record individual supported/unsupported device branches. The active goal continues through remaining H2 and H3–H5, or an audited genuine blocker.

Backend continuation: `SubjectPreviewOutput`, `LatestFrameExchange`, `GpuEglDisplayLease` and explicit `PreviewQuad` are integrated into the GPU pipeline and tested through real EGL window buffers. The subsequent integration exposes preview mode and persisted mirror/view-assist controls, connects generation/identity-scoped service output leases, expires frame status in the UI, and uses a persistent SDR GPU preview graph. Direct/EGL transitions and operator/exterior lease continuity are tested through real Camera2 on the emulator. This remains InProgress: per-output LUTs and physical release qualification are still open. Self-recording controls are implemented in the continuation below.

## 21. Execution Record

Started 2026-09-05. Preserved H1 baseline passed host tests. Final source passed 245 app/camera unit tests and 40 emulator UI/probe tests; lint reported no errors. Public API probe on the API 30 emulator reports Window extension version 0, no window areas and no advertised hinge-angle sensor. Physical qualification remains NOT_RUN. Detailed acceptance/rollback results are in build/implementation-h2/VERIFICATION.txt. Only an emulator is attached at initial inventory; physical connection was requested asynchronously.

Self-recording continuation: the transferred operator role can use a minimal large-capture/lens/audio/return/settings deck or the full console. Settings persist a 0/3/5/10-second timer and minimal-controls preference. The service owns a monotonic, context/generation-bound countdown and shares remaining seconds with operator and subject UI. Role loss, preview teardown/reconfiguration, cancellation, changed camera/profile and an excessively late callback prevent delayed capture. Pending timers are never persisted. Source/evidence: build/implementation-h2-self/VERIFICATION.txt. Physical transfer, microphone-dialog, fold and shutter/encoder timing still require device acceptance.

Self-recording validation: 262 host tests and 57 isolated-emulator instrumentation tests passed; lint has zero errors. The independent rollback runs the 254-test integration baseline. Role/session models are tested, not physical WindowArea transfer on the API 30 emulator. A legacy license test now matches the full offline asset instead of an ambiguous substring.


Parallel integration review identified a separate cross-service same-window handoff gap. Engine close currently queues native retirement, while per-engine pending-preview admission does not serialize distinct engines. Recreating SurfaceView proves ordinary new-window service recreation, not reuse of the still-retiring native window. Next correction requires an explicit engine retirement completion and process-scoped asynchronous capture-owner admission (no UI wait/timeout-based release), token/generation cancellation for queued preview requests and checked operator EGL disconnect/destruction. Recheck closure inside queued GL attach callbacks to prevent resurrection after cleanup. The auxiliary display consumer retains its independent EGL display lease; do not block operator admission on an unrelated stalled subject swap. Required regression: distinct service binders reusing exactly the same Surface, deterministic held-retirement/latch, responsive UI, late callbacks, canceled waiting owner, third owner, invalid surface and failed retirement. This review changed no engine production code in this checkpoint.


Revision5 settings-pane integration: a settings-specific boundary selector prefers the usual bottom/right contiguous pane when usable, otherwise the larger safe side, without requiring both panes to meet the camera preview minimum. Window-origin subtraction uses bounded integer arithmetic; absolute placement keeps physical geometry unchanged under RTL. Twelve unit cases include3390 generated boundaries. All four native cases passed the targeted integration, including a narrow opposite pane, RTL and retained edit state. The top-pane fixture now supplies actual window-coordinate hinge bounds using the measured host origin; its expected geometric boundary/tolerance was not changed. Full shared regression remains in progress.


Final shared acceptance:566 unit and179 native cases pass; app/camera lint has zero errors or new warnings. Exact commands, retained failures, native media inspection, original/modified/rollback results and source hashes: build/implementation-h4-timecode-continuity/VERIFICATION.txt. Full plan remains InProgress; this checkpoint does not close physical/editor or outstanding application-integration gates.


Revision6 implementation connects process-wide capture owner admission to every Camera2 engine's lazy first preview. Constructing an unused engine reserves no native owner. A successor's latest preview waits for actual predecessor retirement; closing a queued owner does not let a third overtake the original live owner. Public readiness/close/release futures are defensive views, so cancelling or completing an observer never grants native ownership. Engine closure waits for pending camera opening/onClosed, GPU retirement and failed-constructor cleanup receipts; failures do not certify release. There is no UI wait, deadline-based force release or setting that bypasses this invariant. Operator EGL detach/destruction is checked, and queued attach/detach requests recheck closure/generation before touching native windows. Auxiliary display retirement keeps its independent display lease rather than blocking an already retired operator.

Verification in progress: deterministic pure owner queue tests, actual engines with held GL retirement and responsive UI, cancelled waiting owner, real initialization collision/cleanup, and RECORD_RUN/REGEN service recreation on exactly the same Surface while retaining the distinct-Surface tests. Evidence: build/implementation-h2-owner-admission/VERIFICATION.txt. These checks do not replace physical fold-cycle/cadence/thermal qualification.


The final central API30 regression passed all202 selected native tests, including four owner-admission cases and both same-Surface service continuations. Queued close is checked after its own native cleanup finishes but before predecessor release; an actual held GL queue delays handoff while UI stays responsive. Failed EGL initialization retains a native cleanup receipt and leaves the original occupant delivering fresh GPU analysis as well as capture metadata. Service reuse checks unchanged SurfaceHolder create/destroy counts, not only Java Surface identity. These are emulator integration results; physical fold/exterior qualification remains pending.
