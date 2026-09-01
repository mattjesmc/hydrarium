#!/usr/bin/env python3
"""Generate hydrarium's built-in water catalogue.

herbarium's generator makes SPRITES. This one makes a TABLE, and the difference between the two
files is the difference between the two mods.

hydrarium ships no block texture at all. Tinted water is ``minecraft:water`` wearing
``minecraft:block/water_still``, so there is nothing to draw: a water is three integers a renderer
multiplies into a quad it was going to draw anyway. What there IS to generate is the sixteen dye
rows and the lang entries beside them, because those are derived from one palette and typing them
out sixteen times by hand is how the catalogue and the language file drift apart.

There are exactly two sprites, and they are the exception that proves the rule: an ITEM cannot be
tinted per position, because an item has no position, so a bucket that shows what it holds needs a
mask laid over vanilla's. Neither is a picture of water -- each is vanilla's own contents pixels
reduced to grey, so that the tint is doing all of the colouring. See BUCKETS.

Two rather than one because the frozen half added the powder snow bucket, which is the same
component and the same tint source over a different mask. Everything else the frozen half needed --
the colour of ice, of snow, of the snow in a cauldron -- is a multiply into a sprite vanilla already
draws, so it is a number and not an asset, and this generator has nothing to say about it beyond the
swatch.

Run through gradle, which declares the outputs:

    ./gradlew art

or directly:

    python authoring/gen_water.py --out src/main/resources
    python authoring/gen_water.py --check           # the palette's own invariants
    python authoring/gen_water.py --swatch out.png  # every water, as it will look in three biomes
    python authoring/gen_water.py --preview <dir>   # write everything somewhere else, install nothing

NOTHING under ``assets/hydrarium/`` is edited by hand. Editing the catalogue is a change the next
``./gradlew art`` silently reverts -- change the palette below instead.

------------------------------------------------------------------------------------------------
The palette
------------------------------------------------------------------------------------------------

The sixteen colours are ``DyeColor``'s own ``textureDiffuseColor`` values, copied from
``net/minecraft/world/item/DyeColor.java`` rather than invented, so that red water is the same red
as a red banner and a red bed. That matters more than it sounds: the cauldron takes a red dye and
makes red water, and a player who has to be told those are the same red has been told the mod is
approximate.

``biome_strength`` is the one knob that is NOT vanilla's. See ``WaterType.DEFAULT_BIOME_STRENGTH``
for why a quarter and not one: a straight multiply crushes a saturated tint towards the biome, so
red water in a swamp comes out brown. The two greys and white take a little more, because a
desaturated colour has nothing to lose to the biome and reads better for picking some up.
"""

from __future__ import annotations

import argparse
import json
import os
import sys

# name, DyeColor.textureDiffuseColor, biome_strength
#
# The order is DyeColor's own, which is also the order these appear in a creative tab and in every
# other sixteen-colour list in the game. Sorting it alphabetically would be tidier and would put
# this list out of step with every other one a player has seen.
DYES: list[tuple[str, int, float]] = [
    ("white",      16383998, 0.35),
    ("orange",     16351261, 0.25),
    ("magenta",    13061821, 0.25),
    ("light_blue",  3847130, 0.25),
    ("yellow",     16701501, 0.25),
    ("lime",        8439583, 0.25),
    ("pink",       15961002, 0.25),
    ("gray",        4673362, 0.35),
    ("light_gray", 10329495, 0.35),
    ("cyan",        1481884, 0.25),
    ("purple",      8991416, 0.25),
    ("blue",        3949738, 0.25),
    ("brown",       8606770, 0.25),
    ("green",       6192150, 0.25),
    ("red",        11546150, 0.25),
    ("black",       1908001, 0.35),
]

# The waters that are not dyes: a colour AND an effect, which is the second axis and the one that is
# not free. Every effect named here must exist in Effect.java -- check() is what holds the two
# together, because a name this build does not have costs that water its behaviour silently.
#
# These three are the starting set from DESIGN.md, drawn from rocketeer's existing biome families.
# They are hydrarium's own so that the effect path has content exercising it in a plain dev world,
# rather than only in a consumer's.
EFFECTS: list[tuple[str, int, str, float]] = [
    ("ash_slurry",  0x404040, "ash",   0.35),
    ("irradiated",  0x78 << 16 | 0xE0 << 8 | 0x30, "decay", 0.15),
    ("lumewater",   0x40E0D0, "glow",  0.10),
]

# DyeColor's own sixteen names, which are also the sixteen ids above and the sixteen values the
# "dye" field may take. Written out rather than derived from DYES so that check() has something to
# check DYES AGAINST -- a list compared with itself passes no matter what is in it.
VANILLA_DYES = {
    "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
    "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black",
}

# The effect names Effect.java has. One list in two languages, exactly like herbarium's KINDS, and
# check() is the only thing holding them together.
KNOWN_EFFECTS = {"none", "glow", "ash", "decay"}

