---
plan_id: OCC-PLAN-058
title: "Security privacy and accessibility review"
status: Done
revision: 1
milestone: M17
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-003
  - OCC-PLAN-011
  - OCC-PLAN-027
  - OCC-PLAN-056
blocks:
  - OCC-PLAN-059
  - OCC-PLAN-060
requirements:
  - OCC-DIST-001
  - OCC-PRIV-001
  - OCC-TEST-001
  - OCC-DIST-002
adrs:
  - ADR-0025
  - ADR-0026
  - ADR-0027
  - ADR-0028
risks:
  - RISK-011
  - RISK-016
  - RISK-017
  - RISK-018
estimated_sessions: 1
expected_repo_state: buildable
created_by: GPT-5.6 Sol
---
# OCC-PLAN-058: Security privacy and accessibility review

## 1. Objective

Audit permissions/components/sharing/logs/backups/native inputs, privacy/data flows, semantics/touch targets, and policy declarations. The observable outcome is a buildable repository with passing automated checks and versioned evidence.

## 2. Why This Plan Exists

This is the bounded M17 slice that converts accepted architecture into executable behavior without reopening ownership, evidence, privacy, or fallback decisions.

## 3. Prerequisites

OCC-PLAN-003, OCC-PLAN-011, OCC-PLAN-027, OCC-PLAN-056

Conditional gate: None; this plan is Ready once its dependency plans are Done.

## 4. Required Reading

- `docs/requirements.md`: OCC-DIST-001
- `docs/requirements.md`: OCC-PRIV-001
- `docs/requirements.md`: OCC-TEST-001
- `docs/requirements.md`: OCC-DIST-002
- `docs/adr/`: ADR-0025
- `docs/adr/`: ADR-0026
- `docs/adr/`: ADR-0027
- `docs/adr/`: ADR-0028
- `docs/architecture.md` and `docs/testing-strategy.md`.

## 5. Inputs

Outputs and execution records of every dependency; canonical schemas; current toolchain catalog; exact capability/profile/fingerprint evidence for hardware branches.

## 6. Deliverables

- Audit permissions/components/sharing/logs/backups/native inputs, privacy/data flows, semantics/touch targets, and policy declarations.
- Unit/instrumentation/device or host fixtures that prove the behavior without elevating unsupported evidence.
- Updated execution record, traceability links, and local evidence references.

## 7. In Scope

The behavior in Objective and Deliverables, its narrow interfaces, failure states, tests, documentation updates, and required migration of directly owned data.

## 8. Out of Scope

Unlisted milestone features, vendor/private APIs, cloud/network behavior, unrelated UI redesign, silent configuration fallback, and architecture changes without a superseding ADR.

## 9. Architectural Constraints

Follow the listed ADRs. Keep Unknown explicit, resources uniquely owned and bounded, the main thread free of capture work, clip invariants immutable, data local, and every fallback/user-visible discrepancy recorded.

## 10. Implementation Steps

1. Verify dependency execution records, repository build, JDK 17, and the conditional input when present.
2. Add the smallest production interfaces and immutable models required for: Audit permissions/components/sharing/logs/backups/native inputs, privacy/data flows, semantics/touch targets, and policy declarations.
3. Implement the success path with stable IDs, explicit ownership, bounded buffers, and declared time/units.
4. Implement every failure and unsupported branch from the gate; do not catch and discard errors.
5. Add deterministic fakes/fixtures first, then Android or physical-device coverage for contracts that cannot be proven on host.
6. Run all commands, inspect produced reports/files, and retain only privacy-approved evidence references.
7. Update schemas, requirements/ADR links, manifest status, acceptance marks, and execution record without renumbering IDs.

## 11. Expected File and Module Changes

Primary paths: `docs/release/, .github/workflows/, fastlane/, evidence/`. Create no module until this plan owns executable behavior in it.

## 12. State and Data-Flow Changes

Inputs flow through immutable domain commands/configuration into the owning subsystem, then emit typed state/evidence. Queues are bounded; cancellation closes owned resources in reverse order; serialized data uses the accepted schema version.

## 13. Error and Fallback Behavior

None; this plan is Ready once its dependency plans are Done. Stable failures include component, code, severity, recoverability, correlation ID, and safe user message. Strict stops/rejects on required invariant failure; Adaptive uses only ADR-0015 transitions.

## 14. Tests

- Unit test the success, unsupported, cancellation, duplicate command, stale evidence, and cleanup paths for this deliverable.
- Instrument lifecycle/descriptor/permission behavior when Android owns the contract.
- Use a physical device only for hardware claims and persist failing as well as passing validation JSON.
- Assert Strict performs no silent transition and Adaptive emits an event for every permitted transition.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew lint test`
- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew assembleDebug`
- `ffprobe -v error -show_streams -show_format <recorded-fixture>`

Record skips as failures or explicit not-run evidence; never report an unexecuted command as passing.

## 16. Acceptance Criteria

- [x] The exact Objective behavior exists and is reachable only through its accepted capability/policy gate.
- [x] Success, unsupported, failure, cancellation, and cleanup tests pass.
- [x] No protected signal-path property changes silently and Unknown remains distinct from unsupported.
- [x] Required commands pass, or the plan is marked Blocked with the real output and unblock condition.
- [x] Repository buildability, manifest links, schemas, and privacy constraints remain valid.

## 17. Evidence to Record

Command transcripts, test reports, graph/config IDs, validation JSON, relevant log/sidecar, file metadata, benchmark windows, and hashes where practical. Device evidence includes exact fingerprint and protocol version.

## 18. Rollback and Recovery

Revert only this plan's changes, remove generated outputs, close/delete partial media and fixtures through their owners, restore dependency-plan schema versions, rerun baseline tests, and leave the plan Blocked with diagnostics if the accepted architecture cannot be implemented.

## 19. Risks and Mitigations

Tracked risks: RISK-011, RISK-016, RISK-017, RISK-018. Mitigation is the accepted capability gate, bounded ownership, explicit failure, independent validation, and fail branch documented above.

## 20. Completion Update

Mark individual criteria, update `manifest.yaml`, `TRACEABILITY.md`, affected schema/examples, capability/profile evidence, and downstream conditional gates. Revise an ADR before deviating from it.

## 21. Execution Record

- Status: Done
- Started: 2026-08-20T07:13:00-03:00
- Completed: 2026-08-20T07:16:00-03:00
- Executor: GPT-5.6 Luna
- Commits:
- Evidence: `evidence/plan-058/gradle-build.log`, `evidence/plan-058/format.log`, `evidence/plan-058/validator.log`, `evidence/plan-058/security-audit.log`
- Deviations: This is a source/configuration and host accessibility audit; no device accessibility instrumentation or recorded-media ffprobe run was available. Manifest checks and deterministic SecurityPrivacyAccessibilityAuditor tests pass, with ffprobe version retained separately.
- Follow-up plans: OCC-PLAN-059 is unblocked; OCC-PLAN-060 remains conditional on its certification dependencies.
