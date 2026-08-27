---
plan_id: OCC-PLAN-061
title: "OCLog2 specification interchange and device qualification"
status: ConditionalReady
revision: 2
milestone: M14
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-036
  - OCC-PLAN-038
  - OCC-PLAN-039
  - OCC-PLAN-041
  - OCC-PLAN-045
  - OCC-PLAN-046
blocks:
  - OCC-PLAN-057
requirements:
  - OCC-COLOR-001
  - OCC-RAW-001
  - OCC-TEST-001
  - OCC-COLOR-002
adrs:
  - ADR-0018
  - ADR-0021
  - ADR-0022
  - ADR-0026
risks:
  - RISK-005
  - RISK-015
  - RISK-020
estimated_sessions: 3
expected_repo_state: buildable
created_by: GPT-5.6 Sol
supersedes:
  - OCC-PLAN-047
  - OCC-PLAN-048
  - OCC-PLAN-049
  - OCC-PLAN-050
---
# OCC-PLAN-061: OCLog2 specification interchange and device qualification

## 1. Objective

Reconcile the launcher-reachable OCLog2 implementation with the accepted Log architecture by freezing its specification, publishing independent interchange artifacts, qualifying exact source branches on physical devices, and exposing only claims supported by the resulting evidence.

## 2. Why This Plan Exists

OCC-PLAN-047 through OCC-PLAN-050 described the unpromoted OCLog1 candidate. Live capture later moved to OCLog2 with separate HLG10-derived and SDR ISP-derived tiers, so completing the OCLog1 sequence would no longer validate the shipped signal path. This plan supersedes that sequence without rewriting its historical records.

## 3. Prerequisites

OCC-PLAN-036, OCC-PLAN-038, OCC-PLAN-039, OCC-PLAN-041, OCC-PLAN-045, and OCC-PLAN-046.

Conditional gate: Input: immutable OCLog2 specification and hashes, independent CPU/GPU/LUT/OCIO/DCTL agreement, exact physical recorded-file fixtures, and source-provenance metadata for each proposed tier. Pass: qualify only exact size/FPS/source/fingerprint tuples whose numerical, file, editor, and sustained-device checks pass. Fail: retain the runtime branch as explicitly experimental or unavailable and make no verified Log claim.

## 4. Required Reading

- `docs/color/opencine-log-v1.md` and `docs/color/opencine-log-v2.md`.
- `docs/architecture.md`: color pipeline and evidence promotion boundaries.
- `docs/adr/ADR-0018-sdr-hlg10-main10-and-color-truth.md`.
- `docs/adr/ADR-0021-deferred-native-code-and-opengl-es-backend.md`.
- `docs/adr/ADR-0022-opencine-log-specification-process.md`.
- `docs/adr/ADR-0026-test-evidence-promotion-and-certification.md`.

## 5. Inputs

Current OCLog2 Kotlin/shader implementation and sidecars; OCC-PLAN-036 file/depth evidence; Camera2 HLG10/BT2020_HLG and constrained-high-speed reports; exact release artifact; controlled charts/gradients; FFmpeg plus an independent editor workflow.

## 6. Deliverables

- Immutable OCLog2 domain, gamut, transfer, range, black/white/middle-gray semantics, source tiers, metadata schema, version, and transform hashes.
- Versioned independent numeric vectors plus CPU, shader/GPU, `.cube`, OCIO, and DCTL artifacts with declared tolerances and reproducible generators.
- Exact physical HLG10-derived and ISP-derived qualification bundles, including graph/request/result facts, sidecar, parseable file metadata, sustained recording, clipping/range, and editor round-trip results.
- Production gating and UI wording that distinguish verified, experimental, unsupported, and unknown tiers without equating Main10 output with source precision.

## 7. In Scope

OCLog2 specification and artifact publication, deterministic cross-implementation tests, existing GLES capture-path audit, exact source provenance, physical file/device/editor qualification, sidecar/schema updates, UI claim boundaries, and traceability/evidence updates.

## 8. Out of Scope

Reviving OCLog1 for new capture, claiming RAW-derived Log while RAW-video remains failed, vendor/private camera APIs, inventing sensor color science, APV integration, unrelated grading features, or certifying devices not present in the evidence bundle.

## 9. Architectural Constraints

OCLog1 remains immutable for reproducibility of earlier experiments. OCLog2 changes require a new version/hash. HLG10-derived and SDR ISP-derived tiers remain distinct. Source precision is never inferred from HEVC Main10 output. Unknown provenance cannot become verified through codec or container metadata alone.

## 10. Implementation Steps

1. Freeze the OCLog2 normative document and machine-readable specification, including middle gray, range, gamut matrices, rounding, legal inputs, and shader identities.
2. Move test vectors out of self-generated assertions into versioned fixtures produced by an independent high-precision reference.
3. Generate and hash CPU, GLES, 1D/3D LUT, OCIO, and DCTL artifacts; compare forward/inverse behavior over boundary, ramp, random, and out-of-range suites.
4. Audit the runtime selector so capability advertisement alone cannot promote a source tier; preserve experimental/unavailable state until its exact evidence tuple passes.
5. Record controlled physical clips for every proposed size/FPS/source tier and retain request/result, dataspace, sidecar, shader, encoder, thermal, dropped-frame, and file metadata.
6. Run FFmpeg and at least one independent editor round trip; measure clipping, range, monotonicity, chromatic error, and transform tolerance.
7. Update schemas, UI copy, profiles, traceability, execution record, and downstream OCC-PLAN-057 dependency only for branches actually qualified.

