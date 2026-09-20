# OpenCineCam Implementation Plans

Production reachability remediation is recorded in
[`PRODUCTION-REMEDIATION-2026-08-20.md`](PRODUCTION-REMEDIATION-2026-08-20.md) without renumbering
the original 60-plan dependency graph. Follow-up plans retain monotonically increasing IDs and
supersede obsolete work without rewriting its historical execution record.

## Purpose and source hierarchy

Accepted requirements → Accepted ADRs → architecture and schemas → implementation plans → code and evidence. An ADR wins over a conflicting plan; revise the plan before execution.

## Statuses

`Draft`, `Ready`, `ConditionalReady`, `InProgress`, `Blocked`, `Done`, `Superseded`, `Cancelled`. Ready means architecture is closed; executability also requires all dependencies Done. ConditionalReady additionally executes its exact evidence gate and pass/fail branch.

## Luna execution contract

Luna reads the plan and referenced requirements/ADRs; verifies dependencies and starting state; marks InProgress; implements only scope; runs every command; records real evidence and deviations; updates traceability; leaves the repository buildable; and marks Done only when every criterion passes. A failed gate becomes its documented unsupported result, while missing external input becomes Blocked. Contradictory evidence stops work and proposes an ADR revision.

Plans are never renumbered. Supersede through a new ID and links. Unplanned expansion becomes a follow-up plan.

## Milestones, order, and parallelism

- M0 foundation → M1 models → M2 probes.
- M3 probe UX and M4 capture foundation can proceed in parallel after M2. M16 profile foundations can begin after its listed dependencies.
- M4 → M5/M7; M5 → M6; M5–M7 → M8; M4/M7/M8 → M9.
- M10 HLG and M11 native branch after their graph/media prerequisites; M12 RAW follows capture/native foundations.
- M13 container tooling uses M12 contracts; M14 Log uses M10–M13 evidence; M15 APV parallels RAW/Log after media/native foundations.
- M17 release consumes every shipped branch.

Capture-certification path: 001 → 004 → 007 → 013 → 014 → 015 → 016 → 017 → 018 → 020 → 027 → 029 → 030 → 033 → 034 → 035 → 036 → 061 → 057 → 060. Distribution branch: 058 → 059 → 062 → 060.

## Current execution

Current execution: OCC-PLAN-001 through OCC-PLAN-046, OCC-PLAN-051–056, and OCC-PLAN-058–059 are Done. OCC-PLAN-047–050 are Superseded historical OCLog1 host contracts. OCC-PLAN-061 has a hash-pinned OCLog2 specification/interchange package, tested fail-closed qualifier, experimental runtime/UI gating, a physical GLES numeric pass, and three cadence-gated process cold-start 32-second HLG-derived 1080p30/Main10 passes without inferred drops on a physical reference foldable. Earlier immediate-start runs exposed an initial dropped frame, so the test now requires a measured stable Camera2 cadence before opening the sustained window rather than ignoring that loss. No tuple is promoted: the ISP-derived tier, artifact-bound OCIO/DCTL/LUT execution, clipping/range measurements, and an independent editor remain open. OCC-PLAN-036 independently validates file signaling and a controlled 10-bit fixture while retaining effective precision UNKNOWN for the uncontrolled camera scene; OCC-PLAN-040 captures public RAW_SENSOR and validates a DNG with required tags/payload; OCC-PLAN-041 parses one public RAW10 frame while preserving its zero packed pixel stride; OCC-PLAN-042 records the required 60-second RAW10 throughput fail branch and keeps RAW-video mode disabled. Host contracts for OCC-PLAN-057 soak evaluation and OCC-PLAN-060 certification evaluation are implemented, but their exact physical execution remains open. The signed direct GitHub channel is operational; OCC-PLAN-062 owns the still-unexecuted F-Droid build-server and Google Play activation. APV correctly completed through its unsupported hardware branch. Blocked plans: none. Conditional plans: 060–062; Ready plan: 057, dependency-gated by completion of 061.

