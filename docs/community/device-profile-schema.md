# Device profile and quirk trust contract

Profiles are local, versioned, and scoped to the exact build fingerprint,
camera/physical-camera IDs, optional codec, probe protocol, and characteristics
digest. A manufacturer-wide or remote rule is not a profile.

## Trust labels

- `BUNDLED_TRUSTED` is release-owned and requires a matching SHA-256 digest
  signature from the bundled key ID.
- `IMPORTED_UNTRUSTED` is retained for inspection and narrow matching but is
  never promoted to release trust by storage or lookup.
- `USER_OVERRIDE` is an explicit narrow local override; its scope remains exact
  and it is selected ahead of an imported profile but below a bundled profile.

The digest covers the schema, identity, exact scope, trust label, source, and
sorted quirk effects. It is not a remote authenticity mechanism; key policy is
owned by the release process.

## Invalidation and storage

`DeviceProfileStore` is bounded local storage. A lookup requires exact scope
equality. Any changed fingerprint, camera, codec, protocol, or characteristics
digest returns `STALE` while retaining the old entry for diagnostics. Unknown
scope returns `UNKNOWN`; mismatched trusted signatures return `REJECTED`.

The reference implementation is
`core/model/src/main/kotlin/com/librestatic/opencinecam/core/model/DeviceProfiles.kt`
with deterministic host tests in `core/model/src/test/kotlin/com/librestatic/opencinecam/core/model/DeviceProfilesTest.kt`.

`ProfilePolicyEngine` loads only release-trusted bundled entries, forces imports
to the untrusted label, gives bundled quirks precedence over imported quirks,
and accepts user changes only as exact-scope disabling kill switches. An
attempt to enable or broaden a quirk is rejected rather than silently applied.
