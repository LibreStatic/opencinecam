# Local validation contribution template

Use this template to prepare a deterministic, reviewable fixture contribution.
It is intentionally local-only; OpenCineCam does not upload reports, profiles,
serial numbers, paths, or crash data automatically.

```yaml
schemaVersion: 1.0.0
contributionId: <stable-local-id>
scope:
  buildFingerprint: <exact-value; redact before sharing>
  cameraId: <exact-value; redact before sharing>
  physicalCameraId: <optional exact-value; redact before sharing>
  codecName: <optional>
  protocolVersion: <probe-protocol>
  characteristicsDigest: <digest>
fixtureId: <fixture-name>
fixtureDigestHex: <sha256>
fixturePassed: true
evidence:
  codec: <public codec name>
  result: <pass/fail>
```

## Review flow

1. Run fixture regression and the exact-scope/privacy checks locally.
2. Submit only after an explicit redacted or full export consent action.
3. A reviewer marks the record reviewed; a release key signs the canonical
   digest only after review.
4. Revoke a superseded or contradictory contribution by stable ID and retain
   the reason for local diagnostics.

The core implementation is
`core/model/src/main/kotlin/com/librestatic/opencinecam/core/model/ContributionWorkflow.kt`.
Redacted exports hash fingerprints, camera IDs, and evidence values; full
exports are never implicit.
