# Three-channel release contract

OpenCineCam ships the same versioned source and dependency-locked build through
three channels. Signing keys never enter the Git repository.

| Channel | Build/signing owner | Runtime policy |
| --- | --- | --- |
| F-Droid | F-Droid reproducible build from the tagged source and lockfiles | No proprietary runtime dependency; reproducible unsigned input is verified before F-Droid signing |
| Direct APK | GitHub Actions signs tagged ARM64 APKs from encrypted repository secrets | The runner is ephemeral; checksums accompany the GitHub Release and a recovery key stays outside GitHub |
| Google Play | Play App Signing owns the upload/release key boundary | Play metadata/tracks are reviewed explicitly; no analytics or remote-crash SDK is added |

## Reproducibility and provenance

1. Pin JDK 17, Gradle wrapper, Android SDK/NDK versions, and dependency lockfiles.
2. Run `lint test assembleDebug` and the release variant in a clean workspace.
3. Generate SPDX 2.3 inventory with:

   ```sh
   python3 tools/generate_sbom.py \
     --lockfile app/gradle.lockfile \
     --output build/release/opencinecam.spdx.json
   ```

4. Compare independently produced APKs with
   `python3 tools/compare_artifacts.py --reference A.apk --candidate B.apk`.
5. Keep checksums, build configuration, notices, and the exact source tag with
   the artifact; do not include device fingerprints or signing secrets.

## Version and track policy

`versionCode` is monotonic and `versionName` is the user-visible semantic
version. Play uses `internal` for review, then `closed` for staged validation,
then `production`; direct and F-Droid artifacts use the same source version and
must not silently enable channel-specific capture behavior.
