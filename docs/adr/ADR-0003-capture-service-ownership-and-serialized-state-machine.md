# ADR-0003: Capture service ownership and serialized state machine

- Status: Accepted
- Date: 2026-08-20
- Decision owners: OpenCineCam architecture maintainers
- Related requirements:
- OCC-CAM-001
- OCC-LIFE-001
- Related risks: RISK-001, RISK-004, RISK-009
- Supersedes: None
- Superseded by: None

## Context

The empty repository needs a portable public-API architecture that distinguishes advertised, requested, reported, file-proven, and empirical behavior. `init.md`, the requirements baseline, and primary references define the constraints.

## Decision

A bound same-process CaptureService and one serialized actor uniquely own Camera2 and media resources; UI uses typed commands and StateFlow.

## Alternatives considered

Rejected alternatives: Activity ownership; independent managers; AIDL process split. They weaken ownership, portability, evidence quality, lifecycle safety, or bounded delivery.

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

OCC-PLAN-013, OCC-PLAN-016, OCC-PLAN-027-029

## Revisit conditions

Revisit only when official platform contracts, measured device evidence, release policy, or an accepted superseding ADR invalidate an assumption. Evidence contradiction blocks the affected plan until review.

## Amendment (2026-10-19)

- Status: Accepted
- Date: 2026-10-19
- Related requirements: OCC-CAM-001, OCC-LIFE-001
- Related ADRs: None

### Context

The original decision gives the bound CaptureService sole ownership of Camera2 and media resources. On a cold start the camera then waited for Compose to draw the first frame, the service to bind and the cameras to be enumerated before it began to open. Since 0.4.1-beta, `MainActivity.onCreate` calls `CaptureStartup.begin()` (`app/src/main/java/com/librestatic/opencinecam/service/CaptureStartup.kt`) to open the `CameraDevice` ahead of the service, which cut the time to first display on the Razr from 422 ms to 276 ms (commits f5c0a28 and 6aee7be).

### Decision

The Activity may pre-open the `CameraDevice` through `CaptureStartup.begin()` and nothing else. It never configures a capture session, never records, and never touches codecs or audio. The CaptureService remains the only owner of sessions and media resources: it adopts the pre-opened engine (`CaptureStartup.adoptEngine`) and configures the session from the saved settings once the viewfinder surface exists. If the service never adopts the device, or the app leaves the screen first, the pre-opened device is closed (`CaptureStartup.stopped()`, `dropPreopen`).

### Alternatives considered

- Start the service from `onCreate`: rejected because the service still binds only after the first frame and the camera loses the head start.
- Keep strict Activity-never-touches-the-camera ownership: rejected because the measured startup gain is the point of the change and the device is handed over, not shared.

### Consequences

- Positive: faster cold start with a single owner for sessions and media; a stale pre-open cannot leak because it is closed when the screen is left.
- Negative: for a short window the Activity process holds an open `CameraDevice` that the service has not yet adopted.
- Operational: `CaptureStartup` must keep a surface limited to preparing, adopting and dropping the device; a host test guards against start or record entry points being added.

The original decision above is retained as history; this amendment governs where they differ.
