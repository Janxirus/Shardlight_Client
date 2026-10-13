# Theme previews

The Shardlight page shows the 15 UI themes as tiles. A tile shows a real in-game screenshot of the theme when
one is bundled, and the theme's pack logo when not. **No screenshots are bundled yet**: the mod repositories
have none, and Minecraft can't run where this launcher is built and tested, so every tile still shows its logo.

## Adding them

1. In the game, with the Shardlight UI mod, pick a theme and take one screenshot of a screen that shows it
   well (the Control Center or the inventory). 16:9 at 1920 x 1080 is ideal; any size works.
2. Name each file after its theme id and put them all in one folder:
   `ember`, `blood_moon`, `soul_fire`, `ashen`, `dark_green`, `purple_flame`, `pink_blossom`, `dark_fantasy`,
   `medieval`, `cyberpunk`, `steampunk`, `celestial`, `frostbound`, `voidborn`, `arcane_academy`
   (`.png` or `.jpg`).
3. Run `python3 tools/themes/import_previews.py <folder>`. It crops each to 16:9, scales it to 640 x 360 and
   writes `launcher-app/src/main/resources/app/themes/preview/<id>.png`.
4. Build. A theme with a preview file uses it; the rest keep their logo, so you can add them one at a time.

`ThemePreviewTest` checks that every file in that folder belongs to a known theme and is 640 x 360.
