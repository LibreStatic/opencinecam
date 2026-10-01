# Play Console "App content" and store settings: draft answers

Draft for OpenCineCam (`com.librestatic.opencinecam`), version 0.1.0-beta (`release/play-beta`). Every answer below is derived from the repository (`app/src/main/AndroidManifest.xml`, `gradle/libs.versions.toml`, `app/build.gradle.kts`, `docs/security-and-privacy.md`, ADR-0027 and ADR-0034, README). Items tagged **[CONFIRM]** need an owner decision or a check against the release build before submitting.

## Store listing basics

| Field | Value |
|---|---|
| App name (Play title) | `OpenCineCam: Cinema Camera` (localized per `listings/<locale>/title.txt`). Launcher label is "OpenCineCam". |
| Default language | English (United States), en-US |
| App or game | App |
| Free or paid | Paid: Argentina ARS 3500, rest of the world USD 6.5. Cannot be switched to free later. Testers get it free plus 25 promo codes (set up under Testing / license testing and Promotions in the Console). |
| Category | Photography (alternative: Video Players & Editors) |
| Tags (pick up to 5 that Play offers) | Camera, Video, Photography, Filmmaking, Professional tools (map to the closest available tags in the Console) |
| Release track | Closed or open testing for the beta (see "Beta and testing" below) |
| Contact email | support@librestatic.com |
| Contact website | https://github.com/LibreStatic/opencinecam |
| Contact phone | Optional, leave empty |
| Developer name and address | **[CONFIRM]** required for paid apps and shown publicly (EU trader status under the DSA applies if selling in the EU) |

## Privacy policy

- Required. Host `store/play/privacy-policy.md` publicly (the same text is in `docs/privacy/index.md` for GitHub Pages) and paste the URL here and in the store listing.
- Decided (owner): the repository becomes public and hosts the policy. URL: `https://librestatic.github.io/opencinecam/privacy/` (requires enabling GitHub Pages for the repository with the `docs/` folder as source).
- The URL should also be reachable from inside the app; the About screen currently only links to the project. **[CONFIRM]**

## Ads

Does the app contain ads? **No.** No ad SDK exists in `gradle/libs.versions.toml` or any module (only AndroidX, Media3 Transformer, Kotlin coroutines/serialization). No `AD_ID` permission.

## App access

All functionality is available without an account, login or special credentials. No reviewer credentials are needed.

Notes for the review team (paste into the instructions field):

- Grant camera (and optionally microphone) permission on first launch. The app shows a first-run introduction, then the camera screen.
- Capabilities depend on the reviewer device's Camera2 implementation. Modes or options the device cannot support are shown as unavailable by design (for example RAW, LOG and high frame rates). This is not a defect.
- Optional features that need extra setup are off by default: WebDAV upload (needs a user-provided HTTPS WebDAV server) and geotagging (needs location permission). They are not needed to review the rest of the app.
- Recording with audio starts a visible foreground service notification while the take is running.
- Emulators often lack hardware video encoders; review on a physical device.

## Content rating (IARC questionnaire)

Category to choose: **Utility, Productivity, Communication, or Other** (not Social, not Game). Rationale: a camera/recording tool; it hosts and distributes no content and has no social features.

| Question | Answer | Reasoning |
|---|---|---|
| Violence, blood, gore | No | Not in the app itself |
| Sexual content or nudity | No | Not in the app itself |
| Profanity or crude humor | No | |
| Controlled substances (drugs, alcohol, tobacco) | No | |
| Gambling or simulated gambling | No | |
| Horror / fear themes | No | |
| Discrimination or hate | No | |
| Users can interact or exchange content with each other | No | No accounts, chat, feed or sharing network. Sharing uses the Android share sheet; WebDAV goes only to the user's own server. |
| Shares user's physical location with other users | No | Optional geotag is written to the user's own capture metadata on device; nothing is sent to other users. |
| Allows purchases of digital goods | No | Paid upfront; no in-app purchases (no Play Billing library in the project) |
| Unrestricted internet access / web browsing | No | No browser; network is used only for user-configured HTTPS WebDAV transfers |
| Displays user-generated content from other users | No | Shows only the user's own captures |

Submitted 2026-09-30 with these answers. Result: ClassInd All ages, ESRB Everyone, PEGI 3, USK All ages.

## Target audience and content

