# OpenCineCam: professional camera and foldable experience

**Status:** scope approved in stages; implementation in progress.
**Date:** 2026-09-05.
**Reviewed baseline:** `libremagic` repository, commit `1110b20`, declared app version `0.3.3`.
**Audience:** product, design, Android/camera/media development, and validation.
**Planning outcome:** H1 → H2 → H3 → H4 → H5; one final APK when the set is complete. The execution log distinguishes code, tests, and physical validation.

## 1. Goal and summary

Consolidate what was discussed about Blackmagic Camera for Android/iOS, ProShot, and foldables into a work program for OpenCineCam. The goal combines three improvements:

1. **Complete everyday controls:** first the torch, its intensity, and use in LOG; then shutter by angle, presets, stabilization, and image processing.
2. **Complete the professional workflow:** monitoring, color, audio, take organization, and files that are useful for editing.
3. **Give the screens of a foldable different roles:** the operator controls from the inside, and the subject receives framing, cues, or text on the outside, when the device supports both screens being active.

The comparison serves as a functional reference, not as a claim that competitors universally lack foldable support. Nor does it turn an iOS feature into a capability available on Android. The proposal is an in-house implementation, with availability verified per camera, device, and mode.

The flash priority, the interest in foldables, and the centralized configuration in Settings come from the user's request. The remaining order, the initial values, and the acceptance limits are proposals to review during planning. The Settings view will be reorganized to make it easier to read, search, and operate, instead of piling controls into an ever-longer list.

## 2. Starting point: what exists and what is missing

This audit is static: local sources and documentation were reviewed, and no new physical tests of the app were run. A class, an enum, or a plan marked as finished does not by itself prove that the feature is accessible and validated end to end.

| Area | Current evidence | Gap to resolve |
|---|---|---|
| Flash/torch | `flashEnabled`, a switch in settings, `setTorchEnabled()`, and `FLASH_MODE_TORCH/OFF`. The UI and the service exclude LOG. | Quick access, intensity, LOG path, and differentiated photo flash. |
| Manual controls | ISO, exposure, focus, WB, compensation, touch metering, and AE/AF locks. | Angle, anti-flicker, accessible tint, and explicit semi-automatic modes. The current composition enables manual exposure when ISO and time are both set. |
| Zoom/focus/anamorphic | Zoom and lens selection, focus marks, transitions, and desqueeze. | Polish persistence, controls, and tests; avoid reimplementation. |
| Monitoring | Histograms, zebra, peaking, grids, and horizon in the UI; false color, waveform, and vectorscope components. | Integrate the pending tools and configure thresholds, colors, and opacity. |
| LUT/color | Rec.709 assist for LOG and LUT components; experimental OCLog2. | Library/import and transforms that differ per output; close out the pending color evidence. |
| Stabilization/ISP | Stabilization and processing models in the request composition. | A selector wired to the effective path, and independent OIS/EIS and image sharpness/noise controls. |
| Audio | AAC, WAV/FLAC depending on the path, inputs, effects, and float PCM in the model; channels limited to one or two. | Listening, manual gain, routing, and multichannel. Meters are not equivalent to listening. |
| Photography | JPEG, DNG, burst, fixed −2/0/+2 EV bracket, and one-second-exposure `LIGHT_TRAIL`. | RAW+JPEG, HEIC, extensions, configurable bracket, and Light/Water/Stars/Bulb accumulation. |
| Time/video | Video, slow motion, timelapse, internal timecode, and integer FPS controls. | Pause, real rational FPS, separate off-speed, and more timecode validation. |
| Files | Saving and local gallery; sidecars and technical reports. | Linked proxies, clapperboard, search by take, and more complete review. |
| Settings | `SettingsScreen` gathers controls in a `LazyColumn`; there are 12 sp titles, 10 sp descriptions, and compact options. | Navigation by category, search, and more legible typography; centralize configuration and sync it with the viewfinder. |
| Foldables | Continuity, preview reconnection, geometry, and adaptive components. | Verified outer-screen capability, dual presentation, roles, and per-posture layouts. |
| Network/accessories | The product declares local operation and the absence of the Internet permission. | Remote control, streaming, and cloud require a prior product decision/ADR. |

### 2.1 Local sources to pick the work back up

- [Overall status and validation conditions](../README.md).
- [Requirements](requirements.md), [architecture](architecture.md), and [plans index](plans/README.md).
- [Settings and persistence](../app/src/main/java/com/librestatic/opencinecam/CameraSettings.kt).
- [Main interface](../app/src/main/java/com/librestatic/opencinecam/CameraScreen.kt) and [UI state](../app/src/main/java/com/librestatic/opencinecam/CameraUiState.kt).
- [Capture ownership and commands](../app/src/main/java/com/librestatic/opencinecam/service/CaptureService.kt).
- [Camera2 engine](../camera/src/main/java/com/librestatic/opencinecam/camera/Camera2PreviewEngine.kt).
- [Controls composition](../camera/src/main/java/com/librestatic/opencinecam/camera/CaptureRequestComposition.kt).
- [Continuity](../app/src/main/java/com/librestatic/opencinecam/ActivityContinuity.kt) and [preview](../app/src/main/java/com/librestatic/opencinecam/ui/viewfinder/SurfaceViewPreviewController.kt).
- [Scopes/LUT](../camera/src/main/java/com/librestatic/opencinecam/camera/VectorscopeLut.kt), [focus/waveform](../camera/src/main/java/com/librestatic/opencinecam/camera/FocusWaveform.kt), and [false color](../camera/src/main/java/com/librestatic/opencinecam/camera/ColorScopes.kt).
- [Timecode](../camera/src/main/java/com/librestatic/opencinecam/camera/SmpteTimecode.kt), [audio](../media/src/main/java/com/librestatic/opencinecam/media/audio/ProfessionalAudio.kt), and [gallery](../app/src/main/java/com/librestatic/opencinecam/storage/LocalMediaRepository.kt).

### 2.2 Inherited conditions that remain open

OCLog2 remains experimental; a Main10 label does not prove effective precision. RAW video keeps its gate closed after the failed performance test, and APV has an unsupported-hardware branch. Those results are preserved until there is new evidence for the exact combination.

There is also documentation that needs reconciliation: [ADR-0029](adr/ADR-0029-professional-audio-formats-and-controls.md) retains old LOG audio decisions, while the README and code describe later progress. Planning must resolve the divergence instead of assuming that code or historical text automatically authorizes the next change.

## 3. Proposed implementation rules

1. Keep a single capture authority in `CaptureService`; no screen opens a second camera on its own.
2. Separate **requested**, **available**, **applied/reported**, and **validated**. Record the rejection and its cause, including temporarily unavailable capability.
3. Qualify complete combinations: camera/lens, firmware/API, session, resolution, FPS, codec, color, audio, outputs, and posture. Do not combine independent capabilities as if they all coexisted.
4. Distinguish live changes from changes for the next take. Codec, file geometry, and audio format stay fixed during the clip; each new control declares its policy.
5. Keep preview, overlays, mirroring, rotation, monitoring LUT, and recording stream as separate decisions.
6. Use public APIs. Manufacturer-specific paths require evaluation and a documented decision; the initial proposal keeps [ADR-0004](adr/ADR-0004-surfaceview-preview-and-foldable-lifecycle.md).
7. Preserve accessible UI, Spanish/English text, and touch targets of at least 48 dp. States do not depend on color alone.
8. Prioritize file and audio integrity over auxiliary tools. Every planned degradation must be visible and respect the existing Strict/Adaptive policy.
9. Keep the app local by default. Network, cloud, and streaming stay in a phase conditioned on an explicit decision.
10. Every user-configurable preference will have a canonical location in Settings and, where appropriate, quick access in the viewfinder. Each delivery documents its coverage and the justified exceptions; a new feature is not left configurable only through constants, manual files, or hidden gestures.

## 4. CAM package: controls and capture experience

The identifiers in this document are proposals; they are not yet accepted new `OCC-*` requirements. P0 identifies the first improvement or an investigation that defines feasibility; P1, the core of professional and foldable use; P2, later expansions; P3, optional integrations. Priority does not replace milestone dependencies.

### CAM-01 — Torch, intensity, and photo flash [priority P0]

**Initial delivery:** quick torch control in the viewfinder, off/on, actual level, and availability per mode, including the LOG path once it passes its tests.