# ------------------------------------------------------------------------------------------------
# The two buckets
# ------------------------------------------------------------------------------------------------
#
# The ONLY art this mod has, and both pieces are masks rather than pictures: sixteen by sixteen,
# opaque over exactly the pixels vanilla draws CONTENTS in and transparent everywhere else, so each
# lies over vanilla's own bucket sprite without touching a pixel of the metal.
#
# There are two of them, and there are exactly two because an ITEM has no BlockPos: the four
# position-shaped render surfaces cannot reach a bucket, so a bucket that shows what it holds needs
# a mask, and the two buckets that can hold a hydrarium water are water and powder snow. The frozen
# half added the second one and no second idea with it -- same component, same tint source, same
# has_component override; a different mask and a different fit.
#
# Digits index that bucket's own shade list, dark to light; `.` is transparent. Both shapes were
# read out of vanilla's textures with a script and pasted here, for the same reason the dye colours
# are pasted here: an approximation of vanilla's silhouette is a bucket whose contents are one pixel
# off, and that is worse than no overlay at all.

BUCKET_WATER = [
    "................",
    "................",
    "................",
    "....01322110....",
    "...0344324230...",
    ".....023320.....",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]

# Vanilla's five water pixels, dark to light. Kept as colours and not as brightnesses because they
# are what the fallback is fitted against, and a fit against numbers nobody can check is not a fit.
WATER_SHADES = [
    (35, 79, 204),
    (46, 88, 211),
    (52, 95, 218),
    (68, 111, 233),
    (90, 130, 243),
]

# Powder snow, which overflows the bucket -- rows 0 and 1 are above the rim, where vanilla's EMPTY
# bucket sprite is transparent -- and sparkles below it. Those stray pixels at rows 6, 7 and 9 are
# vanilla's own: snow crystals drawn over the metal. They are in the mask because they are contents,
# and leaving them out would give a tinted bucket three white specks it could not explain.
BUCKET_POWDER_SNOW = [
    "......0000......",
    ".....044210.....",
    "....04431210....",
    "...0132124201...",
    "...1043421010...",
    ".....022121.....",
    ".........1......",
    ".........1.2....",
    "................",
    ".........1......",
    "................",
    "................",
    "................",
    "................",
    "................",
    "................",
]

# And its five, which are nearly white and slightly cyan. The cyan is exactly why this is still a
# GREY mask: (113, 195, 210) is the shadow under the heap, and a mask that kept its ratios would put
# a cyan cast on every colour a player poured into it. See mask_levels().
POWDER_SNOW_SHADES = [
    (113, 195, 210),
    (190, 240, 240),
    (220, 245, 245),
    (240, 253, 253),
    (255, 255, 255),
]

# item id, art, shades, and how many pixels the art must paint. The pixel count is written out
# rather than derived because it is the one invariant that catches a mis-edited mask: a digit turned
# into a dot still parses, still renders, and leaves one pixel of vanilla's own blue or white showing
# through the tint.
BUCKETS = [
    ("water_bucket", BUCKET_WATER, WATER_SHADES, 24),
    ("powder_snow_bucket", BUCKET_POWDER_SNOW, POWDER_SNOW_SHADES, 48),
]

# The strings that are NOT derived from the palette: what the command says back, and the one word
# the tooltip adds. They live here rather than in a hand-kept lang file because en_us.json is
# written in one piece, and a key added by hand is a key the next `./gradlew art` deletes.
CHROME = {
    "commands.hydrarium.unknown": "No loaded catalogue declares the water %s",
    "commands.hydrarium.give.single": "Gave %s bucket(s) of %s to %s",
    "commands.hydrarium.give.multiple": "Gave %s bucket(s) of %s to %s players",
    "commands.hydrarium.fill.success": "Tinted %s water block(s) %s",
    "commands.hydrarium.fill.cleared": "Cleared the tint from %s water block(s)",
    "commands.hydrarium.fill.none": "No water in that region",
    "commands.hydrarium.fill.toobig": "That region holds %s blocks; the limit is %s",
    "commands.hydrarium.at.field": "%s, painted at this position, in a %s",
    "commands.hydrarium.at.biome": "%s, declared for biome %s, in a %s",
    "commands.hydrarium.at.vanilla": "Ordinary water",
    "commands.hydrarium.at.frozen": "Ordinary ice or snow; vanilla draws it untinted",
    "commands.hydrarium.at.dry": "Nothing there holds water, in any phase",
}


TITLES = {
    "light_blue": "Light Blue",
    "light_gray": "Light Gray",
}


def title(name: str) -> str:
    return TITLES.get(name) or name.replace("_", " ").title()


def catalogue() -> dict:
    """The built-in catalogue, read through the SAME scanner a consumer's is.

    There is no private path for hydrarium's own waters and that is load-bearing rather than tidy:
    the moment the built-ins take a shortcut, the path tier 2 uses stops being the path this mod
    itself exercises, and it becomes possible for the sixteen dyes to work while a consumer's
    catalogue is quietly broken.
    """
    waters = []
    for name, tint, strength in DYES:
        # "dye" is what lets these sixteen MIX. WaterMix blends two waters by blending their dyes
        # through vanilla's own crafting recipes, so red water plus yellow water is orange water for
        # the same reason red dye plus yellow dye is orange dye -- and a pair the game has no recipe
        # for comes out grey. It is written out rather than matched from the id on purpose: a
        # consumer's water joins the mixing by declaring one field, without being NAMED after a dye.
        waters.append({"id": name, "tint": tint, "dye": name, "biome_strength": strength})
    for name, tint, effect, strength in EFFECTS:
        waters.append({"id": name, "tint": tint, "effect": effect, "biome_strength": strength})
    # No "biomes" list. hydrarium declares no biome anywhere in the game to be one of its waters --
    # that is a consumer's decision about a consumer's world, and a library that repainted the
    # vanilla ocean on installation would be a library nobody could depend on.
    return {"waters": waters}