## 11. Expected File and Module Changes

Primary paths: `docs/color/`, `camera/src/main/java/com/librestatic/opencinecam/camera/`, `camera/src/test/`, `native-pipeline/`, `desktop-tools/`, `tools/`, `app/src/main/`, `docs/schemas/`, and privacy-safe evidence manifests.

## 12. State and Data-Flow Changes

Each runtime OCLog2 profile carries an immutable specification version, transform hash, source tier, evidence stage, exact camera/profile tuple, and safe reason code. Qualification consumes recorded fixtures and produces profile evidence; it never mutates recorded media or silently broadens a qualified tuple.

## 13. Error and Fallback Behavior

Missing specification/artifacts/fixtures yields `NOT_RUN` or `UNKNOWN`; numeric, sustained-recording, metadata, or editor failure yields `FAILED`; unsupported hardware yields `UNSUPPORTED`. Strict rejects an unqualified requested tier. Adaptive may offer AVC/HEVC SDR or HLG only through an explicit, recorded user-visible transition.

## 14. Tests

- Golden-vector and dense-domain agreement across independent CPU, GLES, LUT, OCIO, and DCTL implementations.
- Forward/inverse, boundary, clipping, invalid input, hash mismatch, stale evidence, duplicate, cancellation, and cleanup tests.
- Instrumented graph/session/recording tests for exact physical profiles.
- `ffprobe`/MediaInfo and editor round-trip validation of every recorded fixture.
- UI tests proving experimental/unknown/unsupported tiers cannot appear verified.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict lint test assembleDebug`
- `./tools/check_format.sh`
- Native/desktop reference and artifact-generation test commands introduced by this plan.
- `ffprobe -v error -show_streams -show_format <oclog2-fixture>` plus the documented independent editor workflow.
- Exact connected-device collector and sustained-recording commands for every proposed tuple.

Record skips as explicit `NOT_RUN`; never report an unexecuted interoperability or device command as passing.

## 16. Acceptance Criteria

- [x] The OCLog2 normative specification and independent versioned vectors/artifacts are complete and hash-pinned.
- [ ] CPU, GPU/shader, LUT, OCIO, and DCTL implementations pass declared forward/inverse tolerances.
- [ ] Every promoted source tier has exact physical sustained-recording, sidecar, file, and editor evidence for its fingerprint/profile tuple.
- [x] Runtime gating and UI language expose no broader provenance, precision, gamut, range, or device claim than the accepted evidence.
- [ ] Required host/build/device/editor commands pass and privacy-safe evidence manifests remain verifiable.

## 17. Evidence to Record

Specification/artifact hashes, independent vector provenance, test reports, shader identities, graph/request/result JSON, exact fingerprint/profile, sustained-window telemetry, sidecars, file metadata, FFmpeg/editor results, clipping/range measurements, and artifact/source digests.

## 18. Rollback and Recovery

If qualification fails, retain the immutable OCLog2 experimental artifacts for reproducibility, downgrade the affected runtime tier to experimental/unsupported without deleting failure evidence, restore the last qualified UI/profile state, and rerun baseline tests. Do not fall back to OCLog1 capture.

## 19. Risks and Mitigations

RISK-005 is mitigated by independent color/file validation; RISK-015 by bounded native/GPU execution and exact shader hashes; RISK-020 by refusing promotion without independent physical evidence and preserving failed/unknown outcomes.

## 20. Completion Update

Mark individual criteria, update `manifest.yaml`, `README.md`, `TRACEABILITY.md`, color/schema documents, profile evidence, OCC-PLAN-057 dependency status, and the execution record. Qualified claims must name exact artifact, fingerprint, profile, source tier, and protocol.

## 21. Execution Record

- Status: ConditionalReady (host specification, artifacts, qualifier, and fail-closed runtime gating complete; physical/editor gate pending)
- Started: 2026-08-26T22:55:00-03:00
- Completed:
- Executor: GPT-5.6 Sol
- Commits:
- Evidence: `docs/color/oclog2-v2/manifest.json`, `evidence/plan-061/qualification-input.json`, `evidence/plan-061/qualification-result.json`, `evidence/plan-061/qualifier.log`.
- Deviations: The available target is the API 33 `sdk_gphone64_x86_64` emulator and no independent editor is installed. The qualifier therefore records `NOT_RUN` for the physical target, GPU/3D LUT/OCIO/DCTL execution, sustained clip, hashed sidecar/file metadata, FFmpeg clip workflow, and editor workflow. The launcher-reachable runtime remains experimental; no source/profile tuple is promoted.
- Supersedes: OCC-PLAN-047, OCC-PLAN-048, OCC-PLAN-049, OCC-PLAN-050.
- Follow-up plans: OCC-PLAN-057 consumes only the source/profile tiers that this plan qualifies.
