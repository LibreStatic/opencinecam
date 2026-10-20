# ADR-0024: Device profile quirk trust and invalidation

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-FUNC-001
- OCC-PRIV-001
- OCC-TEST-001
- Related risks: RISK-016, RISK-018, RISK-019
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

Scope profiles/quirks to exact fingerprint/camera/codec/protocol/digest. Bundled is release-trusted; imports are untrusted; overrides only narrow.

## Alternatives considered

Rejected alternatives: Manufacturer-wide rules; remote auto-update; imported promotion to verified. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-054-056

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-19): scope by device codename and camera

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-FUNC-001, OCC-PRIV-001, OCC-TEST-001
- Related ADRs: ADR-0024, ADR-0035
- Related plans: OCC-PLAN-054, OCC-PLAN-055

### Context

The original decision scoped a profile to the exact build fingerprint, camera, codec, protocol version and characteristics digest. Nothing in production consumed a profile: the only two device-identity gates in the app (`Build.MANUFACTURER == "motorola"` for the high-speed physical camera choice and for the Motorola slow-motion session keys in `Camera2PreviewEngine`) were hard-coded, which is the manufacturer-wide rule this ADR rejects. Scoping to the exact fingerprint would also switch a proven quirk off on every OTA, until a new build is re-verified, with no signal to the user.

### Decision

1. A profile is scoped to one device model and one camera. The device is the codename (`Build.DEVICE`, `ro.product.device`; the Razr Fold is `blanc`, product `blanc_g`, manufacturer `motorola`) and the scope also names the `cameraId`. `physicalCameraId` and `codecName` narrow the match only when the profile sets them. A profile whose codename is blank, or a wildcard, is rejected. Manufacturer-wide rules stay rejected: a manufacturer is not a scope.
2. The build fingerprint is still recorded in the profile as the build it was verified on, but it is not part of matching. When the running fingerprint differs, the profile stays ACTIVE, so its quirks keep working after an OTA, and the resolution reports "not verified on this build". The Capabilities screen and the capability report show that state. Drift is a warning, not an invalidation. `protocolVersion` and `characteristicsDigest` stay recorded evidence and are not matched either.
3. A quirk effect is one of a closed set (`QuirkEffect`) with a stable id in JSON. An id this build does not know is ignored with a warning and never applied, so a newer profile cannot make an older build do something it does not understand.
4. The rest of the decision is unchanged: bundled profiles are release-trusted and need a matching digest, imports are untrusted and never promoted, user overrides only narrow, and a kill switch is keyed by device, camera and quirk so it survives an OTA.
5. The two Motorola quirks are bundled as a profile for `blanc`, camera `0` (`app/src/main/profiles/device-profiles`), and `Camera2PreviewEngine` asks the resolved quirks instead of `Build`. Capability rules that never depended on identity (the 1x focal still gate, the Qualcomm exposure-priority gates, vendor keys gated by what the HAL advertises) are not quirks and stay as they are.

### Consequences

- Before this amendment any Motorola device got the two high-speed behaviours. Now only a device with a profile for its codename and camera does. A Motorola phone other than `blanc` runs the standard path until its own profile is contributed with evidence. That is the intended effect of "no manufacturer-wide rules".
- A new Razr firmware keeps its quirks and shows "Active, not verified on this build" until a profile for that build is bundled.
- Editing a bundled profile without updating its digest fails the host test that verifies every bundled asset.