def lang() -> dict:
    """One line per water, then the chrome.

    ``water.<namespace>.<path>`` is the key a water is NAMED by, and both things that show a name to
    a player build it from the id rather than from a list of their own: the bucket tooltip and
    ``/water at``. That is what makes a consumer's water nameable without hydrarium knowing it
    exists -- rocketeer ships ``water.rocketeer.lumewater`` in its own lang file and the tooltip
    finds it.
    """
    out = {}
    for name, _tint, _strength in DYES:
        out[f"water.hydrarium.{name}"] = f"{title(name)} Water"
    for name, _tint, _effect, _strength in EFFECTS:
        out[f"water.hydrarium.{name}"] = title(name)
    out.update(CHROME)
    return out


# ------------------------------------------------------------------------------------------------
# The bucket, generated
# ------------------------------------------------------------------------------------------------


def luminance(colour: tuple[int, int, int]) -> float:
    r, g, b = colour
    return 0.299 * r + 0.587 * g + 0.114 * b


def mask_levels(shades: list[tuple[int, int, int]]) -> list[int]:
    """Each shade's brightness as a fraction of the brightest, on 0..255.

    The overlay is GREY rather than a recoloured copy of vanilla's pixels, and that is the whole
    difference between a mod with sixteen water buckets and a mod with sixteen faintly blue ones.
    Vanilla's water shading is not one hue scaled up and down -- its blue channel barely moves
    (204..243) while its red channel nearly triples (35..90) -- so an overlay that preserved those
    ratios would preserve the blue with them, and white water would come out pale blue under a
    multiply. A grey mask keeps only the SHAPE of the shading and lets the tint supply all colour.

    Powder snow is the same argument at a different hue: its darkest pixel is (113, 195, 210), which
    is cyan, and a bucket of red powder snow that came out muddy would come out muddy for exactly
    this reason.
    """
    brightest = max(luminance(colour) for colour in shades)
    return [round(255 * luminance(colour) / brightest) for colour in shades]


def shade_counts(art: list[str], shades: list[tuple[int, int, int]]) -> list[int]:
    counts = [0] * len(shades)
    for row in art:
        for char in row:
            if char != ".":
                counts[int(char)] += 1
    return counts


def bucket_default(art: list[str], shades: list[tuple[int, int, int]]) -> int:
    """The colour that makes this grey mask look like vanilla's own contents again.

    Reached only by a bucket holding a water this build does not have -- one filled in a world with
    rocketeer installed and opened in one without it. That water POURS clear, so its bucket should
    read as ordinary water (or ordinary powder snow), and this is the least-squares fit of
    ``mask * D / 255`` to vanilla's own pixels, weighted by how many pixels each shade covers.

    It is a fit and not an identity, and it cannot be one: a grey mask cannot reproduce a gradient
    whose channels move at different rates, which is exactly the property that made grey the right
    mask. Close is the whole of what is on offer here, and it is only ever on offer to a bucket
    whose contents are already a mystery.
    """
    levels = mask_levels(shades)
    counts = shade_counts(art, shades)
    fitted = []
    for channel in range(3):
        numerator = sum(n * m * c[channel] for n, m, c in zip(counts, levels, shades))
        denominator = sum(n * m * m for n, m in zip(counts, levels))
        fitted.append(min(255, round(255 * numerator / denominator)))
    return fitted[0] << 16 | fitted[1] << 8 | fitted[2]


def overlay(art: list[str], shades: list[tuple[int, int, int]]) -> list[list[tuple[int, int, int, int]]]:
    """The mask as pixels: grey where vanilla draws contents, fully transparent everywhere else."""
    levels = mask_levels(shades)
    rows = []
    for row in art:
        pixels = []
        for char in row:
            if char == ".":
                pixels.append((0, 0, 0, 0))
            else:
                level = levels[int(char)]
                pixels.append((level, level, level, 255))
        rows.append(pixels)
    return rows


def png(path: str, rows: list[list[tuple[int, int, int, int]]]) -> None:
    """Write an RGBA PNG, by hand.

    Twenty lines of zlib instead of a Pillow import, because ``./gradlew art`` is the command that
    INSTALLS assets and a checkout without Pillow should still be able to run it. ``--swatch``, a
    review artifact that ships nowhere, is welcome to need it.
    """
    import struct
    import zlib

    raw = b"".join(b"\x00" + bytes(v for pixel in row for v in pixel) for row in rows)

    def chunk(tag: bytes, data: bytes) -> bytes:
        body = tag + data
        return struct.pack(">I", len(data)) + body + struct.pack(">I", zlib.crc32(body) & 0xFFFFFFFF)

    with open(path, "wb") as f:
        f.write(b"\x89PNG\r\n\x1a\n")
        f.write(chunk(b"IHDR", struct.pack(">IIBBBBB", len(rows[0]), len(rows), 8, 6, 0, 0, 0)))
        f.write(chunk(b"IDAT", zlib.compress(raw, 9)))
        f.write(chunk(b"IEND", b""))


