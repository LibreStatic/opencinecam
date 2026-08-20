# ADR-0026: Test evidence promotion and certification

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-TEST-001
- Related risks: RISK-016, RISK-018, RISK-019
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Use unit/instrumentation/device/file/fault/soak pyramid. Emulator cannot certify. Sustained is 10 min encoded or 60 s RAW; empirical is three cold runs; certification includes 30 min and full faults/reviews.

## Alternatives considered

Rejected alternatives: Mocks as hardware proof; single short run; pass-only evidence. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

## Consequences

### Positive

The choice is deterministic, testable, traceable, and preserves technical honesty across OEM behavior.

### Negative

Implementation carries explicit compatibility branches, evidence storage, and device-test cost instead of optimistic feature exposure.

### Operational consequences

Failures produce a stable error/evidence record and execute Strict or the documented Adaptive branch. No executor can replace the choice inside an implementation plan.

## Validation evidence

Primary references in `docs/references.md`, plan acceptance tests, schemas, and versioned device/file evidence validate this decision. Hardware-dependent outcomes remain ConditionalReady rather than assumed.

## Implementation constraints

Use public Android APIs, bounded resources, immutable identifiers, explicit Unknown values, local-only data, and no silent degradation. Leave the repository buildable after each plan.

## Affected plans

OCC-PLAN-002, OCC-PLAN-036, OCC-PLAN-042, OCC-PLAN-046, OCC-PLAN-050, OCC-PLAN-053, OCC-PLAN-057, OCC-PLAN-060

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.
