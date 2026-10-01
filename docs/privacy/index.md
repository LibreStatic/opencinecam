# OpenCineCam Privacy Policy

Last updated: 2026-09-30. Applies to the Android app OpenCineCam (package `com.librestatic.opencinecam`), version 0.1.0-beta and later.

## Summary

OpenCineCam is a local-first cinema camera app. **We do not collect, receive, store or sell your personal data.** Your photos, videos, audio, timecode and production metadata stay on your device. The app has no account, no ads, no analytics and no crash-reporting service.

## Who is responsible

The app is developed and published by FacuM and the OpenCineCam contributors (project: https://github.com/LibreStatic/opencinecam). Contact: support@librestatic.com.

## Information OpenCineCam accesses on your device

- **Camera.** Used to show the viewfinder and to capture photos and videos. Camera frames are processed on the device for preview, monitoring tools (histogram, waveform, vectorscope, zebra, focus peaking, false color) and encoding. They are not transmitted anywhere by the app.
- **Microphone.** Used only when you record with audio, and for level meters and optional headphone listening. Recording audio is optional; video records without it.
- **Location (optional, off by default).** If you turn on capture geolocation and grant Android location permission, OpenCineCam reads the device location while the app is open in the foreground and stores the coordinates and their reported accuracy in the metadata of new captures on your device. That metadata is included if you choose to share or upload the capture's metadata. Location permission alone does not enable this. Turning the option off stops it, and earlier captures are not changed.
- **Device and camera capabilities.** The app reads which cameras, codecs and features your phone reports, to show only what the hardware supports. You can export a capability report to a place you choose. The export warns about identifying details such as the device model, and the redacted version is the default. Nothing is sent unless you share the file yourself.
- **Notifications.** A visible notification is shown while a recording is running, with a stop action. It contains no personal information.
- **Your media and settings.** Captures are saved in a location you choose (Android MediaStore or a folder you pick with the Storage Access Framework). Settings, presets, imported LUT files and the production slate you type are stored on the device. The app requests no broad storage permission.
- **Credentials you enter.** If you configure your own WebDAV server, its credentials are stored encrypted on your device using the Android Keystore and are used only to connect to that server. They are kept out of presets and diagnostics.

## When OpenCineCam uses the internet

The only network feature is **optional WebDAV transfer**, which is off by default:

- Nothing is uploaded unless you turn the feature on, set your own HTTPS destination and start or register a transfer. Presets never enable it, and there is no background or automatic upload of existing media.
- Files go directly from your device to the WebDAV server you specified, over HTTPS. That server is operated by you or a third party of your choice. We have no access to it, and its own privacy policy applies. An advanced option lets you accept an unverified certificate for a server on a private local network; it is off by default and the app explains that it removes server authentication.
- Transfers use Wi-Fi unless you allow cellular data.
- The hosting of the destination server can see your IP address and the uploaded files, as with any upload.

The app contains no other network requests, no advertising or analytics libraries and no third-party SDKs.

## What we do not do

- We do not collect or transmit your footage, audio, metadata, location or device identifiers.
- We do not use advertising, analytics or crash-reporting SDKs, and do not sell or share data with third parties.
- We do not back up app data to the cloud: Android backup and device-transfer of app data are disabled.

## Third parties

The app is installed from Google Play, which has its own privacy policy and processes your purchase; OpenCineCam never receives your payment details. Google may collect anonymous diagnostics (for example Android vitals) according to your device settings; we do not control or receive that data as app data.

## Data retention and deletion

All data stays on your device until you delete it. You can delete captures, proxies, presets, credentials and imported LUTs from within the app or your file manager, or remove app data by uninstalling OpenCineCam. Files you shared or uploaded elsewhere are outside our control and must be deleted there. Because we do not hold your data, there is nothing for us to delete on a server.

## Children

OpenCineCam is not directed to children under 13 and we do not knowingly collect information from anyone, including children.

## Your rights

Since data is processed only on your device, you control it directly. If you are in the EEA, UK or another region with data protection rights (access, rectification, erasure, portability, objection), you can exercise them in the app or by uninstalling. You may contact us at support@librestatic.com with any question.

## Security

Camera, microphone and location are accessed only through Android permissions you can revoke at any time in system settings. WebDAV credentials are kept in the Android Keystore, transfers use HTTPS, and unencrypted network traffic is not used. No system is perfectly secure, so keep your device updated and protected with a screen lock.

## Changes

If this policy changes, we will update the date above and publish the new version at the same address. Material changes will be mentioned in the release notes.

## Contact

support@librestatic.com, or open an issue at https://github.com/LibreStatic/opencinecam/issues.
