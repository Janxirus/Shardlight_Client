#!/usr/bin/env python3
"""
Draws all of Shardlight Client's original artwork in the shard style: flat triangular facets, a crystal motif
used sparingly, no photographs, no text taken from anywhere else. Needs Pillow and NumPy.

    python3 tools/art/make_art.py

Writes:
  launcher-app/src/main/resources/app/art/hero-home.png (+ @2x)          Home backdrop (transparent, works on dark and light)
  launcher-app/src/main/resources/app/art/covers/<template>.png (+ @2x)  default project art, one per template
  launcher-app/src/main/resources/app/art/empty/<name>.png (+ @2x)       empty-state illustrations
  launcher-app/src/main/resources/app/art/splash.png (+ @2x)             the splash screen
  launcher-app/art-src/*.svg                                             vector copies of the pictures without text
  packaging/windows/banner.bmp, dialog.bmp                               MSI installer images
  packaging/macos/Shardlight Client-background.png                       DMG window background
  packaging/macos/shardlight.icns                                        macOS app icon (from the same shard)

Every picture is generated from a fixed seed, so running the script again gives the same files.
"""
import math
import os
import random
import struct
import io
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
RES = os.path.join(ROOT, "launcher-app", "src", "main", "resources", "app")
ART = os.path.join(RES, "art")
SRC = os.path.join(ROOT, "launcher-app", "art-src")
FONT = os.path.join(RES, "fonts", "Inter-SemiBold.ttf")

ACCENT = (139, 124, 246)
ACCENT2 = (70, 211, 196)
INK = (14, 16, 22)


# ------------------------------------------------------------------------------------------ colour helpers

def hsv(h, s, v):
    h = (h % 360) / 60.0
    i = int(h)
    f = h - i
    p, q, t = v * (1 - s), v * (1 - s * f), v * (1 - s * (1 - f))
    r, g, b = [(v, t, p), (q, v, p), (p, v, t), (p, q, v), (t, p, v), (v, p, q)][i % 6]
    return (round(r * 255), round(g * 255), round(b * 255))


def mix(a, b, t):
    return tuple(round(a[i] * (1 - t) + b[i] * t) for i in range(3))


def shade(c, f):
    """f > 1 lightens toward white, f < 1 darkens toward black"""
    if f >= 1:
        return mix(c, (255, 255, 255), min(1, f - 1))
    return mix(c, (0, 0, 0), 1 - f)


# ------------------------------------------------------------------------------------------ the scene

