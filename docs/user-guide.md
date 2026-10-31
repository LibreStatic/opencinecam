# OpenCineCam user guide

This guide is for camera operators. It explains what each part of OpenCineCam does and where to find it. It covers version 1.0.0.

OpenCineCam only offers what your phone can actually do. A mode, control or option that your camera does not support is hidden, greyed out or labelled with the reason. Results differ between phones.

Settings are grouped into categories: **Capture and exposure**, **Video and photography**, **Color and monitoring**, **Audio**, **Media and files**, **Controls**, **Displays and foldables**, **WebDAV queue** and **About and diagnostics**. The search field at the top of Settings finds any setting by name.

Most settings have an info button with a one-line summary beside it. Tap it to read the full explanation.

## First run and themes

The first launch opens a short welcome tour:

1. What the app does.
2. A tour of the viewfinder: tap a highlight to learn what it does.
3. Permissions, each marked **Required** or **Optional**. Only the camera is required. Without the microphone, clips are silent. Location is used only if you turn on geotagging. Notifications show a notice while recording, so a take keeps going if you leave the app.
4. The license and the open-source components.

You can replay the tour from **Settings › About › Show welcome tour**.

Tip: long-press a control to learn more about it.

**Themes.** Go to **Settings › Controls › Appearance**:

- **Cine** keeps the dark graphite and amber look.
- **Material You** takes its colours from your wallpaper and follows the system light or dark mode, with a pure black background in dark mode.

The same category also holds **Mode selector** (a tactile **Dial** or a **Buttons** grid), **Translucent interface** and **Haptic feedback**.

**Haptic feedback.** Choose **Off**, **Subtle** or **Full**, then turn each group on or off: **Capture** (record, shutter, pause), **Controls and dials**, **Focus and exposure**, and **Menus and navigation**. **Mute ticks during a take** keeps only stop and pause vibrating while recording. Haptics need "Touch feedback" turned on in the system settings.

**Back gesture.** The system back gesture previews the screen you are going back to before you let go.

## Capture modes

Open the mode selector (**MODE**) to switch modes:

| Mode | What it does |
|---|---|
| Photo | Single still. |
| Burst | A series of stills. Set the count in **Video and photography**. |
| Bracket | Stills at several exposures. |
| Light Painting | Accumulates frames into one image for light trails. |
| Video | Regular video, AVC or HEVC. |
| Slow motion | High frame rate takes (120 or 240 fps where the device offers them). Rates at or below the project rate record in real time. |
| Time lapse | Frames at an interval, set with the **INTERVAL** slot. |
| Log | OCLog2 LOG video in HEVC Main10. **Experimental.** |
| HLG | HDR video tagged as BT.2020 HLG. **Experimental.** |
| RAW photo | DNG stills. **Experimental.** |
| APV, RAW video | **Experimental.** Currently not supported on any device. |

LOG and HLG are shown as **Experimental** until your camera has a verified OpenCineLog profile. **Settings › About and diagnostics › Mode availability** lists every mode and why it is or is not available.

**HDR format.** In **Video and photography**, HLG takes can record as **HLG** or **HDR10** (PQ). The choice appears only where the camera and a hardware encoder support HDR10. It applies to the next take.

**Night photo.** In **Video and photography › Night photo**, choose **Off**, **Auto** or **Always**:

- **Auto** offers a **NIGHT** quick toggle on the capture screen when the scene is dark. Tap it to use Night for now.
- **Always** uses Night for every photo.

Night photo uses the device's night extension, which merges several frames. Hold the camera steady until the save finishes. While Night is on, manual exposure, white balance and focus, RAW, HEIC, flash, bracketing and the scopes are unavailable. Night photo captures JPEG only.

**Busy and locked controls.** While a photo, burst, bracket or night photo is saving, the controls show **Busy** and come back when it finishes. With **Lock capture controls during a take** on (in **Operator controls**), the controls show **Locked** during a take. Stop, monitoring and Settings still work, and **Unlock** releases them.

## Exposure and FPS LOCK

The slot strip along the viewfinder holds **RES**, **FPS**, **SHUTTER**, **ISO**, **EV**, **WB** and **FOCUS** (and **INTERVAL** in time lapse). Tap a slot to adjust it. The picture stays in view while you do. A greyed slot says why it cannot be used, for example "This camera does not offer manual exposure."