- **Decided (owner): 13 and over** — select age groups 13–15, 16–17 and 18+. Do not opt into Designed for Families.
- Justification: a professional/prosumer tool with no child-directed content, characters or themes; the privacy policy states the app is not directed to children under 13; the app can record camera, microphone and optional location, which keeps it outside the Families Policy scope.
- Does the app appeal to children unintentionally? No.

## Data safety form

Summary answer: **no user data is collected or shared with the developer or any third party.** Photos, videos, audio, sensor data, optional location, slate text and settings are processed and stored on the device only. The project has no analytics, crash-reporting, advertising or account SDK (`docs/security-and-privacy.md`; ADR-0027 amended 2026-09-26 forbids third-party SDKs and any network or location use beyond the two named opt-in features). Cloud backup and device-transfer of app data are disabled (`android:allowBackup="false"`).

### Questions

| Console question | Answer |
|---|---|
| Does your app collect or share any of the required user data types? | **No** |
| Is all of the user data collected by your app encrypted in transit? | Not applicable (no data collected). If forced to answer: Yes; WebDAV transfers accept only `https://` destinations (enforced in `app/.../transfers/`). |
| Do you provide a way for users to request that their data be deleted? | Not applicable (no data collected). Users delete captures, proxies, presets and credentials in the app or file manager; uninstalling removes app data. |
| Committed to the Play Families Policy? | No (audience is 13+, no under-13 groups) |
| Independent security review (MASA)? | No |

### Per data type (all "Not collected", "Not shared")

| Category / type | Collected | Shared | Notes |
|---|---|---|---|
| Location (approximate / precise) | No | No | `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` are declared for opt-in geotagging only (off by default, read only while the activity is in the foreground). Coordinates are stored on device in capture metadata and never transmitted by the app on its own. Owner decision: not collected (judgement call 1). |
| Personal info (name, email, address, IDs, etc.) | No | No | No accounts. The free-text production slate (project, scene, etc.) stays on device. |
| Financial info | No | No | Purchase is handled by Google Play |
| Health and fitness | No | No | |
| Messages | No | No | |
| Photos and videos | No | No | Captured and stored locally. Uploading to a user-configured WebDAV server is an explicit user action to a destination the user controls; the developer never receives it. |
| Audio files | No | No | Microphone audio is encoded into the local capture; same WebDAV note as above |
| Files and docs | No | No | Imported `.cube` LUT files and preset JSON stay on device |
| Calendar / Contacts | No | No | No permission requested |
| App activity | No | No | No interaction logging leaves the device |
| Web browsing | No | No | |
| App info and performance (crash logs, diagnostics) | No | No | No crash reporter. Logs are local and deleted by the user. Capability reports are exported only when the user shares them. |
| Device or other IDs | No | No | No advertising ID; no device identifiers collected |
| Biometric data | No | No | Not used |

### Judgement calls **[CONFIRM]**

1. **Location.** Decided (owner, 2026-09-30): declare nothing, since no data leaves the device on its own. Background: geotagging is opt-in and local. However, coordinates written into capture metadata can leave the device if the user shares the metadata package or uploads it to their WebDAV server. Play treats user-initiated transfers to a user-chosen destination as not "sharing", but a conservative alternative is to declare Location (approximate and precise) as collected, not shared, optional, purpose "App functionality", which would require the privacy policy wording already present. Owner decision.
2. **WebDAV.** Files and credentials go only to a server the user configures. The destination host sees the IP address and the uploaded files; no developer-operated server exists. Declare nothing; the privacy policy explains it.
3. **Play services / libraries.** No Firebase, ML Kit, Google Play services or other proprietary SDK is in the dependency list. **[CONFIRM]** by inspecting the merged release manifest and APK/AAB dependency report once (the `release/play-beta` bundle may differ from debug).
4. **Crash data from Android vitals** is collected by Google for opted-in users, not by the app. No declaration needed.

## Sensitive permissions and declarations

