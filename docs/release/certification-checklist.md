# Release candidate certification checklist

Certification is an exact `(artifact SHA-256, source digest, device
fingerprint, protocol)` tuple. A green host build is not certification.

Required checks per target:

- reproducible artifact and SBOM checksum;
- manifest/privacy/permission review;
- install, foreground notification, capture lifecycle, and finalization;
- capability truth and unsupported branches;
- sustained recording, thermal/storage faults, and bounded queue behavior;
- RAW/HLG/Log/APV checks only when their ConditionalReady inputs pass;
- file/metadata validation and explicit failure evidence.

Missing physical target, artifact, or check evidence stays `NOT_RUN`; any failed
check stays `FAILED`. Only all-passing exact tuples receive `CERTIFIED`. The
host-testable contract is
`core/model/src/main/kotlin/com/librestatic/opencinecam/core/model/ReleaseCertification.kt`.
