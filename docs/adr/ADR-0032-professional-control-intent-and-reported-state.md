# ADR-0032: Professional control intent and reported state

- Status: Accepted
- Date: 2026-09-05
- Related requirements: OCC-PRO-001, OCC-PRO-005, OCC-PRO-006
- Related plan: OCC-PLAN-065

## Decision

SettingsRepository owns professional exposure and white-balance intent, shared by the settings hub and quick controls. Reuse ExposureMode and WhiteBalanceSelection. Native Camera2 priority is enabled only with the advertised API 36 mode and request key; older cameras show the unsupported branch, not simulated priority. Manual exposure supplies both sensor parameters. Selecting one legacy manual knob freezes the other at its reported value (or a disclosed default), rather than silently returning to full AE.

Angle is a persistent exposure representation, not an encoder/project FPS setting. Resolve it against capture FPS using rational arithmetic, clamp to advertised exposure bounds and frame duration, and retain the selected angle when FPS changes. CaptureResult remains the authority for actual time/ISO. HAL antibanding and 50/60 Hz shutter suggestions are distinct; neither promises removal of arbitrary PWM flicker.

CCT Kelvin/tint requires advertised CCT mode and request keys. Tint is bounded to -50..50; presets remain AWB presets. Unsupported selections on a new camera resolve explicitly to AUTO while preserving requested preferences and displaying a disclosure. Optional metadata remains unknown when absent. WB-at-record locking must be qualified separately before being offered.

Exposure/WB intent can change live on regular sessions. Constrained HFR keeps the explicit unavailable branch until request/cadence qualification, rather than accepting an untested setting. Structural ISP/stabilization changes remain deferred during REC; independently requested noise/edge modes and reported results must not be conflated with audio effects. Presets/programmable controls and scopes/LUT/audio work retain the same shared-settings and requested/reported contracts.

## Validation

Unit tests for rational angle resolution, clamping, native-priority availability and WB adaptation; strict dependency verification; preference round trips and accessible settings; real Camera2/emulator integration without physical promotion. Physical 50/60 Hz, first-frame WB, ISP, REC/LOG/HFR and audio/LUT qualification remain open. H3 is not complete merely because its initial controls pass host tests.