- Conceptually replace the single boolean with torch state and requested/effective strength. Keep the `OFF/AUTO/ON` photo mode separate from the continuous video light.
- Migrate `flashEnabled` preserving the historical torch intent. Do not turn it into AUTO flash or unexpectedly turn on the light when restoring a preset.
- Enumerate supported levels; show `level n/N` or clearly normalized percentages. A percentage of the control range does not promise linear light output.
- Query strength for capture and torch separately. If only on/off exists, the UI shows fixed intensity; if the capability is unknown, it indicates that state.
- Evaluate Camera2's `FLASH_STRENGTH_LEVEL` on compatible versions/requests. Flashlight control with the camera closed does not establish regulation during recording. [Android reference](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#FLASH_STRENGTH_LEVEL).
- Integrate into compatible preview, video, LOG/GPU, and photo requests; test HFR separately. Avoid removing the LOG exclusion without covering the real path.
- For photo flash, implement the necessary metering/precapture and confirmation sequence; evaluate its interaction with manual exposure.
- Restore a coherent state when switching cameras, when a session fails, or when capture closes. A thermal interruption must be reflected in the viewfinder.

**Acceptance:** turning on, changing levels, and turning off produce verifiable changes without restarting the clip in the promoted combinations; light and file keep the expected state in LOG. The test records the request/result and the visible change under controlled conditions. Canceling an adjustment restores the previous state, including the level of a torch that was already on. Losing the capability or selecting a lens without a flash releases the previous light when applicable and updates the indication to its real state; a light is not left on because of a stale state. Photography validates exposure and synchronization, in addition to the shot.

### CAM-02 — Exposure, angle, WB, and anti-flicker [P1]

- Time/angle selector. Calculate `time = angle / (360 × capture FPS)` and clamp to the effective range; show the applied value if there is quantization.
- Lock a chosen angle when FPS changes before recording; include useful presets and 50/60 Hz selection. Distinguish the time-selection aid from the HAL's antibanding capability.
- Incorporate ISO and shutter priorities as explicit modes: saving just one of the two values is not enough if the engine goes back to full AE.
- Add tint where a valid path exists; show differences between an AWB preset and a calibrated Kelvin/tint control. In the code reviewed, the CCT path fixes tint at zero.
- Define WB lock at the start of the take and the AUTO recovery behavior.

**Acceptance:** unit calculations with limits and rational FPS; ISO-only/shutter-only changes have measured behavior; a physical test under 50/60 Hz light and WB verification when recording starts. No preset promises to eliminate all flicker from PWM luminaires.

### CAM-03 — Stabilization and image processing [P1]

- `OFF/OIS/EIS` selector and combinations that the device advertises and accepts; report crop and field-of-view changes.
- Independent noise-reduction and image-sharpness controls. Do not reuse the semantics of audio effects.
- Consistent application in SDR/LOG/HFR only where each path allows it. Classify changes that require recreating the session before the take.

**Acceptance:** compare chart/texture and motion with controls off/on; record effective modes, resolution, crop, and continuity. Names such as Cinematic/Extreme are not adopted as a quality equivalence.

### CAM-04 — Presets, shortcuts, and ergonomics [P1]

- Complete presets with a name, save/update/import/export, and C1/C2-style shortcuts.
- Three programmable buttons; candidates: torch/intensity, LUT, preset, focus, outer screen, and monitoring.
- Volume remapping, startup/restore preferences, and control locking during the take.
- Keep the existing focus marks and transitions; define what is invalidated when the lens changes. Polish the current zoom and desqueeze before adding automations.

**Acceptance:** versioned serialization and migration; a preview of differences before applying an incompatible preset; import with limits and validation; structural formats are deferred to the next take. Presets do not include credentials, other people's private paths, or physical identifiers assumed to be universal.

### UI-01 — Settings as the configuration hub [cross-cutting from H1]

**User requirement:** most features must be configurable from Settings. To make this verifiable, each delivered feature includes a matrix of its preferences with navigation path, initial value, range/unit, persistence, availability, and application time. The goal is to cover 100% of the identified user preferences; exceptions are listed and justified, instead of measuring an ambiguous percentage of features.

The viewfinder shortcuts are shortcuts to the same configuration, not independent states. Settings, viewfinder, presets, and the outer screen consume a shared state and go through the same command validation. A change from any entry point is reflected in the others without recreating the recording.

**Initial category map:**

| Category | Included configuration |
|---|---|
| Capture and exposure | Torch/intensity, photo flash, manual/semi-automatic mode, time/angle, anti-flicker, WB/tint, locks, focus, zoom, and stabilization. |
| Video and photography | Resolution, capture/project FPS, codec, bitrate, geometry/anamorphic, timecode, timelapse, RAW/JPEG/HEIC, quality, burst, bracket, and accumulation. |
| Image, color, and monitoring | Image noise/sharpness, color profile, LUT per output, baking, histograms, zebra, peaking, false color, waveform, vectorscope, grids, and safe areas. |
| Audio | Input, format, sample rate, depth, channels, routing, bitrate, gain, AGC/NS/AEC, listening, output, and meters. |
| Screens and foldables | Outer mode, mirroring, brightness/dimming, overlays, color assist, touch lock, teleprompter, per-posture layout, and closing behavior. |
| Controls and presets | Programmable buttons, volume, startup preferences, value restoration, profiles, and save/import/export. |
| Files and project | Destination, names, clapperboard data, proxy policy, and relationships with sidecars. |
| Connections and accessories | HDMI, external controls, and synchronization; network/streaming only if the corresponding phase is approved and implemented. |
| About and diagnostics | Version, licenses, capabilities, and reports; technical information separated from everyday controls. |

Not all actions are preferences: record/stop, pause, shoot, mark a take, select a clip, or start an outer session stay in their operational context. Their options and behaviors do appear in Settings. The current exposure/focus values must be distinguished from the persisted initial values; saving a preference is not interpreted as proof of active hardware. Memory limits, file integrity, and validation gates are technical invariants, not switches to force capabilities.

### UI-02 — Legible and adaptable redesign [baseline from H1]

- **Navigation:** a category landing page and pages with a title, brief description, and summary of the current value. Avoid a single endless list and, as an initial goal, limit each setting to category → setting page.
- **Search:** locate name, description, and synonyms in Spanish/English (for example, flash/torch/light); show the path and the reason if the result is temporarily disabled. Search includes the advanced options available in that version.
- **Hierarchy:** frequent options first; advanced details in clearly named expandable groups. Incompatible options keep a visible explanation; future functions not yet implemented do not appear as functional switches.
- **Reading:** initial proposal of 16 sp row titles and helper text/values of at least 14 sp, using scalable styles. Replace 9–12 sp text for important controls; allow multiple lines and adjust height before shrinking the font. Units, values, and states are readable without opening each row.
- **Handling:** fully activatable rows when there is a single action, touch targets of at least 48 dp, consistent spacing, and explicit labels. Use a switch for booleans, a selector for options, and a slider accompanied by a value/precise input where appropriate; avoid groups of tiny buttons.
- **Compact/outer screen:** one column, page-by-page navigation, and essential controls reachable through scrolling; no rows that force horizontal scrolling.
- **Wide inner screen:** categories on one side and detail on the other when both panes remain legible; go back to one column with a narrow window or large font. Respect the hinge, cutouts, and keyboard.
- **Continuity:** keep the category, search, selection, focus, and position when rotating or folding. Opening/closing Settings does not change the lens or restart capture.
- **Accessibility:** coherent reading and focus order, TalkBack labels with value/unit/state, keyboard navigation, and verifiable contrast. A row with a switch does not produce two announcements or two changes for the same interaction.

### UI-03 — Application, persistence, and acceptance [cross-cutting]

Each setting declares global, camera/lens, mode, or project scope; whether it applies now, before the next take, or requires recreating the session; and whether it belongs to a preset. Show requested, effective, and pending values separately when they differ. During REC, keep qualified live changes editable and explicitly block or defer the rest; never stop a take to apply a preference without an explicit operator action.

Apply simple, reversible preferences immediately where appropriate. Composite changes (preset, format/routing, or a LUT applied to the file) require joint validation and a summary before confirming. Canceling an editor restores its previous state; leaving an already saved setting does not silently undo the change. Resetting a category shows what is modified and does not delete clips, custom presets, or imported files.

**Acceptance required per delivery:**

