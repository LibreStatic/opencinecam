# ADR-0022: OpenCine Log specification process

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-COLOR-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Keep Log unavailable until versioned domain/gamut/curve/range/provenance, CPU/GPU references, vectors, LUT/OCIO/DCTL, metadata, and tolerances pass; measured sensor mapping follows real fixtures.

## Alternatives considered

Rejected alternatives: Marketing-defined Log; reuse HLG name; device-specific undocumented curve. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-047-050

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-19)

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-COLOR-001
- Related ADRs: None

### Context

The original decision keeps Log unavailable until the specification, references, vectors and tolerances pass. OCC-PLAN-061 later put an OCLog2 runtime behind the launcher, and `docs/color/opencine-log-v2.md` and `docs/architecture.md` describe it as experimental and not qualified. The mode UI labels an available LOG gate "Experimental" unless the camera has an OpenCineLog profile backed by accepted evidence (`showsExperimentalGate` in `app/src/main/java/com/librestatic/opencinecam/CameraScreen.kt`).

### Decision

Log may ship as an Experimental mode with a visible "Experimental" label. It must never be described, labelled or exported as qualified, and no tuple is promoted, until the criteria of the original decision pass for that exact tuple (versioned domain, gamut, curve, range and provenance, CPU/GPU references, vectors, LUT/OCIO/DCTL, metadata and tolerances). The experimental label is removed only for a camera with an accepted OpenCineLog profile. Marketing names and reuse of the HLG name stay rejected.

### Alternatives considered

- Keep Log hidden until qualified: rejected by the user decision of 2026-10-19 because the experimental mode already ships and is useful for evaluation.
- Ship it without a label: rejected because it would imply a qualification that does not exist.

### Consequences

- Positive: the ADR, `architecture.md` and the UI agree.
- Negative: users see a mode whose output is not qualified.
- Operational: any change that removes the label, or claims qualification, needs the qualification evidence of OCC-PLAN-061 first.

The original decision above is retained as history; this amendment governs where they differ.
