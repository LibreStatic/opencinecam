---
plan_id: OCC-PLAN-072
title: "Record takes to an external USB-C drive through SAF"
status: InProgress
revision: 1
milestone: H2
intended_executor: Claude Code
execution_mode: implementation
depends_on:
  - OCC-PLAN-071
blocks: []
requirements:
  - OCC-CAM-001
  - OCC-RAW-001
adrs:
  - ADR-0011
  - ADR-0013
risks:
  - RISK-004
estimated_sessions: 6
expected_repo_state: buildable
created_by: Claude Code
---

# OCC-PLAN-072: Record to an external USB-C drive

## 1. Objective

Every take is written to MediaStore on `VOLUME_EXTERNAL_PRIMARY`, the phone's internal memory. RAW video fills it in minutes: full-sensor RAW10 at 60 fps is 936 MB/s, about 14 GB in 32 s. Long MP4 takes add up too. The operator should be able to record straight to a drive on USB-C, the way a cinema camera records to its media.

## 2. Operator decisions (2026-11-08)

- Every take type can go to the drive: video, RAW video, audio sidecars and stills.
- If the drive is missing when a take starts, the take goes to the phone and the HUD says so. A take is never lost. A hard disk needs tens of seconds to spin up after Android announces it, so for 45 s after a USB storage device attaches (`UsbDriveArrivalMonitor`) a missing drive reads "USB drive starting" instead of "not connected"; takes still go to the phone meanwhile. The window is per device, because a hub's card reader stays attached with no volume. When a mount is cut short (a hub dropping while vold mounts), the Razr's vold mounts the same drive from then on under a stable name with a suffix (`FFF1-7571` becomes `FFF1-7571-01`), which the stored tree and its permission no longer match. A mounted volume with the same identity (`driveIdentityOf`: the id without one trailing `-NN`) reads "Choose USB folder again", and Settings asks for the folder again. The store keeps the tree picked under the other name as an alias, with its permission, and switches back to it when that name mounts again, so the operator picks each name once. The drive's speed and RAW qualification are keyed by the identity and survive the rename.
- If the drive drops out mid-take, the behaviour is a setting. The default is **Stop and save**: the take ends and keeps what reached the drive. The alternative is **Continue on the phone**.
- The gallery lists the drive's takes while it is connected, with a location badge.

## 3. Evidence (Razr Fold, Android 16, 2026-11-08, throwaway probe app)

The drive was a WD Elements Desktop (self-powered, exFAT) on the USB 3 bus, first through a plain OTG adapter, then through a powered hub.

- A SAF descriptor from `com.android.externalstorage.documents` resolves to `/storage/<UUID>/…`, the FUSE path. Through it, writes ran at 96 MB/s on average (1 s windows from 44 to 181 MB/s), the same as `dd` from the shell, so the disk was the limit, not SAF.
- On that descriptor all of these worked:
  - `ftruncate` to 6 × 10⁹ bytes, in 64 ms;
  - `MediaMuxer` writing 3 s of 1080p H.264, then read back by `MediaExtractor` (1 track, 3.04 s);
  - `pread` of `ftyp`, `pwrite` with read-back, and `fsync`;
  - `DocumentsContract.renameDocument`.
- The provider appends an extension from the MIME type: `probe.mp4.part` created as `video/mp4` became `probe.mp4.part.mp4`.
- A MediaStore insert on the USB volume worked on this device (the volume was `VISIBLE_FOR_WRITE`). AOSP does not promise that for USB drives, so it is not used.
- On unplug, `FileChannel.force` threw `IOException: No such device` (ENODEV), sometimes before any volume event arrived. The events came in this order: `StorageVolumeCallback` state `ejecting`, `MEDIA_EJECT`, `MEDIA_UNMOUNTED`, `MEDIA_BAD_REMOVAL`. On replug they were `mounted` and `MEDIA_MOUNTED`.
- On replug, SystemUI's `UsbResolverActivity` put an "open with" chooser over the running app.
- `ACTION_OPEN_DOCUMENT_TREE` was answered by an installed third-party file manager instead of the system DocumentsUI.
- The drive dropped every 3 to 20 s on the OTG adapter even though it has its own 12 V supply; the kernel logged a type-C "Disconnect received". A powered hub mostly cured it.

