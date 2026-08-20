# Reproducible builds and provenance

OpenCineCam keeps build inputs explicit and local to the repository:

- The Gradle wrapper is pinned to 9.5.0 and the version catalog pins every
  direct dependency and plugin.
- CI and release work use JDK 17, `compileSdk`/`targetSdk` 37, and the checked-in
  `gradle.properties` defaults.
- Gradle dependency locking (`app/gradle.lockfile`) and SHA-256 dependency
  verification (`gradle/verification-metadata.xml`) reject unexpected graph
  changes. Update them deliberately with a reviewed dependency change.
- `tools/generate_sbom.py` emits a sorted SPDX 2.3 JSON inventory from the lock
  file. It uses `NOASSERTION` for licenses that must be read from upstream
  artifacts rather than guessing.
- Build reports and local SDK paths are ignored; no credential, signing key, or
  machine path is a source input.

## Verification commands

```bash
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon lint test assembleDebug
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon dependencies --write-locks
JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --write-verification-metadata sha256 help
python3 tools/generate_sbom.py --lockfile app/gradle.lockfile --output build/reports/sbom.json
```

The generated SBOM is evidence, not a checked-in replacement for the lock or
verification metadata.
