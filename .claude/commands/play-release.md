---
description: Bump, gate, build and publish a signed OpenCineCam AAB to Google Play with per-locale release notes
argument-hint: "[patch|minor|major|replace] [track, default: Closed testing - Alpha]"
---

# Play Store release: OpenCineCam

Arguments: `$ARGUMENTS`. The first word is the bump size; if it is missing, choose it from the commits since the
last release using the rule in `AGENTS.md`. `replace` keeps `versionName` and only raises `versionCode`. Use it to
swap the build of a release that Google has not approved yet. Any remaining words name the track.

## Repository facts

| Item | Value |
|---|---|
| Package | `com.librestatic.opencinecam` |
| Play Console app | `https://play.google.com/console/u/0/developers/7999945149961326518/app/4975248504839714701` |
| Closed testing - Alpha track | `…/tracks/4699742688832886914` |
| Version | `versionName` and the local `?: <code>` fallback in `app/build.gradle.kts` |
| CI version code | `GITHUB_RUN_NUMBER + 100` (`.github/workflows/play-prerelease.yml`) |
| Release notes | `store/play/release-notes/<versionName>.txt`, one `<locale>…</locale>` block per folder in `store/play/listings/` |
| Signed bundle | `tools/build-play-bundle.sh`, which uses the upload key from `~/.android/opencinecam/keystore.properties` |
| CI gate | `./gradlew --no-daemon --dependency-verification=strict --console=plain lint test assembleDebug` and `python3 tools/validate_plan_system.py` |

## Steps

1. **Preflight.**
   - You are on `main` and in sync with `origin/main`. List the unpushed commits; they ship in this release.
   - Leave unrelated working-tree changes uncommitted, and never stage them.
   - Run `free -h`. The gate needs about 12 GB with the settings below. With less available, drop to `parallel=false` and `workers.max=2` (about 8 GB); below that, stop.

2. **Version.**
   - Find the last `versionCode` uploaded to Play with `tools/play-publish.py status` ("highest uploaded versionCode").
   - Find the latest CI code with `gh release list --limit 3` and `gh run list --workflow play-prerelease.yml --limit 1`.
   - New code: above both, and equal to the next CI run number + 100 when the Play prerelease workflow is enabled
     (`gh workflow list --all`), so that the GitHub prerelease and the Play build share a code.
   - Set `versionName` (unless `replace`) and the `?: <code>` fallback.
   - Commit the bump alone: `chore(release): bump version to X.Y.Z-beta`.

3. **Release notes.**
   - Write `store/play/release-notes/<versionName>.txt` from `git log --no-merges <last release>..HEAD`.
   - Cover user-facing changes only, in every listing locale (en-US, es-419 with voseo, es-ES, pt-BR, pt-PT,
     fr-FR, de-DE, it-IT).
   - End each block with the issues link.
   - Use in-app names for features: check `app/src/main/res/values*/strings.xml`.
   - Each block must stay at most 500 characters; check that with a script.
   - Commit: `docs(release): add X.Y.Z-beta release notes`.

4. **Local CI gate, before pushing.**
   - Create a clean worktree:
     `git worktree add --detach ~/.cache/claude-tmp/libremagic/release-wt main`. Never use /tmp or /dev/shm.
   - Run the CI gate there with `--continue`, `JAVA_HOME=/usr/lib/jvm/java-17-openjdk`,
     `ANDROID_HOME=ANDROID_SDK_ROOT=$HOME/Android/Sdk`, and
     `GRADLE_OPTS="-Dorg.gradle.parallel=true -Dorg.gradle.workers.max=4 -Dorg.gradle.jvmargs=-Xmx3g -Dkotlin.daemon.jvm.options=-Xmx3g"`.
   - Run it from a script file in the background; the context-mode hook blocks raw `./gradlew` in Bash.
   - `build-play-bundle.sh` runs no tests, so this gate is the only check. Fix every failure in its own commit.
     Known traps:
     - a dependency missing from `gradle/verification-metadata.xml`: verify its sha256 against Maven Central before
       adding it;
     - a plan whose front-matter `revision` drifted from `docs/plans/manifest.yaml`;
     - lint errors such as `NonObservableLocale`.

5. **Push** `main`. If any workflow is `disabled_manually`, say so and do not enable it without asking.

6. **Build.**
   - Move the worktree to the pushed `main`, then run
     `ANDROID_SDK_ROOT=… ANDROID_HOME=… JAVA_HOME=… bash tools/build-play-bundle.sh`.
   - Confirm the printed versionName and versionCode.
   - Copy the `.aab` and `.sha256` to `~/Downloads/`, and remove the older bundles there.

7. **Publish through the API.** See "Publishing to Google Play" in `AGENTS.md`.
   - `tools/play-publish.py publish --aab <aab> --dry-run`: it must print the new versionCode, notes for
     8 languages and "edit validated".
   - Rerun it without `--dry-run`. If the commit refuses to send changes automatically, rerun with `--no-review`
     and finish from Publishing overview (Submit, then Send changes for review).
   - `tools/play-publish.py status` must show the track with the new release.
   - In the Console's release review page, *devices no longer supported* must stay 0. Check it with Claude in Chrome
     when the manifest or dependencies changed.
   - Fall back to the Console flow below only if the API is unavailable (for example a 403: the service account
     lacks access, so ask the user).

7b. **Console fallback.** Use Claude in Chrome (`mcp__claude-in-chrome__*`) and work on the DOM first.
   - Open the track and click **Create new release**.
   - **The upload is the user's step.** `file_upload` caps at 10 MB, and injecting the file with JS or a localhost
     server needs the user's explicit authorization. Otherwise ask the user to click Upload and pick the file in `~/Downloads`.
     Wait until `find` reports the bundle row with the new code.
   - Release name: keep the suggested `<code> (<versionName>)`.
   - Release notes:
     - fill the textarea with `form_input`, using the file contents without the trailing newline;
     - click it, then press `ctrl+End`, type a space and press Backspace, so Angular registers the change;
     - check that the counter reads "Release notes provided for 8 of 8 languages".
   - Click **Next**.
     - Expand the warnings. The deobfuscation-file and native-debug-symbols warnings are expected.
     - In the supported-devices table, *devices no longer supported* must be 0.
     - Stop on any error, or on any other warning or device drop, and report it.
   - Click **Save**, then **Go to overview**. The list must show only the intended track change, "Start full rollout".
   - Click **Submit N changes for review**, then **Send changes for review**. Check that the heading becomes
     "Changes in review".
   - Clicking by `ref` often does nothing in the Console. Click coordinates from a fresh scaled screenshot instead.
     The viewport height changes (677 ↔ 726), so re-read the bottom bar before clicking it.

8. **Replace a build already in review** (`replace`).
   - Publishing overview > **Remove changes** > confirm.
   - On the track, click **Create new release**, which supersedes the unsent draft.
   - Repeat step 7. "Edit release details" only changes the name and the notes.

9. **Close out.**
   - Remove the worktree only if nothing else needs it.
   - Report: the commits pushed, the gate result, the AAB path and versionCode, and the Console state.
   - Update the Play memory notes (`opencinecam-play-closed-test`).