class Scene:
    """A list of drawing operations in design units; it can be rendered to a PNG at any scale and saved as SVG."""

    def __init__(self, w, h, bg=None):
        self.w, self.h, self.bg, self.ops = w, h, bg, []

    def poly(self, pts, color, alpha=1.0):
        self.ops.append(("poly", [(float(x), float(y)) for x, y in pts], tuple(color), alpha))

    def glow(self, cx, cy, r, color, alpha):
        self.ops.append(("glow", cx, cy, r, tuple(color), alpha))

    def render(self, px_w, px_h, ssaa=2):
        k = px_w * ssaa / self.w
        W, H = int(round(self.w * k)), int(round(self.h * k))
        base = (self.bg + (255,)) if self.bg else (0, 0, 0, 0)
        img = Image.new("RGBA", (W, H), base)
        for op in self.ops:
            if op[0] == "poly":
                _, pts, color, alpha = op
                scaled = [(x * k, y * k) for x, y in pts]
                xs, ys = [p[0] for p in scaled], [p[1] for p in scaled]
                x0, y0 = max(0, int(min(xs)) - 1), max(0, int(min(ys)) - 1)
                x1, y1 = min(W, int(max(xs)) + 2), min(H, int(max(ys)) + 2)
                if x1 <= x0 or y1 <= y0:
                    continue
                layer = Image.new("RGBA", (x1 - x0, y1 - y0), (0, 0, 0, 0))
                ImageDraw.Draw(layer).polygon([(x - x0, y - y0) for x, y in scaled], fill=color + (round(255 * alpha),))
                box = img.crop((x0, y0, x1, y1))
                img.paste(Image.alpha_composite(box, layer), (x0, y0))
            else:
                _, cx, cy, r, color, alpha = op
                R = max(2, int(r * k))
                x0, y0 = int(cx * k) - R, int(cy * k) - R
                yy, xx = np.mgrid[-R:R, -R:R]
                d = np.sqrt(xx * xx + yy * yy) / R
                a = np.clip(1 - d, 0, 1) ** 2 * alpha * 255
                layer = np.zeros((2 * R, 2 * R, 4), np.uint8)
                layer[..., 0], layer[..., 1], layer[..., 2], layer[..., 3] = color[0], color[1], color[2], a.astype(np.uint8)
                gl = Image.fromarray(layer, "RGBA")
                cx0, cy0 = max(0, x0), max(0, y0)
                cx1, cy1 = min(W, x0 + 2 * R), min(H, y0 + 2 * R)
                if cx1 <= cx0 or cy1 <= cy0:
                    continue
                gl = gl.crop((cx0 - x0, cy0 - y0, cx1 - x0, cy1 - y0))
                box = img.crop((cx0, cy0, cx1, cy1))
                img.paste(Image.alpha_composite(box, gl), (cx0, cy0))
        return img.resize((px_w, px_h), Image.LANCZOS)

    def svg(self):
        out = [f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {self.w} {self.h}" width="{self.w}" height="{self.h}">']
        if self.bg:
            out.append(f'<rect width="{self.w}" height="{self.h}" fill="#{self.bg[0]:02x}{self.bg[1]:02x}{self.bg[2]:02x}"/>')
        defs, body, n = [], [], 0
        for op in self.ops:
            if op[0] == "poly":
                _, pts, color, alpha = op
                p = " ".join(f"{x:.1f},{y:.1f}" for x, y in pts)
                a = "" if alpha >= 1 else f' fill-opacity="{alpha:.2f}"'
                body.append(f'<polygon points="{p}" fill="#{color[0]:02x}{color[1]:02x}{color[2]:02x}"{a}/>')
            else:
                _, cx, cy, r, color, alpha = op
                n += 1
                c = f"#{color[0]:02x}{color[1]:02x}{color[2]:02x}"
                defs.append(f'<radialGradient id="g{n}"><stop offset="0" stop-color="{c}" stop-opacity="{alpha:.2f}"/>'
                            f'<stop offset="1" stop-color="{c}" stop-opacity="0"/></radialGradient>')
                body.append(f'<circle cx="{cx:.1f}" cy="{cy:.1f}" r="{r:.1f}" fill="url(#g{n})"/>')
        if defs:
            out.append("<defs>" + "".join(defs) + "</defs>")
        out += body
        out.append("</svg>")
        return "\n".join(out)


def save_pair(scene, path, w, h, svg_name=None):
    """path without extension: writes path.png at w x h and path@2x.png at twice that"""
    os.makedirs(os.path.dirname(path), exist_ok=True)
    scene.render(w, h).save(path + ".png", optimize=True)
    scene.render(w * 2, h * 2).save(path + "@2x.png", optimize=True)
    if svg_name:
        os.makedirs(SRC, exist_ok=True)
        with open(os.path.join(SRC, svg_name + ".svg"), "w") as f:
            f.write(scene.svg())


# ------------------------------------------------------------------------------------------ facet building blocks

def facet_field(sc, x0, y0, x1, y1, cell, color_at, seed, jitter=0.32, var=0.07, alpha_at=None):
    """cover a rectangle with jittered triangles, each flat-coloured from color_at(u, v) with a little lightness noise"""
    rnd = random.Random(seed)
    cols = int(math.ceil((x1 - x0) / cell)) + 2
    rows = int(math.ceil((y1 - y0) / cell)) + 2
    pts = [[(x0 + (c - 0.5) * cell + rnd.uniform(-jitter, jitter) * cell,
             y0 + (r - 0.5) * cell + rnd.uniform(-jitter, jitter) * cell) for c in range(cols)] for r in range(rows)]
    for r in range(rows - 1):
        for c in range(cols - 1):
            a, b, d, e = pts[r][c], pts[r][c + 1], pts[r + 1][c], pts[r + 1][c + 1]
            tris = [(a, b, d), (b, e, d)] if rnd.random() < 0.5 else [(a, b, e), (a, e, d)]
            for t in tris:
                cx = sum(p[0] for p in t) / 3
                cy = sum(p[1] for p in t) / 3
                u = min(1.0, max(0.0, (cx - x0) / (x1 - x0)))
                v = min(1.0, max(0.0, (cy - y0) / (y1 - y0)))
                col = shade(color_at(u, v), 1 + rnd.uniform(-var, var))
                sc.poly(t, col, alpha_at(u, v) if alpha_at else 1.0)


def centroid(pts):
    return sum(p[0] for p in pts) / len(pts), sum(p[1] for p in pts) / len(pts)


def faceted(sc, pts, color, seed=0, spread=0.16, light=(-0.6, -0.8), alpha=1.0):
    """a convex polygon split into triangles from its middle; facets facing the light are brighter"""
    rnd = random.Random(seed)
    cx, cy = centroid(pts)
    n = len(pts)
    for i in range(n):
        a, b = pts[i], pts[(i + 1) % n]
        mx, my = (a[0] + b[0]) / 2 - cx, (a[1] + b[1]) / 2 - cy
        d = math.hypot(mx, my) or 1
        facing = (mx * light[0] + my * light[1]) / d
        sc.poly([(cx, cy), a, b], shade(color, 1 + facing * spread + rnd.uniform(-0.03, 0.03)), alpha)


def rot(pts, ang, cx, cy):
    c, s = math.cos(math.radians(ang)), math.sin(math.radians(ang))
    return [(cx + (x - cx) * c - (y - cy) * s, cy + (x - cx) * s + (y - cy) * c) for x, y in pts]


def crystal(sc, cx, cy, h, w, c1, c2, ang=0, seed=1, glow=True):
    """the shard: a tall spindle of four faces; c1 colours the left, c2 the right"""
    if glow:
        sc.glow(cx, cy, h * 1.25, mix(c1, c2, 0.5), 0.34)
    top, bot = (cx, cy - h), (cx, cy + h)
    ul, ur = (cx - w, cy - h * 0.38), (cx + w, cy - h * 0.38)
    ml, mr = (cx - w * 0.55, cy + h * 0.2), (cx + w * 0.55, cy + h * 0.2)

    def r(p):
        return rot(p, ang, cx, cy)

    faces = [
        (r([top, ul, (cx, cy - h * 0.2)]), shade(c1, 1.18)),
        (r([ul, ml, (cx, cy - h * 0.2)]), shade(c1, 0.95)),
        (r([ul, ml, bot]), shade(c1, 0.62)),
        (r([top, ur, (cx, cy - h * 0.2)]), shade(c2, 1.12)),
        (r([ur, mr, (cx, cy - h * 0.2)]), shade(c2, 0.9)),
        (r([ur, mr, bot]), shade(c2, 0.55)),
        (r([(cx, cy - h * 0.2), ml, bot, mr]), shade(mix(c1, c2, 0.5), 0.82)),
    ]
    for pts, col in faces:
        sc.poly(pts, col)
    # a bright edge down the front
    sc.poly(r([top, (cx - w * 0.16, cy - h * 0.3), (cx, cy + h * 0.55), (cx + w * 0.16, cy - h * 0.3)]), (255, 255, 255), 0.55)


def cube(sc, cx, cy, s, base, seed=3):
    k = 0.866
    top = [(cx, cy - s), (cx + s * k, cy - s * 0.5), (cx, cy), (cx - s * k, cy - s * 0.5)]
    left = [(cx - s * k, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx - s * k, cy + s * 0.5)]
    right = [(cx + s * k, cy - s * 0.5), (cx, cy), (cx, cy + s), (cx + s * k, cy + s * 0.5)]
    faceted(sc, top, shade(base, 1.22), seed, 0.05)
    faceted(sc, left, shade(base, 0.9), seed + 1, 0.05)
    faceted(sc, right, shade(base, 0.62), seed + 2, 0.05)


def block(sc, x, y, w, h, color, seed=1):
    """a rectangle made of two facets, lighter on top"""
    sc.poly([(x, y), (x + w, y), (x, y + h)], shade(color, 1.1))
    sc.poly([(x + w, y), (x + w, y + h), (x, y + h)], shade(color, 0.86))


def ring(sc, cx, cy, r_out, r_in, color, sides=12, seed=1):
    rnd = random.Random(seed)
    for i in range(sides):
        a0, a1 = 2 * math.pi * i / sides, 2 * math.pi * (i + 1) / sides
        pts = [(cx + r_out * math.cos(a0), cy + r_out * math.sin(a0)), (cx + r_out * math.cos(a1), cy + r_out * math.sin(a1)),
               (cx + r_in * math.cos(a1), cy + r_in * math.sin(a1)), (cx + r_in * math.cos(a0), cy + r_in * math.sin(a0))]
        sc.poly(pts, shade(color, 1 + 0.18 * math.sin(a0 * 1 + 0.8) + rnd.uniform(-0.03, 0.03)))


def disc(sc, cx, cy, r, color, sides=10, seed=1):
    pts = [(cx + r * math.cos(2 * math.pi * i / sides), cy + r * math.sin(2 * math.pi * i / sides)) for i in range(sides)]
    faceted(sc, pts, color, seed, 0.14)


# ------------------------------------------------------------------------------------------ the pictures

def lerp3(stops, t):
    t = max(0, min(1, t))
    for i in range(len(stops) - 1):
        t0, c0 = stops[i]
        t1, c1 = stops[i + 1]
        if t <= t1:
            return mix(c0, c1, (t - t0) / (t1 - t0) if t1 > t0 else 0)
    return stops[-1][1]


def hero_home():
    """Home backdrop: transparent, so it sits on the dark or the light window background alike"""
    sc = Scene(1280, 320)

    def col(u, v):
        return lerp3([(0, ACCENT), (0.55, mix(ACCENT, ACCENT2, 0.5)), (1, ACCENT2)], u * 0.85 + v * 0.15)

    def alpha(u, v):
        fade = (1 - v) ** 1.6
        side = 0.35 + 0.65 * math.sin(min(1, u * 1.15 + 0.05) * math.pi * 0.62 + 0.25) ** 2
        return 0.30 * fade * side

    facet_field(sc, 0, 0, 1280, 320, 84, col, 11, 0.34, 0.14, alpha)
    return sc


# default project art: one motif per template, hue taken from the template colour
TEMPLATES = {
    "vanilla": (135, "cube"),
    "shardlight-default": (255, "crystal"),
    "ultra-performance": (32, "bolt"),
    "balanced": (172, "pair"),
    "creative-building": (205, "stack"),
    "pvp": (352, "blades"),
    "exploration": (218, "peaks"),
    "modded-survival": (95, "cluster"),
    "custom": (285, "satellites"),
}


def cover(template):
    hue, motif = TEMPLATES[template]
    W, H = 480, 270
    sc = Scene(W, H)
    dark, mid, light = hsv(hue, 0.7, 0.14), hsv(hue + 8, 0.62, 0.38), hsv(hue + 36, 0.5, 0.62)

    def col(u, v):
        return lerp3([(0, dark), (0.5, mid), (1, light)], (1 - v) * 0.55 + u * 0.55 - 0.1)

    facet_field(sc, 0, 0, W, H, 54, col, hash(template) % 1000, 0.34, 0.07)
    a, b = hsv(hue, 0.55, 0.95), hsv(hue + 40, 0.5, 1.0)
    cx, cy = 340, 138
    sc.glow(cx, cy, 150, hsv(hue + 20, 0.4, 1.0), 0.28)
    if motif == "crystal":
        crystal(sc, cx, cy, 104, 46, a, b, 6, glow=False)
        crystal(sc, cx - 92, cy + 50, 44, 20, shade(a, 0.8), shade(b, 0.8), -12, glow=False)
    elif motif == "cube":
        cube(sc, cx, cy - 8, 64, hsv(hue, 0.55, 0.85))
        cube(sc, cx + 76, cy + 52, 30, hsv(hue + 20, 0.45, 0.8), 9)
    elif motif == "bolt":
        bolt = [(cx + 22, cy - 112), (cx - 44, cy + 14), (cx - 2, cy + 14), (cx - 22, cy + 112), (cx + 50, cy - 24), (cx + 6, cy - 24)]
        faceted(sc, bolt, hsv(hue, 0.55, 1.0), 4, 0.2)
        faceted(sc, [(x * 0.35 + cx * 0.65 + 6, y * 0.35 + cy * 0.65) for x, y in bolt], (255, 244, 214), 5, 0.1, alpha=0.7)
    elif motif == "pair":
        crystal(sc, cx - 44, cy + 6, 88, 38, a, b, -9, glow=False)
        crystal(sc, cx + 50, cy + 6, 88, 38, hsv(hue + 70, 0.5, 0.95), hsv(hue + 110, 0.45, 1.0), 9, glow=False)
    elif motif == "stack":
        cube(sc, cx - 46, cy + 60, 44, hsv(hue, 0.5, 0.8), 3)
        cube(sc, cx + 38, cy + 60, 44, hsv(hue + 15, 0.5, 0.86), 6)
        cube(sc, cx - 4, cy - 2, 44, hsv(hue + 30, 0.45, 0.95), 9)
    elif motif == "blades":
        for ang, c in ((38, a), (-38, b)):
            blade = rot([(cx, cy - 104), (cx + 16, cy - 30), (cx + 9, cy + 38), (cx - 9, cy + 38), (cx - 16, cy - 30)], ang, cx, cy)
            faceted(sc, blade, c, int(ang), 0.2)
            guard = rot([(cx - 36, cy + 38), (cx + 36, cy + 38), (cx + 32, cy + 50), (cx - 32, cy + 50)], ang, cx, cy)
            faceted(sc, guard, shade(c, 0.6), 2, 0.1)
            grip = rot([(cx - 6, cy + 50), (cx + 6, cy + 50), (cx + 6, cy + 84), (cx - 6, cy + 84)], ang, cx, cy)
            faceted(sc, grip, shade(c, 0.45), 3, 0.1)
    elif motif == "peaks":
        sc.poly([(cx - 150, H), (cx - 40, cy - 70), (cx + 50, H)], hsv(hue, 0.55, 0.30))
        sc.poly([(cx - 40, cy - 70), (cx + 50, H), (cx - 10, H)], hsv(hue, 0.6, 0.22))
        sc.poly([(cx - 60, H), (cx + 60, cy - 110), (cx + 190, H)], hsv(hue + 10, 0.5, 0.42))
        sc.poly([(cx + 60, cy - 110), (cx + 190, H), (cx + 100, H)], hsv(hue + 10, 0.55, 0.30))
        sc.poly([(cx + 60, cy - 110), (cx + 34, cy - 66), (cx + 62, cy - 74), (cx + 84, cy - 62)], (236, 242, 255), 0.9)
        disc(sc, cx + 130, cy - 90, 22, hsv(hue + 40, 0.2, 1.0), 9, 4)
    elif motif == "cluster":
        crystal(sc, cx - 10, cy + 6, 100, 40, a, b, 0, glow=False)
        crystal(sc, cx - 78, cy + 50, 56, 24, shade(a, 0.85), shade(b, 0.85), -16, glow=False)
        crystal(sc, cx + 62, cy + 44, 62, 26, shade(a, 0.9), shade(b, 0.9), 14, glow=False)
    else:  # satellites
        crystal(sc, cx, cy + 4, 78, 34, a, b, 0, glow=False)
        for i, (dx, dy) in enumerate(((-92, -50), (86, -62), (-70, 66), (94, 54))):
            crystal(sc, cx + dx, cy + dy, 22, 10, shade(a, 0.9), shade(b, 0.9), (i - 1.5) * 18, glow=False)
    return sc


def splash():
    W, H = 480, 280
    sc = Scene(W, H, bg=INK)

    def col(u, v):
        return lerp3([(0, mix(INK, ACCENT, 0.12)), (0.5, mix(INK, ACCENT, 0.3)), (1, mix(INK, ACCENT2, 0.34))], u * 0.7 + v * 0.3)

    facet_field(sc, 0, 0, W, H, 62, col, 21, 0.34, 0.12)
    crystal(sc, 240, 100, 62, 28, ACCENT, ACCENT2, 0)
    return sc


def splash_image(px_w):
    k = px_w / 480
    img = splash().render(px_w, int(280 * k))
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, int(26 * k))
    f2 = ImageFont.truetype(FONT, int(12 * k))
    text = "Shardlight Client"
    w = d.textlength(text, font=f)
    d.text(((px_w - w) / 2, 190 * k), text, font=f, fill=(236, 238, 245))
    sub = "Starting…"
    w2 = d.textlength(sub, font=f2)
    d.text(((px_w - w2) / 2, 232 * k), sub, font=f2, fill=(174, 180, 200))
    return img


