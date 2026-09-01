#!/usr/bin/env python3
"""Generate hydrarium's PAGE art -- the banners modpage.yml lays out.

    python authoring/gen_pageart.py            # write assets/banners/*.png
    python authoring/gen_pageart.py --out DIR  # somewhere else

``authoring/gen_water.py`` generates what the MOD ships. This one generates what the PAGE ships,
and the two must not be confused: nothing here ever lands in ``src/main/resources``.

The one idea both share is the palette. Every banner is built out of the same nineteen tints the
catalogue declares, read from the generated catalogue rather than retyped -- so a colour changed in
``gen_water.py`` reaches the page banners on the next run of this file, and a colour that only
exists on the page cannot exist at all.

The screenshots are NOT generated here. They are captured from a running game and downscaled into
``assets/gallery/``; this file owns only the flat art, which is the half that can be derived.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

REPO = Path(__file__).resolve().parents[1]
CATALOGUE = REPO / "src/main/resources/assets/hydrarium/hydrarium/catalogue.json"

# The banner ground. Deepslate-dark, because a tint is a multiply and a multiply only darkens: the
# colours have to sit ON something dark to read as light in water rather than as paint.
GROUND = (18, 21, 26)
GROUND_2 = (28, 33, 41)
INK = (233, 240, 245)
DIM = (140, 158, 172)

# Every section modpage.yml actually lays out, and the window of the palette its banner takes.
#
# The offset is WRITTEN DOWN rather than derived from the position in this list, and that is the
# whole point of the third column: with `offset=i * 3`, deleting one entry shifts every entry below
# it and the next run repaints banners belonging to sections nobody touched. The numbers below are
# the ones the index used to produce, so the art on disk is unchanged -- they are a starting point
# for a new section, not a constraint on it. Any multiple works; the palette wraps.
#
# There is no `recipes` row. hydrarium registers zero items and so ships zero recipe JSON --
# `src/main/resources/data/` does not exist -- and modpage's recipes section is opt-in for exactly
# that reason. A row here that modpage.yml does not also declare is a banner for a section that
# never renders, and nothing warns: `modpage build` reads its own section list, not this one.
#
# `configuration` is still such a row, deliberately left: the page says there is nothing to
# configure, so the banner is spare art rather than a section waiting to be written. Drop it the
# day that stays true.
SECTIONS = [
    ("about", "About", 0),
    ("features", "Features", 3),
    ("commands", "Commands", 6),
    ("catalogue", "Adding your own waters", 9),
    ("gallery", "Gallery", 12),
    ("dependencies", "Dependencies", 18),
    ("incompatibilities", "Incompatibilities", 21),
    ("installation", "Installation", 24),
    ("building", "Building", 27),
    ("configuration", "Configuration", 30),
    ("faq", "FAQ", 33),
    ("credits", "Credits", 36),
    ("license", "License", 39),
]


def tints() -> list[tuple[int, int, int]]:
    """The nineteen colours, in catalogue order, as RGB triples."""
    waters = json.loads(CATALOGUE.read_text(encoding="utf-8"))["waters"]
    out = []
    for water in waters:
        value = int(water["tint"])
        out.append(((value >> 16) & 0xFF, (value >> 8) & 0xFF, value & 0xFF))
    return out


def font(size: int, bold: bool = True):
    names = (
        ["segoeuib.ttf", "arialbd.ttf", "DejaVuSans-Bold.ttf"]
        if bold
        else ["segoeui.ttf", "arial.ttf", "DejaVuSans.ttf"]
    )
    for name in names:
        try:
            return ImageFont.truetype(name, size)
        except OSError:
            continue
    return ImageFont.load_default()


def ground(width: int, height: int) -> Image.Image:
    """A vertical two-stop ground, so the strip does not sit on a flat rectangle."""
    image = Image.new("RGB", (1, height))
    pixels = image.load()
    for y in range(height):
        t = y / max(height - 1, 1)
        pixels[0, y] = tuple(round(a + (b - a) * t) for a, b in zip(GROUND_2, GROUND))
    return image.resize((width, height))


def ribbon(width: int, height: int, colours: list[tuple[int, int, int]]) -> Image.Image:
    """The palette as one continuous band -- nineteen stops, linearly interpolated."""
    stops = len(colours)
    row = Image.new("RGB", (stops * 64, 1))
    pixels = row.load()
    for x in range(row.width):
        t = x / (row.width - 1) * (stops - 1)
        i = min(int(t), stops - 2)
        f = t - i
        a, b = colours[i], colours[i + 1]
        pixels[x, 0] = tuple(round(p + (q - p) * f) for p, q in zip(a, b))
    return row.resize((width, height))


def swatches(width: int, height: int, colours: list[tuple[int, int, int]], gap: int) -> Image.Image:
    """The palette as nineteen discrete blocks -- what the water actually is, not a gradient."""
    image = Image.new("RGB", (width, height), GROUND)
    draw = ImageDraw.Draw(image)
    n = len(colours)
    cell = (width - gap * (n - 1)) / n
    for i, colour in enumerate(colours):
        x = i * (cell + gap)
        draw.rectangle([x, 0, x + cell - 1, height], fill=colour)
    return image


def header(path: Path, colours: list[tuple[int, int, int]]) -> None:
    """Title banner: the palette shot, darkened, with the nineteen swatches spelled out under it.

    The photograph carries "this is Minecraft water"; the swatch strip carries "there are nineteen
    of them and they are exact". Neither says both, which is why the banner has both.
    """
    width, height = 1600, 460
    image = ground(width, height)

    shot = REPO / "assets" / "gallery" / "palette.png"
    if shot.exists():
        plate = Image.open(shot).convert("RGB")
        scale = max(width / plate.width, height / plate.height)
        plate = plate.resize((round(plate.width * scale), round(plate.height * scale)),
                             Image.LANCZOS)
        left = (plate.width - width) // 2
        top = round((plate.height - height) * 0.42)
        plate = plate.crop((left, top, left + width, top + height))
        plate = plate.filter(ImageFilter.GaussianBlur(1.2))
        image = Image.blend(image, plate, 0.62)
        # A scrim from the middle down, so the title never fights the picture underneath it.
        scrim = Image.new("L", (1, height))
        px = scrim.load()
        for y in range(height):
            t = max(0.0, (y / height - 0.10) / 0.90)
            px[0, y] = round(235 * t ** 1.4)
        image = Image.composite(ground(width, height), image, scrim.resize((width, height)))
    else:
        wash = ribbon(width, height, colours).filter(ImageFilter.GaussianBlur(70))
        image = Image.blend(image, wash, 0.22)

    draw = ImageDraw.Draw(image)
    title, tagline = "hydrarium", "coloured water that is still water"
    title_font, tag_font = font(150), font(40, bold=False)
    tw = draw.textbbox((0, 0), title, font=title_font)
    draw.text(((width - (tw[2] - tw[0])) / 2 - tw[0], 150), title, font=title_font, fill=INK)
    tg = draw.textbbox((0, 0), tagline, font=tag_font)
    draw.text(((width - (tg[2] - tg[0])) / 2 - tg[0], 328), tagline, font=tag_font, fill=DIM)

    image.paste(swatches(width - 160, 22, colours, gap=6), (80, 400))
    image.save(path)
    print(f"wrote {path}")


def banner(path: Path, text: str, colours: list[tuple[int, int, int]], offset: int) -> None:
    """Section banner: a title, and a stack of five of the nineteen down the left edge.

    Five discrete blocks and not a blurred wash, for the reason the swatch strip exists at all --
    these are exact colours, and a gradient between two of them is a colour no water in the
    catalogue has.
    """
    width, height = 1200, 150
    image = ground(width, height)
    draw = ImageDraw.Draw(image)

    # Each section takes a different five-colour window of the palette, so the set reads as one
    # family without any two banners being the same picture.
    window = [colours[(offset + i) % len(colours)] for i in range(5)]
    bar, cell = 46, height / len(window)
    for i, colour in enumerate(window):
        draw.rectangle([0, i * cell, bar, (i + 1) * cell], fill=colour)

    label_font = font(52)
    box = draw.textbbox((0, 0), text, font=label_font)
    draw.text((bar + 44 - box[0], (height - (box[3] - box[1])) / 2 - box[1]), text,
              font=label_font, fill=INK)
    draw.rectangle([bar, height - 5, width, height], fill=window[2])
    image.save(path)
    print(f"wrote {path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", default=str(REPO / "assets" / "banners"))
    args = parser.parse_args()

    out = Path(args.out)
    out.mkdir(parents=True, exist_ok=True)
    colours = tints()

    header(out / "header.png", colours)
    for sid, title, offset in SECTIONS:
        banner(out / f"{sid}.png", title, colours, offset=offset)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