def bucket_model(item: str) -> dict:
    """Vanilla's bucket with the mask laid over it.

    layer0 is vanilla's OWN sprite, untouched and untinted, so the metal of the bucket stays
    vanilla's metal and hydrarium never has to draw a bucket. layer1 is the mask, opaque over
    exactly the pixels layer0 draws contents in, so what is underneath is covered rather than
    blended with -- which is why the mask has to be vanilla's silhouette to the pixel.
    """
    return {
        "parent": "minecraft:item/generated",
        "textures": {
            "layer0": f"minecraft:item/{item}",
            "layer1": f"hydrarium:item/{item}_tint",
        },
    }


def bucket_override(item: str, art: list[str], shades: list[tuple[int, int, int]]) -> dict:
    """``assets/minecraft/items/<item>.json`` -- the vanilla assets hydrarium overrides.

    The condition is the point of the whole file. A bucket with no ``hydrarium:tint`` component
    takes ``on_false``, which is vanilla's own model naming vanilla's own sprite: an ordinary bucket
    in a world with hydrarium installed is not approximately vanilla, it is the same model reached
    by a different route. Only a bucket that actually holds a water pays for a second layer.

    ``tints`` is indexed by LAYER -- ``item/generated`` gives layer N tint index N -- so the first
    entry exists to leave layer0 alone and the second is the one that does the work. Drop the first
    and our tint source lands on the bucket's metal instead.

    There are two of these files and there is one function, which is the point: the frozen half's
    bucket is the water bucket's override with a different item id in it, and if the two ever want
    to differ, that difference should have to be written down here.
    """
    return {
        "model": {
            "type": "minecraft:condition",
            "property": "minecraft:has_component",
            "component": "hydrarium:tint",
            "on_false": {
                "type": "minecraft:model",
                "model": f"minecraft:item/{item}",
            },
            "on_true": {
                "type": "minecraft:model",
                "model": f"hydrarium:item/{item}_tinted",
                "tints": [
                    {"type": "minecraft:constant", "value": 0xFFFFFF},
                    {"type": "hydrarium:water_tint", "default": bucket_default(art, shades)},
                ],
            },
        }
    }


# Vanilla's own six faces, with the one thing vanilla left off them.
CUBE_FACES = {
    "down": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "down", "tintindex": 0},
    "up": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "up", "tintindex": 0},
    "north": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "north", "tintindex": 0},
    "south": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "south", "tintindex": 0},
    "west": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "west", "tintindex": 0},
    "east": {"uv": [0, 0, 16, 16], "texture": "#all", "cullface": "east", "tintindex": 0},
}

# And block/snow_height2's, which are not a cube: two pixels tall, and the sides show two pixels of
# the sprite rather than sixteen.
LAYER_FACES = {
    "down": {"uv": [0, 0, 16, 16], "texture": "#texture", "cullface": "down", "tintindex": 0},
    "up": {"uv": [0, 0, 16, 16], "texture": "#texture", "tintindex": 0},
    "north": {"uv": [0, 14, 16, 16], "texture": "#texture", "cullface": "north", "tintindex": 0},
    "south": {"uv": [0, 14, 16, 16], "texture": "#texture", "cullface": "south", "tintindex": 0},
    "west": {"uv": [0, 14, 16, 16], "texture": "#texture", "cullface": "west", "tintindex": 0},
    "east": {"uv": [0, 14, 16, 16], "texture": "#texture", "cullface": "east", "tintindex": 0},
}


def frozen_model(texture: str, shape: str) -> dict:
    """Vanilla's model with a tint index on it, and nothing else changed.

    hydrarium ships no block texture and no block model, and this is not one: it names VANILLA's
    sprite and it is only ever reached by an item model. The elements are written out rather than
    inherited because elements are inherited wholesale -- there is no "block/cube_all plus a tint
    index" to name -- which is the same wall FrozenModels hits in the world and answers after the
    bake. An item model has no equivalent seam, so the elements are transcribed here, once, from
    block/cube and block/snow_height2, and the parent stays vanilla's so that the display
    transforms (how a block sits in a hand, in a frame, in the inventory) stay vanilla's too.
    """
    if shape == CUBE:
        return {
            "parent": "minecraft:block/cube_all",
            "textures": {"all": texture},
            "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "faces": CUBE_FACES}],
        }
    return {
        "parent": "minecraft:block/thin_block",
        "textures": {"particle": texture, "texture": texture},
        "elements": [{"from": [0, 0, 0], "to": [16, 2, 16], "faces": LAYER_FACES}],
    }


