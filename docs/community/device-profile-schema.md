# Device profile and quirk trust contract

Profiles are local, versioned, and scoped to one device model and one camera:
the device codename (`Build.DEVICE`, for example `blanc` for the Razr Fold) and
the camera ID. A physical-camera ID or a codec narrows the scope only when the
profile names one. A profile records the build fingerprint it was verified on,
the probe protocol and, when known, the characteristics digest, but only the
device, camera, physical camera and codec are matched. A manufacturer-wide or
remote rule is not a profile: a blank or wildcard codename is rejected.

## Trust labels

- `BUNDLED_TRUSTED` is release-owned and requires a matching SHA-256 digest
  signature from the bundled key ID.
- `IMPORTED_UNTRUSTED` is retained for inspection and narrow matching but is
  never promoted to release trust by storage or lookup.
- `USER_OVERRIDE` is an explicit narrow local override; its scope is the same
  device and camera, and it is selected ahead of an imported profile but below
  a bundled profile.

The digest covers the schema, identity, device and camera scope, recorded build
fingerprint, trust label, source, and sorted quirk effects. It is not a remote
authenticity mechanism; key policy is owned by the release process.

## Lookup, build drift and storage

`DeviceProfileStore` is bounded local storage. A lookup matches the device
codename and camera ID (and the physical camera or codec when the profile sets
them). When several profiles match, the higher trust wins, then the one recorded
for the running build.

A running build fingerprint that differs from the recorded one does not
invalidate the profile: it stays `ACTIVE`, so its quirks keep working after an
OTA, and the lookup and the resolution report `NOT_VERIFIED_ON_THIS_BUILD`
(a "not verified on this build" warning). The Capabilities screen and the
capability report show it. No match returns `UNKNOWN`; a trusted profile whose
digest does not verify returns `REJECTED` and applies nothing. `STALE` is
produced only by `DeviceProfileStore.invalidate`, which re-evaluates the stored
entries for the current device and camera and marks the ones that no longer
match as `STALE`, retaining them for diagnostics.

The reference implementation is
`core/model/src/main/kotlin/com/librestatic/opencinecam/core/model/DeviceProfiles.kt`
with deterministic host tests in `core/model/src/test/kotlin/com/librestatic/opencinecam/core/model/DeviceProfilesTest.kt`.

## Quirk effects

A quirk names its effect by a stable id from a closed set (`QuirkEffect`):

- `high-speed-prefer-physical-camera`: open a constrained high-speed session on
  the physical camera that owns the high-speed mode.
- `moto-high-speed-session-keys`: supply the Motorola slow-motion session keys
  on constrained high-speed requests.

An id this build does not know is ignored with a warning and never applied.
Capability rules that do not depend on device identity are not quirks.

## Policy and bundled assets

`ProfilePolicyEngine` loads only release-trusted bundled entries, forces imports
to the untrusted label, gives bundled quirks precedence over imported quirks,
and accepts user changes only as disabling kill switches keyed by device, camera
and quirk. An attempt to enable or broaden a quirk is rejected rather than
silently applied.

Bundled profiles are JSON documents in `app/src/main/profiles/device-profiles`
that follow `docs/schemas/device-profile.schema.json` and ship as app assets.
`DeviceProfileCodec` reads them; a host test checks that each one validates
against the schema and verifies `ACTIVE`, so editing an asset without updating
its signature digest fails the build.
