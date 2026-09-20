# ADR-0031: Fold display sessions and roles

- Status: Accepted
- Date: 2026-09-05
- Related requirements: OCC-PRO-003, OCC-PRO-004, OCC-LIFE-001, OCC-UI-001
- Related plan: OCC-PLAN-064
- Extends ADR-0004 without replacing the primary SurfaceView or Camera2 graph.

## Decision

Use public WindowAreaController operations to distinguish simultaneous presentation from activity transfer. Each has independent UNKNOWN / UNSUPPORTED / UNAVAILABLE / AVAILABLE / ACTIVE capability. Application session state is separate: IDLE / STARTING / ACTIVE plus actual visibility and failure. Only an explicit operator action starts a session. Stored display settings never auto-start it.

The Activity owns FoldDisplayCoordinator and the presentation window. CaptureService retains all camera/recording ownership. Subject-only content receives immutable capture status and display preferences, not a camera binder or settings-navigation action. Closing, hiding or cancelling a display session never issues a capture stop. Generation tokens reject late start/end/visibility callbacks and close late session objects rather than resurrecting a cancelled request.

The initial simultaneous content is STATUS or TELEPROMPTER, not a simulated camera preview. Recording status derives from the service; pending finalization is labelled saving, not saved. Local scripts have a 20,000-character bound and operator cues a 200-character bound. Scroll speed uses frame time with a bounded elapsed-time step; brightness is a window request. Playback and imported reference images remain later units.

Activity transfer offers rear-screen operation using the existing camera interface and a visible return action. Initiation is between takes; it is not labelled simultaneous presentation. System dialogs remain system controlled. This does not change cameras automatically.

FoldingFeature bounds are translated from window coordinates before arranging panes. Tabletop/book layouts separate the existing preview and control deck with a gutter; settings stay in one contiguous pane. The folded layouts use manual/rocker controls rather than interpreting taps on the separate control pane as preview metering. No hinge angle or closed state is inferred from an absent FoldingFeature.

The default close policy continues recording. An optional stop-on-close policy requires a publicly exposed hinge-angle sensor and is frozen when the take starts. Hysteresis requires an observed open before a close edge. This does not guarantee camera availability after a system revocation.

## Follow-up before a live subject preview

Choose and qualify a bounded render distributor for operator / subject / encoder. A second synchronous EGL swap on the recording worker is not accepted as proof of independence. Camera graph, frame age, transforms, per-output LUT, thermal limits, disconnect behaviour and physical cadence require their own tests before a preview mode is enabled. HDMI is separately discovered and never treated as an integrated rear display.

### Implemented GPU output backend (2026-09-05)

OpenCineLogGpuPipeline now accepts an optional SubjectPreviewOutput, after the encoder draw. At that backend-only checkpoint, it did not yet enable a subject-preview mode in the application or change the SDR camera graph. The application integration below advances that checkpoint; physical qualification remains open.

The producer renders into three RGBA8 texture/FBO leases, capped at a 720-pixel longest edge (at most 6,220,800 bytes of color targets, excluding native-window and driver allocations), at approximately 15 frames per second. A latest-only exchange returns superseded frames; a producer polls completion fences with zero timeout before reusing a texture. A separate shared-context worker samples completed textures and owns the native-window swap. Its input-fence wait has a 5 ms budget. An unfinished consumer never owns more than one lease; failed sampling without a completion fence quarantines its lease until teardown. There is no additional CPU frame readback, encoder resize or recording transform mutation.

One process-wide worker lease prevents repeated disconnect/reconnect attempts from accumulating stalled workers. Detach returns without joining the consumer. GL targets and the managed EGL display lease remain alive until the consumer actually retires; a new camera pipeline may proceed while another exterior request reports busy. Surface extents are fixed per attachment. A size change fails only the auxiliary output and requires a new surface attachment, rather than stretching old letterboxing silently. Window integration must guard attachments/status callbacks by session generation and surface identity.

Subject options independently select mirror, display compensation, squeeze and the existing flat/view-assist transform. These are not a user LUT library or physical color qualification. Status records monotonic source-receipt and successful swap-submission times, never panel scan-out or sensor capture time. The presentation UI must expire the status using a clock even when no more frames arrive; a successful swap is not proof that a panel is visible. Shared GPU/driver workload still requires real cadence and thermal measurements.

The synthetic GLES fixture exposed black output from the existing attribute-less vertex draw on the API 30 emulator. Explicit vertex-buffer input now supplies the same quad positions/UV mapping to operator, encoder and subject shaders. Pixel assertions verify red/blue separation, independent subject mirroring and right-angle aspect-fit; lifecycle assertions cover held output buffers, source expiry, abandoned surfaces, slow observers and a retired worker overlapping a new pipeline. A held ImageReader may discard queued buffers instead of blocking swap on a particular driver, so that test is not labelled a measured physical swap stall. The physical encoder/LOG cadence and numerical color gates stay open.