| Item | Answer |
|---|---|
| `CAMERA` | Core function (camera app). |
| `RECORD_AUDIO` | Optional audio tracks, WAV/FLAC sidecars and level meters. Video still records without it. |
| `ACCESS_FINE_LOCATION` / `ACCESS_COARSE_LOCATION` | Opt-in geotagging of captures, off by default, requested at runtime, used only while the app is in the foreground. No background location. The Console may ask for a permission declaration: describe it as an optional feature that records the capture location in metadata. `uses-feature` for GPS/network location is `required=false`. |
| `INTERNET`, `ACCESS_NETWORK_STATE` | Opt-in WebDAV transfers of finished takes only; network state is used to restrict uploads to Wi-Fi unless cellular is allowed. |
| `POST_NOTIFICATIONS` | Visible recording notification with a stop action. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_CAMERA` + `FOREGROUND_SERVICE_MICROPHONE` | Foreground-service declaration, see next section. |
| `READ/WRITE_EXTERNAL_STORAGE`, `MANAGE_EXTERNAL_STORAGE`, `READ_MEDIA_*` | Not declared. MediaStore and the Storage Access Framework own destinations. |
| Exact alarms, VPN, accessibility, SMS/call log, background location | Not used. |

### Foreground service declaration (Play Console: App content > Foreground service permissions)

Declared in the manifest: `CaptureService` with `android:foregroundServiceType="camera|microphone"`; the microphone type is added at runtime only when audio recording is enabled (`RecordingForegroundController`).

- **Camera type, task description:** the user starts a video (or long-exposure, time-lapse or slow-motion) recording. The service keeps the capture session and the file writer alive when the screen is turned off, the user switches apps, the activity is recreated by a fold or rotation, or the user glances at another app. Without it Android would cut the camera mid-take, and the file could not be finalized.
- **Microphone type, task description:** the same recording when audio is enabled; it keeps the audio capture (AAC track or WAV/FLAC sidecars) in sync with the video.
- **User-visible:** a persistent notification titled "OpenCineCam recording" (channel "Recording", with a stop action) is shown for the whole take. The service starts only from a user action on the capture screen and stops when the take is saved or cancelled.
- **Why no alternative:** a recording must not be interrupted or deferred, so WorkManager or other deferrable background work does not fit.
- **Video for Play (required):** record a short screen capture that shows (1) granting camera/microphone, (2) tapping record, (3) pulling down the notification shade to show the "OpenCineCam recording" notification, (4) switching to another app and back while the take continues, and (5) stopping the take and the notification disappearing. Upload as an unlisted YouTube link or file. **[CONFIRM]** video recorded and link pasted.

## Other declarations

| Declaration | Answer |
|---|---|
| Government app | No |
| Financial features (banking, loans, crypto, trading, payments) | No |
| Health features / health apps declaration | No |
| News app | No |
| COVID-19 contact tracing / status | No |
| Advertising ID | Not used (declare "No" in the advertising ID form) |
| Content guidelines: user-generated content | Not applicable (no UGC shared between users) |
| Encryption / export compliance | Uses standard HTTPS/TLS and Android Keystore (AES) only for WebDAV transfers and credential storage: standard cryptography for US export purposes. **[CONFIRM]** with counsel if selling in regulated regions. |
| Play App Signing | Enable; upload-key signing is described in the release-build docs (`docs/release/`). |
| Target API level | 37 (`targetSdk`, meets the current Play requirement); `minSdk` 29 (Android 10) |

## Beta and testing

- The full description says the app is an early beta; the title and short description carry no store-status words (Play metadata policy). Use a closed or open testing track while it is a beta. Testers receive the paid app free and 25 promo codes. **[CONFIRM]** which track (Early access has extra requirements such as a feedback channel).
- Feedback channel: GitHub issues at https://github.com/LibreStatic/opencinecam/issues (**[CONFIRM]** issues are enabled).
- Experimental modes (RAW photo/DNG, LOG video, APV, RAW video) are gated and unavailable unless the device qualifies; README and the in-app text say so. The listing describes them as gated experimental modes.

## Price and distribution

- Price: Argentina ARS 3500; all other countries USD 6.5 as the default price (set the Argentina local price explicitly and let Play convert the rest, or override manually). Do not repeat prices in listing text.
- Countries: all supported by Play paid apps. Review EU (DSA trader) and India/Brazil tax settings.
- Device catalog: phones, tablets and foldables (adaptive layouts). `uses-feature` camera and microphone are `required=false`, so the Console may list devices without them; consider restricting the device catalog to devices with a rear camera. The release build is ARM64 (`arm64-v8a`) only, so 32-bit-only and x86 devices are excluded. **[CONFIRM]** this is intended for the Play bundle.
- UI languages shipped: English and Spanish (`values`, `values-es`). The store listing is localized into eight locales (en-US, es-419, es-ES, fr-FR, pt-BR, pt-PT, de-DE, it-IT); in-app text for the French, Portuguese, German and Italian listings falls back to English. **[CONFIRM]** acceptable, or limit the listing languages.