1. Preference inventory: each one has a path in Settings or a documented exception; dependent capabilities show the cause of their unavailability.
2. Round-trip tests between Settings, viewfinder, and preset, with persistence after recreation and restart according to the defined scope.
3. Spanish/English and font scales of 100%, 150%, and 200%, along with increased display size: titles, values, and actions remain complete, operable, and free of overlaps. Use scroll/reflow, not automatic text shrinking.
4. TalkBack/keyboard: predictable focus, announcement of unit/value/state, and a single activation per gesture. Verify touch sizes and contrast with accessibility tools.
5. Search finds torch, intensity, audio output, and outer screen; from the result the correct setting is reached and the user returns keeping the query/position.
6. During a test recording, navigating Settings, changing one preference live, and leaving another pending keeps the file/codec/geometry; the indicators distinguish both outcomes.
7. Folding or switching displays keeps navigation and values. The subject screen does not get access to Settings except through an explicit switch to an authorized operator/self-recording role.

## 5. MON package: monitoring and color

### MON-01 — Accessible and parameterizable tools [P1]

Integrate false color, waveform, and vectorscope; configure zebra, peaking, histograms, colors, opacity, and thresholds. Add aspect-ratio guides and safe areas, without confusing them with cropping of the file. Allow a tool to be temporarily enlarged while keeping essential controls available.

**Acceptance:** charts and fixtures produce expected values; the user knows whether a tool measures the signal before or after the LUT, its SDR/HDR domain, and its update rate. A suspended monitor shows its state and does not keep an old image as if it were current.

### MON-02 — LUTs and color management [P1/P2]

- Import of `.cube` LUTs with sizes/limits defined in planning, local library, preview, export, and quick selection.
- Differentiate technical transforms from creative looks; record expected input/output, range, and hash.
- Choose the LUT per output: operator viewfinder, subject monitor, and, only by explicit decision before recording, the final file.
- Keep the existing Rec.709 assist and improve OCLog2 interoperability. Do not label a proprietary curve as Apple Log, and do not apply transforms by name without verifying the domain.
- HDR10, other HDR deliveries, and an ACES workflow are subject to path, metadata, and editor validation; they are color work, not simple label options.

**Acceptance:** CPU/GPU tests and a color chart; a monitoring-only LUT does not appear in the file. Baking is identified in the sidecar and in the color tagging. The file and transforms open correctly in an independent editor. The gates of [PLAN-061](plans/PLAN-061-oclog2-specification-interchange-and-device-qualification.md) remain in force.

## 6. AUD package: professional audio

### AUD-01 — Listening and gain [P1]

Add a listening monitor, output selection, and manual gain with a documented range. Prioritize wired/USB headphones; measure Bluetooth separately. Differentiate the hardware input gain available, the digital recording gain, and the listening volume. Avoid speaker feedback by default; explain the change clearly if the user enables it.

Keep configurable clip indicators, VU/PPM, and the real state of NS/AGC/AEC. Define exclusion or precedence between AGC and manual gain. The AAC/MediaRecorder and AudioRecord/MediaCodec paths require their own implementation; parity is not presumed.

**Acceptance:** audible listening and correct channel, measured latency, absence of duplication, explicit headphone reconnection, and A/V continuity. Recording float does not imply immunity to analog clipping.

### AUD-02 — Routing and channels [P2]

Extend to dual mono and four channels only with inputs that support it. Keep valid relationships among sample rate, depth, channels, and container; keep the WAV/FLAC sidecar where applicable and link them to the clip. Test interface disconnection, a silent channel, and channel order. Evaluate playback of music from another app during capture as a separate option; it does not imply internally recording that music.

**Acceptance:** identifiable signal per channel and decodable files; requested audio that is missing prevents marking the take as fully successful. The final semantics are incorporated into a revision of ADR-0029.

## 7. VID package: time, formats, and photography

### VID-01 — Pause, off-speed, and rational FPS [P2]

- Pause/resume in the same clip, with a coherent timeline for video, audio, and sidecars; prevent pause on paths not yet qualified.
- Model FPS as a numerator/denominator ratio. Separate capture FPS, project FPS, and timecode numbering.
- Include 24000/1001, 30000/1001, and 60000/1001 only when the cadence and the file prove it. Drop-frame changes numbering; it does not remove images or substitute for fractional capture.
- Review round-trip conversions of the existing timecode; its presence in the HUD does not establish an interoperable timecode track.
- Off-speed defines playback duration and audio policy: mute, keep separately, or process explicitly. Do not silently change the audio speed.
- Extend bitrate with values supported by codec/resolution/FPS; validate duration, throughput, and storage. 8K or HFR options are conditional capabilities, not universal goals.

**Acceptance:** monotonic PTS, expected duration, aligned audio, and readability in an editor; tests of multiple pauses, minute/drop-frame boundaries, and long recordings. Do not enable buttons just because the enum exists.

### VID-02 — Timelapse and geometry [P2]

Complete timelapse as a movie or an image sequence, with interval, count, and duration. Validate the actual count and timer drift. Separate sensor resolution, read region, file geometry, guides, orientation, and desqueeze. Evaluate open gate per sensor mode/stream actually available; `NATIVE_RASTER` does not by itself demonstrate a full-sensor readout.

**Acceptance:** dimensions, SAR/rotation, framing, and number of frames match the selection; folding or rotating the viewfinder does not change the geometry of the clip that was started.

### PHO-01 — Expanded photography [P2]

- RAW+JPEG associated with the same capture, and HEIC when the encoding path exists.
- Bracket with configurable count/steps, documenting whether it delivers separate exposures or an HDR composite.
- Optional integration of public manufacturer extensions for HDR/night/bokeh. Show their incompatibilities with RAW and manual controls.
- JPEG quality and custom aspect ratios; clarify whether there is cropping or readout of another region.
- Light/Water/Stars/Bulb as differentiated accumulation/exposure algorithms, with cancellation, progress, and bounded memory. The current one-second exposure remains as an existing function, not as a substitute for accumulation.

**Acceptance:** consistent pairs and metadata, decodable images, verified exposure, and stable memory; losses/interruptions explicitly keep or discard partial results. Do not confuse bracket with automatic HDR, or a brightness increase with additional dynamic range.

### FMT-01 — Formats and the Apple ecosystem [conditional investigation]

ProRes, ProRes RAW, Apple Log/Log 2, iOS-specific stabilization, and DockKit serve as a reference, not as a portability commitment. Prioritize HEVC quality, interoperable OCLog2, and APV if it clears availability/performance; any additional codec requires investigation of implementation, distribution, and interoperability. RAW video keeps its throughput gate. This proposal does not start a rewrite of the RAW container or promise equivalence with Apple hardware.

## 8. MED package: files and shooting workflow

### MED-01 — Project presets, clapperboard, and gallery [P2]

Add project, camera, scene, take, reel, lens, interior/exterior, day/night, and a good-take mark. Define take increment, file names, and association with WAV/FLAC, JSON, LUT, and future proxies. Gallery with search/filters, playback, and precise navigation; optional geolocation with its own permissions decision.

**Acceptance:** renaming, sharing, or deleting a take respects its relationships; deletion of originals is confirmed and deleting only proxies is distinguished. The technical export stays separate from production data.

### MED-02 — Proxies [P2]

Create lightweight versions with identity and time aligned to the original. Compare transcoding after recording against simultaneous encoding; the former is the proposed initial option to reduce thermal pressure. Design retries, a cancelable queue, and a battery/storage policy.

**Acceptance:** the editor relinks proxy and original; audio, duration, and framing match. A proxy failure does not invalidate or delete a correct original. Simultaneous proxies are enabled only after testing the complete graph.

### NET-01 — Remote control, streaming, and collaboration [P3, requires decision]

Evaluate control from a browser/tablet through a local API, multi-camera monitoring, RTMP/SRT to a configurable destination, and proxy management. Remote teleprompter and clock/accessory controller are later extensions of existing commands.

This phase requires deciding whether to change the local-only promise, create a variant or a separate component, and update requirements/ADRs before adding permissions. Explicit pairing, authentication, sign-out, operator/observer roles, and user-configured destinations are part of the design. Upload and cloud are never activated by importing a preset.

**Future acceptance:** disconnections do not stop the local original; repeated commands do not duplicate recordings; remote permissions and state are visible. A cloud or editing-project integration defines destination, queue, retries, and originals/proxies policy. Remote collaboration is not a requirement to edit or share local files.

### ACC-01 — HDMI, timecode, and external controls [P3, independent of NET-01]

Evaluate clean HDMI output or output with overlays chosen by the operator, including vertical orientation where the hardware supports it. Reuse the FOLD output separation without confusing HDMI with the integrated outer screen. Tentacle, focus/zoom controls, and gimbals will have an accessory/protocol matrix and command routing before compatibility is promised.

