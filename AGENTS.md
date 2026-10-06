# Agent instructions

## Compose UI: visual feedback with Compose Driver

When changing native Android Jetpack Compose UI, use Compose Driver as the default visual feedback loop when the affected composable can run in the headless environment.

After meaningful UI changes:

1. render the affected screen/composable;
2. inspect its semantics tree;
3. inspect the screenshot visually;
4. interact with it if the task involves states, menus, scrolling, dialogs, sheets, or navigation;
5. correct obvious layout, clipping, hierarchy, contrast, or state issues;
6. render again after corrections.

Do not claim a UI change looks correct without inspecting a rendered result when Compose Driver is available.

Use a real Android emulator/device instead when behavior depends on hardware, OEM behavior, SurfaceView/TextureView, CameraX/camera hardware, codecs/video surfaces, WebView behavior, graphics behavior that Robolectric cannot reproduce, system UI/insets requiring device verification, or anything Compose Driver cannot faithfully execute.

### How it is wired

[Compose Driver](https://github.com/jdemeulenaere/compose-driver) runs a composable inside a Robolectric `ComposeUiTest` and serves it over HTTP. No emulator, APK or install is involved.

- The Compose UI lives in `:app`, which is an application module. Compose Driver's settings plugin only wires `com.android.library` modules, so the library is used directly instead. It is on the `:app` host-test classpath only (`testImplementation`) and never reaches the APK.
- The server is the host test `app/src/test/java/com/librestatic/opencinecam/driver/ComposeDriverServer.kt`. Ordinary `test` runs exclude it (see `app/build.gradle.kts`), so CI on JDK 17 never loads it.
- The renderable screens are in `app/src/test/java/com/librestatic/opencinecam/driver/DriverScreens.kt`. Each is a zero-argument `@Composable` that feeds fake state to the real production composable.
- `tools/compose-driver.sh` is the entry point. Run `tools/compose-driver.sh help` for every command.

### Requirements

- `ANDROID_HOME` (or `ANDROID_SDK_ROOT`).
- Gradle runs on the repository's usual JDK 17.
- The server JVM needs a JDK 21 or newer, because Compose Driver 0.5.0 is compiled for Java 21. The script picks the oldest JDK ≥ 21 it finds in `JAVA_HOME`, `/usr/lib/jvm/*`, `~/.jdks/*` or the macOS JVM folder. Override the choice with `COMPOSE_DRIVER_JAVA_HOME`.
- Robolectric downloads its SDK 36 image from Maven Central on first use.
- Optional: `ffmpeg`, needed to record GIFs.

### Commands

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk ANDROID_HOME=$HOME/Android/Sdk   # adjust to the host

tools/compose-driver.sh list                       # screens in DriverScreens.kt
tools/compose-driver.sh start Settings             # build + serve; prints "ready" (~40 s warm, minutes cold)
tools/compose-driver.sh tree                       # semantics tree, also build/compose-driver/tree.txt
tools/compose-driver.sh screenshot settings-home   # -> build/compose-driver/screenshots/settings-home.png
tools/compose-driver.sh click tag=settings-category-MONITORING
tools/compose-driver.sh navigateBack
tools/compose-driver.sh textInput zebra tag=settings-search
tools/compose-driver.sh swipe UP tag=gallery-list
tools/compose-driver.sh scrollTo text="About and diagnostics"
tools/compose-driver.sh click tag=settings-category-AUDIO gif=1000   # -> build/compose-driver/screenshots/click.gif
tools/compose-driver.sh reset                      # recreate the current screen (fresh state)
tools/compose-driver.sh reset Gallery              # switch screen without restarting the server
tools/compose-driver.sh stop
```

`start` takes these options:

- `--device`: one of the following, or raw Robolectric qualifiers.
  - `phone` (default): 411x914 dp @ 420 dpi, i.e. 1080x2400.
  - `cover`: the Razr Fold cover screen, 411x960 dp.
  - `inner`: the Razr Fold inner screen, 850x946 dp.
  - `tablet`: 800x1280 dp.
  - `landscape`: 914x411 dp.
  - `compact`: 360x640 dp.
  - `w1280dp-h800dp-land-mdpi` (raw qualifiers): a 1280x800 dp desktop window, the capture inspector layout.
- `--night`: dark system mode. It only matters for the You theme; Cine is always dark.
- `--theme cine|you`: the app theme (default `cine`).
- `--port N`: the server port, default 8765. `COMPOSE_DRIVER_PORT` also sets it.

Device, theme and night mode are fixed per server, so changing them needs `stop` and then `start`.

Selectors:

- `tag=<testTag>`: exact match.
- `text=<text>`: exact match; add `substring=true` and/or `ignorecase=true` to loosen it.
- Combining `tag=` and `text=` requires both to match.
- With no selector, the command targets the root.

A selector must match exactly one node: `text=Photos` fails in the gallery, where both the filter chip and the take cards show "Photos". Prefer existing tags such as `settings-search`, `settings-category-<CATEGORY>`, `settings-back`, `gallery-list`, `gallery-search`, `media-action`, `exposure-strip`, `capture-start-rail` and `capture-end-rail`. Run `tree` for the full list.

For any other endpoint, call it raw: `tools/compose-driver.sh get <endpoint> k=v ...`. Examples are `waitForNode`, `longClick`, `doubleClick`, `textReplacement`, `textClearance`, `keyEvent` and `pointerInput/*`; see the upstream README for the full API. The HTTP API also answers directly, e.g. `curl "http://127.0.0.1:8765/printTree"` or `curl "http://127.0.0.1:8765/screenshot" -o out.png`.

Outputs and logs:

- Everything is written under `build/compose-driver/`, which git ignores.
- If `start` fails, read `tools/compose-driver.sh log`.
- Test stdout, including server stack traces, is in `app/build/test-results/testDebugUnitTest/`.

### Screens

| Screen | Production composable | State |
|---|---|---|
| `CapturePortrait` | `AdaptiveCaptureChrome` | photo, previewing, with the histogram |
| `CaptureRecording` | `AdaptiveCaptureChrome` | video take recording at 01:23; `click tag=recording-reveal-surface` reveals the chrome |
| `CaptureLandscape` | `AdaptiveCaptureChrome` | video, previewing (use `--device landscape` for the side rails) |
| `CaptureSheetWb` / `CaptureSheetFocus` | `AdaptiveCaptureChrome` | the white balance or focus panel open (focus marks A and B) |
| `CaptureModeSheet` | `AdaptiveCaptureChrome` | the phone's modal mode sheet open (a dialog: use `tree`) |
| `CaptureModes` / `CaptureMonitor` | `AdaptiveCaptureChrome` | the modes or the monitoring toggles in the docked pane |
| `CaptureHlg` | `AdaptiveCaptureChrome` | HLG selected, with LOG and HLG offered in the docked modes pane (use `--device inner`) |
| `CaptureScopes` / `CaptureScopesHidden` | `AdaptiveCaptureChrome` | waveform, vectorscope and false colour with live fake analysis; shown, or hidden as with H |
| `CaptureLocked` | `AdaptiveCaptureChrome` | controls locked while a photo saves, with the saved notice |
| `CaptureDesktop` | `AdaptiveCaptureChrome` | video with the scopes and a hardware keyboard (use `--device w1280dp-h800dp-land-mdpi`) |
| `Settings` | `SettingsScreen` | home; 840 dp or wider (`--device inner`) shows two panes |
| `SettingsRecording` | `SettingsScreen` | locked while recording |
| `Gallery` | `MediaCatalogContent` | seven takes (two LOG videos, DNG, legacy JPEG, AAC and WAV audio, a plain video) with gradient thumbnails, codec badges, one proxy ready and one being made |
| `GalleryInspector` | `MediaCatalogContent` | the same takes with take 1's details open in a sheet: at the bottom on `phone` and `compact`, at the side on the others. Screenshot it with `tag=gallery-info-sheet`. The docked side pane needs a large or expanded landscape window, e.g. `--device w1280dp-h800dp-land-mdpi` |
| `GalleryInspectorProbed` | `MediaCatalogContent` | take 7's details: a plain video whose codec chip comes from a fake file probe (its card has no codec badge) |
| `GalleryEmpty` / `GalleryLoading` / `GalleryError` | `MediaCatalogContent` | empty, first page pending, MediaStore failure |
| `About` | `AboutScreen` | real license catalog from assets |
| `Onboarding` | `OnboardingScreen` | first page, reduced motion |
| `CapturePeaking` | `MonitoringOverlay` under `AdaptiveCaptureChrome` | video, focus peaking on a synthetic 320×240 analysis frame (sharp ring, disc and glyphs peak; the soft disc must not); the frame is centred in the window, and portrait or landscape follows `--device` |
| `SubjectStatus` / `SubjectStatusRecording` | `SubjectDisplayScreen` | the exterior status display at rest ("Ready") or recording (REC 01:23, with a cue); use `--device cover` for every `Subject*` screen |
| `SubjectPrompter` / `SubjectPrompterRecording` | `SubjectDisplayScreen` | the teleprompter, without and with the REC header |
| `SubjectPreview` / `SubjectFillLight` / `SubjectReview` / `SubjectInterview` / `SubjectSlate` | `SubjectDisplayScreen` | the other subject modes: waiting for a frame, 4300 K fill light, no take yet, question 2 of 3, slate |
| `SelfCapture` / `SelfCaptureRecording` | `SelfCaptureChrome` | the self-recording controls on the cover, idle with a 3 s timer or recording |
| `FoldMenuPane` / `FoldMenuFolded` | `FoldDisplaySettings` in `AdaptiveCaptureChrome` | the Displays pane (`CaptureInitialPane.DISPLAYS`) unfolded with the teleprompter chosen, or folded (presentation unavailable); the fold state comes from `LocalFoldDisplayStateWithoutCoordinator` |
| `FoldMenu` | `FoldDisplaySettings` | the Settings hub version, which adds the inner-screen and folding section |

The capture screens run the fake camera and analysis in `CaptureDriverFakes.kt`, keep their own copy of the settings so toggles, F-keys and scope keys work, and pick the layout from the device: `phone` gives compact portrait, `landscape` the side rails, `inner` and `tablet` the stacked deck, and the 1280x800 dp window the inspector. A panel opens through `AdaptiveCaptureChrome(initialPane = …)`, which production never sets.

### Adding a screen

Add a zero-argument `@Composable fun` to `DriverScreens.kt`. Follow these rules:

- Call the real production composable; never copy its UI.
- Wrap it in `DriverTheme { }`. That gives the app theme over the same full-window background `CameraRootScreen` provides. The capture chrome uses `DriverTheme(forceDark = true)`, like `CaptureTheme`.
- Pass fake state (`CameraUiState(...)`, `CameraSettings(...)`, a fake `MediaCatalogSource`) and no-op callbacks. Leave `binder = null`.
- When a screen builds its own repository or service, render the stateless content composable it delegates to, as `Gallery` does with `MediaCatalogContent`. If no such seam exists, extract one in production code; that is the only production refactor this tooling justifies.
- Fakes stay in the test source set; never add them to `main`.
- `start <Fqn>` (or `reset <Fqn>`) also accepts any fully qualified zero-argument top-level composable, private `@Preview`s included, e.g. `com.librestatic.opencinecam.CameraScreenKt.LandscapeChromePreview`. Previews that do not apply `OpenCineCamTheme` themselves render with plain Material light defaults, so for real review add a `DriverScreens.kt` entry instead.

### Known limits

- The capture chrome renders, but the viewfinder does not. Camera2, `SurfaceView`/`TextureView`, the GPU viewfinder, codecs, playback and the LOG pipeline need the emulator or the Razr (see `docs/device-validation.md`).
- Window insets, display cutouts, the fold hinge and posture, and real system bars are not modelled. Robolectric reports zero insets and no `FoldDisplayCoordinator`.
- `LocalSubjectReviewFeed` and other hardware-backed locals are null. The capture screens provide a fake `LocalOperatorActions`; other screens leave it null.
- Screenshots do not capture dialogs, so a `ModalBottomSheet` such as the mode sheet is missing from the picture. Inspect it with `tree`, where it shows as `[IsDialog]`.
- Runtime permissions read as not granted.
- Endless frame loops (`while (true) withFrameNanos`) never let the test clock go idle, and requests fail after 60 s. Render those screens with `LocalReducedMotion provides true`, as `Onboarding` does.
- Robolectric lacks a few framework services. `ShadowThermalPowerManager` covers the thermal listener the capture HUD registers; add similar test-only shadows rather than changing production code.
- Fonts, shadows and blur come from Robolectric's native graphics. They are close to a device but not pixel-identical, so do final visual sign-off on a device.