- **Shutter** can be shown as a speed or as an angle (**Shutter unit** in **Capture and exposure**). The angle follows the capture frame rate.
- **White balance** has presets (Daylight, Cloudy, Tungsten, Fluorescent) and a **TINT** control.
- **Focus** has AF and MF, focus marks you can save and pull to, and pull curves (Linear, Slow start, Slow end, S-curve).
- **AE LOCK** and **AF LOCK** sit beside the slots. **AF Lock behavior** chooses between **Freeze current** and **Focus and lock**.

**FPS LOCK (cadence lock).** In low light, a camera normally lowers the frame rate to get a brighter image. FPS LOCK keeps the frame rate instead, by holding the shutter at 1/fps. The image gets darker, but the cadence stays fixed.

- It is on by default.
- It works in Video, Log and HLG, with automatic exposure.
- An **FPS** key appears beside the lock keys on cameras that support it. It turns amber while it is holding the shutter at 1/fps.
- Some cameras ignore the request to hold the shutter. On those, OpenCineCam holds it itself with manual exposure and adjusts ISO in software.
- The switch is in **Settings › Video and photography › FPS LOCK**.

**Low-light auto frame rate** (off by default) does the opposite: it lowers the frame rate for a brighter image, and files may become variable frame rate. It applies only to regular Video. When it is on, it overrides FPS LOCK. Leave it off for professional recording.

## Lenses

### Zoom anchors

On phones with several rear lenses, the anchor buttons (for example 0.5×, 1×, 2×) jump to each lens. Pinch or use the zoom rocker for the values in between.

**Settings › Capture and exposure › Lens switching** chooses how zoom crosses lenses:

- **Manual presets**: pinch and the rocker stay inside each lens' range. Use the anchors to change lens.
- **Automatic**: the camera switches lenses as you zoom.

### Lens lock

Lens lock keeps a take on one physical lens. Zoom becomes digital, and the camera never switches lens.

- On the capture screen, tap the **LENS** key beside the lock keys. It locks to the lens in use.
- While locked, the anchor bar shows the lenses you can lock to. The locked one has a padlock. Tap another lens to lock to it instead.
- In Settings, use **Capture and exposure › Lens lock**: **Off**, or one choice per lens (ultra-wide, wide, tele).
- You cannot change the lock during a take. It is not offered in slow motion.
- If the lens cannot be opened on its own, OpenCineCam falls back to a digital crop and shows "Lens lock: digital crop".
- If the camera still switches lens in low light, the viewfinder shows "The camera switched lens in low light".

### Lens calibration

Each lens on a phone renders colour, exposure and framing a little differently. Lens calibration measures how each lens differs from the main one and corrects it, so the picture holds steady when zoom crosses a lens.

Open **Settings › Capture and exposure › Lens calibration**, then tap **Calibrate**.

What a calibration run needs:

- A phone with more than one rear lens. A single-lens camera has nothing to match.
- The video pipeline in the current mode.
- A static scene with plenty of texture and colour, farther than 2 m away, in even light.
- The phone held steady, or on a tripod.

The run shows a card over the viewfinder: **Preparing**, **Finding switch points**, **Measuring** and **Solving**, with a status for each lens. Keep the phone still. When it finishes, choose **Save** or **Discard**.

Each lens gets a quality score, a colour error (ΔE) and an alignment error in pixels. Flags warn about **Low texture**, **Low colour**, **Clipped**, **Unstable exposure** or **Parallax**. If the run fails, the card says why, for example that exposure never settled or that no lens gave a trustworthy result.

The corrections apply once a calibration is saved:

- **Correct preview and video**.
- **Correct photos (JPEG/HEIC, experimental)**. On some phones the photo pipeline renders each lens differently from video, so a corrected photo may not match a corrected video.
- **Align framing between lenses**. Alignment is exact only near the calibrated distance.

If the light has changed since the calibration, the page warns you. Calibrate again in the light you shoot in. **Delete calibration** returns each lens to its own colour and framing.

### Smooth lens switch

Off by default. When you zoom across lenses, the camera holds back a few frames before switching. **Smooth lens switch** crops those frames so the framing does not jump. It is off with lens lock and in slow motion. Find it in **Capture and exposure**.