def frozen_override(vanilla_model: str, tinted_model: str, frost_strength: float) -> dict:
    """``assets/minecraft/items/<item>.json`` -- bucket_override() at the other phase.

    Same condition, same component, same on_false-is-vanilla's-own-model claim: an ordinary block
    of ice in a world with hydrarium installed is not approximately vanilla, it is the same model
    reached by a different route, and only a stamped one pays for anything.

    ``tints`` is indexed differently from the buckets' and the difference is worth naming, because
    getting it wrong is silent both ways. An ``item/generated`` model is tinted by LAYER, so the
    buckets need a leading ``minecraft:constant`` to leave layer0 alone. A BLOCK model is tinted by
    the ``tintindex`` written on each face -- which is the one thing frozen_model() adds -- so
    index 0 is the only index there is and the list holds exactly one entry.

    THE SNOWBALL PASSES THE SAME MODEL TWICE, and that is not a mistake. Its picture is a SPRITE
    rather than a block, so vanilla's own item/snowball is item/generated with one layer -- and
    item/generated numbers its layers, so layer0 already has tint index 0. The five block-shaped
    items needed a model of their own for exactly one reason (vanilla writes no ``tintindex`` on
    block/ice or block/snow_height2) and a sprite item needs none: on_false and on_true name the
    same file and differ only by the ``tints`` list on one of them.

    The frost is in the file rather than in the source because it belongs to the SURFACE: one tint
    source serves every one of these items, and each of them tells it how much white its own sprite
    has already spent. See FrozenWater.Frost.
    """
    return {
        "model": {
            "type": "minecraft:condition",
            "property": "minecraft:has_component",
            "component": "hydrarium:tint",
            "on_false": {
                "type": "minecraft:model",
                "model": vanilla_model,
            },
            "on_true": {
                "type": "minecraft:model",
                "model": tinted_model,
                "tints": [
                    {"type": "hydrarium:frozen_tint", "frost": frost_strength},
                ],
            },
        }
    }


def check() -> list[str]:
    """The palette's own invariants. Returns a list of problems; empty is a pass."""
    problems = []

    seen = set()
    for name, tint, *_ in [(n, t) for n, t, _ in DYES] + [(n, t) for n, t, _, _ in EFFECTS]:
        if name in seen:
            problems.append(f"{name} is declared twice")
        seen.add(name)
        if not 0 <= tint <= 0xFFFFFF:
            problems.append(f"{name}: tint {tint} is not a plain 0xRRGGBB")

    for name, _tint, effect, _strength in EFFECTS:
        if effect not in KNOWN_EFFECTS:
            problems.append(f"{name}: effect '{effect}' is not one Effect.java has ({sorted(KNOWN_EFFECTS)})")

    for name, _tint, strength in DYES:
        if not 0.0 <= strength <= 1.0:
            problems.append(f"{name}: biome_strength {strength} is outside 0..1")
    for name, _tint, _effect, strength in EFFECTS:
        if not 0.0 <= strength <= 1.0:
            problems.append(f"{name}: biome_strength {strength} is outside 0..1")

    if len(DYES) != 16:
        problems.append(f"{len(DYES)} dyes, not 16 -- the cauldron reaches for all sixteen by name")

    # Every dye row is named after a real DyeColor, because the row's id is ALSO its "dye" and a
    # misspelt one fails silently in both directions at once: the cauldron reaches for
    # hydrarium:<name> and finds nothing, and Waters reads the "dye" field, does not recognise it,
    # and ships a water that will not mix. Neither is an error anywhere; both are just a colour
    # quietly doing less than it should.
    for name, _tint, _strength in DYES:
        if name not in VANILLA_DYES:
            problems.append(f"{name} is not one of vanilla's sixteen dyes")
    missing = VANILLA_DYES - {n for n, _t, _s in DYES}
    if missing:
        problems.append(f"no water for {sorted(missing)} -- that dye would do nothing in a cauldron")

    # The overlays' invariants. Every one of these breaks a sprite SILENTLY -- it still renders,
    # over the wrong pixels or in the wrong order, and only a screenshot would say so.
    for item, art, shades, expected in BUCKETS:
        if len(art) != 16 or any(len(row) != 16 for row in art):
            problems.append(f"{item}: the mask is not 16x16, which is the size of vanilla's sprite")
            continue
        painted = 0
        for y, row in enumerate(art):
            for x, char in enumerate(row):
                if char == ".":
                    continue
                if not char.isdigit() or int(char) >= len(shades):
                    problems.append(f"{item} {x},{y}: '{char}' is not an index into its shades")
                else:
                    painted += 1
        if painted != expected:
            problems.append(f"{item}: the mask paints {painted} pixels; vanilla's contents are {expected}")
        if sorted(shades, key=luminance) != shades:
            problems.append(f"{item}: its shades are not ordered dark to light -- the mask's digits"
                            " were assigned in that order, so reordering repaints the sprite"
                            " without saying so")

    # The frozen half. These pin the ONE number in it that was tuned rather than measured.
    for name, sprite, strength in FROZEN:
        if not 0 <= sprite <= 0xFFFFFF:
            problems.append(f"{name}: sprite average {sprite} is not a plain 0xRRGGBB")
        if not 0.0 <= strength <= 1.0:
            problems.append(f"{name}: frost {strength} is outside 0..1")

    # A tint is a multiply and a multiply can only darken, so a washed surface has a floor below
    # which its darkest water stops being a colour and becomes a hole. This is what the ice frost is
    # FOR, and this is the check that fails if somebody sets it back to zero: black water on plain
    # ice lands at luminance 21, and the wash is what lifts it to 76.
    #
    # THE ZERO-WASH SURFACES ARE EXEMPT, AND THE EXEMPTION IS THE CLAIM. The floor is a statement
    # about washing, and where there is no wash there is nothing to have got wrong; what has to be
    # argued is why those surfaces take none.
    #
    # Snow's argument is its sprite: #f9fefe, so the multiply is exact and black water is MEANT to
    # make black snow. Applying the floor to it would be this file telling FrozenWater.java that its
    # measurement was mistaken.
    #
    # The snowball's is one step longer, because #c7dede is NOT white -- it is a dimmer one, and a
    # naive reading of the floor would buy it a wash. It does not need one. The wash rescues dark
    # colours from a sprite that is BLUE: block/ice is 72% as bright as white and blue with it, so it
    # drags every water toward its own colour and turns the darkest into holes. #c7dede is barely
    # tinted at all -- it darkens every water by the same eighth and turns none of them a different
    # hue -- and a snowball ought to look like a lump of the snow it was shovelled out of. Washing it
    # would make a red snowball paler than the red drift beside it, bought in the one currency
    # FrozenWater says the wash is paid in. See FrozenItem.SNOWBALL, which is the same paragraph in
    # the other language.
    for name, sprite, strength in FROZEN:
        if strength == 0.0:
            continue
        for dye, tint, _strength in DYES:
            result = multiply(frost(tint, strength), sprite)
            if luminance(channels(result)) < FROZEN_FLOOR:
                problems.append(f"{name}: {dye} lands on #{result:06x}, below the readable floor"
                                f" -- raise that surface's frost")

    # The frozen items are pictures of frozen surfaces, and each one has to be washed by exactly as
    # much as the surface it is a picture of -- otherwise a block of ice in a hand is a different
    # colour from the same block one second later in the world, which is the kind of wrong nobody
    # reports because nobody believes their own eyes about it.
    frosts = {name: strength for name, _sprite, strength in FROZEN}
    for item, _vanilla, texture, shape, strength in FROZEN_ITEMS:
        # A SPRITE writes no model of ours, so a texture named for one would be a texture nothing
        # ever reads -- and a block shape without one would write a model naming nothing.
        if (texture is None) != (shape == SPRITE):
            problems.append(f"{item}: shape {shape} with texture {texture!r};"
                            " only a sprite item has no model of ours to name a texture in")
        surface = FROZEN_ITEM_SURFACE.get(item)
        if surface is None:
            problems.append(f"{item}: no FROZEN surface is declared for it")
        elif frosts.get(surface) != strength:
            problems.append(f"{item}: frost {strength}, but the surface it pictures"
                            f" ({surface}) is {frosts.get(surface)}")
        if shape not in (CUBE, LAYER, SPRITE):
            problems.append(f"{item}: unknown shape {shape}")

    return problems


