#!/usr/bin/env python3
"""
Regenerate the LightNews launcher icon.

The mark is a letter: a page of ruled copy with the top rule shortened into a headline,
and a filled dot at the corner for the unread state the app exists to clear. White line
art on black, matching the icon language of the sibling Light Phone III tools.

Geometry is defined once, in the 108x108 adaptive-icon canvas, and emitted twice — as
Android vector paths and as raster fallbacks — so nothing is ever rescaled by string
surgery. Everything sits inside the 18..90 safe zone so no launcher mask can clip it.

    python3 scripts/generate_icon.py

Needs Pillow. Rewrites app/src/main/res/{drawable,mipmap-*}.
"""

from __future__ import annotations

import os

from PIL import Image, ImageDraw

RES = os.path.join(os.path.dirname(__file__), "..", "app", "src", "main", "res")

CANVAS = 108
SAFE = (18, 90)
STROKE = 5.0

# The page, as a rectangle in canvas units.
PAGE = (26.0, 24.0, 74.0, 84.0)
# Ruled lines: (y, x_end). The first is the headline, so it stops short.
RULES = [(40.0, 58.0), (52.0, 66.0), (62.0, 66.0), (72.0, 60.0)]
RULE_X0 = 36.0
# Unread dot, top right, deliberately breaking the page edge.
DOT = (78.0, 30.0)
DOT_R = 8.0

DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
SUPERSAMPLE = 8


def check_safe_zone() -> None:
    lo, hi = SAFE
    edge = STROKE / 2
    extents = [
        PAGE[0] - edge, PAGE[1] - edge, PAGE[2] + edge, PAGE[3] + edge,
        DOT[0] - DOT_R, DOT[1] - DOT_R, DOT[0] + DOT_R, DOT[1] + DOT_R,
    ]
    for value in extents:
        if value < lo or value > hi:
            raise SystemExit(f"geometry leaves the {lo}..{hi} safe zone: {value}")


def vector_paths() -> str:
    """The page outline, the rules, and the dot, as one <vector> body."""
    x0, y0, x1, y1 = PAGE
    parts = [
        f'    <path android:strokeWidth="{STROKE}" android:strokeColor="#FFFFFF"'
        f' android:strokeLineJoin="round" android:fillColor="#00000000"\n'
        f'        android:pathData="M{x0},{y0} L{x1},{y0} L{x1},{y1} L{x0},{y1} Z" />'
    ]
    for y, x_end in RULES:
        parts.append(
            f'    <path android:strokeWidth="{STROKE * 0.7:.2f}" android:strokeColor="#FFFFFF"'
            f' android:strokeLineCap="round" android:fillColor="#00000000"\n'
            f'        android:pathData="M{RULE_X0},{y} L{x_end},{y}" />'
        )
    cx, cy = DOT
    parts.append(
        f'    <path android:fillColor="#000000"\n'
        f'        android:pathData="M{cx - DOT_R - 2},{cy} a{DOT_R + 2},{DOT_R + 2} 0 1,0'
        f' {2 * (DOT_R + 2)},0 a{DOT_R + 2},{DOT_R + 2} 0 1,0 {-2 * (DOT_R + 2)},0 Z" />'
    )
    parts.append(
        f'    <path android:fillColor="#FFFFFF"\n'
        f'        android:pathData="M{cx - DOT_R},{cy} a{DOT_R},{DOT_R} 0 1,0 {2 * DOT_R},0'
        f' a{DOT_R},{DOT_R} 0 1,0 {-2 * DOT_R},0 Z" />'
    )
    return "\n".join(parts)


def write_vectors() -> None:
    drawable = os.path.join(RES, "drawable")
    os.makedirs(drawable, exist_ok=True)
    with open(os.path.join(drawable, "ic_launcher_background.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<shape xmlns:android="http://schemas.android.com/apk/res/android"\n'
            '    android:shape="rectangle">\n'
            '    <solid android:color="#000000" />\n'
            "</shape>\n"
        )
    with open(os.path.join(drawable, "ic_launcher_foreground.xml"), "w") as f:
        f.write(
            '<?xml version="1.0" encoding="utf-8"?>\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{CANVAS}dp" android:height="{CANVAS}dp"\n'
            f'    android:viewportWidth="{CANVAS}" android:viewportHeight="{CANVAS}">\n'
            f"{vector_paths()}\n"
            "</vector>\n"
        )


def raster(size: int) -> Image.Image:
    scale = SUPERSAMPLE * size / CANVAS
    img = Image.new("RGB", (size * SUPERSAMPLE, size * SUPERSAMPLE), "black")
    draw = ImageDraw.Draw(img)

    def s(value: float) -> float:
        return value * scale

    x0, y0, x1, y1 = PAGE
    draw.rectangle([s(x0), s(y0), s(x1), s(y1)], outline="white", width=round(s(STROKE)))
    for y, x_end in RULES:
        draw.line([s(RULE_X0), s(y), s(x_end), s(y)], fill="white", width=round(s(STROKE * 0.7)))
    cx, cy = DOT
    # Punch the page out from behind the dot so the two shapes stay readable.
    draw.ellipse(
        [s(cx - DOT_R - 2), s(cy - DOT_R - 2), s(cx + DOT_R + 2), s(cy + DOT_R + 2)],
        fill="black",
    )
    draw.ellipse([s(cx - DOT_R), s(cy - DOT_R), s(cx + DOT_R), s(cy + DOT_R)], fill="white")
    return img.resize((size, size), Image.LANCZOS)


def write_rasters() -> None:
    for density, size in DENSITIES.items():
        out = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(out, exist_ok=True)
        icon = raster(size)
        icon.save(os.path.join(out, "ic_launcher.png"))
        icon.save(os.path.join(out, "ic_launcher_round.png"))

    anydpi = os.path.join(RES, "mipmap-anydpi-v26")
    os.makedirs(anydpi, exist_ok=True)
    for name in ("ic_launcher.xml", "ic_launcher_round.xml"):
        with open(os.path.join(anydpi, name), "w") as f:
            f.write(
                '<?xml version="1.0" encoding="utf-8"?>\n'
                '<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">\n'
                '    <background android:drawable="@drawable/ic_launcher_background" />\n'
                '    <foreground android:drawable="@drawable/ic_launcher_foreground" />\n'
                "</adaptive-icon>\n"
            )


if __name__ == "__main__":
    check_safe_zone()
    write_vectors()
    write_rasters()
    print("wrote drawable/ and mipmap-*/ under", os.path.normpath(RES))