## Monitoring tools

All monitoring tools are display only. They never change the recorded file.

- **Zebra**, **Focus peaking**, **Histogram**, **Composition grid** and **Horizon level**.
- **Scopes**: waveform, vectorscope and false colour, one per tab. Each tab explains how to read it.
- Quick keys in the capture row turn zebra, peaking, false colour and the scopes on and off.
- **LUTs**: separate operator, subject and recording LUTs, managed in **Color and monitoring › Preview and recording LUT library**.
- **LOG View Assist** shows LOG footage as Rec.709 while you shoot.
- Some cameras cannot run the preview, the scopes and RAW at the same time. OpenCineCam then drops the scopes first and RAW second, and a notice under the zoom says what was lost ("Scopes unavailable with this configuration", "RAW unavailable with this configuration", or both). In RAW photo mode the shutter then fails with that reason instead of saving a different format.
- The thermal indicator shows the device's heat load. When the device is too hot, the scopes pause ("Scopes paused: device is too hot") and come back by themselves. Recording is not affected.
- **Gyro data for Gyroflow** (in **Video and photography**, off by default) saves the phone's motion next to each Video, LOG or HLG take as a `.gcsv` file with the same name. Turn off the camera's own stabilization for the best match. It is not used for slow motion or time lapse.

**Keyboard shortcuts** (Chromebook, DeX or a tablet keyboard):

| Key | Action |
|---|---|
| R | Start or stop a take, or take a photo |
| F | Open or close the focus panel |
| Z, P, G | Zebra, peaking, grid |
| H | Show or hide the scopes |
| L | LOG view (capture) or LOG/709 view (playback) |
| Space | Play or pause (playback) |
| ← / → | One frame back or forward; with Shift, one second |
| Esc | Close the open panel, sheet or inspector |

**Operator controls** (in **Controls**) let you assign actions to three on-screen buttons and to the volume keys.

## Audio

Audio settings are in **Settings › Audio**.

- **Record microphone audio** turns audio on. Video still records without it.
- **Professional audio** chooses the format. AAC is stored inside the MP4. WAV and FLAC are saved as separate files, synchronized with the video.
- **Audio meters**: Peak / RMS, VU or Quasi-PPM, with an adjustable peak hold.
- **Gain** is digital processing of the recorded samples. Positive gain can clip.
- **Listening output** plays the input to wired or USB headphones, Bluetooth or the speaker. Speaker playback can be picked up by the microphone.

### Input device and USB microphones

**Input device** lists the inputs Android reports: built-in, **USB microphone**, Bluetooth and wired. **Auto** shows which one it is using. Android lets you choose the input device. It does not guarantee a specific built-in microphone capsule.

**If the input is disconnected** decides what happens during a take:

- **Stop the take**.
- **Continue on built-in mic**.
- **Continue with silence**. Takes recorded through the standard H.264 path cannot write silence, so they stop instead.

A take never changes microphone silently. The badge turns amber and the change is logged. If the selected input is not connected when you start, the take is refused until you connect it or change the setting.

## Recording policy

**Settings › Video and photography › Recording policy** decides what happens when storage, heat or frame timing leave the safe range during a take:

- **Strict** (the default on new installs) stops the take and states the reason. The file is saved.
- **Adaptive** keeps recording, reduces monitoring work and shows a warning under the REC strip.

If the device's HEVC encoder refuses the chosen format when a take starts, the take records H.264 instead under either policy, and the warning under the REC strip says "Recording in H.264: HEVC encoder unavailable".

The policy applies to the next take.

If a take is interrupted by a failure, OpenCineCam keeps what was written. The take is saved as an **Incomplete take**, and the gallery lists the reasons.

## Media and transfers

The gallery shows your takes with thumbnails, codec badges and proxy badges. Open a take for its details, playback, rename, share or delete. Playback of LOG footage can switch between the LOG and 709 view.

Each video take has a `<take>.clip.json` file next to it. It records what you asked for when the take started, what actually ran, pauses, the device profile and any conditions logged during the take. Values that were not known are written as unknown, never guessed.

**Settings › Media and files** holds sharing, gallery, playback and file naming options, plus **Production slate** and **Capture geolocation** (off by default).