EMPTY_PURPLE, EMPTY_TEAL, EMPTY_SLATE = (124, 108, 242), (58, 190, 176), (132, 140, 168)


def empty(name):
    W, H = 160, 120
    sc = Scene(W, H)
    sc.glow(80, 62, 66, EMPTY_PURPLE, 0.22)
    P, T, S = EMPTY_PURPLE, EMPTY_TEAL, EMPTY_SLATE
    if name == "library":
        block(sc, 38, 52, 22, 52, S); block(sc, 62, 38, 22, 66, P); block(sc, 86, 58, 22, 46, T)
        block(sc, 110, 46, 18, 58, shade(S, 1.1))
        sc.poly([(30, 104), (136, 104), (136, 108), (30, 108)], shade(S, 0.7))
        crystal(sc, 73, 20, 14, 7, P, T, glow=False)
    elif name == "search":
        ring(sc, 74, 54, 34, 25, P, 14, 2)
        sc.poly([(98, 78), (106, 70), (130, 94), (122, 102)], shade(T, 0.9))
        crystal(sc, 74, 54, 15, 8, shade(P, 1.2), T, glow=False)
    elif name == "content":
        cube(sc, 80, 64, 36, P)
        crystal(sc, 80, 24, 14, 7, T, shade(T, 1.15), glow=False)
    elif name == "downloads":
        sc.poly([(70, 14), (92, 14), (92, 52), (110, 52), (81, 82), (52, 52), (70, 52)], T)
        sc.poly([(70, 14), (81, 14), (81, 82), (52, 52), (70, 52)], shade(T, 1.14))
        block(sc, 40, 90, 82, 14, P)
        sc.poly([(40, 90), (122, 90), (112, 82), (50, 82)], shade(P, 1.2))
    elif name == "servers":
        for i, y in enumerate((30, 56, 82)):
            block(sc, 40, y, 82, 20, [P, S, T][i])
            disc(sc, 52, y + 10, 4, (230, 236, 250), 6)
            sc.poly([(66, y + 8), (108, y + 8), (108, y + 12), (66, y + 12)], (230, 236, 250), 0.7)
    elif name == "world":
        disc(sc, 80, 60, 40, P, 12, 5)
        sc.poly([(62, 40), (84, 34), (96, 52), (82, 66), (64, 60)], shade(T, 0.95))
        sc.poly([(86, 70), (104, 62), (108, 80), (94, 90)], shade(T, 0.8))
        ring(sc, 80, 60, 46, 43, shade(S, 1.1), 16)
    elif name == "image":
        block(sc, 34, 26, 92, 68, shade(S, 0.9))
        block(sc, 40, 32, 80, 56, shade(P, 0.7))
        sc.poly([(40, 88), (70, 52), (92, 76), (104, 62), (120, 88)], T)
        sc.poly([(70, 52), (92, 76), (82, 88), (60, 88)], shade(T, 0.8))
        disc(sc, 104, 44, 7, (240, 232, 190), 7, 3)
    elif name == "archive":
        block(sc, 36, 52, 88, 52, P)
        block(sc, 30, 36, 100, 20, shade(P, 1.25))
        block(sc, 66, 66, 28, 8, (230, 236, 250))
        crystal(sc, 80, 18, 12, 6, T, shade(T, 1.15), glow=False)
    elif name == "trash":
        sc.poly([(44, 40), (116, 40), (108, 104), (52, 104)], S)
        sc.poly([(44, 40), (80, 40), (80, 104), (52, 104)], shade(S, 1.14))
        sc.poly([(36, 28), (124, 28), (124, 38), (36, 38)], shade(S, 0.8))
        sc.poly([(68, 20), (92, 20), (92, 28), (68, 28)], shade(S, 0.65))
        for x in (64, 80, 96):
            sc.poly([(x - 2, 52), (x + 2, 52), (x + 2, 92), (x - 2, 92)], shade(S, 0.62))
    elif name == "user":
        disc(sc, 80, 42, 20, T, 10, 4)
        sc.poly([(36, 104), (44, 76), (62, 66), (98, 66), (116, 76), (124, 104)], P)
        sc.poly([(36, 104), (44, 76), (62, 66), (80, 66), (80, 104)], shade(P, 1.16))
    elif name == "shard":
        crystal(sc, 80, 60, 46, 22, P, T)
    else:
        raise ValueError(name)
    return sc


