# OpenCineCam promo video

A 66-second feature video in two cuts: 16:9 at 1920×1080 and 9:16 at 1080×1920, both at 60 fps. Everything in it is generated from code in this folder:

- **Pictures:** every frame is drawn on a canvas from code, with no stock footage and no screenshots.
- **Soundtrack:** synthesised from scratch, with no samples.

## Build

```bash
cd marketing/video
./make.sh               # both cuts: out/opencinecam-landscape.mp4, out/opencinecam-portrait.mp4
./make.sh portrait      # one cut
```

Requirements:
- Node 18 or newer.
- ffmpeg.
- Playwright with a Chromium build. `render.mjs` looks for `playwright` on the Node path or in `/opt/node-tools/node_modules`, and for Chromium in `PLAYWRIGHT_BROWSERS_PATH`. Set `CHROMIUM` to use another binary.

### Single steps

```bash
node music.mjs                                    # out/music.wav, -14 LUFS / -1 dBTP
node render.mjs --format landscape                # out/opencinecam-landscape-video.mp4 (silent)
node render.mjs --format portrait --stills 7,23   # PNG stills in out/stills/
```

### Preview

Serve the folder, for example with `npx serve .`, then open:

- `index.html?dev=1` for the 16:9 cut, with play/pause and a scrubber.
- `index.html?dev=1&format=portrait` for the 9:16 cut.

## How it is built

- **Deterministic frames:** each frame is a pure function of time. `window.__seek(t)` draws frame `t`, so the renderer can capture frames in parallel and any frame can be re-rendered on its own.
- **`src/scenes.js`:** the storyboard, with one function per scene.
  1. Logo
  2. Manual control
  3. Capture modes
  4. Monitoring
  5. Log and LUTs
  6. Audio
  7. Timecode and slate
  8. Media
  9. Foldables
  10. Keyboard
  11. Privacy
  12. End card

  Every scene cut falls on an even second, which is a bar of the 120 BPM soundtrack.
- **`src/backdrop.js`:** a port of the onboarding's `OnboardingBackdrop.kt`.
  - The same Material shapes drift with parallax, wobble and spin.
  - Each scene cut gives them a spring "beat".
  - Their opacity is lower than in the app, and they dim further behind text and devices.
- **`src/shapes.js`:** the M3 Expressive shapes, rebuilt as polar outlines so any two of them can morph.
- **`src/motion.js`:** springs using M3 Expressive dampings and stiffnesses.
- **`src/ui.js`:** the app's surfaces in the Cine theme colours from `ui/theme/OpenCineCamTheme.kt`: the capture screen, exposure strip, scope cards and chips.
- **`src/landscape.js`:** a procedural landscape that stands in for the camera feed.
  - Zebra (92 %, yellow), peaking (cyan), false colour (classic palette), Log, waveform, vectorscope and histogram are all computed from its pixels.
  - Those settings are the defaults in `MonitoringOptions`.
- **`music.mjs`:** A minor (Am9 · Fmaj7 · C/E · Em7) at 120 BPM.
  - Low end: a four-on-the-floor kick, plus an off-beat sub bass that ducks under the kick.
  - Top: a plucked arp, an FM bell line, hats and a soft pad.
  - Arrangement: a quiet drop for the privacy scene, then a final hit on the logo.

Fonts are Google Sans Flex and Google Sans Code, under the SIL Open Font License; see `fonts/`. Rendered output goes to `out/`, which git ignores.