**Future acceptance:** connecting/disconnecting a monitor does not cut the original; verify framing, color, and absence of overlays on clean output. Measure offset and drift of external timecode; joint triggering, timecode, and genlock are distinct capabilities. A local USB/HDMI connection does not depend on approving the Internet: only integrations that use the network are subject to NET-01. Bluetooth or other transports require their own permissions evaluation.

## 9. FOLD package: complete experience for foldables

### 9.1 Product: two roles, one capture

The operator keeps the technical settings on the inner screen. The outer screen offers useful information to the subject. Alternatively, a single outer screen serves for self-recording with rear cameras. A book-style foldable, a clamshell one, and an external HDMI display are not treated as the same device.

Android distinguishes moving the activity to the outer screen from presenting simultaneously on both; the former can turn off the inner one. The proposal is to use `WindowAreaController` capabilities to differentiate them, without inferring compatibility by model or from the mere existence of two displays. [Foldable display modes API](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes).

### FOLD-01 — Discovery and early testing [technical P0]

Before committing to the dual monitor, record:

- Actual model of the development device, firmware, API, WindowManager/extensions version, and visible cameras. The repository's historical identification does not replace this inventory.
- Differentiated capabilities: per-posture layout, continuity between screens, outer transfer, and simultaneous presentation.
- Capability states: unknown, unsupported, temporarily unavailable, available, and active; session state and last failure separately.
- Relationships among the active camera, the screen facing the subject, the hinge, cutouts, and orientation.
- Camera2/GPU session support with recording plus one or two presentations. Test SDR and LOG separately.

**Output:** a reproducible report and a matrix of combinations. If the device only supports transfer, deliver self-recording and continuity; the simultaneous monitor is identified as unsupported on that device. If it supports neither transfer nor accessible outer execution, deliver only per-posture layouts and continuity on the screens the system enables; outer self-recording is unsupported. Do not simulate simultaneity by duplicating buttons or by trying to open a second camera.

### FOLD-02 — Subject monitor [first foldable product]

Outer mode `FRAMING`: clean image, REC, time, and countdown; optional mirroring of the monitor only, separate color assist, and touches locked by default. The operator's UI configures and closes the outer session.

- REC derives from the confirmed capture state; distinguish preparation, recording, finalization, and error. A file pending finalization does not appear as saved.
- Each output keeps an independent transform, aspect ratio, and mirroring. The visible framing corresponds to the recorded area and makes bands/crops explicit.
- The absence of recent frames produces a stopped-preview indicator, never a frozen image presented as live video.
- Keep the operator's controls on the inner screen. If the system only supports transfer, explicitly offer the self-recording mode, without attributing dual-screen to it.

**Acceptance:** image verifiable from both sides, independent mirroring, and zero outer overlays in the file; the subject does not alter parameters through accidental touches. Changing the outer mode does not restart a take in a qualified combination.

### FOLD-03 — Self-recording [P1]

`SELF-RECORDING` mode: outer preview, large REC, timer, lens, audio, and minimal shortcuts, with a visible way back to the operator's interface. Explicit activation and a system dialog when applicable. Lens selection follows the rules for switching during a clip; a rear camera does not imply that any posture points at the subject.

**Acceptance:** frame and record without looking at the inner screen, keep legibility on a small screen, and retain essential controls with camera cutouts.

### FOLD-04 — Postures and continuity [P1]

- **Tabletop:** preview on the vertical half and controls/audio/scopes on the resting half; a button to swap halves.
- **Open:** large preview and a selectable side panel, without stretching the entire UI.
- **Book/separated:** independent content on both sides of the hinge, without crossing buttons or important text.
- **Closed:** compact interface according to actual capability and the continuity policy chosen before recording.

Detect posture with `FoldingFeature`/layout, without assuming an exact angle from that object. Separate each window's orientation from the physical orientation used for the file; review reads of `displays.firstOrNull()` so each viewfinder uses its own display. [Posture guide](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware).

**Acceptance:** opening/closing and rotating keeps lens, exposure, focus, geometry, and clip on the supported paths; reconnecting surfaces does not duplicate sessions or stop the encoder. When the system revokes the camera or process, finalize/recover according to the existing mechanism and describe the result, without promising absolute continuity against system termination.

### FOLD-05 — Expanded outer modes [P2]

| Mode | Proposed behavior | Delivery condition |
|---|---|---|
| `OFF` | Close outer content and release resources; physical screen according to the control the system allows. | Unambiguous outer state; independent capture. |
| `TELEPROMPTER` | Local scrollable text, size/speed, pause, and optional small preview. | Text near the lens when the geometry allows; never included in the recording. |
| `STATUS` | Preparing, REC, cut, countdown, and brief operator signals. | Legible messages synchronized with real states. |
| `REFERENCE` | Image or silhouette chosen for position/pose/framing. | Explicit activation and import limits; does not alter the original. |
| `REVIEW` | Last image or clip chosen by the operator. | Avoid automatic gallery exposure; playback audio off during a new take. |

The initial teleprompter does not include voice recognition, automatic gaze tracking, or pose generation. Those automations would require independent proposals.

### 9.2 Proposed screen and rendering model

Create separate components, with final names to be resolved in planning:

| Component | Responsibility |
|---|---|
| `FoldDisplayCoordinator` | Observe capabilities and the lifecycle of outer sessions; transfer/present only on explicit command. |
| `DisplayRoleState` | Map operator/subject/self-recording roles to active displays/sessions, without assuming fixed IDs. |
| `PreviewOutputRegistry` | Register surfaces with an identifier and generation; ignore callbacks from old surfaces. |
| `PreviewRenderRouter` | Distribute the image to authorized outputs, with per-output transforms/LUT and an independent encoder. |
| `SubjectDisplaySettings` | Mode, mirroring, requested brightness, overlays, color assist, and touch lock. |

Test two Camera2 outputs first if the graph supports it and compare them with GPU distribution. The final choice must consider LOG, thermal cost, and surface changes. Do not add a full per-frame CPU copy as the default solution, and do not rebuild the encoder just because the monitor is opened.

Windows own their presentation; `CaptureService` keeps the capture. Decouple the automatic closing of a presentation from the end of a take. Assign a buffer budget and maximum times to each consumer so that a slow screen does not block recording.

### 9.3 Brightness, performance, and controls

- Request brightness per window when possible; distinguish requested brightness from system behavior. Dimming does not always equal physically turning off a panel.
- Avoid automatic brightness adjustment that causes unexpected lighting changes on the subject. Dimming and timeout are configurable.
- Reduce the outer preview's resolution/frequency under an explicit policy before harming the encoder; indicate the degradation. Strict may reject the combination before recording.
- Keep buttons away from the hinge/cutouts and lock the subject's touches; self-recording enables only its own set of commands.
- Using the outer screen as a fill light is not included in the first delivery: it is evaluated later to see whether it provides enough light without affecting legibility, temperature, or exposure.

## 10. Architecture, persistence, and documentation changes

| Existing area | Planned work | Linked document/decision |
|---|---|---|
| `app` settings, UI, and service | UI-01/02/03: categories, search, preference catalog, shared state, versioned presets, screen roles, and serialized commands. | ADR-0003, ADR-0017, and UI/LIFE requirements. |
| `camera` probes/engine | Flash strength, effective controls, multi-output session, FPS, and capability per graph. | ADR-0004, ADR-0007–0009. |
| `camera`/`native-pipeline` GPU | LUT/scopes/differentiated outputs and bounded distribution. | ADR-0018, ADR-0021, and PLAN-061. |
| `media` and `app` storage | Pause/timestamps, audio, proxies, file relationships, and metadata. | ADR-0010–0013, ADR-0029. |
| `core/model`, schemas, and evidence | Capability/state models and versions of presets/sidecars/reports. | ADR-0005, ADR-0014, ADR-0026. |
| Future network/accessories | Variant/component and public contracts; local accessories independent of the network. | Revision of the local-only requirements for NET-01; ADR on outputs, commands, and permissions depending on transport for ACC-01. |

Before implementing, promote the selected IDs to accepted requirements and create new plans with monotonic numbering, using the existing index/manifest. Do not renumber historical plans or mark tasks Done because of the creation of this document.

Every migration keeps compatible settings and explains changes of meaning. Before modifying a take or importing a preset, validate the complete set of options. The effective hardware state is not restored from preferences as if it were still active.

## 11. Proposed sequence and dependencies

