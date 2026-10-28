# ADR-0015: Strict default and bounded Adaptive fallbacks

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-FUNC-001
- OCC-NFR-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Strict is default. Adaptive preflight order is same codec/bit depth encoder, bitrate, resolution, FPS, then confirmed codec-family change; during capture only monitoring can degrade and video may continue after audio failure. Camera, RAW, HLG, and bit depth never change.

## Alternatives considered

Rejected alternatives: Adaptive default; arbitrary fallback; stopping for all non-recording load. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-006, OCC-PLAN-020, OCC-PLAN-023, OCC-PLAN-029

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-28)

- Status: Accepted
- Date: 2026-10-28

### Context

Some fallbacks run under Strict too:

- When the camera rejects the preview session graph, the engine retries without the analysis output, then without RAW.
- It retries without stream use cases.
- When the physical still routing of OCC-PLAN-069 is refused, it retries with logical-only still sizes.
- The SDR video route falls back from HEVC to H.264 when the HEVC encoder refuses the format.

Until now these were recorded only in the log; the take's `.clip.json` sidecar did record the real codec. Failing the whole preview instead would leave the camera unusable on hardware that rejects one optional stream.

### Decision

Allow these fallbacks under both policies. Strict still forbids a silent change:

- **Analysis or RAW dropped.** The capture UI shows a notice for the lost function: scopes when an analysis-backed monitor is on, RAW when the mode wants a DNG.
- **HEVC to H.264.** The take shows a notice and records the real codec in its sidecar.
- **Stream use cases removed.** This changes no output, so it needs no notice.
- **Physical still sizes refused.** The still uses the logical sizes. There is no notice yet, and the RES list still offers the physical size; that gap stays open.

Camera, HLG and bit depth still never change. A dropped RAW stream is reported, never replaced by another format.