Historical execution artifacts under `evidence/` were intentionally removed during the privacy cleanup and are not restored by a clean checkout. Consequently, `tools/validate_plan_system.py` currently rejects the missing evidence paths referenced by Done plans even though the plan graph and statuses are otherwise intact. Before release certification, the project needs a durable privacy-safe evidence store or committed redacted manifests; weakening the validator or silently treating absent evidence as present is not acceptable.

Conditional-gate identity evidence can be collected with `python3 tools/device_gate.py`; it records exact serial/API/fingerprint facts when a device is attached and emits `NOT_RUN` without fabricating capability promotion when none is available.

## Ordered plan list

- [OCC-PLAN-001: Repository and Android build bootstrap](PLAN-001-repository-and-android-build-bootstrap.md) — M0 / Done
- [OCC-PLAN-002: CI quality and test baseline](PLAN-002-ci-quality-and-test-baseline.md) — M0 / Done
- [OCC-PLAN-003: Licensing provenance and reproducibility](PLAN-003-licensing-provenance-and-reproducibility.md) — M0 / Done
- [OCC-PLAN-004: Capability and evidence domain model](PLAN-004-capability-and-evidence-domain-model.md) — M1 / Done
- [OCC-PLAN-005: Report sidecar and profile serialization](PLAN-005-report-sidecar-and-profile-serialization.md) — M1 / Done
- [OCC-PLAN-006: Error taxonomy and Strict Adaptive policy engine](PLAN-006-error-taxonomy-and-strict-adaptive-policy-engine.md) — M1 / Done
- [OCC-PLAN-007: Camera enumeration and characteristics probe](PLAN-007-camera-enumeration-and-characteristics-probe.md) — M2 / Done
- [OCC-PLAN-008: Stream manual and HDR capability probe](PLAN-008-stream-manual-and-hdr-capability-probe.md) — M2 / Done
- [OCC-PLAN-009: Codec audio probe and cache invalidation](PLAN-009-codec-audio-probe-and-cache-invalidation.md) — M2 / Done
- [OCC-PLAN-010: Probe UI and adaptive foldable layout](PLAN-010-probe-ui-and-adaptive-foldable-layout.md) — M3 / Done
- [OCC-PLAN-011: Manual private report export and sharing](PLAN-011-manual-private-report-export-and-sharing.md) — M3 / Done
- [OCC-PLAN-012: Storage benchmark and result persistence](PLAN-012-storage-benchmark-and-result-persistence.md) — M3 / Done
- [OCC-PLAN-013: CaptureService actor and state machine](PLAN-013-captureservice-actor-and-state-machine.md) — M4 / Done
- [OCC-PLAN-014: SurfaceView preview transforms and overlays](PLAN-014-surfaceview-preview-transforms-and-overlays.md) — M4 / Done
- [OCC-PLAN-015: Logical physical routing and graph negotiation](PLAN-015-logical-physical-routing-and-graph-negotiation.md) — M4 / Done
- [OCC-PLAN-016: Lifecycle recreation disconnection recovery](PLAN-016-lifecycle-recreation-disconnection-recovery.md) — M4 / Done
- [OCC-PLAN-017: Video encoder selector and configuration](PLAN-017-video-encoder-selector-and-configuration.md) — M5 / Done
- [OCC-PLAN-018: Asynchronous drain and single-owner muxer](PLAN-018-asynchronous-drain-and-single-owner-muxer.md) — M5 / Done
- [OCC-PLAN-019: MediaStore SAF finalization and clip sidecars](PLAN-019-mediastore-saf-finalization-and-clip-sidecars.md) — M5 / Done
- [OCC-PLAN-020: AVC HEVC SDR recorder integration](PLAN-020-avc-hevc-sdr-recorder-integration.md) — M5 / Done
- [OCC-PLAN-021: Audio source and effect negotiation](PLAN-021-audio-source-and-effect-negotiation.md) — M6 / Done
- [OCC-PLAN-022: AAC encoding and muxer coordination](PLAN-022-aac-encoding-and-muxer-coordination.md) — M6 / Done
- [OCC-PLAN-023: A V clock drift and overrun diagnostics](PLAN-023-a-v-clock-drift-and-overrun-diagnostics.md) — M6 / Done
- [OCC-PLAN-024: Deterministic CaptureRequest composition](PLAN-024-deterministic-capturerequest-composition.md) — M7 / Done
- [OCC-PLAN-025: CaptureResult auditing and evidence events](PLAN-025-captureresult-auditing-and-evidence-events.md) — M7 / Done
- [OCC-PLAN-026: Manual controls and Hardware Truth HUD](PLAN-026-manual-controls-and-hardware-truth-hud.md) — M7 / Done
- [OCC-PLAN-027: Recording foreground service and notification](PLAN-027-recording-foreground-service-and-notification.md) — M8 / Done
- [OCC-PLAN-028: Foldable Activity and camera continuity](PLAN-028-foldable-activity-and-camera-continuity.md) — M8 / Done
- [OCC-PLAN-029: Thermal storage fault and soak hardening](PLAN-029-thermal-storage-fault-and-soak-hardening.md) — M8 / Done
- [OCC-PLAN-030: Analysis stream and performance governor](PLAN-030-analysis-stream-and-performance-governor.md) — M9 / Done
- [OCC-PLAN-031: Histogram zebra and false color](PLAN-031-histogram-zebra-and-false-color.md) — M9 / Done
- [OCC-PLAN-032: Focus peaking and waveform](PLAN-032-focus-peaking-and-waveform.md) — M9 / Done
- [OCC-PLAN-033: Vectorscope and monitoring LUT insertion](PLAN-033-vectorscope-and-monitoring-lut-insertion.md) — M9 / Done
- [OCC-PLAN-034: HLG10 Main10 candidate graph](PLAN-034-hlg10-main10-candidate-graph.md) — M10 / Done
- [OCC-PLAN-035: HDR preview monitoring and recording](PLAN-035-hdr-preview-monitoring-and-recording.md) — M10 / Done
- [OCC-PLAN-036: Main10 file and effective-depth qualification](PLAN-036-main10-file-and-effective-depth-qualification.md) — M10 / Done
- [OCC-PLAN-037: NDK JNI and AHardwareBuffer foundation](PLAN-037-ndk-jni-and-ahardwarebuffer-foundation.md) — M11 / Done
- [OCC-PLAN-038: OpenGL ES processing and encoder output](PLAN-038-opengl-es-processing-and-encoder-output.md) — M11 / Done
- [OCC-PLAN-039: Native crash performance and thermal hardening](PLAN-039-native-crash-performance-and-thermal-hardening.md) — M11 / Done
- [OCC-PLAN-040: Camera2 RAW still and DNG](PLAN-040-camera2-raw-still-and-dng.md) — M12 / Done
- [OCC-PLAN-041: RAW10 RAW12 buffers metadata and ring](PLAN-041-raw10-raw12-buffers-metadata-and-ring.md) — M12 / Done
- [OCC-PLAN-042: RAW throughput benchmark and device gate](PLAN-042-raw-throughput-benchmark-and-device-gate.md) — M12 / Done
- [OCC-PLAN-043: OpenCine RAW specification and fixtures](PLAN-043-opencine-raw-specification-and-fixtures.md) — M13 / Done
- [OCC-PLAN-044: Append-only writer journaling and recovery](PLAN-044-append-only-writer-journaling-and-recovery.md) — M13 / Done
- [OCC-PLAN-045: Desktop reader index recovery and DNG export](PLAN-045-desktop-reader-index-recovery-and-dng-export.md) — M13 / Done
- [OCC-PLAN-046: Container crash and interoperability validation](PLAN-046-container-crash-and-interoperability-validation.md) — M13 / Done
- [OCC-PLAN-047: OpenCine Log specification and test vectors](PLAN-047-opencine-log-specification-and-test-vectors.md) — M14 / Superseded by OCC-PLAN-061
- [OCC-PLAN-048: CPU GPU references LUT OCIO and DCTL](PLAN-048-cpu-gpu-references-lut-ocio-and-dctl.md) — M14 / Superseded by OCC-PLAN-061
- [OCC-PLAN-049: RAW P010 ISP-derived Log integration](PLAN-049-raw-p010-isp-derived-log-integration.md) — M14 / Superseded by OCC-PLAN-061
- [OCC-PLAN-050: Numerical editor and device Log qualification](PLAN-050-numerical-editor-and-device-log-qualification.md) — M14 / Superseded by OCC-PLAN-061
- [OCC-PLAN-051: APV probe and codec storage qualification](PLAN-051-apv-probe-and-codec-storage-qualification.md) — M15 / Done (accepted unsupported branch)
- [OCC-PLAN-052: APV Surface encode MP4 and file validation](PLAN-052-apv-surface-encode-mp4-and-file-validation.md) — M15 / Done (accepted unsupported branch)
- [OCC-PLAN-053: APV UI fallback and APV RAW verdict](PLAN-053-apv-ui-fallback-and-apv-raw-verdict.md) — M15 / Done (accepted unsupported branch)
- [OCC-PLAN-054: Device profile schema trust and invalidation](PLAN-054-device-profile-schema-trust-and-invalidation.md) — M16 / Done
- [OCC-PLAN-055: Bundled imported quirks and user overrides](PLAN-055-bundled-imported-quirks-and-user-overrides.md) — M16 / Done
- [OCC-PLAN-056: Community validation contribution and regression](PLAN-056-community-validation-contribution-and-regression.md) — M16 / Done
- [OCC-PLAN-057: Complete device fault and soak protocol](PLAN-057-complete-device-fault-and-soak-protocol.md) — M17 / Ready
- [OCC-PLAN-058: Security privacy and accessibility review](PLAN-058-security-privacy-and-accessibility-review.md) — M17 / Done
- [OCC-PLAN-059: F-Droid direct Google Play release and SBOM](PLAN-059-f-droid-direct-google-play-release-and-sbom.md) — M17 / Done
- [OCC-PLAN-060: Release candidate certification checklist](PLAN-060-release-candidate-certification-checklist.md) — M17 / ConditionalReady
- [OCC-PLAN-061: OCLog2 specification interchange and device qualification](PLAN-061-oclog2-specification-interchange-and-device-qualification.md) — M14 follow-up / ConditionalReady
- [OCC-PLAN-062: F-Droid and Google Play channel activation](PLAN-062-f-droid-and-google-play-channel-activation.md) — M17 follow-up / ConditionalReady

## Approved camera and foldable program

[OCC-PLAN-063](PLAN-063-settings-and-session-torch.md) is InProgress for the H1 settings/torch unit. The [approved proposal](../PROPOSAL-PRO-CAMERA-AND-FOLDABLES.md) retains H2–H5 and one final APK after completion of the whole program. Host/emulator checks do not close physical light or foldable acceptance.

[OCC-PLAN-064](PLAN-064-fold-display-sessions-and-continuity.md) is InProgress for public fold-display sessions and roles. The [active goal ledger](GOAL-PROGRESS.md) records remaining H1–H5 work and evidence without presenting a partial APK as the final release.

- [OCC-PLAN-065: Professional control intent and operation](PLAN-065-professional-control-intent-and-operation.md) — H3 / InProgress

- [OCC-PLAN-067: Opt-in WebDAV transfers and finalized capture bundles](PLAN-067-opt-in-webdav-transfers.md) — InProgress; transport exists, app wiring and full H5 acceptance remain open.