| Milestone | Delivery | Dependencies/exit to advance |
|---|---|---|
| H0 — Foundation and decisions | Reconcile documentation status, device inventory, FOLD-01 tests, and flash capability; inventory preferences and sketch Settings navigation. | Feasibility evidence, required ADRs identified, and approved scope. |
| H1 — Light and continuity | Initial CAM-01, UI-01/02/03 foundation for existing settings/light, migration and recording regression; surface/posture contract. | Torch/LOG qualified per combination and configurable from Settings; photo flash can be a differentiated sub-delivery. |
| H2 — Foldable differentiator | FOLD-02/03/04; if dual screen fails, self-recording/continuity on the available paths. | Physical dual-screen tests or a documented unsupported result; original intact. |
| H3 — Professional operation | CAM-02/03/04, MON-01/02, and AUD-01. | Controls/color/audio contracts and specific tests. |
| H4 — Editing and expanded capture | AUD-02, VID-01/02, PHO-01, MED-01/02, and FOLD-05. | Timestamp infrastructure, related files, presets, and screens already stabilized. |
| H5 — Integrations | NET-01, ACC-01, and additional formats evaluated in FMT-01. | Protocols and performance budget demonstrated; network decision only for the paths that use it. |

H0 investigates foldables early, without displacing the first visible light improvement. The approved order places H2 before H3. H1 is a technical unit, not a release: the user chose to receive the APK when the set is complete. There are no committed dates.

UI-01/02/03 accompany every milestone: each new function adds its preferences to Settings when delivered, not in a final cleanup. H1 establishes the navigation and migrates the existing controls; later categories are added along with their functions, without empty pages that promise future support.

## 12. Test strategy and acceptance evidence

### 12.1 Minimum matrix

Cross critical families instead of assuming that a global success establishes all combinations:

- **Devices:** identified primary foldable; one with verified public dual support if the primary lacks it; a foldable with limited capabilities and a conventional phone.
- **Postures:** open, closed, tabletop, book where applicable; portrait/landscape; transitions during preview and capture.
- **Paths:** JPEG/DNG photo, SDR, LOG, HFR, and timelapse; unsupported modes must cover their rejection branch.
- **Outputs:** operator only, outer only, both, outer mode without video, close/reopen, and surface disappearance.
- **Interactions:** torch/intensity, LUT, audio/headphones, allowed lens switching, thermal pressure, and scarce storage.
- **Interruptions:** revoked permission, busy camera, app loses focus, process recreated/terminated, audio input removed, and partial file.
- **Settings/accessibility:** categories and search, synchronization with viewfinder/presets, 100/150/200% font, increased display size, TalkBack/keyboard, navigation while folding, and changes during REC according to UI-03.

### 12.2 Test levels

1. Unit: state models, migrations, strength ranges, angle calculation, FPS/timecode, transforms, capabilities, and relationships between files.
2. Integration/instrumentation: commands/UI down to the engine, outer presentation, flash sequence, rendering, audio, and real codec.
3. Physical: lighting, cadence, latency, posture, correct camera/output, and firmware behavior.
4. File/editor: decoding, timestamps, color metadata, orientation/SAR, audio, proxies, and sidecars.
5. Regression: preservation of the capture workflow already available and of its supported/unsupported outcomes.

### 12.3 Initial thresholds proposed for approval in planning

| Measurement | Proposed initial threshold/case |
|---|---|
| Foldable continuity | 20 open/close cycles in a 10-minute take: zero file cuts, unrequested lens changes, or geometry changes. |
| Cadence | After the stable start defined by the path, zero inferred losses attributable to turning the monitor on/off; record every interval greater than 1.5× the expected period. |
| Outer preview | At 1080p30 SDR, end-to-end latency p95 ≤ 150 ms in a run of at least 60 s; record resolution and method. Other profiles have their own budget. |
| Audio | Absolute A/V drift ≤ 40 ms at the end of 10 minutes; wired listening p95 ≤ 80 ms in a controlled run, independent of file drift. |
| Thermal/resources | 30-minute soak with two outputs on the target profile, without OOM, ANR, or corruption; record memory, temperature/thermal state, and degradations. |
| Light | Walk through all advertised levels and repeat on/off 10 times; consistent applied state and verifiable light change where the hardware allows. |
| Interoperability | An independent decoder and an editor open each new format/path; LUT, proxy, and original keep the declared relationship. |

These numbers are engineering targets, not observed results or per-phone promises. Each plan must set the method, device, and threshold before execution; if a target is revised, record the decision and reason, and do not change it afterward to hide a failure. An outer screen may have a resolution lower than 1080p: the capture profile and the presentation profile are recorded separately.

### 12.4 Record required per delivery

Commit/build and configuration; device/firmware identity; exact commands and results; requested/reported; state changes; files and hashes; inspection of frames/PTS/audio/color; screenshots of both interfaces; latencies and power consumption; result per criterion. Fixtures or emulators do not replace evidence from a physical outer screen.

Starting commands from the repository, to be run during implementation from its root; this documentation stage does not declare them executed:

```bash
rtk proxy env JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict lint test assembleDebug
rtk proxy env JAVA_HOME=/usr/lib/jvm/java-17-openjdk ./gradlew --no-daemon --dependency-verification=strict connectedDebugAndroidTest
rtk proxy ./tools/check_format.sh
```

Each new plan adds concrete suites and file validators. The documentation checks of this draft only establish structure/links, not those product tests.

## 13. Risks and rollback strategy

| Risk | Proposed treatment |
|---|---|
| Outer screen not accessible through a public API | Early probe; enable by capability, deliver transfer/continuity when applicable. |
| Two previews affect LOG/HFR | Qualify the graph; GPU/bounded distribution; a status or lower-cost outer mode offered explicitly before recording. |
| A surface blocks the encoder | Decoupled consumers, timeout, and clear ownership; disconnection and late-callback tests. |
| Presets restore a state that is dangerous for the take | Transactional validation, change summary, and separation between preference and active state. |
| Monitoring and file differ without explanation | Domain, LUT, mirroring, crop, and color visible per output; charts and file verification. |
| DSP/flash/stabilization ignore requests | Show reported/unknown and block promotion until there is evidence; keep the previous control available. |
| New functions contradict ADRs | Resolve in H0 or before the affected phase; this draft does not replace them. |

Deliver changes in small units, with activation by capability and backward-compatible migrations when viable. Rolling back the UI/render frees the added sessions/surfaces and recovers the single viewfinder without changing the active take. If the change requires rebuilding the session, it is rolled back between takes. A version rollback must preserve originals and treat new presets as an unknown version, without reinterpreting them or deleting data.

Before each release, test turning the new paths on/off and going back to the previous path on a copy of test data. The concrete plans must define which commits, internal flags, and migrations are rolled back; this document does not execute an app rollback.

## 14. Planning decisions and technical open items

### Closed with the user

- Implement the set in stages, not cut the product down to H1.
- Resolve light and Settings first; prioritize foldables ahead of the professional controls.
- Publish a single final APK when the set is finished; instrumentation builds are not deliveries.
- Keep a single app, with optional network features disabled initially.
- Continue recording when folding by default, preserving the service's ownership and the take's geometry.
- Restore torch on/off and intensity on reopening; distinguish preference from reported state.
- Integrate OFF/AUTO/ON photo flash with the photography expansion, not with H1.
- Include local remote control, RTMP/SRT, and WebDAV in H5.
- Upload finalized clips to WebDAV automatically over Wi-Fi; pause uploads and new proxies during REC. Mobile data requires explicit activation.
- Incorporate each function's preferences into Settings when it is implemented, with categories, search, and scalable text.

### Technical open items, with no new scope decision

DEC-01/04: physical inventory and qualification of the multi-output graph. DEC-07/08/09: color, audio, and rational-time contracts. DEC-11: demonstrate physical thresholds. The network ADRs will be updated before transports are incorporated; the current manifest still has no Internet. Effective support is determined per device and path, not by this approval.

### Current execution

[OCC-PLAN-063](plans/PLAN-063-settings-and-session-torch.md) implements the first technical unit: shared settings repository, categories/search, legibility, persistence of existing monitoring and timecode, quick access to light, strength advertised by Camera2, requested/reported separation, and structural changes deferred during REC. Its status remains InProgress until acceptance and physical tests are closed. OCC-PLAN-064 continues H2 with outer sessions, roles, and postures; the dual preview and physical validation remain open. H3–H5 keep their scope and remain pending. The [active goal](plans/GOAL-PROGRESS.md) keeps the work going until the set is complete or a real blocker is demonstrated; this log does not declare full support or a final APK.

## 15. External references and scope of the comparison

Reference lookup: 2026-09-05. Public lists are used to identify ideas; compatibility and operation are verified in each product/device.

