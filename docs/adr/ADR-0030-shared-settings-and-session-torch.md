# ADR-0030: Shared settings and session-owned torch

- Status: Accepted
- Date: 2026-09-05
- Related requirements: OCC-PRO-001, OCC-PRO-002, OCC-CAM-007, OCC-CAM-009
- Related plan: OCC-PLAN-063

## Decision

An application-scoped SettingsRepository owns persistent intent. CaptureService observes the same stream as Compose. CameraUiState owns effective settings and reported camera values; preferences never certify hardware state. During recording, only light and monitoring preferences apply live; structural changes remain requested until the clip finishes.

The existing flash-enabled key continues to mean continuous torch, not photographic AUTO/ON flash. The app restores its on/off preference and requested level. Camera2 requests own the light while the session is open; CameraManager must not compete for the same camera. API 35 strength is enabled only when request support and torch characteristics advertise it. Earlier/fixed-level cameras retain on/off. Constrained high-speed sessions keep light unavailable pending qualification. Standard LOG follows the same capability-backed request path as SDR.

Rejected light updates attempt to restore the previous request and report a nonfatal light error; they never discard or finalize a clip. Unknown CaptureResult values remain unknown. Physical output and cadence still require device tests.

## Consequences

Categories, search, wrapping controls, 16 sp labels, 14 sp supporting text and at least 48 dp targets replace an undifferentiated settings list. Quick monitor controls and settings update the same preferences. Timecode persistence preserves existing configuration; this does not certify timecode file output or rational-FPS capture.

The phased product scope and final-APK release policy are recorded in the proposal. This ADR does not introduce networking, multiview rendering, photographic flash or new codecs. Those later stages retain their own architectural and physical acceptance criteria.

## Validation

Repository/catalog/torch unit tests; preference recreation and migration instrumentation; category/search and adaptive-layout tests; lint. Final acceptance additionally requires real torch luminance, LOG and recording-continuity measurements.

Reference: [Camera2 flash strength request](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest#FLASH_STRENGTH_LEVEL).
