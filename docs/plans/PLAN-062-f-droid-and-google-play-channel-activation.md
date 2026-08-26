---
plan_id: OCC-PLAN-062
title: "F-Droid and Google Play channel activation"
status: ConditionalReady
revision: 1
milestone: M17
intended_executor: GPT-5.6 Luna
execution_mode: implementation
depends_on:
  - OCC-PLAN-058
  - OCC-PLAN-059
blocks:
  - OCC-PLAN-060
requirements:
  - OCC-DIST-001
  - OCC-DIST-002
  - OCC-DIST-003
  - OCC-DIST-004
adrs:
  - ADR-0027
  - ADR-0028
risks:
  - RISK-016
  - RISK-018
estimated_sessions: 2
expected_repo_state: buildable
created_by: GPT-5.6 Sol
---
# OCC-PLAN-062: F-Droid and Google Play channel activation

## 1. Objective

Turn the release contracts prepared by OCC-PLAN-059 into operational F-Droid and Google Play channels for the same versioned source while preserving identical runtime policy and explicit signing ownership.

## 2. Why This Plan Exists

OCC-PLAN-059 completed dependency locking, SBOM/artifact comparison, metadata, signing handoff, and the direct tagged GitHub release path. Its execution record explicitly did not run an F-Droid build server or a Google Play upload. Those external activations must remain open rather than being implied by the completed direct-channel contract.

## 3. Prerequisites

OCC-PLAN-058 and OCC-PLAN-059.

Conditional gate: Input: a publicly fetchable immutable source tag accepted by F-Droid, an authorized Google Play application with Play App Signing/upload credentials, current store metadata, and an exact releasable version. Pass: build and publish the same source/runtime policy through both channels with recorded checksums and track state. Fail: retain only the already operational direct GitHub channel and do not claim three-channel availability.

## 4. Required Reading

- `docs/release/three-channel-release.md`.
- `docs/release/signing-handoff.md` and `docs/release/play-metadata.md`.
- `metadata/com.librestatic.opencinecam.yml`.
- `.github/workflows/release.yml`.
- ADR-0027 and ADR-0028.

## 5. Inputs

Exact source tag, current `versionName`/`versionCode`, direct-release checksums, dependency locks, SBOM/notices, F-Droid metadata, Play listing assets/text, Play Console application identity, and credentials held outside Git.

## 6. Deliverables

- F-Droid metadata/build recipe accepted against a publicly fetchable immutable tag and independently produced artifact.
- Reproducible release AAB, Play App Signing/upload boundary, and automated or documented authenticated upload to the internal track.
- Store listing/version/permission declarations aligned with the direct artifact and privacy contract.
- Channel comparison record proving no channel-specific capture behavior or proprietary runtime dependency.

## 7. In Scope

F-Droid recipe validation/submission, AAB production, Play Console/fastlane configuration without committed secrets, internal-track upload, checksum/SBOM comparison, metadata synchronization, and failure/rollback documentation.

## 8. Out of Scope

Changing capture behavior per store, adding analytics or proprietary SDKs, committing signing credentials, automatic promotion to Play production without review, certifying devices, or changing the direct GitHub release key.

## 9. Architectural Constraints

All channels derive from the same tag and runtime policy. F-Droid owns its signing key; Play App Signing owns the Play release boundary; direct APK signing remains independent. Credentials and recovery material never enter the repository or evidence bundle.

## 10. Implementation Steps

1. Confirm repository/tag fetchability and synchronize F-Droid metadata to the exact version.
2. Run the F-Droid-compatible clean build and artifact comparison; submit/update the recipe and record the external build result.
3. Add a release AAB task and secret-free fastlane/Play configuration with explicit track and rollout policy.
4. Configure the external Play application and upload key, then upload to `internal` only.
5. Compare source/version/SBOM/permissions/runtime policy across direct, F-Droid, and Play inputs.
6. Record public channel identifiers, artifact hashes, external build/upload results, and any rejected state.
7. Update README, release docs, PLAN-059 follow-up, and OCC-PLAN-060 inputs without marking unavailable channels as shipped.

## 11. Expected File and Module Changes

Primary paths: `metadata/`, `fastlane/`, `docs/release/`, `.github/workflows/`, Gradle release configuration, and privacy-safe evidence manifests.

## 12. State and Data-Flow Changes

Release metadata binds a source tag and version to separate channel artifacts/signing owners. External credentials flow only through protected operator or CI secret stores. Publication results emit immutable channel IDs, hashes, timestamps, and track states.

## 13. Error and Fallback Behavior

Unavailable repository access, rejected metadata, non-reproducible artifacts, absent credentials, upload rejection, or policy mismatch keeps the affected channel `NOT_RUN` or `FAILED`. The direct channel remains available; no automated fallback republishes a differently configured artifact.

## 14. Tests

- Validate F-Droid metadata against current Gradle version and immutable tag.
- Clean dependency-verified APK/AAB builds with SBOM and permission comparisons.
- Artifact/source/version consistency checks across channel inputs.
- Secret scanning and tests proving credentials are absent from Git/artifacts/logs.
- Dry-run and authenticated internal-track upload behavior, including duplicate/rejected/cancelled cases.

## 15. Commands to Run

- `JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict lint test bundleRelease`
- `python3 tools/generate_sbom.py --lockfile app/gradle.lockfile --output <sbom>`
- F-Droid metadata/build validation commands in its documented clean environment.
- `fastlane supply --track internal` or the accepted equivalent, first in validation-only mode and then authenticated.
- Artifact checksum, manifest, ABI, signing-owner, and policy comparisons.

## 16. Acceptance Criteria

- [ ] F-Droid accepts and builds the exact tagged source without proprietary runtime dependencies.
- [ ] Google Play accepts the exact version AAB into the internal track under the documented signing boundary.
- [ ] Version, source, SBOM, permissions, metadata, and runtime policy agree across all three channel inputs.
- [ ] Credentials remain outside Git and logs; rejection/cancellation/rollback paths are recorded.
- [ ] README and release/certification inputs claim only channels with verifiable external publication state.

## 17. Evidence to Record

Source/tag digest, clean-build logs, F-Droid request/build identifiers, APK/AAB hashes, SBOM hashes, Play package/edit/release/track identifiers, signing-owner fingerprints where safe, metadata diffs, and explicit rejected/not-run outcomes.

## 18. Rollback and Recovery

Withdraw or halt only the affected external submission/track, retain the direct release and immutable failure evidence, rotate compromised external credentials outside Git, correct metadata/versioning through a new source tag when required, and never replace an existing release artifact in place.

## 19. Risks and Mitigations

RISK-016 is mitigated through clean multi-channel builds, immutable tags, SBOMs, and independent signing owners. RISK-018 is mitigated by exact source/version/channel identities and refusal to interpret external availability without recorded publication state.

## 20. Completion Update

Mark criteria only from external build/upload results, update `manifest.yaml`, `README.md`, `TRACEABILITY.md`, release docs, metadata, OCC-PLAN-059 follow-up, and OCC-PLAN-060 dependencies. Preserve failed and not-run channel states.

## 21. Execution Record

- Status: ConditionalReady (direct channel operational; F-Droid and Play external gates pending)
- Started: 2026-08-26T23:05:00-03:00
- Completed:
- Executor: GPT-5.6 Sol
- Commits:
- Evidence:
- Deviations: Tag v0.3.3 and the signed ARM64 GitHub Release are operational inputs. F-Droid build-server acceptance and Google Play upload have not been executed.
- Follow-up plans: OCC-PLAN-060 consumes exact external channel state and certifies only available artifact/fingerprint tuples.
