---
plan_id: OCC-PLAN-063
title: "Shared settings and session torch"
status: InProgress
revision: 2
milestone: H1
intended_executor: Codex
execution_mode: implementation
depends_on:
  - OCC-PLAN-015
blocks: []
requirements:
  - OCC-PRO-001
  - OCC-PRO-002
adrs:
  - ADR-0030
risks:
  - RISK-020
estimated_sessions: 2
expected_repo_state: buildable
created_by: Codex
---
# OCC-PLAN-063: Shared settings and session torch

## 1. Objective

Implement the first technical unit of the approved camera/foldable program without declaring the entire program or a release finished.

## 2. Why This Plan Exists

The previous settings list duplicated state with the viewfinder and excluded torch from LOG. Intensity, readable navigation and requested/reported separation were missing.

## 3. Prerequisites

OCC-PLAN-015 runtime. Baseline source 1110b20. The approved proposal remains the H1–H5 product contract.

## 4. Required Reading

Proposal sections 8–14, CameraSettings, CaptureService, Camera2PreviewEngine, ADR-0030.

## 5. Inputs

Existing source, clean baseline archive, Android SDK, host unit tests and API 30 emulator. Physical torch qualification remains open.

## 6. Deliverables

SettingsRepository; searchable categories; accessible controls; persisted monitor/timecode configuration; capability-aware torch intensity and quick access; nonfatal rejection path; tests and rollback evidence.

## 7. In Scope

H1 CAM-01 continuous light and the UI-01/02/03 foundation. Preserve existing flash key semantics and migrate additively.

## 8. Out of Scope

This unit does not implement H2–H5. They remain requested, not cancelled. Photographic flash belongs with PHO-01. No final APK publication.

## 9. Architecture

ADR-0030. SettingsRepository stores requested state; CameraUiState exposes effective state and pending flag. CaptureService applies live preferences without mutating a recording format.

## 10. Implementation Steps

1. Preserve the baseline.
2. Centralize state and persistence.
3. Reorganize settings and quick controls.
4. Add capability-aware torch requests and nonfatal rejection.
5. Run unit, emulator and lint checks.
6. Record physical acceptance as pending until executed.

## 11. State and Data

Null torch level means the camera default. Level clamping occurs against the active camera. Missing CaptureResult keys remain null. Structural preferences become effective after finalization.

## 12. Failure Handling

Reject unsupported/HFR intensity; retain on/off on fixed-level devices. Revert the attempted repeating light request on rejection and report it without discarding video. Preserve previous data on source rollback.

## 13. Tests

TorchControlTest, SettingsRepositoryTest, SettingsCatalogTest, SettingsPersistenceTest, SettingsHubTest, CaptureAdaptiveUiTest. A preexisting landscape test lacked a landscape viewport; baseline reproduction is retained before repairing its fixture.

## 14. Documentation Updates

Proposal decisions and execution status; requirements OCC-PRO-001/002; ADR-0030; manifest/index/traceability. Do not mark physical criteria Done based on host tests.

## 15. Commands to Run

```bash
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:testDebugUnitTest :camera:testDebugUnitTest :app:lintDebug :camera:lintDebug
rtk proxy env ANDROID_HOME=$HOME/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.librestatic.opencinecam.SettingsHubTest,com.librestatic.opencinecam.SettingsPersistenceTest,com.librestatic.opencinecam.CaptureAdaptiveUiTest
rtk proxy ./tools/check_format.sh
```

## 16. Acceptance Criteria

- [ ] New unit tests and selected instrumented tests pass.
- [ ] Lint and formatting checks pass for the changed source.
- [ ] A separate restored copy passes baseline tests.
- [ ] Physical device verifies advertised light levels, ten on/off cycles and SDR/LOG recording continuity.
- [ ] Settings accessibility is verified at 100/150/200% font scales and with TalkBack.

## 17. Evidence to Record

Local build/implementation-h1 contains baseline/hash, modified archive, diff, exact logs, VERIFICATION.txt and executable ROLLBACK.sh. These are development evidence, not physical-camera certification.

## 18. Rollback and Recovery

Restore touched paths from the preserved source baseline on an independent copy. The rollback script verifies modified-file hashes before restoration; the working implementation remains changed. App media and preferences are not deleted.

## 19. Risks and Mitigations

HAL request rejection and unknown result keys retain explicit outcomes. Constrained HFR stays disabled for light. Capability advertising does not prove luminance or sustained cadence. Shared requested/effective state avoids structural changes in a clip.

## 20. Completion Update

Keep InProgress until every acceptance criterion is met. H2 foldable screen roles and continuity follow next, then H3–H5; the user requested one final APK after the whole program.

## 21. Execution Record

Started: 2026-09-05. Code implementation in progress. The clean baseline host suite passed. Detailed final run results are recorded in build/implementation-h1/VERIFICATION.txt. No physical light or foldable-screen result is claimed.


Revision2 parallel accessibility integration: named slider level/action semantics, native progress action, discrete increase/decrease buttons and keyboard focus/Enter/Tab/arrow behavior. A native48dp regression exposed Material3 internal track constraints shrinking the actual progress node despite outer heightIn. Sizing the measured native thumb slot corrects the real node, while preserving native slider semantics. Full-width level/reported labels avoid horizontal wrap-content measurement overflow at fontScale2. All six dedicated native cases now pass on API30, including48dp reachability and zero text overflow; actual TalkBack and physical luminance remain pending. Retained failing diagnostics and corrected pass: build/implementation-h4-timecode-continuity/VERIFICATION.txt. Full shared regression remains in progress.


Final shared acceptance:566 unit and179 native cases pass; app/camera lint has zero errors or new warnings. Exact commands, retained failures, native media inspection, original/modified/rollback results and source hashes: build/implementation-h4-timecode-continuity/VERIFICATION.txt. Full plan remains InProgress; this checkpoint does not close physical/editor or outstanding application-integration gates.