# Three real biome water colours, so the swatch shows what the modulation actually does rather than
# what it does against a neutral grey. From the vanilla biome JSONs.
SWATCH_BIOMES = [
    ("ocean", 0x3F76E4),
    ("swamp", 0x617B64),
    ("warm ocean", 0x43D5EE),
]

# The frozen phase: surface name, the AVERAGE of vanilla's own sprite, and that surface's frost.
#
# Four of these are blocks and one -- the snowball -- is an item sprite, which is why the list is
# named after the PHASE and not after the blocks. A snowball is water that has stopped moving, seen
# in a hand; it is what a drift turns into when you shovel one; and its frost has to be looked at on
# the swatch beside the snow it came from, or it is a number nobody ever checked.
#
# The averages are measured, not chosen -- they are the mean of every opaque pixel in
# assets/minecraft/textures/block/<name>.png -- and they are here so that the swatch shows what the
# multiply really lands on rather than what it would land on against white. They are an
# approximation in one direction only: the real sprite has a range around this mean, so a swatch
# cell is the colour of that block's average pixel and not of any particular pixel.
#
# The frost column is one list in two languages, exactly like KNOWN_EFFECTS: FrozenWater.java holds
# the same numbers and check() is the only thing holding the two together. See that file for why the
# knob belongs to the surface and not to the water.
FROZEN = [
    ("snow", 0xF9FEFE, 0.00),
    ("snowball", 0xC7DEDE, 0.00),
    ("ice", 0x91B7FD, 0.15),
    ("packed ice", 0x8DB4FA, 0.15),
    ("blue ice", 0x74A7FD, 0.15),
]

# The frozen blocks a player can hold, which is the frozen half's answer to BUCKETS.
#
# A bucket needed a MASK because vanilla draws water inside it and that water is blue: a tint is a
# multiply, so colouring vanilla's own pixels would leave every water pale blue. These need none,
# and the difference is the whole reason this table is short. An ice item is vanilla's ice SPRITE,
# and the block in the world is that same sprite multiplied by the same number -- so the item is
# already the picture we want, and all that is missing is somewhere for the colour to land.
#
# Which is the trap FrozenModels documents, met a second time down the item path: vanilla puts NO
# tintindex on block/ice or block/snow_height2, and `tints` in an item model is keyed by exactly
# that index. The block half fixes it after the bake, where a resource pack's own ice model keeps
# working. An item model cannot be reached that way, so the fix here is a model of our own that is
# vanilla's elements with "tintindex": 0 written on every face -- which is why these carry a SHAPE
# rather than a parent to inherit from.
#
# ...and the SNOWBALL is where that stops being true, which is the one row worth reading twice. Its
# picture is a SPRITE rather than a block, and item/generated numbers its layers -- layer0 is tint
# index 0 -- so vanilla's own item/snowball already has somewhere for a colour to land. That row
# therefore writes NO model of ours at all: on_false and on_true both name minecraft:item/snowball
# and differ only by a tints list on one of them. The block-shaped five needed a model for exactly
# one reason and a sprite item does not have it.
#
# item id, the vanilla item model an UNSTAMPED one still uses, its texture (None for a SPRITE, which
# has no model of ours to put one in), its shape, and the frost of the surface it is a picture of.
# That frost is the same number FrozenWater.java holds, and check() is what holds the two together.
CUBE = "cube"
LAYER = "layer"
SPRITE = "sprite"

