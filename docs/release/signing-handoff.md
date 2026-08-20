# Signing handoff (keyless repository)

The repository produces unsigned/reproducible inputs only. The release operator
receives:

- source tag and commit/archive digest;
- Gradle wrapper, JDK, SDK, dependency-lock, and build-task versions;
- unsigned APK/AAB SHA-256 checksums;
- SPDX SBOM and `NOTICE`/Apache-2.0 files;
- artifact comparison output and release notes.

The operator signs the direct APK in an offline keystore and uploads the Play
artifact through Play App Signing. F-Droid performs its own source build and
signing. Keys, passwords, Play service credentials, and device identifiers are
never stored in this repository or CI logs.