## 4. Decisions

- **SAF is the only mechanism.** The operator picks one folder on the drive. SAF works with every drive Android mounts, and its descriptors are seekable files, which MediaMuxer, `Os.pread`/`pwrite` (`ProjectMp4Descriptor`) and the WAV and FLAC header rewrites need.
- **The picker is the system DocumentsUI.** The picker contract targets `com.google.android.documentsui`, then `com.android.documentsui`. An adopted tree must come from `com.android.externalstorage.documents` and must not be the phone's own storage.
- **exFAT is required.** At adoption, a probe extends a scratch file past 4 GiB with `ftruncate`, then deletes it. If that fails, the drive is rejected with "Format the drive as exFAT", because FAT32 caps a file at 4 GB, which breaks long MP4 takes and every RAW take.
- **The destination lives outside presets.** It is stored in its own SharedPreferences (`recording_destination`) with the persisted URI permission, like the CinemaDNG export folder. Presets never hold paths.
- **Layout on the drive.** Each take gets its own folder, `<picked>/OpenCineCam/<stem>/`, with the video, WAV/FLAC and JSON sidecars together. Stills go in `<picked>/OpenCineCam/Stills/<stem>/`.
- **Final names from the start, with an in-progress marker.** Every file is created under its final name. A hidden `.occ-in-progress` document (`application/octet-stream`) in the take folder is the drive's equivalent of `IS_PENDING`: it is deleted at commit, or when recovery publishes the take as incomplete. Document URIs therefore never change. The approved plan renamed `.part` files instead, but a rename changes the document ID, and with it every URI already recorded in prepared artifacts and sidecars. The extension finding above made that scheme worse still.
- **Journal.** `recording-recovery.sqlite` records each take's destination: the volume and the take folder's document URI. Recovery skips takes whose volume is not mounted and keeps them for later; today a missing volume fails the whole pass with "Recording volume unavailable". Recovery runs again when that volume is mounted.
- **Disconnects.** Either an ENODEV write failure or a volume `ejecting` event ends the take through the existing early-stop path, under the default Stop and save. What reached the drive is recovered as an incomplete take when the drive returns.
- **Losing focus is tolerated.** The camera must survive the USB chooser on replug; it is an ordinary loss of focus.
- **The chooser can be answered once.** No app can hide another app's USB chooser, so while a USB folder is the destination the app enables `.UsbDriveAttachAlias`, an activity with no display for `USB_DEVICE_ATTACHED` on mass storage (class 8) that finishes at once (it opens the camera only when the app has no task; an alias of `MainActivity` stacked a new instance over the settings or the picker on every attach). The operator picks OpenCineCam with Always, and Android then brings the app to the front on attach with no chooser (per USB device). It is off otherwise: as the only candidate the app would make Android confirm every attach. See ADR-0037 decision 9.
- **Transfers.** Delivery 1 does not enrol drive takes in the WebDAV outbox; it waits for Delivery 2.

## 5. Deliveries and slices

### Delivery 1: video, audio and stills on the drive

Status (2026-11-08): slices A to F are implemented on `feat/external-ssd-recording`, uncommitted, with 2353 host tests passing. The device cases in `docs/device-validation.md` are pending. ADR-0037 records the decisions.

