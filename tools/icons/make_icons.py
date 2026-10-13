#!/usr/bin/env python3
"""
Draws the Shardlight Client app icon (the faceted shard on a dark rounded tile) at every size the app
and the installers need. Needs Pillow and NumPy.

    python3 tools/icons/make_icons.py
"""
import os

import numpy as np
from PIL import Image, ImageDraw, ImageFilter

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
ACCENT = (139, 124, 246)
ACCENT2 = (70, 211, 196)
BG_TOP = (28, 31, 46)
BG_BOTTOM = (11, 13, 19)

LEFT = [(32, 4), (14, 22), (26, 60), (32, 60)]
RIGHT = [(32, 4), (50, 22), (38, 60), (32, 60)]
CORE = [(32, 12), (24, 23), (30, 52), (34, 52), (40, 23)]


def gradient_fill(size, poly, c0, c1, direction):
    """a polygon filled with a linear gradient (direction: (dx, dy) in 0..1 space)"""
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).polygon(poly, fill=255)
    y, x = np.mgrid[0:size, 0:size] / max(1, size - 1)
    t = np.clip(x * direction[0] + y * direction[1], 0, 1)
    if direction[0] < 0:
        t = np.clip((1 - x) * -direction[0] + y * direction[1], 0, 1)
    rgb = np.zeros((size, size, 4), np.uint8)
    for i in range(3):
        rgb[..., i] = (np.array(c0[i]) * (1 - t) + np.array(c1[i]) * t).astype(np.uint8)
    rgb[..., 3] = np.array(mask)
    return Image.fromarray(rgb, "RGBA")


def scale(poly, s, off):
    return [(x * s + off, y * s + off) for (x, y) in poly]


def icon(size):
    big = size * 4
    img = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    # rounded dark tile with a soft vertical gradient
    tile = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    y = np.linspace(0, 1, big)[:, None]
    arr = np.zeros((big, big, 4), np.uint8)
    for i in range(3):
        arr[..., i] = (BG_TOP[i] * (1 - y) + BG_BOTTOM[i] * y).astype(np.uint8)
    arr[..., 3] = 255
    tile = Image.fromarray(arr, "RGBA")
    mask = Image.new("L", (big, big), 0)
    r = int(big * 0.22)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, big - 1, big - 1], radius=r, fill=255)
    img.paste(tile, (0, 0), mask)
    # glow behind the shard
    glow = Image.new("RGBA", (big, big), (0, 0, 0, 0))
    s = big / 64 * 0.82
    off = big * 0.09
    ImageDraw.Draw(glow).polygon(scale(LEFT + RIGHT[1:3], s, off), fill=ACCENT + (150,))
    glow = glow.filter(ImageFilter.GaussianBlur(big * 0.06))
    img = Image.alpha_composite(img, glow)
    light = tuple(min(255, int(c * 1.15)) for c in ACCENT)
    dark = tuple(int(c * 0.55) for c in ACCENT)
    img = Image.alpha_composite(img, gradient_fill(big, scale(LEFT, s, off), light, dark, (0.6, 0.6)))
    img = Image.alpha_composite(img, gradient_fill(big, scale(RIGHT, s, off), ACCENT2, tuple(int(c * 0.5) for c in ACCENT2), (0.2, 0.9)))
    core = gradient_fill(big, scale(CORE, s, off), (255, 255, 255), tuple(int((a + b) / 2) for a, b in zip(ACCENT, ACCENT2)), (0, 1))
    core_arr = np.array(core)
    core_arr[..., 3] = (core_arr[..., 3] * 0.85).astype(np.uint8)
    img = Image.alpha_composite(img, Image.fromarray(core_arr, "RGBA"))
    img = img.resize((size, size), Image.LANCZOS)
    # keep the tile's corners transparent after resampling
    return img


def main():
    res = os.path.join(ROOT, "launcher-app", "src", "main", "resources", "app", "icons")
    pack = os.path.join(ROOT, "packaging", "icons")
    os.makedirs(res, exist_ok=True)
    os.makedirs(pack, exist_ok=True)
    sizes = [16, 24, 32, 48, 64, 128, 256]
    images = {s: icon(s) for s in sizes + [512]}
    for s in [16, 32, 48, 64, 128, 256]:
        images[s].save(os.path.join(res, f"shardlight-{s}.png"), optimize=True)
    images[512].save(os.path.join(pack, "shardlight.png"), optimize=True)
    images[256].save(os.path.join(pack, "shardlight.ico"), sizes=[(s, s) for s in sizes])
    print("icons written")


if __name__ == "__main__":
    main()
