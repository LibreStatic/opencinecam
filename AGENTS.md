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
| `CaptureNightSuggested` / `CaptureNightOn` | `AdaptiveCaptureChrome` | photo in Auto on a camera with the night extension, the scene dark: the quick toggle (`capture-night-toggle`) offered beside the lock keys, or engaged (highlighted) |
| `CaptureFpsLock` / `CaptureFpsLockHolding` | `AdaptiveCaptureChrome` | video on a camera with exposure-time priority: the FPS LOCK key (`capture-fps-lock`) beside the lock keys, on, or amber while it is holding the shutter at 1/fps |
| `CaptureSlowMotion` / `CaptureSlowMotionLength` / `CaptureSlowMotionRecording` | `AdaptiveCaptureChrome` | slow motion at 1080p 240 fps (constrained high-speed) on a 30 fps project, bounded to 10 s: the strip reads RES · FPS · LENGTH · SHUTTER · ISO · WB with `capture-slot-length` at "10 s"; `Length` opens the take length panel (`take-length-panel`, the readout "10 s · plays 01:20" over the stepped slider `take-length-slider`, no limit to 60 s in 5 s stops; with no limit the slot reads "∞"); `Recording` is the take at "REC 00:07 / 00:10". Check `phone`, `landscape` and `de-w384dp-h832dp-port-xxhdpi` |
| `CaptureLensLocked` | `AdaptiveCaptureChrome` | video locked to the tele (lenses "3" 0.55, "2" 1.0, "5" 3.0; zoom 3–24×, at 4.5×; direct physical): the anchor bar is the lock bar (`zoom-anchor-bar-locked`, padlock on 3.0) and LENS LOCK (`capture-lens-lock`) is on; check `phone`, `inner` and `landscape` |
| `CaptureLensLockAvailable` / `CaptureLensLockCrop` | `AdaptiveCaptureChrome` | the lens lock offered but off (LENS LOCK beside the lock keys, normal anchors), or locked on the GPU-crop fallback with the "Lens lock: digital crop" notice (`lens-lock-crop`) under the zoom |
| `CaptureGraphFallback` | `AdaptiveCaptureChrome` | RAW photo whose session lost the RAW and analysis streams to the graph fallbacks: the "RAW and scopes unavailable with this configuration" notice (`graph-fallback-notice`) under the zoom; check `phone` and `landscape` |
| `CaptureCodecFallback` | `AdaptiveCaptureChrome` | a video take that asked for HEVC recording H.264: "Recording in H.264: HEVC encoder unavailable" as the `recording-warning` |
| `CaptureDestinationSsd` / `CaptureDestinationFallback` | `AdaptiveCaptureChrome` | video with a USB recording drive (PLAN-072): the next take goes to the drive, so the `USB` chip (`capture-destination-chip`) sits beside the status line, whose free space is the drive's (1.7 TB); or the drive is configured but not connected, so an amber `PHONE` chip and the "USB drive not connected: recording to the phone" notice (`capture-destination-fallback`) under the zoom, which in a column stops short of the lock keys. Both show in RAW video too (see `CaptureRawVideoSsd`). Check `phone`, `inner` and `landscape` |
| `CaptureDestinationStarting` | `AdaptiveCaptureChrome` | video with the drive configured and still spinning up (a USB storage device attached in the last 45 s, its volume not mounted yet): the amber `PHONE` chip (`capture-destination-chip`) and the "USB drive starting: recording to the phone" notice (`capture-destination-fallback`) under the zoom. Check `phone`, `inner` and `landscape` |
| `CaptureDestinationRenamed` | `AdaptiveCaptureChrome` | video with the configured drive mounted under another volume name (vold's stable name, `FFF1-7571-01`, after a mount was cut short): the amber `PHONE` chip (`capture-destination-chip`) and the "Choose USB folder again: recording to the phone" notice (`capture-destination-fallback`) under the zoom. Check `phone`, `inner` and `landscape` |
| `CaptureDestinationContinued` | `AdaptiveCaptureChrome` | PLAN-072 Delivery 4: a drive lost mid-take with *Continue on the phone*, the `_cont1` take recording on the phone at 00:04: the amber `PHONE` chip (`capture-destination-chip`) and the "Drive disconnected: continuing on the phone" notice (`capture-destination-continued`) under the zoom; `click tag=recording-reveal-surface` if the chrome is hidden. Check `phone`, `inner` and `landscape` |
| `CaptureRecording` | `AdaptiveCaptureChrome` | video take recording at 01:23; `click tag=recording-reveal-surface` reveals the chrome |
| `CaptureRecordingWarning` | `AdaptiveCaptureChrome` | the same take with the Adaptive policy's live warning (`recording-warning`) under the REC strip; check it on `phone` and `inner` |
| `CaptureLandscape` | `AdaptiveCaptureChrome` | video, previewing (use `--device landscape` for the side rails) |
| `CaptureSheetWb` / `CaptureSheetFocus` | `AdaptiveCaptureChrome` | the white balance or focus panel open (focus marks A and B) |
| `CaptureModeSheet` | `AdaptiveCaptureChrome` | the phone's modal mode sheet open (a dialog: use `tree`) |
| `CaptureModes` / `CaptureMonitor` | `AdaptiveCaptureChrome` | the modes or the monitoring toggles in the docked pane |
| `CaptureHlg` | `AdaptiveCaptureChrome` | HLG selected, with LOG and HLG offered in the docked modes pane (use `--device inner`) |
| `CaptureHlgPortrait` | `AdaptiveCaptureChrome` | HLG with no pane over a 9:16 portrait frame, so the instruments stack in a column beside the lock keys: the `log-source-badge` wraps short of them (check `w384dp-h832dp-port-xxhdpi`, the S24 Ultra) |
| `CaptureScopes` / `CaptureScopesHidden` | `AdaptiveCaptureChrome` | waveform, vectorscope and false colour with live fake analysis; shown, or hidden as with H |
| `CaptureThermal` / `CaptureThermalRecording` | `AdaptiveCaptureChrome` | video with the scopes, analysis suspended for heat: the "Scopes paused" notice sits in the instrument stack under the zoom (on its own line in a row layout); idle or recording |
| `CaptureLocked` | `AdaptiveCaptureChrome` | controls locked while a photo saves, with the saved notice |
| `CaptureOperatorLocked` | `AdaptiveCaptureChrome` | video recording with *lock during take* on: the slots give way to the Locked card with Unlock |
| `CaptureDesktop` | `AdaptiveCaptureChrome` | video with the scopes and a hardware keyboard (use `--device w1280dp-h800dp-land-mdpi`) |
| `CaptureRawVideo` / `CaptureRawVideoConfig` | `AdaptiveCaptureChrome` | RAW video on S24-like qualified configurations (full 4080×3060 @15/24/30, centre crops): the status line and info bar show MB/s and minutes left; `Config` opens the RES/FPS chooser (`raw-video-config-panel`, tiles `raw-video-config-<key>`; the block-pack twins of full@30 and 3840×2160@30, `raw-video-config-full@30+bp`, read " · Near-lossless" because the camera has a noise profile and the setting is on); check `phone`, `landscape` and `inner`, and `w384dp-h832dp-port-xxhdpi` with a locale prefix for the wrapped labels |
| `CaptureRawVideoRisky` | `AdaptiveCaptureChrome` | RAW video with 4080×3060 · 60 fps qualified as risky and chosen by hand: the RES/FPS chooser open with the tile's warning icon (content description "…, risky rate") and the hint `raw-video-config-risk-hint`, and the notice `raw-video-risky-notice` under the zoom, which with a known burst (a fake 75 s) reads "Risky rate: frames may drop after about 1 min (with a rested phone)" (on its own line in a row layout); Automatic never resolves to a risky configuration. Check `phone` and `landscape` |
| `CaptureRawVideoReducedRate` | `AdaptiveCaptureChrome` | the RES/FPS chooser with reduced-rate configurations: 4080×3060 · 60 fps risky and reaching 51.9 fps, 3840×2160 · 60 fps reaching 49.1 fps; their tiles (`raw-video-config-full@60`, `raw-video-config-3840x2160@60`) lead their detail with "Reaches … fps", the others show no rate; Automatic resolves to 4080×3060 · 30 fps (it never picks a reduced-rate configuration). Check `phone` and `landscape` |
| `CaptureRawVideoSsd` / `CaptureRawVideoSsdRecording` / `CaptureRawVideoDriveFallback` | `AdaptiveCaptureChrome` | RAW video with a USB drive (PLAN-072 Delivery 3): the next RAW take goes to the drive, so the `USB` chip (`capture-destination-chip`) and the drive's free space and minutes left (1734 GB, "1 h 1 min left" at full@30) in the status line; a RAW take on the drive at 00:42 (`click tag=recording-reveal-surface` reveals the chrome; the drive's figure comes from the service); or the drive configured but not connected, so the amber `PHONE` chip and the `capture-destination-fallback` notice, gated by the phone's qualification. Check `phone`, `landscape` and `de-w384dp-h832dp-port-xxhdpi` |
| `CaptureRawVideoRecording` / `CaptureRawVideoRingHigh` | `AdaptiveCaptureChrome` | a RAW take at 00:42 with the ring meter (`raw-ring-meter`) at 37 %, or at 84 % (red) |
| `CaptureRawVideoCandidate` / `CaptureRawVideoCandidateSheet` | `AdaptiveCaptureChrome` | RAW video not yet qualified: the modes pane or the mode sheet lists it as choosable under its gate label; choosing it (`click tag=capture-mode-raw_video`) opens the qualification prompt instead of switching |
| `CaptureRawVideoQualifyPrompt` / `CaptureRawVideoQualifyNoSpace` | `AdaptiveCaptureChrome` | the qualification prompt (`raw-video-qualify-prompt`, a dialog: use `tree`) with a partial-space line and `raw-video-qualify-start`/`raw-video-qualify-later`, or the no-space variant with only `raw-video-qualify-later` |
| `CaptureRawVideoQualifyPromptDrive` / `RawQualificationReadyDrive` / `RawQualificationReadyDriveAbsent` | `AdaptiveCaptureChrome` / `RawQualificationStep` in `RawQualificationFrame` | qualifying a USB drive (PLAN-072 Delivery 3): the prompt (a dialog: use `tree`) leads its facts with "Testing the USB drive WD Elements"; the step shows it as `raw-qualification-target` above the checklist and the drive's 1.7 TB in `raw-qualification-free`; `Absent` is a configured drive not connected, so the phone is tested ("Testing the phone's storage"). Without a drive configured nothing new shows. Check `phone`, `landscape` and `de-w384dp-h832dp-port-xxhdpi` |
| `RawQualificationReady` / `RawQualificationReadyAllFit` / `RawQualificationBlocked` / `RawQualificationBatteryLow` / `RawQualificationBatteryCharging` | `RawQualificationStep` in `RawQualificationFrame` | the qualification step (`raw-qualification-step`) as the mode selector opens it, over the wizard's still shapes: chips (`raw-qualification-chips`), the checklist card (`raw-qualification-checklist`) all passing with `raw-qualification-start` enabled and, with 26 GB free, the space section (`raw-qualification-space`: free-space bar, `raw-qualification-tested`, `raw-qualification-skipped` with 3840×2160 · 60 fps needing 54 GB and 4080×3060 · 60 fps 81 GB); `AllFit` has room for everything, so only the compact `raw-qualification-free` line; `Blocked` is battery 3 % and hot (thermal status severe), each check saying what to do and Start disabled; `BatteryLow` is battery 35 % and cool, the battery row (`raw-qualification-check-battery`) a warning (amber triangle, "plug in the charger") and Start enabled. `BatteryCharging` is the same 35 % on external power: the row passes and reads "35% · charging", with no warning. The ready screen scrolls with the "More below" cue on `phone`; check `phone`, `landscape` and `inner` |
| `RawQualificationRunning` / `RawQualificationRunningSlow` / `RawQualificationRunningBench` / `RawQualificationRunningRest` / `RawQualificationRunningSweep` / `RawQualificationRunningCompressed` / `RawQualificationDone` / `RawQualificationDoneHot` / `RawQualificationNone` | `RawQualificationStep` in `RawQualificationFrame` | the run in its order (camera checks heaviest first while the phone is cool, the compression benchmark, a 2 min storage rest, the sweep, the long runs): the running list (`raw-qualification-items`, rows `raw-qualification-item-<spec>`) leads with the row in progress, the rest heaviest first with each compressed twin after its twin. Camera check 4/15 (`raw-qualification-ring` counts steps, `raw-qualification-overall` labels the overall bar `raw-qualification-progress`; 24 fps Testing on top with the live rate `raw-qualification-live-fps`, "23.4 / 24 fps" in green, 4080×3060 · 30 fps "Measured" (outlined, muted check, with its rate), two "No space"); `Slow` is check 7/15 with 1920×1080 · 60 fps Testing at "49.8 / 60 fps" (under the tolerance: amber) and the four checked before it Measured with their rates; `Bench` is the compression benchmark (9/15) streaming 4080×3060 · 30 fps: its row leads, highlighted and still Measured, with "29.7 / 30 fps" in place of its rate; `Rest` is the storage rest ("Resting before the storage test") and `Sweep` the storage test after it at 45 %, both keeping the ring at 9 / 15 with every fitting row Measured and no live rate; `Compressed` is a compressed (block-pack) long run at 11/13 (live "29.8 / 30 fps", on top) after the descent stopped at 4080×3060 · 30 fps (Ready), the longest header label (the size and rate shrink to one line, "· Compressed" goes below); six configurations ready (`raw-qualification-passed`) and the two skipped ones in `raw-qualification-left-out` with `raw-qualification-retry-hint` and `raw-qualification-done`, each section heaviest first; `DoneHot` is a pass the heat cut short (two 60 fps entries failed, 3840×2160 and 1920×1080 at 30 fps "Too hot" with a thermometer, `raw-qualification-heat-hint`); or none qualified. Check the S24 Ultra width `w384dp-h832dp-port-xxhdpi`, also with a locale prefix (`de-w384dp-h832dp-port-xxhdpi`), for chips on one row and labels that never truncate, and `phone` for the row in progress showing without scrolling |
| `RawQualificationDoneSlowDrive` | `RawQualificationStep` in `RawQualificationFrame` | a USB drive too slow for RAW (a 91 MB/s sweep) on a phone that also got hot: "No configuration qualified", seven rows in `raw-qualification-left-out` reading "storage too slow for this rate" (the sweep had ruled them out, so the heat of their run is not the cause) and only 1920×1080 · 30 fps, which the drive could hold, "skipped: the phone was too hot" with the thermometer and `raw-qualification-heat-hint`. Check `phone` and `w384dp-h832dp-port-xxhdpi`, also with a locale prefix |
| `RawQualificationDoneCameraLimit` | `RawQualificationStep` in `RawQualificationFrame` | the Razr's result: its HAL delivers RAW at 30 fps at most, so full, 3840×2160 and 1920×1080 at 60 fps are left out as "Camera limit" (camera-off icon, "this camera doesn't deliver RAW at 60 fps" in `raw-qualification-left-out`), and the five configurations at 15 to 30 fps are in `raw-qualification-passed`; check `w384dp-h832dp-port-xxhdpi`, also with a locale prefix |
| `RawQualificationStorageBusy` | `RawQualificationStep` in `RawQualificationFrame` | a run stopped after a sweep that found the write cache still flushing (`storage-not-rested`, a second qualification soon after a full one): "Storage needs to rest" with its body, the hourglass hint `raw-qualification-rest-hint` (leave the phone idle for about an hour) and, since the camera was qualified before, `raw-qualification-rest-kept`; no configuration list, only `raw-qualification-done`. Check `w384dp-h832dp-port-xxhdpi`, also with a locale prefix |
| `RawQualificationDoneRisky` | `RawQualificationStep` in `RawQualificationFrame` | the Razr-like result under the 1.1225x margin: 4080×3060 · 60 fps kept up but its storage median is under the margin, so it sits in its own card (`raw-qualification-risky`, body `raw-qualification-risky-body`, row `raw-qualification-item-full@60` with a warning icon and "storage too slow for this rate; frames may drop after about 1 min (with a rested phone)", a fake 75 s burst; during the run the item shows the outlined amber "Risky rate" pill); the 60 fps crops and everything at 15 to 30 fps are in `raw-qualification-passed`. Check `w384dp-h832dp-port-xxhdpi`, also with a locale prefix |
| `RawQualificationDoneReducedRate` | `RawQualificationStep` in `RawQualificationFrame` | reduced-rate passes (the camera holds a steady rate under its target, no hotter than MODERATE): 4080×3060 · 60 fps risky and reaching 51.9 fps (`raw-qualification-item-full@60` in `raw-qualification-risky`, "Reaches 51.9 fps" above the risk line), 3840×2160 · 60 fps qualified but reaching 49.1 fps (`raw-qualification-item-3840x2160@60` in `raw-qualification-passed`); every other row shows the rate it achieved ("29.9 fps"). Check `w384dp-h832dp-port-xxhdpi`, also with a locale prefix |
| `OnboardingRawQuestion` | `OnboardingScreen` | the wizard's last question (`onb-raw-question`, `onb-raw-now`, `onb-raw-later`) over the still shapes, with the same chips as the step; shown only with the camera granted and the back camera's RAW video a candidate |
| `Settings` | `SettingsScreen` | home; 840 dp or wider (`--device inner`) shows two panes; Capture has the Lens lock row (`settings-lens-lock`, pills `lens-lock-off`/`lens-lock-<id>`) and the opt-in Smooth lens switch (`settings-smooth-lens-switch`); Controls has the Scroll long text row (`settings-scroll-long-text`, help `scroll-long-text-help`) and the Rotate with rotation lock on row (`settings-rotate-when-locked`); Media's playback settings have the RAW playback row (`playback-raw-row`, Automatic or Frame viewer) |
| `SettingsVolumeKeys` | `SettingsScreen` | the volume keys as a zoom pair with Both keys set to Capture: `click tag=settings-category-CONTROLS` reaches Operator controls with the *Volume keys* pills (`operator-volume-pair-<PAIR>`), Invert direction (`operator-volume-pair-inverted`), Both keys (`operator-volume-both`) and the help (`operator-volume-gestures-help`); clicking `operator-volume-pair-NONE` shows Press, Hold and Double press per key (`operator-volume-up`, `-up-long`, `-up-double`, `-down`, `-down-long`, `-down-double`). Check `phone`, `inner` and `de-w384dp-h832dp-port-xxhdpi` |
| `SettingsRecording` | `SettingsScreen` | locked while recording |
| `SettingsRecordingPolicy` | `SettingsScreen` | the Adaptive recording policy; `textInput policy tag=settings-search` shows the Strict/Adaptive row (`recording-policy-*`) |
| `SettingsRawVideo` / `SettingsRawVideoCandidate` | `SettingsScreen` | RAW video qualified (Capture > `settings-raw-video`, pills `raw-video-option-auto`/`raw-video-option-<key>`, the outlined "Qualify again" `settings-raw-video-requalify` with its note, and the RAW compression row `settings-raw-compression` with `raw-compression-lossless`/`raw-compression-near-lossless`, shown because block-pack twins are qualified and the camera has a noise profile; `textInput compression tag=settings-search` reaches it), or not yet qualified with the hint to choose RAW video in the mode selector (`settings-raw-video-hint`) |
| `SettingsRecordingDestination` / `SettingsRecordingDestinationRejected` | `SettingsScreen` | Recording > Recording destination (`settings-recording-destination`, pills `recording-destination-phone`/`recording-destination-usb`) with a fake store through `LocalRecordingDestinationSettings`: a drive "WD Elements" configured and connected (`recording-destination-status` "Connected · 1.7 TB free", its measured speed `recording-destination-speed` "Writes at 412.3 MB/s", `recording-destination-change` and `recording-destination-measure`) and the If the drive disconnects row (`settings-recording-drive-disconnect`, `recording-drive-disconnect-stop_and_save`, `-continue_on_phone`, whose summary changes with the choice); clicking Phone clears the drive and hides that row. `Rejected` is the phone with the FAT32 refusal (`recording-destination-error`). `textInput usb tag=settings-search` reaches the rows; check `phone`, `inner` and `de-w384dp-h832dp-port-xxhdpi` |
| `SettingsRecordingDestinationStarting` | `SettingsScreen` | the same drive plugged in moments ago and still spinning up: `recording-destination-status` reads "Starting: takes go to the phone until it is ready" in amber, and `recording-destination-measure` is absent (only a connected drive offers it). `textInput usb tag=settings-search` reaches the row; check `phone` and `de-w384dp-h832dp-port-xxhdpi` |
| `SettingsRecordingDestinationRenamed` | `SettingsScreen` | the same drive mounted under another volume name: `recording-destination-status` reads "Connected under another name: choose the folder again" in amber, `recording-destination-change` is offered and enabled, and `recording-destination-measure` is absent. `textInput usb tag=settings-search` reaches the row; check `phone` and `de-w384dp-h832dp-port-xxhdpi` |
| `SettingsRecordingDestinationSlowDrive` / `SettingsRecordingDestinationMeasuring` | `SettingsScreen` | the drive speed measured when a folder is adopted (and again with `recording-destination-measure`): a "Generic flash" drive at 9.1 MB/s against 40 Mb/s video at 60 fps, which needs 10.0 MB/s (a drive must beat the need by 25 %), so the amber `recording-destination-speed-slow` "Writes at 9.1 MB/s: slower than the 10.0 MB/s these settings need" replaces the speed line; or the measurement running (`recording-destination-measuring`, a polite live region) with the pills, Change folder and Measure speed disabled. `textInput ssd tag=settings-search` reaches the row; check `phone` and `de-w384dp-h832dp-port-xxhdpi`, where Measure speed wraps under Change folder |
| `CapabilitiesExport` | `CameraCapabilitiesScreen` | the report export dialog open (`capabilities-export-*` tags): redacted or full, consent gate, Save/Share/Copy |
| `CapabilitiesMain10` | `CameraCapabilitiesScreen` | the latest Main10 file check card (`capabilities-main10-check`): HLG signalling passed, effective precision "needs a controlled capture" |
| `CapabilitiesDeviceProfile` | `CameraCapabilitiesScreen` | the device profile card (`capabilities-device-profile`): the Razr's bundled `blanc` profile read on another build, so "Active, not verified on this build" with the two high-speed quirks (`capabilities-quirk-*`) |
| `NetworkDrive` / `NetworkDriveOriginals` / `NetworkDriveRunning` / `NetworkDriveRunningRevealed` | `NetworkDriveContent` | the network drive page (Settings > Transfers > `settings-open-network-drive`): stopped with the switch (`network-drive-switch`) off and the original `.ocraw` files hidden (`network-drive-show-originals` off, the default; `Originals` has it on, and clicking it toggles), or running with two fake addresses, user `occ` and a fake 16-character password (`network-drive-credentials`, `network-drive-url-<i>`, `network-drive-user`, `network-drive-password`, each with a `-copy` button), hidden as bullets with `network-drive-password-reveal`, or revealed in groups of four; both show the security help (`network-drive-security-help`), the per-OS steps (`network-drive-connect`) with the Windows 50 MB / 4 GB help, the show-originals switch with its help (`network-drive-show-originals-help`), the copy-before-editing advice and the auto-stop note |
| `StorageBenchmark` | `StorageBenchmarkContent` | a finished qualified benchmark: outcome, margin and three windows |
| `LensCalibration` / `LensCalibrationEmpty` | `LensCalibrationContent` | the lens calibration page (`lens-calibration-page`): a saved three-lens calibration (0.5x with a parallax flag, 1x reference, 2x) with the video and alignment switches on, or no calibration yet |
| `LensCalibrationRunning` / `LensCalibrationResult` / `LensCalibrationFailed` | `AdaptiveCaptureChrome` | the `lens-calibration-card` over the viewfinder, above the deck: measuring (0.5x done, 2x at 40 %, Cancel), the result with Discard and Save, or a failed run listing each lens' numbers and flags; check `phone` and `landscape` |
| `Gallery` | `MediaCatalogContent` | seven takes (two LOG videos, DNG, legacy JPEG, AAC and WAV audio, a plain video) with gradient thumbnails, codec badges, one proxy ready and one being made |
| `GalleryInspector` | `MediaCatalogContent` | the same takes with take 1's details open in a sheet: at the bottom on `phone` and `compact`, at the side on the others. Screenshot it with `tag=gallery-info-sheet`. The docked side pane needs a large or expanded landscape window, e.g. `--device w1280dp-h800dp-land-mdpi`. After Integrity come the technical sections, built by the real `buildTakeDetails` from fake reader inputs (`DriverTakeDetails.kt`): a full clip sidecar with the camera block, the OCLog sidecar and a probe give File, Video, Colour, Audio, Camera and exposure, and Recording (`gallery-detail-<group>`, rows `gallery-detail-<group>-<key>` with `-value`; the path's copy key is `gallery-detail-path-0-copy`); `scrollTo tag=gallery-detail-camera` to reach them |
| `GalleryInspectorProbed` | `MediaCatalogContent` | take 7's details: a plain video whose codec chip comes from a fake file probe (its card has no codec badge); with no clip sidecar its sections hold only what the probe and MediaStore give (File, Video, Colour, Audio) |
| `GalleryPhotoInspector` / `GalleryAudioInspector` | `MediaCatalogContent` | the DNG photo's details (EXIF in Image, Camera and exposure, the colour filter in RAW), or the WAV recorder track's (File and Audio only) |
| `GalleryDrive` / `GalleryDriveInspector` | `MediaCatalogContent` | the sample takes merged by date with three on the recording drive (PLAN-072): a declared 4K video take with its WAV, a RAW take (its folder's `.ocraw`, badged RAW10) and a still folder whose sidecar was lost ("Take record missing"), each with the `USB` badge (content description "On the USB drive"; its `gallery-location-ssd` tag merges into the card) and no `gallery-rename-<id>` in its menu; the inspector shows the "On the USB drive" chip (`gallery-details-location-ssd-<id>`), no Rename, and paths as the drive's name and folder (`WD Elements/Takes/OpenCineCam/…`). Check `phone` and `inner` |
| `GalleryInspectorLoading` | `MediaCatalogContent` | take 1's details whose file reads never finish: the `gallery-detail-loading` line in place of the technical sections |
| `GalleryRaw` / `GalleryRawInspector` | `MediaCatalogContent` | two `.ocraw` takes (`textInput ocraw tag=gallery-search` to isolate them): the codec badge their first chunk declares (`gallery-codec-raw:<id>`, RAW10 and Near-lossless; "RAW" until it is read), size instead of duration, in `GalleryRaw` the first take's tile with a picture (its developed first frame, faked as a gradient) and the second's with the neutral placeholder of a take whose frame gave none, Play (`media-action`) opens the RAW review, no rename/delete; the inspector offers Play and Share and the RAW integrity text (a developed preview here, full quality through the desktop DNG converter), then the sections summarised from a constructed frame index (`gallery-detail-raw`: encoding, levels, compression, data rate, missing frames; the second take is near-lossless 3840×2160 at 30 fps with two missing frames) and the copyable build fingerprint (`gallery-detail-fingerprint-copy`) |
| `GalleryCinemaDngExport` / `GalleryCinemaDngExportFolder` / `GalleryCinemaDngExportNoSpace` | `CinemaDngExportForm` | the CinemaDNG export form for the first RAW take, drawn in the dialog card's look (`cinemadng-export-dialog`) because screenshots leave dialogs out: Documents with LJ92 offered, two damaged frames (`cinemadng-export-skipped`) and "Needs 23 GB · 26 GB free" (`cinemadng-export-space`); a picked USB folder (`cinemadng-export-folder`) with LJ92 on, an estimate and unknown free space; or not enough space with `cinemadng-export-start` disabled |
| `GalleryRawExporting` / `GalleryRawExportingInspector` / `GalleryRawExported` | `MediaCatalogContent` | a fake `CinemaDngExportControl`: the first RAW take exporting at 517/1440 (`cinemadng-export-progress`, `cinemadng-export-cancel`) on its card or in its details (screenshot with `tag=gallery-info-sheet`), or the "Exported to…" notice (`cinemadng-export-notice`) above the grid. The card menu's `gallery-export-<id>` and the inspector's `gallery-inspector-export` open the export dialog |
| `PlaybackPhotoZoom` | `PlaybackStageArea` | the review's photo stage in full screen (the dialog itself is a window screenshots leave out) with a 4:3 test chart; the readout `media-playback-zoom-readout` shows scale, pan and single taps. `pointerInput/down` ignores `x`/`y` and lands at the window centre, so pinch by parking finger 0 with `moveTo`, then putting finger 1 down and `moveTo`-ing it outward (from 141 px to 283 px apart is 2x); drag one finger while zoomed to pan, `doubleClick tag=media-playback-zoom-area` toggles 2.5x, and a single `click` counts after the double-tap timeout |
| `GalleryEmpty` / `GalleryLoading` / `GalleryError` | `MediaCatalogContent` | empty, first page pending, MediaStore failure |
| `About` | `AboutScreen` | real license catalog from assets |
| `Onboarding` | `OnboardingScreen` | first page, reduced motion |
| `CapturePeaking` | `MonitoringOverlay` under `AdaptiveCaptureChrome` | video, focus peaking on a synthetic 320×240 analysis frame (sharp ring, disc and glyphs peak; the soft disc must not); the frame is centred in the window, and portrait or landscape follows `--device` |
| `SubjectStatus` / `SubjectStatusRecording` | `SubjectDisplayScreen` | the exterior status display at rest ("Ready") or recording (REC 01:23, with a cue); use `--device cover` for every `Subject*` screen |
| `SubjectPrompter` / `SubjectPrompterRecording` | `SubjectDisplayScreen` | the teleprompter, without and with the REC header |
| `SubjectPreview` / `SubjectFillLight` / `SubjectReview` / `SubjectInterview` / `SubjectSlate` | `SubjectDisplayScreen` | the other subject modes: waiting for a frame, 4300 K fill light, no take yet, question 2 of 3, slate |
| `SelfCapture` / `SelfCaptureRecording` | `SelfCaptureChrome` | the self-recording controls on the cover, idle with a 3 s timer or recording |
| `SubjectPrompterSplit` / `SubjectStatusSplit` / `SubjectInterviewSplit` / `SubjectSlateSplit` / `SubjectReviewSplit` | `SubjectDisplayScreen` | the split cover: the mode on top, the rear camera preview below a thin divider (no viewfinder headless, so the lower half is empty); use `--device cover` |
| `SubjectFillLightSplit` | `SubjectDisplayScreen` | fill light on top, the camera preview below under the 50% black dither mask (`subject-preview-dither`) |
| `FoldMenuPaneSplit` | `FoldDisplaySettings` in `AdaptiveCaptureChrome` | the Displays pane with the teleprompter and its *Split screen with camera* switch on (use `--device inner`) |
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
- The marquee for long text (`LocalScrollLongText`, `cineMarquee`, `FitText`) is never provided in driver screens, because it animates forever; they render the ellipsis or the status line's two-row fallback. Check the scrolling on a device.
- Endless frame loops (`while (true) withFrameNanos`) never let the test clock go idle, and requests fail after 60 s. Render those screens with `LocalReducedMotion provides true`, as `Onboarding` does.
- Robolectric lacks a few framework services. `ShadowThermalPowerManager` covers the thermal listener the capture HUD registers; add similar test-only shadows rather than changing production code.
- Fonts, shadows and blur come from Robolectric's native graphics. They are close to a device but not pixel-identical, so do final visual sign-off on a device.

## Baseline profile and startup benchmarks

- Why: Compose is unbundled, so a fresh install runs interpreted until ART compiles it; the baseline profile (`app/src/main/generated/baselineProfiles/`, merged into `:app`) precompiles startup, the capture chrome, the mode sheet and the gallery. Debug is interpreted and has Live Literals, so never judge performance on a debug build. Android Studio and `adb install` skip profiles, so measure with the benchmark tasks below or `adb shell cmd package compile -m speed-profile -f <pkg>`.
- Module `:baselineprofile` (`com.android.test`, UiAutomator, Macrobenchmark) targets the `benchmarkRelease` (R8, profileable) and `nonMinifiedRelease` variants of `:app`. Both use the app id `com.librestatic.opencinecam.benchmark`, debug signing and x86_64 libs, so they coexist with the Play install. Release builds never run generation and never need a device.
- Generate: `./gradlew :app:generateBaselineProfile` writes `baseline-prof.txt` and `startup-prof.txt` under `app/src/main/generated/baselineProfiles/`; commit both. Regenerate after UI changes that alter startup, the capture chrome, the mode sheet or the gallery.
- Benchmark: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest` runs `StartupBenchmarks` (cold start with `CompilationMode.None` against `Partial(BaselineProfileMode.Require)`, plus gallery frame timing).
- Both need one connected device or emulator on API 33 or newer (rooted or userdebug below that); `ANDROID_SERIAL` picks it. The journeys grant camera, audio and notification permissions with `pm grant`.
- Only Razr numbers count; emulator numbers are smoke checks. Macrobenchmark refuses emulators unless you add `-Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR`. Generation works on emulators without it.
- Run nothing else heavy meanwhile: under host load the emulator hangs, and ddmlib then drops it with "Unknown API Level". The benchmarks find views through `testTagsAsResourceId` set on the root in `MainActivity`.

## Versioning

- Bump `versionName` in `app/build.gradle.kts` in its own commit before every store release; never upload a new build under the previous version. Patch (`x.y.Z`) for fixes and small, self-contained changes (including a single small feature), minor (`x.Y.0`) for medium changes (several features, a reworked screen or flow), major (`X.0.0`) for a large overhaul; keep the pre-release suffix (e.g. `-beta`) on alpha releases, and drop it for production (the first was `1.0.0`). The `versionCode` must also be higher than the last uploaded one.

## Publishing to Google Play

`tools/play-publish.py` publishes through the Play Developer API, so a release needs no Console clicks and no manual upload.

- The key is the organisation-wide LibreStatic service account `play-publisher@librestatic-publisher.iam.gserviceaccount.com`. Its JSON key lives in `~/.android/librestatic/` (override the path with `LIBRESTATIC_PLAY_KEY`). Never copy it into a repository, print it or paste it anywhere.
- The script only needs the standard library and the system `openssl`.
- `tools/play-publish.py status` lists every track with its releases.
- `tools/play-publish.py publish --aab <aab> [--mapping mapping.txt] [--track alpha] [--dry-run] [--no-review]` does the following:
  1. uploads the bundle, and the R8 mapping if given;
  2. puts one release named `<versionCode> (<versionName>)` on the track, with the notes from `store/play/release-notes/<versionName>.txt`;
  3. commits the edit, which sends it for review.
- Run `--dry-run` first: it validates the edit and discards it.
- If the commit says the changes cannot be sent automatically, rerun with `--no-review`, then send them from Publishing overview.
- `tools/play-publish.py listings [--dry-run] [--no-review]` sets the title, short and full description of every locale folder under `store/play/listings/` (images and other locales are untouched) and prints which locales changed. Run `python3 store/play/check_listings.py` first. It needs the service account's *Manage store presence* permission.
- The closed track is `alpha`; production is `--track production` (add `--rollout 0.2` for a staged rollout). Production must target countries first, which only the Console can set; `publish` checks it before uploading.
- If the API answers 403, the service account lacks access in Play Console > Users and permissions. Ask the user; do not grant it yourself.