FROZEN_ITEMS = [
    ("ice", "minecraft:block/ice", "minecraft:block/ice", CUBE, 0.15),
    ("packed_ice", "minecraft:block/packed_ice", "minecraft:block/packed_ice", CUBE, 0.15),
    ("blue_ice", "minecraft:block/blue_ice", "minecraft:block/blue_ice", CUBE, 0.15),
    ("snow_block", "minecraft:block/snow_block", "minecraft:block/snow", CUBE, 0.00),
    ("snow", "minecraft:block/snow_height2", "minecraft:block/snow", LAYER, 0.00),
    ("snowball", "minecraft:item/snowball", None, SPRITE, 0.00),
]

# Which row of FROZEN each of them is a picture of, so that check() can hold the two tables
# together. Written out rather than matched on the id, because "snow_block" is a picture of "snow".
FROZEN_ITEM_SURFACE = {
    "ice": "ice",
    "packed_ice": "packed ice",
    "blue_ice": "blue ice",
    "snow_block": "snow",
    "snow": "snow",
    "snowball": "snowball",
}


# The luminance below which a frozen surface has stopped showing a colour and started showing a
# hole. Not a perceptual constant -- it is the number that makes the check above fail if the ice
# frost is removed, and it sits between black-on-plain-ice (21) and black-at-0.15 (45).
#
# It is deliberately a FLOOR and not a target. The wash is paid for in hue -- every step toward white
# is a step toward the sprite's own blue -- so the right strength is the least one that clears this,
# not the one that looks safest. See FrozenWater.Frost.ICY.
FROZEN_FLOOR = 40.0


def channels(colour: int) -> tuple[int, int, int]:
    return ((colour >> 16) & 0xFF, (colour >> 8) & 0xFF, colour & 0xFF)


def modulate(tint: int, biome: int, strength: float) -> int:
    """The client's arithmetic, transcribed. See WaterTint.modulate for why it is a blend."""
    out = 0
    for shift in (16, 8, 0):
        t = (tint >> shift) & 0xFF
        b = (biome >> shift) & 0xFF
        multiplied = t * b / 255.0
        c = max(0, min(255, round(t + (multiplied - t) * strength)))
        out |= c << shift
    return out


def frost(tint: int, strength: float) -> int:
    """FrozenTint.frost, transcribed: a per-channel lerp toward WHITE.

    Not modulate() against a white biome, which is what DESIGN.md's wording asks for and which does
    nothing at all: modulate blends a colour toward its own product with the target, and a colour
    times white is that colour. The frozen half needs the colour moved toward white before the
    sprite multiplies it back down, which is this.
    """
    out = 0
    for shift in (16, 8, 0):
        t = (tint >> shift) & 0xFF
        out |= max(0, min(255, round(t + (255 - t) * strength))) << shift
    return out


def multiply(tint: int, sprite: int) -> int:
    """What the renderer does with the number: one multiply, per channel, into the sprite."""
    out = 0
    for shift in (16, 8, 0):
        t = (tint >> shift) & 0xFF
        s = (sprite >> shift) & 0xFF
        out |= round(t * s / 255.0) << shift
    return out