EMPTY_NAMES = ["library", "search", "content", "downloads", "servers", "world", "image", "archive", "trash", "user", "shard"]


# ------------------------------------------------------------------------------------------ installer pictures

def windows_banner():
    """493 x 58: the installer shows its title over the left part, so the art lives on the right"""
    sc = Scene(493, 58, bg=(245, 246, 250))

    def col(u, v):
        return mix((245, 246, 250), ACCENT, max(0, (u - 0.62) / 0.38) * 0.75)

    facet_field(sc, 0, 0, 493, 58, 30, col, 31, 0.34, 0.04)
    crystal(sc, 455, 29, 22, 10, ACCENT, ACCENT2, 0, glow=False)
    return sc.render(493, 58, 3).convert("RGB")


def windows_dialog():
    """493 x 312: the left 164 pixels are art, the rest stays white because the welcome text is drawn there"""
    sc = Scene(493, 312, bg=(255, 255, 255))
    sc.poly([(0, 0), (164, 0), (164, 312), (0, 312)], INK)

    def col(u, v):
        return lerp3([(0, mix(INK, ACCENT, 0.18)), (0.5, mix(INK, ACCENT, 0.38)), (1, mix(INK, ACCENT2, 0.40))], v * 0.8 + u * 0.2)

    rnd = random.Random(44)
    sub = Scene(164, 312)
    facet_field(sub, 0, 0, 164, 312, 46, col, 44, 0.34, 0.1)
    crystal(sub, 82, 112, 58, 26, ACCENT, ACCENT2, 0)
    sc.ops += sub.ops
    # keep the white area clean: repaint over anything that crossed the border
    sc.poly([(164, 0), (493, 0), (493, 312), (164, 312)], (255, 255, 255))
    img = sc.render(493, 312, 3).convert("RGB")
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, 17)
    d.text((82 - d.textlength("Shardlight", font=f) / 2, 226), "Shardlight", font=f, fill=(236, 238, 245))
    d.text((82 - d.textlength("Client", font=f) / 2, 248), "Client", font=f, fill=(174, 180, 200))
    return img


