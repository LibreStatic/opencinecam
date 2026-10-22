# ADR-0006: Public camera enumeration and physical routing

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-CAM-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Enumerate only public IDs, infer lens labels visibly, route with OutputConfiguration, and audit active physical result metadata. Missing result is Unknown.

## Alternatives considered

Rejected alternatives: Hard-coded lens roles; vendor tags; assuming physical lock. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-007, OCC-PLAN-015, OCC-PLAN-025

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-19)

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-PRIV-006 (amended 2026-10-19)
- Related ADRs: None

### Context

The original decision rejects "vendor tags". Some capabilities (documented in `docs/device-validation.md`) are only reachable through Camera2 vendor tags, and the shipped code already reads them through `resolveVendorKey` in `camera/src/main/java/com/librestatic/opencinecam/camera/VendorKeys.kt`.

### Decision

Camera2 vendor tags may be used only when resolved by name against a static gate key the HAL reports: `resolveVendorKey` returns ADVERTISED when the key is in the public key list, BY_NAME when it is not listed but the gate key is present, and ABSENT otherwise (including below API 29). An ABSENT tag is never requested, and every use has a public-API fallback. Root, privileged permissions and OEM libraries stay outside portable OSS. Enumeration and physical routing still use public IDs and `OutputConfiguration` only.

### Alternatives considered

- Keep the blanket ban on vendor tags: rejected because it would remove capabilities that degrade safely on devices without the tag.
- Allow vendor tags by device model: rejected because it is not portable and hides the evidence that the HAL supports the tag.

### Consequences

- Positive: vendor tags are opt-in per device through a HAL-reported gate and cannot break other devices.
- Negative: each tag needs a documented gate key and a fallback path.
- Operational: `VendorKeysTest` covers the ABSENT case when the gate key is missing; new tags are documented in `docs/device-validation.md`.

The original decision above is retained as history; this amendment governs where they differ.
