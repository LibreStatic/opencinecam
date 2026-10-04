# OCLog2 LUTs for Lightforge Studio (experimental)

These cubes turn OCLog2 clips into Rec.709 with a look close to the phone's stock camera:
bright mid-tones, a soft highlight shoulder and some extra saturation on muted colours. They
are a creative look, not a normative transform. For a neutral conversion use the
[OCLog2 interchange package](../oclog2-v2/).

| Download | Use it for |
|---|---|
| [OCLog2_HLG_to_Stock709.cube](https://github.com/LibreStatic/opencinecam/raw/main/docs/color/lightforge/OCLog2_HLG_to_Stock709.cube) | Regular takes (HLG-derived 10-bit tier). |
| [OCLog2_HFR-SDR_to_Stock709.cube](https://github.com/LibreStatic/opencinecam/raw/main/docs/color/lightforge/OCLog2_HFR-SDR_to_Stock709.cube) | 120/240 fps takes (HFR ISP-derived tier), and HLG takes recorded with **Middle-grey reference** set to `MATCH_SDR`. |

## Using them

1. In Lightforge, set the video's input profile to **Standard Rec.709**. Lightforge decodes the clip with that profile before the LUT, and the cube undoes exactly that decode. Any other profile gives wrong colours.
2. Import the `.cube` as a custom LUT and leave its intensity at 100%.
3. Adjust exposure and contrast on top as needed.

## How they are built

`tools/generate_lightforge_luts.py` writes both files; `--check` verifies the committed ones.
Each cube runs the following chain:

1. Undoes the Rec.709 decode.
2. Decodes OCLog2 to scene-linear BT.2020.
3. Converts to Rec.709 primaries. Out-of-gamut colours are pulled toward their luma.
4. Applies the tier's grey gain.
5. Applies a Hable filmic curve with a soft roll-off to 1.0.

The cube's output is linear Rec.709, because Lightforge applies the Rec.709 OETF after the LUT.

Each cube is a plain 65³ cube, the largest that Lightforge accepts. Its input is linear, so
there are few lattice points near black. Along the neutral axis, the result is monotonic and
within about 7/255 of the exact transform in the deepest shadows, and within 1/255 from the
mid-tones up. Very saturated dark colours can differ more.

Status: checked against a simulation of Lightforge's LUT sampling, not yet on a real Razr clip
inside Lightforge.