def mac_background():
    W, H = 660, 400
    sc = Scene(W, H, bg=INK)

    def col(u, v):
        return lerp3([(0, mix(INK, ACCENT, 0.14)), (0.5, mix(INK, ACCENT, 0.28)), (1, mix(INK, ACCENT2, 0.30))], u * 0.7 + v * 0.3)

    facet_field(sc, 0, 0, W, H, 76, col, 51, 0.34, 0.1)
    crystal(sc, 330, 70, 34, 15, ACCENT, ACCENT2, 0)
    img = sc.render(W, H)
    d = ImageDraw.Draw(img)
    f = ImageFont.truetype(FONT, 15)
    t = "Drag Shardlight Client to Applications"
    d.text(((W - d.textlength(t, font=f)) / 2, 360), t, font=f, fill=(210, 214, 228))
    return img


def write_icns(path):
    """macOS icon file: PNG chunks of the same shard icon, 16 px to 1024 px"""
    sys.path.insert(0, os.path.join(ROOT, "tools", "icons"))
    import make_icons
    kinds = {"icp4": 16, "icp5": 32, "icp6": 64, "ic07": 128, "ic08": 256, "ic09": 512, "ic10": 1024, "ic11": 32, "ic12": 64, "ic13": 256, "ic14": 512}
    chunks = b""
    for code, size in kinds.items():
        buf = io.BytesIO()
        make_icons.icon(size).save(buf, "PNG", optimize=True)
        data = buf.getvalue()
        chunks += code.encode() + struct.pack(">I", len(data) + 8) + data
    with open(path, "wb") as f:
        f.write(b"icns" + struct.pack(">I", len(chunks) + 8) + chunks)


def main():
    save_pair(hero_home(), os.path.join(ART, "hero-home"), 1280, 320, "hero-home")
    for t in TEMPLATES:
        save_pair(cover(t), os.path.join(ART, "covers", t), 480, 270, "cover-" + t)
    for n in EMPTY_NAMES:
        save_pair(empty(n), os.path.join(ART, "empty", n), 160, 120, "empty-" + n)
    os.makedirs(ART, exist_ok=True)
    splash_image(480).save(os.path.join(ART, "splash.png"), optimize=True)
    splash_image(960).save(os.path.join(ART, "splash@2x.png"), optimize=True)
    win = os.path.join(ROOT, "packaging", "windows")
    mac = os.path.join(ROOT, "packaging", "macos")
    os.makedirs(win, exist_ok=True)
    os.makedirs(mac, exist_ok=True)
    windows_banner().save(os.path.join(win, "banner.bmp"))
    windows_dialog().save(os.path.join(win, "dialog.bmp"))
    mac_background().save(os.path.join(mac, "Shardlight Client-background.png"), optimize=True)
    write_icns(os.path.join(ROOT, "packaging", "icons", "shardlight.icns"))
    print("art written")


if __name__ == "__main__":
    main()
