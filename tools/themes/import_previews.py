#!/usr/bin/env python3
"""
Turns in-game screenshots of the 15 Shardlight UI themes into the preview pictures the Shardlight page shows.

    python3 tools/themes/import_previews.py <folder of screenshots>

Name each screenshot after its theme id: ember.png, blood_moon.png, soul_fire.png, ashen.png, dark_green.png,
purple_flame.png, pink_blossom.png, dark_fantasy.png, medieval.png, cyberpunk.png, steampunk.png, celestial.png,
frostbound.png, voidborn.png, arcane_academy.png (.png, .jpg and .jpeg are read). Each is cropped to 16:9 from the
middle, scaled to 640 x 360 and written to launcher-app/src/main/resources/app/themes/preview/<id>.png. Themes
without a screenshot keep their pack logo. See docs/THEME_PREVIEWS.md. Needs Pillow.
"""
import os
import sys

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
OUT = os.path.join(ROOT, "launcher-app", "src", "main", "resources", "app", "themes", "preview")
IDS = ["ember", "blood_moon", "soul_fire", "ashen", "dark_green", "purple_flame", "pink_blossom", "dark_fantasy",
       "medieval", "cyberpunk", "steampunk", "celestial", "frostbound", "voidborn", "arcane_academy"]


def main(folder):
    os.makedirs(OUT, exist_ok=True)
    missing = []
    for theme in IDS:
        src = next((os.path.join(folder, theme + e) for e in (".png", ".jpg", ".jpeg") if os.path.isfile(os.path.join(folder, theme + e))), None)
        if src is None:
            missing.append(theme)
            continue
        im = Image.open(src).convert("RGB")
        w, h = im.size
        if w * 9 > h * 16:  # wider than 16:9: trim the sides
            nw = h * 16 // 9
            im = im.crop(((w - nw) // 2, 0, (w - nw) // 2 + nw, h))
        else:
            nh = w * 9 // 16
            im = im.crop((0, (h - nh) // 2, w, (h - nh) // 2 + nh))
        im.resize((640, 360), Image.LANCZOS).save(os.path.join(OUT, theme + ".png"), optimize=True)
        print("wrote", theme)
    if missing:
        print("no screenshot for:", ", ".join(missing))


if __name__ == "__main__":
    if len(sys.argv) != 2 or not os.path.isdir(sys.argv[1]):
        sys.exit(__doc__)
    main(sys.argv[1])
