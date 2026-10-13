# Credits

Third-party material bundled with Shardlight Client, other than the libraries listed in Settings > About.

## Fonts

| Font | Files | Licence | Source |
| --- | --- | --- | --- |
| Inter 4.001 by The Inter Project Authors | `Inter-Regular.ttf`, `Inter-SemiBold.ttf` | SIL Open Font License 1.1 (`Inter-OFL.txt`) | https://github.com/rsms/inter |
| Inter Tabular (Inter with tabular figures switched on) | `InterTabular-Regular.ttf`, `InterTabular-SemiBold.ttf` | SIL Open Font License 1.1 (`Inter-OFL.txt`) | made from Inter, see below |
| JetBrains Mono by The JetBrains Mono Project Authors | `JetBrainsMono-Regular.ttf`, `JetBrainsMono-Bold.ttf` | SIL Open Font License 1.1 (`JetBrainsMono-OFL.txt`) | https://github.com/JetBrains/JetBrainsMono |

The files are in `launcher-app/src/main/resources/app/fonts/`, next to their licence texts, and ship
inside the launcher jar.

JavaFX can't choose weights from a variable font, so the Inter files are static instances made from
Inter's variable font (`Inter[opsz,wght].ttf` from the Google Fonts repository) at optical size 14 and
weights 400 and 600, with `tools/fonts/make_fonts.py` (needs Python and fontTools). The 600 instance is
named as the bold style because JavaFX only asks for regular or bold. Inter Tabular is the same instances
with the `tnum` feature applied to the character map, so digits are always equal width. These are
Modified Versions under the OFL; neither font declares a Reserved Font Name. JetBrains Mono is bundled
unchanged.

To rebuild them:

```
python3 tools/fonts/make_fonts.py "Inter[opsz,wght].ttf" <folder with JetBrainsMono-*.ttf> launcher-app/src/main/resources/app/fonts
```

## Artwork

All pictures below are original to Shardlight Client. They are drawn by `tools/art/make_art.py` from simple
shapes (flat triangular facets and a faceted crystal, the same motif as the logo), with fixed random seeds,
so running the script again gives the same files. No picture, shape or colour scheme was taken from another
launcher or from a stock library. They are covered by the project's licence (all rights reserved), not by a
free licence. Pictures with text use Inter SemiBold (see Fonts). Vector copies of the pictures without text
are in `launcher-app/art-src/`.

| Asset | Files | Used for |
| --- | --- | --- |
| Home backdrop | `app/art/hero-home.png`, `hero-home@2x.png` (1280 x 320 and 2560 x 640, transparent so it works on dark and light) | behind the top of Home |
| Default project art, 9 templates | `app/art/covers/<template>.png` and `@2x` (480 x 270 and 960 x 540); templates `vanilla`, `shardlight-default`, `ultra-performance`, `balanced`, `creative-building`, `pvp`, `exploration`, `modded-survival`, `custom` | the cover of a project that has no picture of its own: Home hero, Library tiles, project page |
| Empty-state illustrations, 11 | `app/art/empty/<name>.png` and `@2x` (160 x 120 and 320 x 240); `library`, `search`, `content`, `downloads`, `servers`, `world`, `image`, `archive`, `trash`, `user`, `shard` | the empty, no-results and sign-in-needed states |
| Splash screen | `app/art/splash.png`, `splash@2x.png` (480 x 280 and 960 x 560) | the window shown while the launcher starts |
| App icon | `packaging/icons/shardlight.png`, `.ico`, `.icns`, `app/icons/shardlight-*.png` (`tools/icons/make_icons.py`) | window, taskbar, installers |
| Windows installer pictures | `packaging/windows/banner.bmp` (493 x 58), `dialog.bmp` (493 x 312) | the MSI's header and welcome page, put in by `tools/release/set_msi_images.ps1` |
| macOS disk image background | `packaging/macos/Shardlight Client-background.png` (660 x 400) | the window that opens when the `.dmg` is mounted |
| Theme logos | `app/themes/<theme>.png`, `app/modules/*.png` | from the Shardlight mod packs, by the same author |

To redraw everything: `python3 tools/art/make_art.py` (needs Python 3, Pillow and NumPy).