Sources: [Camera2 requests](https://developer.android.com/reference/android/hardware/camera2/CaptureRequest), [Camera2 characteristics](https://developer.android.com/reference/android/hardware/camera2/CameraCharacteristics). API source also inspected locally under Android SDK sources/android-36.1.

## ISP/stabilization continuation

ImageProcessingSelection reuses StabilizationMode (OFF/VIDEO/OPTICAL) and IspMode (DEFAULT/OFF/FAST/HIGH_QUALITY). Null stabilization and DEFAULT processing preserve the request template, not a guessed vendor default. Noise reduction and edge enhancement are independent. Each control is gated by both its Camera2 request key and advertised values. OIS and EIS are mutually exclusive; separate support does not prove a combined mode. Unadvertised choices retain their preference and resolve to the original template with a visible disclosure.

CaptureService freezes processing from video preparation through recording/finalization, while exposure/WB and monitoring remain live. A rejected update restores the previous processing intent without stopping the take. Per-builder weakly held template snapshots allow DEFAULT to restore exact request defaults. New regular sessions receive processing session parameters where advertised; changing session keys between takes may cause a HAL reconfiguration delay and is disclosed. Constrained HFR retains template defaults pending qualification. Request/result values and raw SCALER_CROP_REGION are displayed separately; the latter does not measure all EIS field-of-view changes. Physical movement, crop, texture, noise, color and sustained recording acceptance remain open.

## Recording white-balance preparation

`recordingWhiteBalance` is persisted as `CONTINUOUS` (existing live behavior) or `LOCK_ON_RECORD` (hold for one take). The service freezes structural preferences before preparation and creates no clip output until Camera2 confirms the requested route. AUTO waits for convergence, submits `CONTROL_AWB_LOCK=true`, then requires an AUTO request/result, reported lock, AWB LOCKED and a positive sensor timestamp strictly newer than the unlocked converged frame. A three-second monotonic deadline, camera/session generation checks and explicit cancellation prevent delayed starts. Unsupported or constrained-HFR routes do not silently switch policy.

Fixed presets and CCT/tint also await a matching submitted/result mode and positive timestamp; direct CCT/tint must be reported as requested. They are labeled FIXED, never AUTO-locked. The held selection survives live exposure changes and request rebuilding; pending WB changes apply after finalization, when the AUTO lock is released. Changing the policy during a take is also deferred. The preview may continue while GPU frames older than the confirmed sensor timestamp are excluded from the encoder in retained SDR and LOG graphs and newly constructed SDR graphs. Actual codec first-frame/color and cross-session lock preservation still need device qualification; the software boundary is not physical color proof.

Primary API reference inspected locally: Android SDK 36.1 `CaptureRequest.CONTROL_AWB_LOCK` documentation (in-flight requests and the need to wait for a locked result; AWB lock has meaning only in AUTO). No private device APIs or inferred support are used.

## Portable presets and C1/C2

Presets use a closed version-2 JSON schema: 80 explicitly registered portable preference keys plus capture mode, manual-focus intent and zoom. A snapshot adapter reuses `CameraSettingsStore` without reading or writing live preferences; historical app preference migrations are marked already handled in the isolated adapter so importing a WAV preset does not rewrite it to AAC. Version 1 (the settings-only draft) migrates missing settings from defaults and introduces VIDEO, autofocus and 1× zoom. Unknown versions/fields, invalid primitive types, noncanonical values, duplicate keys (including escaped duplicates), arrays, excessive nesting and documents over 64 KiB are rejected. New application preferences are not exported automatically: the V2 registry and its coverage test require a schema/privacy decision.

Physical audio input IDs, teleprompter script/cue, app migration flags and per-camera focus marks are not portable. Applying a preset preserves current local routing and script content; it never opens an exterior session or starts capture. A local library holds at most 32 presets with distinct case-insensitive names; C1/C2 reference local library IDs, which are not exported. Save/update/rename/delete and slot changes commit storage before publishing state. A damaged library is retained until explicit reset confirmation, and failed writes leave published state unchanged.

All applications, including C1/C2, show a scrollable difference/compatibility review against the current lens. Unavailable modes stay unchanged; unsupported exposure/WB/ISP/geometry values retain the existing disclosed adaptation paths. During preparation, REC and finalization, structural preferences and preset mode/focus/zoom wait; live-qualified preferences follow `withLivePreferencesFrom`. The next idle transition resolves the pending capture mode/geometry and restores focus/zoom after preview configuration. No imported physical camera ID is selected. Focus marks remain engine-local and camera-specific; broader programmable controls/startup policies and focus-transition lifecycle review remain CAM-04 work.

Document IO uses explicit Android document-picker URIs, bounded UTF-8 reads on the IO dispatcher and a separate save confirmation after import. Import does not automatically apply a preset. Rename is separate from replacing settings with the current requested snapshot. C1/C2 are optional (unassigned slots occupy no capture-row space). A disabled `ChoiceTile` now disables its click action and accessibility semantics, rather than only changing color.

## Focus-transition lifetime before programmable controls

Focus pulls now run through `FocusPullSession` tokens on the camera executor. The image handler is only a timer: each scheduled closure carries both the pull token and camera generation, and stale work returns before changing focus or submitting a request. Cancellation, replacement, explicit manual focus/autofocus, tap focus, AF-lock commands and resource closure invalidate the transition. Completion is emitted once; delayed execution catches up from monotonic elapsed time rather than accumulating intermediate positions.

Manual-focus intent is associated with its camera ID: an idle recreation of the same camera retains the last distance, while switching cameras clears it to autofocus. A-D focus marks remain camera-specific, process-local immutable maps in a concurrent registry. Invalid/nonfinite marks and nonfinite or out-of-range-duration pull requests are rejected before dispatch. Service callbacks report the retained/cleared selection and refresh the visible mark set when the new preview begins. This is session/request ownership, not optical focus accuracy or smoothness qualification. Real lens/HFR/REC continuity and HAL rejection behavior still require acceptance.


## Operator inputs, startup and capture-control lock

Three operator buttons use named actions: none, capture/stop/cancel, torch toggle/next advertised level, peaking, zebra, histogram, view assist, autofocus, focus A/B transitions, C1/C2 review, exterior subject session and take-control lock. Volume up/down independently support the same actions plus system volume (default). There is no hidden long-press/repeat action. Each consumed down owns its matching up; activity pause/focus loss revokes held keys. Only the focused, resumed capture section dispatches mappings. Dialogs, open control panels, visible IME and other sections retain system volume. A transferred minimal-self role permits only capture and lock actions via this mapping, not operator preset/display commands.

Capture inputs reuse the same permission dialog and role/generation ticket as the touch button. C1/C2 still open the reviewed diff; an unavailable action is disabled or explicitly reported, not simulated. Exterior starts only a publicly available presentation or closes the current explicit session. Button names describe requested monitoring state; they do not claim physical output acceptance.

Startup mode is PHOTO (default), VIDEO or LAST. The last mode is local session preference data, never a camera/device ID; unsupported remembered modes fall back to PHOTO. Startup applies once per capture-service creation, not per activity/fold recreation. Existing exposure/WB values restore by default; torch restoration defaults off. Disabling restoration resets the corresponding saved values on service startup. Focus/zoom/physical lens are not restored across service lifetimes, and capture never starts automatically.

The persistent take-lock preference applies from capture preparation through finalization. Direct focus/zoom/EV/AE/AF/preset commands are rejected while locked; exposure/WB/torch edits in Settings remain pending. Monitoring, layout, stop/cancel and explicit unlocking stay live. Turning the switch off applies pending live camera preferences; structural values still await take completion. A focus pull cancels at locked-take entry or when the lock is engaged. Presets cannot implicitly unlock an already locked take. Photo completion replays pending settings after the final burst result or DNG save.

### Preference matrix — Controls and presets → Operator controls

| Preference | Default | Domain | Persistence/application |
|---|---|---|---|
| Button 1 / 2 / 3 | Torch / Peaking / View assist | Named non-system actions including None | Shared settings; live remapping, no action on selection |
| Volume up / down | System volume / System volume | Named actions, None or System volume | Shared settings; new first-down only, capture screen/lifecycle/focus gated |
| Startup mode | PHOTO | PHOTO / VIDEO / LAST | Next service creation; current capability gate, never capture |
| Restore exposure/WB | On | Boolean | Next service creation; off resets to AUTO/default exposure |
| Restore torch | Off | Boolean | Next service creation; off clears enabled intent, keeps preferred intensity |
| Lock during take | Off | Boolean | Live policy; stop/cancel/monitoring/unlock stay accessible |

Preset schema V3 explicitly adds those nine scalar preferences to the unchanged 80-key V2 allowlist. V2 imports require exactly the V2 fields and obtain default operator preferences; V1 remains the bounded settings-only migration. V2 cannot inject V3 fields. Unknown enum values and using SYSTEM_VOLUME as a button action fail canonical validation. The separate last-mode memory, library IDs, hardware IDs and private content remain excluded. This supersedes V2 as the export format without changing old source-evidence archives.

Finalization or failed preparation releases the started-service lifetime, while active bindings keep the service alive. Unbinding an idle service also releases that lifetime; unbinding during a take does not stop the take. This permits startup policy to run on the next actual service creation without resetting an active fold/window session.

Visual inspection also found default light-theme control labels on the dark settings background. The app now declares a coherent dark Material color scheme with amber/cyan actions, white on-surface text and dark modal surfaces; this fixes mapping choices, unselected chips and search fields rather than painting isolated labels.