### Proxies

A proxy is a smaller copy of a take for editing on the device. Generate one from the gallery with **Generate proxy / retry**. The original stays intact. **Proxy settings** set the maximum long edge, the requested bitrate, a minimum battery level and reserved space. Proxies are listed under **Saved proxies**.

### WebDAV transfers

WebDAV sends takes to your own server. It is off by default. Set it up in **Settings › WebDAV queue**:

1. Enter the **HTTPS destination** and tap **Save destination**.
2. Add **Destination credentials**. They are stored only on this device, in Android Keystore.
3. Turn on **Register new takes for WebDAV**. Only takes recorded after this are registered.

Transfers use Wi-Fi only, unless you turn on **Allow cellular and metered networks for transfers**. Transfers pause while recording.

**Manual sending.** Under **Send registered takes**, choose a take and tap **Send and verify take**. OpenCineCam sends its files and then reads back the remote copy to verify it. It never overwrites a remote file that differs.

**Automatic upload.** Turn on **Upload new takes automatically** to upload takes in the background. It is off by default and needs WebDAV turned on. Only takes recorded after you turn it on are uploaded. A "Transfers" notification shows while it runs.

**Ignore certificates (LAN)** is only for a destination with a private or loopback IP address. It allows server impersonation. Leave it off unless you trust the local network.

## Foldables and the cover screen

On a foldable, open **Displays and foldables** from the capture screen or from Settings.

**Inner screen and folding:**

- **Separate preview and controls at the hinge**, and **Swap preview and controls panes**.
- **Continue recording when folding**. If the device has no hinge sensor, automatic stop on closing is unavailable, so stop explicitly.

**Exterior display content.** With the phone unfolded, you can show one of these on the cover screen, facing your subject. Tap a content to choose it and open its settings, then tap **Open subject display**.

| Content | What the subject sees |
|---|---|
| Status | **Ready** at rest, then a large REC and the take time |
| Teleprompter | Your local script, with adjustable text size and speed |
| Camera preview | The rear camera's view (Video and LOG modes), optionally mirrored |
| Fill light | The cover screen as a soft light |
| Take review | A take or photo you choose with **Show to subject** in the gallery viewer, played muted |
| Interview questions | Your question list, one at a time |
| Digital slate | A slate, with an optional sync beep at REC |

**Split screen with camera** shows the chosen content on top and the rear camera preview below. With fill light, the preview sits under a dither mask.

Optional extras for the subject: **Subject self-monitor** guides, **Tally and countdown** (red only while recording is confirmed, amber while preparing, paused or saving), and an **Out-of-frame warning** where the camera offers face detection.

**Self-recording.** **Move camera to exterior** moves the capture controls to the cover screen, so you can film yourself with the rear cameras. It offers a **Timer** and **Audio for the next take**. Start it between takes. **Return to the inner display** brings the controls back.

Closing a display session does not stop recording.

## Diagnostics

All diagnostics are in **Settings › About and diagnostics**.

### Camera capabilities

**Camera capabilities** lists every parameter each camera reports and what OpenCineCam does with it, plus what the app asked for next to what the camera reported.

- **Device profile** shows whether a profile for your phone and camera is **Active**, **Active, not verified on this build**, **Rejected**, or absent, and which quirks it applies.
- **Latest Main10 file check** shows the result of the automatic check on your last HLG or LOG take: **File signalling** (Pass or Fail) and **Effective precision**. Effective precision often reads "Not measurable: needs a controlled capture".

**Export capability report** lets you choose what leaves the app:

- **Redacted** (the default) replaces the build fingerprint, model and manufacturer with `[redacted]`.
- **Full** includes them. You must tick a consent box first.

Then choose **Save as file**, **Share** or **Copy as text**. Nothing is sent until you pick one.

### Storage benchmark

**Storage benchmark** checks that a destination keeps up with your current recording settings. Tap **Choose a destination and start**. It writes test data for a few seconds, measures several windows, then deletes the test file. The result is **Qualified** when every window sustains the required rate, and shows the margin.

## Privacy

OpenCineCam has no accounts, ads, analytics or tracking. Network access is used only for the WebDAV transfers you turn on, to the server you choose. See the [privacy policy](privacy/index.md).
