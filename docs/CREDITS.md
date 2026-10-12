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
