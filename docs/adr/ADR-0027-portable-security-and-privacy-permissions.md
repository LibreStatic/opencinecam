# ADR-0027: Portable security and privacy permissions

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-PRIV-001
- OCC-DIST-001
- Related risks: RISK-016, RISK-018, RISK-019
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Use least permissions, visible FGS start, non-exported components, immutable PendingIntents, content grants, no Internet/broad storage, and local-only data.

## Alternatives considered

Rejected alternatives: Background service starts; File URI; Play Services analytics. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-003, OCC-PLAN-011, OCC-PLAN-019, OCC-PLAN-027, OCC-PLAN-058

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-09-26)

- Status: Accepted
- Date: 2026-09-26
- Related requirements: OCC-PRIV-001 (amended 2026-09-26), OCC-PRO-008
- Related ADRs: ADR-0034

### Context

The original decision ("no Internet") no longer matched the shipped manifest. The app declares `INTERNET` and `ACCESS_NETWORK_STATE` for opt-in WebDAV transfers of finalized captures (PLAN-067, ADR-0034) and `ACCESS_COARSE_LOCATION`/`ACCESS_FINE_LOCATION` for opt-in photo/take geotagging (PLAN-066 Rev49). Both features are off by default and never enabled by presets. The `SEC-NETWORK` audit check only passed because it was exercised with synthetic input.

### Decision

Keep least permissions, but allow network and location permissions only when a named opt-in feature justifies them: WebDAV transfers justify `INTERNET` and `ACCESS_NETWORK_STATE`; geotagging justifies `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION`. Each feature is off by default and started by explicit user action. There is no background or automatic network use, no analytics, telemetry or accounts, transfers are HTTPS only, and no third-party SDK is added. Any other network, location (including background location) or sensitive permission is still rejected. `SecurityPrivacyAccessibilityAuditor` models this as an allowlist keyed by declared `OptInFeature`s, and a host test audits the real `app/src/main/AndroidManifest.xml`.

### Alternatives considered

- Separate no-network build flavor: rejected for now. It doubles the build and release-channel matrix (F-Droid, direct, Play) and its certification evidence, for features that are already off by default. It can be revisited if a channel requires a manifest with no network permission.
- Keep the absolute "no Internet" rule and remove WebDAV transfers: rejected because OCC-PRO-008 is an accepted, user-approved requirement.

### Consequences

- Positive: requirement, ADR, audit and manifest agree; the audit now runs against the real manifest, so an unjustified permission fails a host test.
- Negative: the installed app can technically reach the network, so store listings and privacy documentation must explain the opt-in features and their defaults.
- Operational: adding a permission requires a new or extended opt-in feature, an amendment to OCC-PRIV-001 and this ADR, and an updated audit test.

The original decision above is retained as history; this amendment governs where they differ.
