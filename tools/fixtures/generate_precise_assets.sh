#!/usr/bin/env bash
# SPDX-License-Identifier: Apache-2.0
#
# Regenerates the precise playback fixtures used by the instrumented tests:
#
#   app/src/androidTest/assets/e16-precise-sdr.mp4         AVC Constrained Baseline 192x128
#   app/src/androidTest/assets/e16-precise-gop.mp4         AVC Main 192x128, 12 frames, B frames, two IDRs
#   app/src/androidTest/assets/e16-precise-anamorphic.mp4  AVC Constrained Baseline, coded 192x128,
#                                                          SPS-cropped to 192x124, SAR 2:1 (display 384x124)
#   app/src/androidTest/assets/e16-precise-hlg10.mp4       HEVC Main10 lossless 256x128, BT.2020 HLG
#   app/src/androidTest/assets/e16-precise-pq10.mp4        HEVC Main10 lossless 256x128, BT.2020 PQ
#   app/src/androidTest/assets/e1-video.mp4                AVC Constrained Baseline 192x144, 12 IDR frames
#
# Why at least 96 px in both dimensions: Qualcomm c2.qti decoders (for example on the Galaxy
# S24 Ultra) refuse to configure for streams below 96x96. The first generation of these fixtures
# was 96x64 / 256x64, which those devices rejected, so every asset is now at least 128 px high.
#
# Content (all limited range; values are code values written directly with geq, no RGB conversion):
#   sdr, anamorphic  4 frames at PTS 0, 0.4, 1.1, 1.7 s (last frame lasts 0.1 s).
#                    Left half: BT.601 red (81,90,240), green (145,54,34), blue (41,240,110),
#                    yellow (210,16,146) as Y,Cb,Cr; right half: white (235,128,128).
#                    Tags: primaries SMPTE170M (6), transfer BT.709 (1), matrix SMPTE170M (6).
#   gop              12 frames at PTS 0, 0.09, 0.23, 0.41, 0.65, 0.91, 1.2, 1.54, 1.92, 2.35, 2.81, 3.3 s.
#                    Left half: neutral grey Y = 32 + 18 * frame (Cb = Cr = 128); right half white.
#                    keyint 6, two non-adaptive B frames, no pyramid, closed GOP: I B B P B P x 2.
#   hlg10, pq10      4 frames at the sdr PTS. Sixteen 16 px vertical luma bands with the 10-bit codes
#                    64 65 66 67 128 129 130 131 256 257 258 259 512 513 768 924, each plus 4 * frame;
#                    Cb = Cr = 512. Lossless, so every decoded sample equals the code above.
#                    Tags: primaries BT.2020 (9), transfer HLG (18) or PQ (16), matrix BT.2020nc (9).
#   e1-video         12 IDR frames on a 90 kHz timescale at the irregular PTS ticks 0 14405 14740
#                    14994 15263 15455 15659 15899 16094 16357 16542 16757 (last frame 215 ticks,
#                    duration 16972); left half red, right half blue; BT.709 tags in the VUI and
#                    the colr box. The proxy tests feed it to the platform AVC encoder, which on
#                    Qualcomm c2.qti refuses anything under 128x128, hence 192x144 (was 128x96).
#
# Run from anywhere (needs ffmpeg built with libx264 and a 10-bit capable libx265):
#
#   tools/fixtures/generate_precise_assets.sh [output-dir]
#
# The default output directory is app/src/androidTest/assets. Scratch files are not created.
#
# The tests pin the exact bytes of every asset (SHA-256 and byte length). Encoder versions change
# the bitstream, so whenever this script is rerun, update the pins in:
#   app/src/androidTest/java/com/librestatic/opencinecam/storage/PreciseVideoFramesDeviceTest.kt (sdr)
#   app/src/androidTest/java/com/librestatic/opencinecam/storage/PreciseGopFixture.kt (gop)
#   app/src/androidTest/java/com/librestatic/opencinecam/storage/PreciseAnamorphicFixture.kt (anamorphic)
#   app/src/androidTest/java/com/librestatic/opencinecam/storage/PreciseHdrFramesDeviceTest.kt (hlg10, pq10)
#   app/src/androidTest/java/com/librestatic/opencinecam/storage/ProxyPcmProbeDeviceTest.kt and the
#   byte length checks in ProxyCatalogUiTest, ProxyCatalogActionsUiTest, ProxyDeletionUiTest (e1-video)
# The committed assets were produced with ffmpeg n9.0.2 (libavformat 63.1), x264 core 165 r3222
# b35605a and x265 4.3 (build 217); two consecutive runs with those versions are byte-identical.
# The decoded pictures are encoder independent (flat regions at CRF 1 for AVC, lossless HEVC), so
# a different encoder version changes the pins but not what the tests decode.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
out_dir="${1:-$repo_root/app/src/androidTest/assets}"
mkdir -p "$out_dir"

