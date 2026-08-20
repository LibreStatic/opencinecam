# Primary References

Accessed 2026-08-20. ADRs use these IDs. Search snippets are never evidence.

| ID | Primary authority and source | Applies to |
|---|---|---|
| REF-API37 | [Android Developers — Set up the Android 17 SDK](https://developer.android.com/about/versions/17/setup-sdk) | SDK policy and guards |
| REF-AGP93 | [Android Developers — AGP 9.3.1 release notes](https://developer.android.com/build/releases/agp-9-3-0-release-notes) | AGP 9.3.1, Gradle 9.5, JDK 17 |
| REF-KOTLIN | [Android Developers — Built-in Kotlin](https://developer.android.com/build/migrate-to-built-in-kotlin) | Kotlin 2.2.10 build policy |
| REF-COMPOSE | [Android Developers — Compose BOM mapping](https://developer.android.com/develop/ui/compose/bom/bom-mapping) | UI dependency alignment |
| REF-CAMERA2 | [Android Developers — Camera2](https://developer.android.com/reference/android/hardware/camera2/package-summary) and AOSP camera contracts | Camera ownership, requests, results |
| REF-DYNAMIC | [DynamicRangeProfiles](https://developer.android.com/reference/android/hardware/camera2/params/DynamicRangeProfiles), ColorSpaceProfiles, SessionConfiguration, OutputConfiguration | HLG, color, physical routing |
| REF-CODEC | [MediaCodec](https://developer.android.com/reference/android/media/MediaCodec), MediaCodecInfo, MediaFormat | encoder selection, Main10, APV |
| REF-MUXER | [MediaMuxer](https://developer.android.com/reference/android/media/MediaMuxer) and MediaExtractor | MP4, metadata, APV, file validation |
| REF-AUDIO | [AudioRecord](https://developer.android.com/reference/android/media/AudioRecord), AudioTimestamp, AudioManager, AudioSource, audiofx | audio source/effects/clocks |
| REF-STORAGE | [Android shared media](https://developer.android.com/training/data-storage/shared/media) and SAF | scoped storage and descriptors |
| REF-FGS | [Android foreground-service types](https://developer.android.com/develop/background-work/services/fgs/service-types) and [while-in-use restrictions](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start) | background recording |
| REF-THERMAL | [PowerManager thermal APIs](https://developer.android.com/reference/android/os/PowerManager) | performance policy |
| REF-RAW | [ImageReader](https://developer.android.com/reference/android/media/ImageReader), ImageFormat, DngCreator | RAW handling |
| REF-AHB | [Android NDK AHardwareBuffer](https://developer.android.com/ndk/reference/group/a-hardware-buffer) | native buffer ownership |
| REF-GL | [Khronos OpenGL ES registry](https://registry.khronos.org/OpenGL/index_es.php) and EGL | GPU backend |
| REF-HLG | [ITU-R BT.2100](https://www.itu.int/rec/R-REC-BT.2100) and ARIB STD-B67 | HLG signaling |
| REF-ISOBMFF | [ISO/IEC 14496-12](https://www.iso.org/standard/83102.html) | MP4 structure/timing |
| REF-CBOR | [IETF RFC 8949](https://www.rfc-editor.org/rfc/rfc8949) | canonical container metadata |
| REF-JSON | [JSON Schema 2020-12](https://json-schema.org/draft/2020-12) | external schemas |
| REF-FDROID | [F-Droid inclusion](https://f-droid.org/docs/Inclusion_Policy/) and reproducibility documentation | FOSS distribution |
| REF-PLAY | [Google Play target API requirements](https://developer.android.com/google/play/requirements/target-sdk), FGS, Data Safety, signing policies | Play release |
| REF-SPDX | [SPDX specification](https://spdx.github.io/spdx-spec/) | SBOM/license identity |

Third-party camera projects are implementation references only. GPL source must not be copied into this Apache-2.0 repository.
