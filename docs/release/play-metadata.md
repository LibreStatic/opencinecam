# Google Play metadata and tracks

Package: `com.librestatic.opencinecam`

Releases are published through the Play Developer API with
`tools/play-publish.py` (see "Publishing to Google Play" in `AGENTS.md`).

- **Internal:** not used.
- **Closed testing - Alpha (`alpha`):** staged testers; used for 0.2.0-beta
  through 0.5.0-beta (versionCode 119). Alpha versions keep the `-beta` suffix.
  Review policy declarations before promotion.
- **Production (`production`):** versions carry no suffix; the track must target
  countries first, which only the Console can set. 1.0.0 (versionCode 120) was
  the first production release, sent for review on 2026-10-28 with a full
  rollout to 173 countries. Publish only the signed artifact whose source and
  release notes match the reviewed commit.

The listing must state that hardware-dependent HLG10, RAW video, OpenCine Log,
and APV modes are capability-gated and may be unavailable. No analytics,
accounts, ads, or remote crash collection are disclosed or enabled.