def swatch(path: str) -> None:
    """Every water in every biome and every phase it may be seen in.

    A review artifact; it does not ship. It is also the only place the frozen arithmetic can be
    looked at rather than argued about, which is what the ice frost was chosen by: the last four
    columns are the same water frozen, and the eye can tell in one glance which strength turns red
    ice into mauve and which leaves it black.
    """
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        sys.exit("--swatch needs Pillow: python -m pip install pillow")

    rows = [(n, t, s) for n, t, s in DYES] + [(n, t, s) for n, t, _e, s in EFFECTS]
    columns = [(name, "liquid", colour, 0.0) for name, colour in SWATCH_BIOMES]
    columns += [(name, "frozen", sprite, strength) for name, sprite, strength in FROZEN]

    cell, pad, label, gap = 48, 4, 120, 16
    width = label + len(columns) * (cell + pad) + gap + pad
    height = pad + len(rows) * (cell + pad) + 24

    image = Image.new("RGB", (width, height), (24, 24, 28))
    draw = ImageDraw.Draw(image)

    def column_x(index: int) -> int:
        # One gap between the phases, so that "the same water, frozen" reads as a second block of
        # columns rather than as four more biomes.
        return label + index * (cell + pad) + (gap if index >= len(SWATCH_BIOMES) else 0)

    for i, (name, _phase, _colour, _strength) in enumerate(columns):
        draw.text((column_x(i), 6), name, fill=(200, 200, 200))

    for r, (name, tint, biome_strength) in enumerate(rows):
        y = 24 + pad + r * (cell + pad)
        draw.text((pad, y + cell // 2 - 6), name, fill=(200, 200, 200))
        for i, (_name, phase, colour, frost_strength) in enumerate(columns):
            x = column_x(i)
            if phase == "liquid":
                shown = modulate(tint, colour, biome_strength)
            else:
                # The renderer's whole frozen path in one line: wash, then let the sprite multiply.
                shown = multiply(frost(tint, frost_strength), colour)
            draw.rectangle([x, y, x + cell, y + cell], fill=channels(shown))

    image.save(path)
    print(f"swatch: {len(rows)} waters x {len(SWATCH_BIOMES)} biomes"
          f" + {len(FROZEN)} frozen surfaces -> {path}")


def write(out: str) -> None:
    catalogue_dir = os.path.join(out, "assets", "hydrarium", "hydrarium")
    lang_dir = os.path.join(out, "assets", "hydrarium", "lang")
    os.makedirs(catalogue_dir, exist_ok=True)
    os.makedirs(lang_dir, exist_ok=True)

    catalogue_path = os.path.join(catalogue_dir, "catalogue.json")
    with open(catalogue_path, "w", encoding="utf-8") as f:
        json.dump(catalogue(), f, indent=2)
        f.write("\n")

    lang_path = os.path.join(lang_dir, "en_us.json")
    with open(lang_path, "w", encoding="utf-8") as f:
        json.dump(lang(), f, indent=2)
        f.write("\n")

    # The buckets. Three files and one sprite each, and those sprites are the only art hydrarium has.
    texture_dir = os.path.join(out, "assets", "hydrarium", "textures", "item")
    model_dir = os.path.join(out, "assets", "hydrarium", "models", "item")
    # assets/MINECRAFT, not assets/hydrarium: these are the only vanilla assets this mod overrides,
    # and therefore the only place a resource pack can collide with it. See bucket_override().
    override_dir = os.path.join(out, "assets", "minecraft", "items")
    os.makedirs(texture_dir, exist_ok=True)
    os.makedirs(model_dir, exist_ok=True)
    os.makedirs(override_dir, exist_ok=True)

    print(f"catalogue: {len(DYES)} dyes + {len(EFFECTS)} effect waters -> {catalogue_path}")
    print(f"lang:      {len(lang())} entries -> {lang_path}")

    for item, art, shades, _expected in BUCKETS:
        overlay_path = os.path.join(texture_dir, f"{item}_tint.png")
        png(overlay_path, overlay(art, shades))

        model_path = os.path.join(model_dir, f"{item}_tinted.json")
        with open(model_path, "w", encoding="utf-8") as f:
            json.dump(bucket_model(item), f, indent=2)
            f.write("\n")

        override_path = os.path.join(override_dir, f"{item}.json")
        with open(override_path, "w", encoding="utf-8") as f:
            json.dump(bucket_override(item, art, shades), f, indent=2)
            f.write("\n")

        print(f"{item}: {sum(shade_counts(art, shades))} px over {len(shades)} shades, "
              f"clear fallback #{bucket_default(art, shades):06x}")
        print(f"           {overlay_path}")
        print(f"           {model_path}")
        print(f"           {override_path}")

    # The frozen items: one model of vanilla's own shape with a tint index on it, and one override
    # that reaches it only when the stack is stamped. No sprite, because there is nothing to mask.
    # See FROZEN_ITEMS -- and note the SPRITE rows, which write no model either, because a sprite
    # item's layers are already numbered and there is nothing for a model of ours to add.
    for item, vanilla_model, texture, shape, strength in FROZEN_ITEMS:
        if shape == SPRITE:
            tinted_model = vanilla_model
            model_path = None
        else:
            tinted_model = f"hydrarium:item/{item}_tinted"
            model_path = os.path.join(model_dir, f"{item}_tinted.json")
            with open(model_path, "w", encoding="utf-8") as f:
                json.dump(frozen_model(texture, shape), f, indent=2)
                f.write("\n")

        override_path = os.path.join(override_dir, f"{item}.json")
        with open(override_path, "w", encoding="utf-8") as f:
            json.dump(frozen_override(vanilla_model, tinted_model, strength), f, indent=2)
            f.write("\n")

        print(f"{item}: a {shape} of {texture or vanilla_model}, frost {strength}")
        if model_path:
            print(f"           {model_path}")
        print(f"           {override_path}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--out", help="resource root to write into (src/main/resources)")
    parser.add_argument("--preview", help="write everything HERE instead, installing nothing")
    parser.add_argument("--swatch", help="write a review PNG of every water in every biome")
    parser.add_argument("--check", action="store_true", help="run the palette's invariants and stop")
    args = parser.parse_args()

    problems = check()
    for problem in problems:
        print(f"FAIL {problem}", file=sys.stderr)
    if problems:
        return 1
    if args.check:
        print(f"ok: {len(DYES)} dyes + {len(EFFECTS)} effect waters, every effect known;"
              f" {len(BUCKETS)} masks; {len(FROZEN)} frozen surfaces above the floor;"
              f" {len(FROZEN_ITEMS)} frozen items washed like the surface they picture")
        return 0

    if args.swatch:
        swatch(args.swatch)
    if args.preview:
        write(args.preview)
    elif args.out:
        write(args.out)
    elif not args.swatch:
        parser.error("one of --out, --preview, --swatch or --check is required")
    return 0


if __name__ == "__main__":
    sys.exit(main())