| Slice | Owner files | Depends on |
|---|---|---|
| A. Destination model, store, tree validation, exFAT probe, volume monitor, picker contract, shared `safFreeBytes` | `storage/RecordingDestination*.kt`, `storage/RecordingVolumeMonitor.kt`, `storage/SafStorage.kt`, `storage/RecordingDriveProbe.kt`, `export/SafCinemaDngDestination.kt` | — |
| B. Take storage backend: MediaStore or SAF behind `RecordingCaptureRecovery`, the marker, journal v2, recovery that skips absent volumes | `storage/RecordingCaptureRecovery.kt`, `VideoOutput.kt`, `WavAudioSidecarRecorder.kt`, `FlacAudioSidecarRecorder.kt`, `IncompleteTakeRecovery.kt` | A |
| C. Stills on the drive | `storage/StillImageSaver.kt`, `StillCaptureRecovery.kt`, `StillPublication.kt` | A |
| D. Service wiring: resolve the destination at take start, fall back with a reason, free space from the destination, stop and save on ENODEV or eject, recover on mount | `service/CaptureService.kt`, `service/RecordingStoragePlan.kt`, `CameraUiState` | B, C |
| E. UI: the Recording destination and If the drive disconnects settings rows, HUD destination chip and fallback notice, strings in six locales, driver screens | settings, HUD, `DriverScreens.kt`, `AGENTS.md` | D |
| F. Per-drive MP4 benchmark at adoption, stored by volume | `storage/ContentResolverBenchmarkDestination.kt` or a SAF twin | A |

### Delivery 2: gallery, network drive and export

- The gallery adds a SAF source that walks the tree while the volume is mounted. It merges with MediaStore by date and shows the badge `gallery-location-ssd`.
- Thumbnails come from `MediaMetadataRetriever`. Rename and delete use `DocumentsContract`.
- The network drive gets a SAF `ShareTakeSource`, and the WebDAV outbox gets a SAF artifact source.
- Proxies and the CinemaDNG export read from SAF; their outputs stay where they go today.

### Delivery 3: RAW video on the drive

Status (2026-11-09): implemented on `feat/external-ssd-recording`, uncommitted, with host tests passing; the device cases in `docs/device-validation.md` are pending. ADR-0037 decision 7 records it. A drive qualification is a full run (camera checks are not reused from the phone's); the journal stays at schema v2.

- `SafRawVideoOutput` implements `RawVideoTakeOutput`, with recovery entries in the SAF journal.
- Qualification is per drive, with `destinationId = "saf:<volumeId>"` replacing the hard-coded `DESTINATION_MEDIASTORE_DOCUMENTS`.
- A drive's qualification ignores the storage rest rule (`storage-not-rested`): a USB drive writes unevenly when rested too, so the rule would end every run on a hard disk. The sweep's `rest` object still records the verdict, marked `ignored`.
- The storage sweep and RAW admission use the drive's free space.

### Delivery 4: continue on the phone

Status (2026-11-09): implemented on `feat/external-ssd-recording`, uncommitted, with host tests passing; the device cases in `docs/device-validation.md` are pending. ADR-0037 decision 6 records it. RAW uses the same restart as MP4 instead of switching the writer's channel: the drive part is stopped and kept, and a new `.ocraw` take `<stem>_cont1` starts on the phone with `continuation*` keys in its static metadata. The gap is about 1–2 s rather than 1 s. Time-lapse and high-speed takes stop and save.

- **MP4:** the take closes on the drive and a continuation take, `<stem>_cont1`, starts on the phone, linked in the clip sidecar. The gap is about 1 s.
- **RAW:** the writer switches its channel to a phone output. This is allowed only if the configuration is qualified for the phone.

## 6. Acceptance

- Host tests cover:
  - the destination resolver and its fallbacks;
  - tree validation;
  - the exFAT probe decision;
  - the journal with SAF entries and with an absent volume (its takes are kept, not discarded);
  - the merged gallery cursor;
  - the RAW store keyed by `destinationId`.
- Compose Driver renders the new settings rows and HUD states on `phone`, `inner` and `landscape`.
- On the devices:
  - a 10 min 4K MP4 + WAV take to the drive, and a still;
  - starting with no drive (fallback plus warning);
  - pulling the drive mid-take in both modes, then replugging (recovery);
  - qualifying a drive for RAW and recording a full-sensor 30 fps RAW take;
  - the gallery with the drive on and off.

## 7. Out of scope

- NTFS and other filesystems that some OEMs mount: they may work, but there is no promise.
- More than one recording drive at a time.
- Writing proxies or CinemaDNG exports to the recording drive automatically.
