# Signing handoff

Tagged direct APK releases are built and signed by GitHub Actions. The release
keystore is stored as an encrypted repository secret and reconstructed only in
the ephemeral release runner. Its passwords and alias are separate secrets.
A recovery copy exists outside the repository with owner-only permissions.

The GitHub Actions workflows are currently disabled, so releases are gated with
the CI command and built locally. The Play bundle is built and signed with the
upload key (`~/.android/opencinecam/keystore.properties`, outside the
repository) by `tools/build-play-bundle.sh`.

Each release provides:

- source tag and commit/archive digest;
- Gradle wrapper, JDK, SDK, dependency-lock, and build-task versions;
- signed ARM64 release and debug APK SHA-256 checksums;
- SPDX SBOM and `NOTICE`/Apache-2.0 files;
- artifact comparison output and release notes.

The direct release key is not used for Google Play or F-Droid. Play App Signing
owns the Play key boundary, and F-Droid performs its own source build and
signing. Keystore bytes, passwords, Play credentials, and device identifiers
must never be committed or printed in CI logs.
