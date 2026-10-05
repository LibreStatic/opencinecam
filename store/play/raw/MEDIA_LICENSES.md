# Sample media used for Play Store screenshots

All files come from Wikimedia Commons (landscapes only, no people). License as reported by the Commons API (extmetadata LicenseShortName) and re-verified on 2026-09-30; only CC0 / Public domain files were used. Photos were downsized to <=2600 px. No video clips are used: the Android emulator has no AVC encoder, so the gallery shows photo takes only. The downloaded files live in `raw/media/` (gitignored); 

How the photos appear in the screenshots: the headless Compose Driver renders (tools/compose-driver.sh, test-only code in app/src/test/.../driver/StoreScreens.kt) draw the photo behind the real production capture chrome, since the camera viewfinder is not rendered headless. The viewfinder overlays (zebra, focus peaking, false color, waveform, histogram, vectorscope) are computed by the app's own monitoring code from a downsampled copy of the photo. Used: 18 (capture and LOG screens), 17 (overlays screen and gallery), 22 (scopes screen and gallery), 24, 20, 21, 16 (gallery thumbnails). No other media, no people.

Candidates 27-31 of the reference list were rejected (people visible or partly visible, or not a landscape).

| Local file | Commons title | License | Author (as listed) | Source URL |
|---|---|---|---|---|
| media/16-scene.jpg | Kunanyi (Mount Wellington) lone eucalypt and fallen tree trunk in the fog (landscape).jpg | CC0 | JustinH | https://commons.wikimedia.org/wiki/File:Kunanyi_(Mount_Wellington)_lone_eucalypt_and_fallen_tree_trunk_in_the_fog_(landscape).jpg |
| media/17-scene.jpg | Mount Pico de Loro, Nasugbu, Philippines.jpg | CC0 | Fabian Irsara | https://commons.wikimedia.org/wiki/File:Mount_Pico_de_Loro,_Nasugbu,_Philippines.jpg |
| media/18-scene.jpg | Sunrise with Mount Fuji - March 2025.jpg | CC0 | Romain Guy | https://commons.wikimedia.org/wiki/File:Sunrise_with_Mount_Fuji_-_March_2025.jpg |
| media/19-scene.jpg | Almsee Filzmoos.jpg | CC0 | Dimitry Anikin | https://commons.wikimedia.org/wiki/File:Almsee_Filzmoos.jpg |
| media/20-scene.jpg | Lake Kreda (230457099).jpeg | CC0 | Rok B | https://commons.wikimedia.org/wiki/File:Lake_Kreda_(230457099).jpeg |
| media/21-scene.jpg | Evening Reflections Over Lake Rotoroa (234830789).jpeg | CC0 | Jackson | https://commons.wikimedia.org/wiki/File:Evening_Reflections_Over_Lake_Rotoroa_(234830789).jpeg |
| media/22-scene.jpg | Lone Ranch Beach Oregon at sunset.jpg | CC0 | Bonnie Moreland | https://commons.wikimedia.org/wiki/File:Lone_Ranch_Beach_Oregon_at_sunset.jpg |
| media/23-scene.jpg | La Fajana beach sunset, La Palma.jpg | CC0 | Gerda Arendt | https://commons.wikimedia.org/wiki/File:La_Fajana_beach_sunset,_La_Palma.jpg |
| media/24-scene.jpg | Rio de Janeiro skyline and Sugarloaf Mountain at sunset, Brazil 3.jpg | CC0 | Wilfredor | https://commons.wikimedia.org/wiki/File:Rio_de_Janeiro_skyline_and_Sugarloaf_Mountain_at_sunset,_Brazil_3.jpg |
| media/25-scene.jpg | Frankfurt am Main city center at night 2019-09-13 08.jpg | CC0 | Leonhard Lenz | https://commons.wikimedia.org/wiki/File:Frankfurt_am_Main_city_center_at_night_2019-09-13_08.jpg |
| media/26-scene.jpg | Chateau Frontenac illuminated at night in Quebec City.jpg | CC0 | Wilfredor | https://commons.wikimedia.org/wiki/File:Chateau_Frontenac_illuminated_at_night_in_Quebec_City.jpg |
