# ADR-0001: Product identity and Android build baseline

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-FUNC-001
- OCC-NFR-001
- OCC-PRIV-001
- OCC-DIST-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Use OpenCineCam, `com.librestatic.opencinecam`, minSdk 29, compile/target 37, AGP 9.3.1, Gradle 9.5, JDK 17, built-in Kotlin 2.2.10, Compose BOM 2026.08.00, and no Internet permission.

## Alternatives considered

Rejected alternatives: Folder-derived identity; lower target SDK; preview toolchains. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-001-003

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-19)

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-PRIV-001 (amended 2026-09-26)
- Related ADRs: ADR-0027 (amended 2026-09-26), ADR-0034

### Context

The original decision lists "no Internet permission" as part of the build baseline. The shipped manifest declares `INTERNET` and `ACCESS_NETWORK_STATE` (`app/src/main/AndroidManifest.xml`) for opt-in WebDAV transfers, a change that ADR-0027 already accepted in its amendment of 2026-09-26.

### Decision

The identity, SDK levels and toolchain above are unchanged. The "no Internet permission" clause is governed by ADR-0027 as amended on 2026-09-26: `INTERNET` and `ACCESS_NETWORK_STATE` are allowed only for the named opt-in WebDAV transfers, which are off by default and started by explicit user action. Any other network permission is still rejected.

### Alternatives considered

- Restate the network rules here: rejected because ADR-0027 owns them and a second copy would drift.

### Consequences

- Positive: the baseline no longer contradicts the manifest or ADR-0027.
- Negative: readers of this ADR must follow the reference to ADR-0027 for the network rule.
- Operational: none; no code or manifest change.

The original decision above is retained as history; this amendment governs where they differ.