command -v ffmpeg >/dev/null || { echo "ffmpeg not found" >&2; exit 1; }
encoders="$(ffmpeg -hide_banner -encoders 2>/dev/null)"
grep -q ' libx264 ' <<<"$encoders" || { echo "ffmpeg lacks libx264" >&2; exit 1; }
grep -q ' libx265 ' <<<"$encoders" || { echo "ffmpeg lacks libx265" >&2; exit 1; }

# Nested if(eq(N,i),v_i, ...) over a list of values; the last value is the fallback.
by_frame() {
    local values=("$@") expr="${values[-1]}" i
    for ((i = ${#values[@]} - 2; i >= 0; i--)); do expr="if(eq(N\\,$i)\\,${values[i]}\\,$expr)"; done
    printf '%s' "$expr"
}

# Common output flags: microsecond media timescale, no frame duplication or dropping, no
# container or stream metadata beyond what the codec signals, bit-exact muxing.
common_out=(-fps_mode passthrough -enc_time_base 1/1000000 -video_track_timescale 1000000
    -map_metadata -1 -map_chapters -1 -fflags +bitexact -flags:v +bitexact -movflags -faststart
    -metadata:s:v:0 encoder= -threads 1 -an -sn -dn)

# Frame properties: lavfi sources tag SAR 1:1, so clear it (SAR 0/1 = not signalled, as in the
# original fixtures; the anamorphic asset sets 2:1 later). Colour is signalled only in the
# bitstream VUI (x264/x265 parameters), exactly like the original fixtures, so the frames carry
# unspecified colour and the MP4 gets no colr box. Range stays limited (tv).
frame_params="setsar=0/1,setparams=range=tv:color_primaries=unknown:color_trc=unknown:colorspace=unknown"
sdr_vui="colorprim=smpte170m:transfer=bt709:colormatrix=smpte170m:range=tv"

# Sets the mdhd media duration (version 0 box, timescale 1000000) to the presentation end.
# libavformat 62 wrote max(PTS + duration) there; libavformat 63 writes the sum of the stts decode
# durations, which is shorter when B frames reorder the last pictures. The tests read
# KEY_DURATION (3.4 s for the GOP asset), so restore the original value. The edit list already
# carries the same presentation duration.
pin_mdhd_duration() { # $1 file, $2 duration in microseconds
    local file=$1 want=$2 offsets offset version timescale
    offsets="$(grep -obUa mdhd "$file" | cut -d: -f1)"
    [[ $(wc -l <<<"$offsets") -eq 1 ]] || { echo "expected one mdhd in $file" >&2; exit 1; }
    offset=$((offsets + 4))
    version=$(od -An -tu1 -j "$offset" -N1 "$file" | tr -d ' ')
    timescale=$(od -An -tu1 -j $((offset + 12)) -N4 "$file" | awk '{print $1*16777216+$2*65536+$3*256+$4}')
    [[ $version == 0 && $timescale == 1000000 ]] || { echo "unexpected mdhd in $file" >&2; exit 1; }
    printf "$(printf '\\x%02x\\x%02x\\x%02x\\x%02x' $((want >> 24 & 255)) $((want >> 16 & 255)) $((want >> 8 & 255)) $((want & 255)))" \
        | dd of="$file" bs=1 seek=$((offset + 16)) conv=notrunc status=none
}

four_pts="$(by_frame 0 400000 1100000 1700000)"
gop_pts="$(by_frame 0 90000 230000 410000 650000 910000 1200000 1540000 1920000 2350000 2810000 3300000)"

# Left half (x < 96 luma, x < 48 chroma) carries the frame colour, right half is white.
sdr_filter() {
    local w=$1 h=$2
    local y u v
    y="$(by_frame 81 145 41 210)"; u="$(by_frame 90 54 240 16)"; v="$(by_frame 240 34 110 146)"
    printf 'color=c=black:s=%sx%s:r=10:d=0.4,format=yuv420p,%s,settb=1/1000000,setpts=%s,' "$w" "$h" "$frame_params" "$four_pts"
    printf "geq=lum='if(lt(X/SW\\,96)\\,%s\\,235)':cb='if(lt(X/SW\\,96)\\,%s\\,128)':cr='if(lt(X/SW\\,96)\\,%s\\,128)'" "$y" "$u" "$v"
}

encode_baseline() { # $1 output, $2 height, $3 extra filter
    ffmpeg -hide_banner -loglevel error -y -f lavfi -i "$(sdr_filter 192 "$2")$3" -frames:v 4 \
        -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level:v 1.0 -preset veryslow -crf 1 \
        -x264-params "keyint=100:min-keyint=51:scenecut=0:bframes=0:ref=4:threads=1:sliced-threads=0:$sdr_vui" \
        "${common_out[@]}" "$1"
}

# SDR: 192x128, no crop, square pixels.
encode_baseline "$out_dir/e16-precise-sdr.mp4" 128 ""

# Anamorphic: x264 pads 192x124 to the 192x128 macroblock raster and signals a 4-row bottom crop
# (frame_crop_bottom_offset 2) plus SAR 2:1, keeping the original 192:62 display aspect ratio.
encode_baseline "$out_dir/e16-precise-anamorphic.mp4" 124 ",setsar=2/1"

# GOP: 12 grey ramp frames, Main profile with B frames and two closed GOPs.
gop_y="$(by_frame 32 50 68 86 104 122 140 158 176 194 212 230)"
ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i "color=c=black:s=192x128:r=10:d=1.2,format=yuv420p,$frame_params,settb=1/1000000,setpts=$gop_pts,geq=lum='if(lt(X\\,96)\\,$gop_y\\,235)':cb=128:cr=128" \
    -frames:v 12 -c:v libx264 -pix_fmt yuv420p -profile:v main -level:v 1.0 -preset medium -crf 1 \
    -x264-params "keyint=6:min-keyint=4:scenecut=0:bframes=2:b-adapt=0:b-pyramid=none:open-gop=0:ref=3:threads=1:sliced-threads=0:$sdr_vui" \
    "${common_out[@]}" "$out_dir/e16-precise-gop.mp4"
pin_mdhd_duration "$out_dir/e16-precise-gop.mp4" 3400000

# HDR: lossless Main10, 16 bands of 16 px across 256 px, codes + 4 * frame.
band_codes=(64 65 66 67 128 129 130 131 256 257 258 259 512 513 768 924)
band_expr="${band_codes[15]}"
for ((i = 14; i >= 0; i--)); do band_expr="if(lt(X\\,$(((i + 1) * 16)))\\,${band_codes[i]}\\,$band_expr)"; done
for transfer in hlg pq; do
    if [[ $transfer == hlg ]]; then trc=arib-std-b67; else trc=smpte2084; fi
    ffmpeg -hide_banner -loglevel error -y -f lavfi \
        -i "color=c=black:s=256x128:r=10:d=0.4,format=yuv420p10le,$frame_params,settb=1/1000000,setpts=$four_pts,geq=lum='$band_expr+4*N':cb=512:cr=512" \
        -frames:v 4 -c:v libx265 -pix_fmt yuv420p10le -profile:v main10 -preset ultrafast -tag:v hev1 \
        -x265-params "lossless=1:keyint=4:min-keyint=4:bframes=0:scenecut=0:open-gop=1:ref=1:frame-threads=1:pools=1:log-level=1:colorprim=bt2020:transfer=$trc:colormatrix=bt2020nc:range=limited" \
        "${common_out[@]}" "$out_dir/e16-precise-${transfer}10.mp4"
done

# e1-video: every frame an IDR; setts rewrites the encoder's timestamps to the original ticks.
e1_pts="$(by_frame 0 14405 14740 14994 15263 15455 15659 15899 16094 16357 16542 16757 | tr -d '\\')"
e1_dur="$(by_frame 14405 335 254 269 192 204 240 195 263 185 215 215 | tr -d '\\')"
ffmpeg -hide_banner -loglevel error -y -f lavfi \
    -i "color=red:s=96x144:r=30,format=yuv420p[l];color=blue:s=96x144:r=30,format=yuv420p[r];[l][r]hstack" \
    -frames:v 12 -c:v libx264 -pix_fmt yuv420p -profile:v baseline -level:v 4.1 -bf 0 \
    -x264-params "keyint=1:scenecut=0:threads=1:sliced-threads=0:colorprim=bt709:transfer=bt709:colormatrix=bt709:range=tv" \
    -color_range tv -colorspace bt709 -color_trc bt709 -color_primaries bt709 \
    -enc_time_base 1/90000 -bsf:v "setts=ts='$e1_pts':duration='$e1_dur'" -video_track_timescale 90000 \
    -map_metadata -1 -fflags +bitexact -flags:v +bitexact -metadata:s:v:0 encoder= -an "$out_dir/e1-video.mp4"

for name in e16-precise-sdr e16-precise-gop e16-precise-anamorphic e16-precise-hlg10 e16-precise-pq10 e1-video; do
    file="$out_dir/$name.mp4"
    printf '%s  %s bytes  %s\n' "$(sha256sum "$file" | cut -d' ' -f1)" "$(stat -c %s "$file")" "$file"
done