API basis: [Android GLES30](https://developer.android.com/reference/android/opengl/GLES30), [Khronos OpenGL ES registry](https://registry.khronos.org/OpenGL/index_es.php). Source and rollback evidence are in `build/implementation-h2-preview/VERIFICATION.txt`.

## Validation

Pure state-machine, hinge geometry, close-edge and preference policy tests; subject-content UI and persistence tests; public-API probe on the exact device. Emulator layout tests do not qualify physical double-screen output or light.

References: [display modes](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/support-foldable-display-modes), [fold-aware layouts](https://developer.android.com/develop/ui/compose/layouts/adaptive/foldables/make-your-app-fold-aware).

### Application integration (development build)

PREVIEW is now a persisted exterior role alongside STATUS and TELEPROMPTER. Settings expose mirror, independent LOG view assist, status visibility and existing brightness. The window receives an output-only SubjectPreviewPort, not the capture binder. Surface leases have monotonically increasing tokens: stale destroys and frame callbacks cannot affect a replacement surface, even if Android reuses a Surface wrapper. Public presentation availability and an explicit session-start action still govern exterior activation; persistence never starts a window by itself.

For Video with the preview role selected, the camera uses a persistent single-input SDR GPU graph. Starting/stopping its encoder does not replace the Camera2 session. LOG reuses its existing GPU graph; unsupported capture modes show an explicit preview message. A direct-camera to EGL producer transition waits for CameraDevice.onClosed, retaining only the newest pending start. The engine continues accepting the closing device callback during shutdown so an in-flight open can be closed instead of leaking its device.

The operator SurfaceView follows layout-sized GPU buffers and avoids a second view-level front-camera mirror. An active exterior lease can keep an idle GPU preview alive while the operator opens Settings. Returning the operator to an unchanged graph only reattaches its surface. Releasing the last output reclaims an idle camera; it never stops an ongoing take. Display visibility controls the exterior output lease. Independent viewfinder failures detach the affected output rather than failing a recording solely because a window vanished.

The subject badge expires from a monotonic timer even if neither camera nor service emits another state. It reports receipt age, waiting, failure or stale preview; it never calls a queued swap physical panel scan-out. An output busy retiring receives bounded retry attempts, guarded by both camera and surface generations. Dynamic aspect/rotation changes reattach the exterior output rather than reusing an old-sized target.

A real Camera2 emulator test exercises direct → GPU → direct on the same operator Surface, exterior frames, stale lease rejection and detach/reattach of the operator without a new camera session. GLES tests cover texture transforms and output failures. These are development-integration results, not physical dual-screen, encoder-cadence, thermal, HFR or LOG numerical release qualification. Those release gates remain open. Evidence: build/implementation-h2-integration/VERIFICATION.txt.

Reference: [CameraDevice.StateCallback](https://developer.android.com/reference/android/hardware/camera2/CameraDevice.StateCallback).

### Self-recording controls and delayed capture

Only an active activity-transfer session grants the self-recording role. The coordinator reports role changes synchronously; ending a transfer revokes it before a queued deadline can act. The service checks the expected role again for capture requests, including microphone-permission results, rather than trusting a stale UI role. Subject-only presentation still receives no capture commands.

A persisted preference chooses minimal or full operator controls on the transferred screen. The minimal deck keeps an 88 dp capture target, visible return/settings actions, lens selection and next-take audio; camera/audio changes are disabled during a take or countdown. Short layouts scroll instead of dropping controls. Lens selection uses existing service camera-switch rules.

Timer choices are 0 (immediate), 3, 5 and 10 seconds, applied only to self-role capture. Active deadlines are service-owned and never persisted. Remaining seconds derive from monotonic time with ceiling, not from counting handler callbacks. Tickets bind generation, camera, mode, dimensions and frame rate. Returning, changing camera/profile, losing/reopening the primary preview, changing timer duration, explicit cancellation, stopping or destroying the service cancels the ticket. A callback more than one second late or from a backwards clock is cancelled, never converted into a surprise late shot. Old callbacks neither fire nor overwrite a newer timer. Audio choice is captured before countdown and ordinary microphone/orientation/capture validation still runs at dispatch.

A second capture press cancels a pending timer; stopping an existing recording remains immediate. Operator and subject share the same remaining-seconds state, with an accessible live-region announcement. The subject role remains touch-locked/output-only. Timer model, persistence and 200% font UI tests accompany this development increment; physical transferred-screen capture timing remains a release gate.

Microphone authorization also carries a capture-action generation. Reconfiguration, preview loss, role exit/re-entry and timer cancellation revoke earlier tickets even when the eventual Boolean role is equal. Recreated UI has no pending ticket and ignores a restored permission callback. The UI additionally checks the binder instance that requested permission, so a replacement service cannot reuse an old authorization.


## Process owner retirement refinement

Per-engine queues do not serialize a retiring service against a newly created engine using the same native window. CaptureOwnerAdmission therefore chains engine leases process-wide, acquired lazily at first preview rather than construction. An engine's closeAsync completion includes actual Camera2/operator-GPU retirement and predecessor completion; a closed waiter preserves the predecessor barrier. Exposed futures are defensive and native retirement failures remain failures, not permission to acquire an uncertain window. Failed GPU constructors retain a cleanup receipt so throwing construction cannot drop ownership. Queued GL surface operations revalidate generation/closure and checked EGL destruction precedes a successful receipt. The subject consumer remains independent under its retained EGL display lease. Physical window handoff and fold-cycle qualification remain additional acceptance gates.
