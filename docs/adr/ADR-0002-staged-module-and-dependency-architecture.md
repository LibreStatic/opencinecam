# ADR-0002: Staged module and dependency architecture

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-NFR-001
- OCC-FUNC-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Introduce `:app`, pure `:core:model`/`:core:testing`, `:camera`, and `:media` when used; later add benchmark, native, and desktop modules. Use constructor injection.

## Alternatives considered

Rejected alternatives: Single app module; dozens of feature modules; Hilt/Koin. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-001-006

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.