- [Blackmagic Camera — Android/iOS specifications](https://www.blackmagicdesign.com/products/blackmagiccamera/techspecs): reference for LUT/presets, controls, audio, metadata, proxies, and monitoring. Separate sections by platform.
- [Blackmagic Camera — official iOS history](https://apps.apple.com/us/app/blackmagic-camera/id6449580241): reference for programmable shortcuts, light, REST remote control, RTMP/SRT streaming, open gate, and accessories. These ideas are not declared exclusive to iOS.
- [ProShot — Android guide](https://www.riseupgames.com/proshot/android): reference for C1/C2, Light Painting, timelapse, and photo settings.
- [ProShot — official Android listing](https://play.google.com/store/apps/details?id=com.riseupgames.proshot2): reference for pause, RAW+JPEG, HEIC, and extensions. The sources reviewed describe flash/light but do not establish universal intensity levels.
- [Android — foldable displays](https://developer.android.com/develop/adaptive-apps/guides/foldables/support-foldable-display-modes) and [postures](https://developer.android.com/develop/adaptive-apps/guides/foldables/make-your-app-fold-aware): basis for discovery and display sessions.
- [Android — reported flash strength](https://developer.android.com/reference/android/hardware/camera2/CaptureResult#FLASH_STRENGTH_LEVEL): capability and effective-state reference.
- [Android — media formats](https://developer.android.com/media/platform/supported-formats): starting point for codecs; an enum/API does not prove the performance of a combination.
- [Android — AudioRecord](https://developer.android.com/reference/android/media/AudioRecord): capture/channels/PCM reference; it does not by itself establish listening or hardware quality.

## 16. Expected outcome of the next stage

Planning approved the set in stages. The implementation must produce evidence per criterion before the final APK is delivered. The document will remain as a product reference and traceability for the set; the concrete plans will contain detailed changes and commands.

**Product success criterion:** a camera that is faster to operate, reliable files for editing, and a foldable that lets people collaborate from two sides, without sacrificing recording or attributing capabilities that have not yet been demonstrated.


### H4 progress: interval-based movie

OCC-PLAN-066 and ADR-0033 record the capture/project work. The implementation selects images before the encoder, generates PTS values independent of the interval, limits by frames actually submitted, and keeps the GPU viewfinder. Settings and the INT panel share interval, integer project FPS, resolution, and limits; during REC they keep pending intent. The status reports the real encoder, frames, and lost intervals. The implementation and regression evidence is in `build/implementation-h4-timelapse/VERIFICATION.txt`; the full scope of H4 — including fractional FPS across the whole app, pause/off-speed/A-V/timecode, and image sequences — remains open.


### H4 progress: rational projects and explicit off-speed

PLAN-066 rev. 2 incorporates numerator/denominator for the timelapse and SDR VIDEO project, configures off-speed explicitly without audio, and V4 presets (93 portable fields, strict V1–V3 migration). The sensor cadence is not labeled fractional just because a rational project is chosen. The take keeps its configuration while the new settings remain pending. The temporal finalization of a single-video-track MP4 keeps samples, payload, and offsets and checks absolute timestamps; the evidence is in `build/implementation-h4-project/VERIFICATION.txt`. Pause, off-speed policies for separate/processed audio, interoperable timecode, and physical/editor qualification remain open; this implementation does not complete the H1–H5 program.


### H4 progress: file closing and ownership

PLAN-066 rev. 3 requires the muxer to be closed correctly before adjusting times and publishing. The first finalization keeps its result: a discarded take does not reappear as saved through a later call. Failures try to clean up the video and sidecar; publication checks the affected rows, and SAVED requires a confirmed URI. Switching modes clears the cadence of the previous take and keeps the GPU viewfinder policy. Evidence: `build/implementation-h4-finalization/VERIFICATION.txt`. Pause and the shared A/V clocks remain pending; this error compensation is also not equivalent to an atomic transaction across providers in the face of process termination.


### H4 progress: separate audio associated with the take

PLAN-066 rev. 4 keeps ownership of the WAV/FLAC and its metadata until the video is confirmed. If the requested audio is missing or saving the video fails, the take fails and an attempt is made to delete the set. A normal close keeps an already saved audio; an explicit discard revokes it without allowing it to reappear through another call. Evidence: `build/implementation-h4-audio-ownership/VERIFICATION.txt`. A/V synchronization, pause, physical quality, and recovery after process termination remain open.


### H4 progress: audio stop with resource ownership

PLAN-066 rev. 5 separates the maximum wait time from the effective release: if an audio thread is still active, it keeps its resources until it finishes, the take fails, and the file is not published. A coordinator performs native stop/release and bounds the caller's wait; FLAC shares a deadline for EOS and draining. The app avoids creating another separate audio while the previous one is still closing. Evidence: `build/implementation-h4-audio-retirement/VERIFICATION.txt`. Pause/resume, A/V synchronization, and GPU closing remain pending.


### H4 progress: GPU closing and integrated AAC audio

PLAN-066 rev. 6 gives the GPU muxer its own file descriptor and orders the closing according to the actual termination of GL/AAC, not just an expired wait. Pipeline closing is asynchronous and Camera2 waits before reusing its surfaces. Terminal callbacks from an obsolete session do not update a new session. Evidence: `build/implementation-h4-gpu-retirement/VERIFICATION.txt`. Pause and the shared A/V clocks, physical AAC/VIDEO/LOG tests, and the other stages remain open.


### H4 progress: real timelapse pause without audio

PLAN-066 rev. 7 connects pause/resume with the take's GLES owner, keeps the viewfinder, and generates continuous project PTS values in the same file. Resuming starts a new interval with the first real image; images already observed during the pause are discarded. The counter and the duration limit use confirmed active time, from the completed preparation of the encoder until the stop of its input is confirmed on the GL thread, without adding pauses or native finalization. The `.timing.json` sidecar keeps the events, project indices, and durations, and participates in the compensation of the video file.

Settings explains the policy and allows finding it by searching for pause/resume; interval, project, and limits remain configurable. The interface keeps Stop separate, acknowledges the commands, and shows PAUSE to the subject as well. The vertical view separates the controls on narrow screens; the compact control keeps a complete accessible description. Each interface action keeps the take, role, and generation that originated it.

The regression passed 403 unit tests and 127 instrumented tests, with app/camera lint without errors. Four independent tests produced 480×640 files of 12, 16, and 1 frames: exact PTS/DTS and durations, consistent sidecars, and correct FFmpeg decoding. Reversible evidence: `build/implementation-h4-pause/VERIFICATION.txt`. This path is silent timelapse on the verified emulator; VIDEO/LOG/audio pause, shared A/V epochs, interoperable timecode, physical/editor tests, and the rest of H1–H5 remain open. The final APK remains pending until the set is complete.


### H4 progress: correspondence between A/V capture epochs

PLAN-066 rev. 8 connects the REALTIME clock declared by Camera2 with BOOTTIME timestamps and PCM frame positions from AudioRecord. The muxer keeps the initial offset between tracks even though their codecs restart their own PTS values. An unknown camera epoch remains explicitly independent; the absence of an audio timestamp is labeled as an estimate, without changing the origin mid-take. The VIDEO/LOG metadata record the observed values and keep `waveformAlignmentVerified=false`.

The regression passed 415 unit tests and 129 instrumented tests, with lint without errors and without new warnings. Two independent tests exercise GLES, AudioRecord, AAC/AVC, MP4, and real metadata publication. In the final repetition, the captured offset was 399833 µs and ffprobe read 399800 µs; the unknown-clock case kept independent starts. Both files decoded correctly as containers, but their 52661 submitted PCM frames produced 52224 decoded frames. That difference of 437 frames demands an investigation of priming/tail with markers; it does not establish waveform fidelity or physical alignment.

Evidence and independent rollback: `build/implementation-h4-av-epochs/VERIFICATION.txt`. VIDEO/LOG/audio pause also needs shared intervals and PCM trimming at its boundaries; that function, interoperable timecode, and the rest of H1–H5 remain pending. The final APK remains reserved for the complete termination of the set.


### H4: AAC tail diagnosis — pending integrated correction

Comparing the known PCM with 12 real AAC files confirmed that the previous deficit of 437 samples hid 2048 samples of initial delay and 2485 of missing final content in the emulator's encoder. EOS together with the last block or separately gave the same result; with an input whose length was a multiple of 1024, content was also missing. The additional 4096-zero test preserved the original signal but left delay and surplus: it does not constitute gapless audio or finished A/V synchronization. The zeros are exclusively experimental; production recording remains without this change.

A reproducible instrumented test and an analyzer with independent per-channel verification and an explicit acceptance condition were incorporated. Integrating flush, accounting of real samples, delay measurement, and interoperable container trimming remains pending before enabling general A/V pause. Evidence: `build/implementation-h4-aac-tail/VERIFICATION.txt`; detail and next action in PLAN-066 revision 9. The full H1–H5 scope remains active.


### H4: AAC sample interval explicitly represented in MP4

A finalizer was implemented that receives the actual number of samples, the verified encoder delay, and the track's temporal offset. It rejects files that do not contain the entire signal and keeps the encoded data and block positions intact. The container declares the useful interval through an edit list and pre-roll groups; video times keep their meaning.

Tests with real AAC remove the initial delay when decoding and preserve the known signal. Readers do not uniformly interpret the end of the interval: FFmpeg can deliver additional samples from the last block and MediaExtractor reports the duration of the encoded media. Automatic integration into recording, qualification of the delay per configuration, and playback/editing behavior remain pending. Experimental padding was not enabled in production. PLAN-066 revision 10 and `build/implementation-h4-aac-window/VERIFICATION.txt` keep the detail. H1–H5 remains active.


### H4: AAC calibration integrated into recording

Recording measures the delay and flush of the specific AAC configuration before opening the microphone, with two known signals and per-channel checking. The measurement is reused for five minutes within the process and is verified against the encoder's actual configuration. The test signal never enters the take. Real samples, flush-only zeros, and encoded packets are counted separately; the MP4 automatically receives its useful interval and keeps the audio's temporal placement.

The metadata and the save status show this correction without presenting it as physical synchrony or acceptance by all players. The file declares the exact interval; a decoder may still deliver surplus from the last block. Hardware measurement, playback/editing limits, general A/V pause, and the rest of H1–H5 remain pending. PLAN-066 revision 11 and build/implementation-h4-aac-calibration/VERIFICATION.txt keep the tests and the rollback. The final APK remains reserved for completing the whole set.


### H4: confirmed start versus deadline expiry

Recording start now has a single decision between confirmation and cancellation. A canceled preparation keeps its resources until its owner finishes and rejects overlapping attempts; an already confirmed take keeps its result even if the start callback takes longer than the observation deadline. The output descriptor is duplicated before the work is enqueued, and the encoder worker waits for the decision before accessing native resources. PLAN-066 revision 12 records the cancellation, recovery, and delayed-callback tests. A/V pause and the rest of the H1–H5 scope remain pending.

The blocking of new preparations also waits for the delivery of the result to the caller to finish: a real test reproduced the acceptance of a new take before the notification of the previous failure had finished. The fix gathers both terminations before admitting another start.


### H4: shared pause of video and integrated AAC

VIDEO/LOG with calibrated AAC and comparable capture clocks pauses without restarting the file, the viewfinder, or the microphone. Both producers exclude the same intervals on the audio sample grid; the real samples retained, those captured during the pause, and the codec flush are kept separate. Stopping during a pause closes the interval, and the file receives the corresponding capture metadata.

The existing controls keep their take/role identity; Settings → recording/project time explains availability, and search includes pause, resume, and synchronization. Unknown or estimated clocks, separate WAV/FLAC, and off-speed do not receive this path by assumption. Physical measurement, playback/editing compatibility, and the rest of H1–H5 remain open. PLAN-066 revision13 and build/implementation-h4-shared-pause/VERIFICATION.txt keep the acceptance and rollback. There is no final APK until the set is complete.


Final shared-pause regression passed 469 Android unit tests (160 app +309 camera), 55 tools tests and 138 instrumented cases on the test emulator; app/camera lint reports no errors or new warnings. Independent replay passed both actual AudioRecord/AAC/GLES cases. Repeated pause retained63831 of117027 captured PCM frames; stop while paused retained60074 of130924. Every encoded video PTS matches an ordered submitted source timestamp outside the exact shared cut windows. Both resume joins in each recording are34.72–35.15ms; maximum initial video gaps176.856ms and229.5ms remain disclosed separately. Terminal audio/video differences are2.78ms and44.22ms in these controlled fixtures, not physical lip-sync qualification.

Independent FFmpeg decoding is clean. Explicit source-window clipping yields exactly63831/60074 retained frames; raw decoder final blocks expose681/342 extra frames, so automatic player clipping remains unqualified. Preview analysis and microphone meters remain live during pause, and stopping during a third pause seals the source window without publishing excluded PCM. Full evidence, original hashes and independently tested rollback are recorded in build/implementation-h4-shared-pause/VERIFICATION.txt. Full H1–H5 remains ACTIVE; separate WAV/FLAC timing/pause, service/UI and physical/editor qualification, timecode and remaining capture/integration requirements remain open. No final APK.


## Separate WAV/FLAC source epochs (revision 14)

Lossless sidecars now distinguish start/stop command receipts from an actual AudioRecord BOOTTIME source-frame epoch. The PCM writer/feeder samples AudioTimestamp after reads, establishes frame zero from the first successful source observation, and measures later residuals without moving that origin. Missing timestamps remain explicitly unavailable; no scheduling receipt or System.nanoTime anchor substitutes for source capture. The metadata records first/last timestamp observations, unavailable-observation count, actual sample rate, interleaved frame size, captured/written frames and exact rational source duration. Existing sidecar schema fields remain; captureTiming is additive.

Both writers reject actual PCM format mismatches and partial interleaved reads, and require captured/written/container frame accounting to agree before successful publication. WAV buffers and FLAC codec reads request complete interleaved frames, including stereo and float WAV. Standalone FLAC codec PTS now start at source frame zero and advance by exact source-frame duration rather than a feeder scheduling-time anchor. The existing retained native ownership, failed-output cleanup and paired-take compensation still apply. No new preference can replace a missing source timestamp: format/rate/channels remain configurable through the existing audio settings.

Ten pure tests exercise unknown/delayed source anchors, fixed-origin residuals, rational-rate stereo float and packed24 frame accounting, partial writes and regression rejection. Five native cases record actual WAV mono48k, WAV stereo44.1k, WAV float stereo44.1k and FLAC mono48k/stereo44.1k. They reopen published metadata and cache actual files for independent header/STREAMINFO and FFmpeg frame-count verification. Results and independently tested rollback are recorded after execution in build/implementation-h4-sidecar-epoch/VERIFICATION.txt.

This makes the separate audio source epoch observable; it does not yet apply video placement, shared separate-audio pause, hardware-overrun detection or per-take waveform alignment. captureTiming explicitly reports videoAlignmentApplied=false and waveformAlignmentVerified=false. Camera/video linkage and coordinated lossless pause remain next integration work, with off-speed/timecode, player/editor/physical qualification and all remaining H1–H5 requirements retained. Full goal remains ACTIVE; no final APK.

API reference: https://developer.android.com/reference/android/media/AudioRecord


Independent five-case replay passed on the test emulator. WAV48k mono retained and decoded59244 frames; WAV44.1k stereo PCM16 and float each retained and decoded55104; FLAC48k mono retained and decoded59244; FLAC44.1k stereo retained and decoded54432. All files match their actual WAV/STREAMINFO frame counts and metadata sample rates. Source clocks obtained14–21 successful timestamp observations per take; measured maximum residuals were10.073–25.467microseconds in these short emulator captures. These observations are not a physical drift or microphone waveform qualification. Original published metadata, file hashes and FFmpeg commands/results are retained in build/implementation-h4-sidecar-epoch.


Final selected regression passed479 Android unit tests (160 app +319 camera),143 instrumented cases and app/camera lint with zero errors. UTP identifies the test emulator. The full run completed in16m27s; no timeout was treated as terminal and the source stayed frozen throughout. Separate original/modified/rollback host results and restored original hashes are retained in build/implementation-h4-sidecar-epoch/VERIFICATION.txt. Full H1–H5 remains ACTIVE, with separate audio/video linkage and coordinated pause still open.


## Shared VIDEO/LOG and separate lossless pause (revision 15)

VIDEO and LOG now pass a fresh per-take CaptureEpochClock to either embedded AAC or the separate WAV/FLAC recorder, never both. The separate route requires matching camera timestamp provenance and regular capture (not timelapse/off-speed). All existing/new GPU graph entry points forward the same clock. A standalone-video muxer does not wait for an external microphone timestamp or shift its zero-based file PTS: the shared source clock governs cuts, while each file keeps its own local timeline. Unknown or missing audio/camera anchors therefore do not stall muxing or falsely expose shared pause.

WAV and FLAC select the same half-open PCM source windows that map video input. PCM compaction preserves complete interleaved PCM16, packed24 and float frames byte-for-byte, validates all spans before writing, and preserves the no-cut path. Audio meters/effects still receive live source data. FLAC reads into a bounded reusable source buffer independently of codec input availability, then submits only retained complete frames, split to actual codec capacity. A fully paused read queues no empty audio packet. Stop seals the shared source boundary on the GL owner; later lossless reads exclude samples beyond that boundary while the existing native retirement/publication protocol finishes.

AudioSidecarRecordingResult/captureTiming retain captured versus written counts. Successful external-clock publication requires retained source selection to equal actual written/submitted frames, not captured frames including pauses. The audio JSON stores the final sharedTiming after actual audio retirement. CaptureService refreshes VIDEO timing and LOG provenance from that same final clock after audio completion, keeping paired-output cleanup if either file fails. audioStorage distinguishes separate WAV/FLAC from embedded AAC; codecInputFrames is null for separate files, and sourceAudioMinusVideoNs is populated only for actual comparable source anchors. This is source-offset metadata, not automatic editor file placement or measured microphone waveform alignment.

Settings → recording/project timing explains shared pause for embedded AAC and separate WAV/FLAC, live meters and the need for explicit editor alignment. The existing configurable audio format/rate/depth/channel intent selects this route; runtime clock availability is not force-enabled by a new switch. Operator/self/exterior pause controls retain their existing take/role acknowledgement guards.

Six new pure cases exercise bit-preserving stereo float/packed24 compaction, complete validation before mutation, empty pause output and rational source-grid/terminal cuts. Five native cases pair actual GLES video with real WAV/FLAC: repeated pause, stop while paused with stereo float WAV or stereo FLAC, and unknown camera clock without shared pause. Published audio metadata and both files are reopened for independent decoded PCM counts, header/STREAMINFO checks and every-video-PTS source-window mapping. The first compile rejected Kotlin cross-module nullable smart casts in the offset serializer; explicit checked reads correct this and preserve the failed compile evidence.

Evidence: build/implementation-h4-lossless-pause/VERIFICATION.txt. Physical sensor/microphone timing, per-device cadence/drift, service/UI/editor acceptance, silent regular VIDEO/off-speed policy, timecode and the rest of H1–H5 remain open. Full goal stays ACTIVE; no final APK.


The second fixture compile required the existing explicit pause-completion callback in the unknown-clock rejection case. The corrected fixture supplies it; both failed compile logs remain retained. Final host/assembly completed successfully before actual media replay, with485 unit tests (160 app +325 camera).


The first independent inspector rejected a null-output FFmpeg muxer warning on the float-WAV paired video: its default output timebase quantized two distinct input DTS to the same tick. The original MP4 has38 strictly increasing PTS/DTS at1/90000 timebase with minimum separation3097ticks. The same bytes decode cleanly with -err_detect explode -fps_mode passthrough -enc_time_base demux. The retained first-inspect-evidence includes the failed command/file/log and the clean same-file diagnostic. The inspector now preserves the input timebase, separately requires strictly increasing packet DTS/PTS and matches every video PTS to the actual input source/window mapping; no production timestamps are rewritten to hide the diagnostic. FFmpeg option contract: https://www.ffmpeg.org/ffmpeg.html


Independent same-file inspection passed all five paired takes after preserving the decoder timebase. Repeated WAV/FLAC pause retained73449/66441 PCM frames and excluded60401/60094; stereo float WAV/stereo FLAC stopped while paused retained66560/68837 and excluded72544/70939. Independent decoding returns exactly each retained count. Every video PTS maps to actual submitted source input outside the same pause/stop windows. The two resume joins per known-clock take measure34.81–42.75ms; terminal A/V differences after applying the recorded source offset are below9ms in these controlled fixtures. Initial video gaps169–310ms remain separately disclosed, not constant-camera-cadence acceptance. Unknown-clock WAV retains58513 frames, exposes no shared pause and keeps sourceAudioMinusVideoNs=null. Preview analysis/meters remain live. Physical waveform alignment and automatic editor placement remain unverified.


Final selected regression passed485 Android unit tests (160 app +325 camera),148 instrumented cases and app/camera lint with no errors or new warnings. UTP identifies the test emulator; the frozen-source full run completed in10m14s. The complete148-case selection includes existing audio retirement/paired-output failure tests, embedded-AAC pause and the five new lossless-pause cases. This confirms the tested native/media paths, not unexecuted physical or editor gates. Independent original/modified/rollback host results, file hashes and all514 restored original bytes are recorded in build/implementation-h4-lossless-pause/VERIFICATION.txt. Full H1–H5 remains ACTIVE; no final APK.


## Timecode arithmetic and valid local labels (revision 16)

A retained failing regression proves that the previous inverse conversion mapped01:00:00;00 at29.97DF to108000 instead of107892 timecode-frame ordinals. SmpteTimecode.toTotalFrames now subtracts omitted DF labels and rejects skipped labels, mismatched DF flags and frame numbers outside the nominal rate. Inverse conversion wraps at the correct DF/NDF24-hour frame count before doing arithmetic, including negative and Long-limit offsets without overflow. Canonical labels use ASCII digits independently of the device locale.

TimecodeRate now exposes exact numerator/denominator for the existing integer NDF and29.97/59.94DF choices, validates supported combinations and counts elapsed nanoseconds with exact rational arithmetic. frameDurationUs remains a truncated legacy single-frame value, no longer an accumulator. TimecodeTracker FREE_RUN uses an injectable BOOTTIME nanosecond clock and exact elapsed-frame conversion; an observed clip frame index is no longer added a second time to an already elapsed FREE_RUN count. Invalid start labels fail before changing tracker configuration; unchanged configuration preserves its anchor.

Local persisted timecode values are normalized together: unsupported DF/rate combinations are repaired, frame bounds follow the chosen nominal rate and skipped DF labels advance to the first valid frame of that minute. Settings rate/DF controls use the same normalization, including60→24 frame-bound changes. Presets remain strict: their existing canonical snapshot comparison rejects malformed/skipped labels rather than silently importing repaired values. No preset schema or new rate preference was added.

An independent host oracle calls public FFmpeg libavutil timecode functions to generate11535 forward/inverse vectors across all seven existing rates, minute/hour/day boundaries and large positive offsets. The production Kotlin tests consume those stored vectors; the generator can independently reproduce their exact bytes. Two actual FFmpeg reference MOV files contain tmcd sample ordinals107892/215784 for01:00:00;00 at30000/1001 and60000/1001. These are independent reference files, not timecode tracks emitted by OpenCineCam. Sixteen new camera tests (including the initially failing inverse regression) and five app settings/preset tests bring host acceptance to506 unit tests (165 app +341 camera).

This corrects arithmetic/configuration and the existing FREE_RUN display clock. CaptureService currently configures the tracker and queries FREE_RUN display only: RECORD_RUN/REGEN frame/lifecycle calls remain unwired, and actual per-take timecode freezing, pause/source/project mapping, fractional NDF choices and interoperable app-written timecode tracks remain required. The reference tmcd files do not complete those requirements or external timecode/genlock qualification. Full H1–H5 remains ACTIVE; no final APK.

Evidence: build/implementation-h4-timecode-arithmetic/VERIFICATION.txt. Public oracle contracts: https://www.ffmpeg.org/doxygen/8.0/timecode_8h_source.html and https://www.ffmpeg.org/doxygen/8.0/timecode_8c_source.html


The first full regression passed148 existing native cases but lint correctly rejected BigInteger.longValueExact as API31-only with min29. This exposed a platform gap that JVM-only timecode tests did not prove. The implementation now checks bitLength before the longstanding toLong conversion. Two new instrumented cases execute exact DF counts, Long-limit arithmetic and FREE_RUN hour behavior on actual API30 platform classes. Original failed lint/build evidence remains under acceptance-before-api-fix; the final selection includes150 native cases.


Final frozen-source regression completed successfully in9m5s:506 Android unit tests (165 app +341 camera),150 instrumented cases including both new API30 timecode executions, and app/camera lint without errors or new warnings. The independent11535-vector oracle remains byte-identical, and the reference tmcd samples retain107892/215784 at the one-hour DF labels. The failed original inverse regression and failed API31-only lint run remain preserved. Byte-for-byte rollback of all516 original files and separate original/modified/restored test results are recorded in build/implementation-h4-timecode-arithmetic/VERIFICATION.txt. Full H1–H5 stays ACTIVE. Next timecode work must connect actual encoded-frame progress and per-take lifecycle/configuration to RECORD_RUN/REGEN and final file metadata/tracks; the corrected arithmetic alone does not complete that integration. No final APK.
