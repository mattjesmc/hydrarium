<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/header.png" alt="hydrarium" width="860">
</p>

<p align="center"><i>Coloured water that is still water.</i></p>

<p align="center">
  <a href="https://github.com/mattjesmc/hydrarium/releases/latest"><img alt="GitHub release" src="https://img.shields.io/github/v/release/mattjesmc/hydrarium?style=for-the-badge&logo=github&logoColor=white&label=Release&color=2f6f8f"></a>
  <img alt="Loaders" src="https://img.shields.io/badge/Loader-Fabric-2f6f8f?style=for-the-badge">
  <img alt="Minecraft versions" src="https://img.shields.io/badge/Minecraft-26.2-2f6f8f?style=for-the-badge">
  <img alt="License" src="https://img.shields.io/badge/License-Source--available_(no_redistribution)-2f6f8f?style=for-the-badge">
</p>

<p align="center">
<b>Loaders:</b> Fabric &nbsp;•&nbsp; <b>Minecraft:</b> 26.2 &nbsp;•&nbsp; <b>Side:</b> Client & Server
</p>

---
<a id="about"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/about.png" alt="About" width="560">
</p>

**The fluid is expensive. The colour is free.**

A fluid is a registry entry, a block, a bucket, a tag membership, and a compatibility surface
with every mob, boat, sponge, fishing rod and waterlogged stair in the game. A colour is three
integers a renderer multiplies into a quad it was going to draw anyway.

So **hydrarium registers zero fluids and zero blocks.** Tinted water *is* `minecraft:water`;
tinted ice is `minecraft:ice`; tinted snow is `minecraft:snow`. Same tags, same buckets, same
swimming, same sponges, same waterlogged stairs. The colour is a side-channel the renderer reads
and nothing else in the game has to know about.

It ships **nineteen waters**: the sixteen dye colours, plus `ash_slurry`, `irradiated` and
`lumewater`, which carry behaviour as well as colour. A consumer mod adds its own by shipping
one JSON file — no Java.

---
<a id="features"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/features.png" alt="Features" width="560">
</p>

- **They are water, all the way down** — Tinted water is `minecraft:water`. It flows, it drowns you, a sponge soaks it up, a boat floats on it, a stair waterlogs in it, and a fishing rod works in it — because none of that is code this mod wrote.

- **They flow, and they blend on the way** — Red pouring into blue makes purple, decided by vanilla's own dye recipes. A pair with no recipe muddies to grey rather than guessing at one.

- **They freeze and they melt** — Ice, packed ice, blue ice, snow, snow layers and powder snow all keep the colour of the water they came from, and melting gives it back. Break ice without silk touch and it turns back into the water it was.

- **They fit in things** — Buckets, bottles, cauldrons and powder-snow buckets carry the colour, and a bucket's tooltip names its water. A dye dropped in a cauldron colours it; that cauldron then fills coloured bottles and buckets.

- **An untinted world stores no bytes** — Colour lives in a sparse per-position field, persisted in the chunk and synced only to whoever is watching it. A whole biome made of one water costs one catalogue row and nothing per position.

- **Nothing has to know** — A world saved with a consumer mod's waters and loaded without that mod shows plain water at those positions — and gets the colour back when the mod returns.

---
<a id="commands"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/commands.png" alt="Commands" width="560">
</p>

All `/water` subcommands need permission level 2.

| | |
|---|---|
| `/water give <targets> <water> [<count>]` | buckets, stamped with a water |
| `/water fill <from> <to> <water>` | repaint water that is **already there**; it places nothing. Ice and snow included — the only way to paint a frozen lake that was never tinted water |
| `/water clear <from> <to>` | take the paint off again |
| `/water at <pos>` | what water is here, which layer said so, and which block it is in |

**Paint the source, not the stream.** A source block holds the colour you give it; a *flowing*
cell derives its colour from whatever feeds it, and re-derives it the next time it ticks.

---
<a id="catalogue"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/catalogue.png" alt="Adding your own waters" width="560">
</p>

A consumer mod writes **no Java**. Ship one file in your own jar:

```
assets/<your mod id>/hydrarium/catalogue.json
```

The path is keyed on *your* mod id, which is what puts your water ids in your namespace.
hydrarium's own nineteen are read through this same scanner from this same path in
hydrarium's own jar — there is no private path for the built-ins.

```json
{
  "waters": [
    { "id": "lumewater", "tint": 4251856, "effect": "glow", "biome_strength": 0.1 },
    { "id": "brine",     "tint": 7385292, "dye": "light_blue" }
  ],
  "biomes": [
    { "biome": "yourmod:glow_sea", "water": "yourmod:lumewater" }
  ]
}
```

**`waters`** declares colours:

| field | | |
|---|---|---|
| `id` | required | path only; the namespace is your mod id |
| `tint` | required | a plain `0xRRGGBB` written as a **decimal** integer |
| `effect` | optional | one of `none`, `glow`, `ash`, `decay`. An unknown name costs that water its behaviour, keeps its colour, and logs — it never fails the catalogue |
| `biome_strength` | optional, default `0.25` | how much of the biome's own water colour bleeds in. `0` is this colour exactly, everywhere; `1` is vanilla's straight multiply |
| `dye` | optional | the dye this water **is**. Naming one of the sixteen puts this water into dye mixing without it having to be *named* after a dye. A water with no `dye` muddies to grey against anything but itself |

**`biomes`** says which biome is made of which water, and **stores nothing** — an entire
planet's green ocean is one row here and not one byte in any chunk. A biome row may name a
water some *other* mod's catalogue declared; the whole scan resolves before any of it is
wired.

**Effects are a closed set in Java on purpose.** A tint is a number, so a new colour is a row;
an effect is code. A catalogue may choose from the set and parameterise it, which is what
keeps "a consumer writes no Java" true rather than nearly true.

### Resolution order

A position's colour is decided in three layers, and `/water at` reports which one answered:

1. **the field** — a per-position entry, sparse, persisted in the chunk and synced to whoever
   is watching it. The only layer that costs bytes.
2. **the biome** — a catalogue row. Costs nothing per position.
3. **vanilla** — ordinary water, in the ordinary way.

---
<a id="gallery"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/gallery.png" alt="Gallery" width="560">
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/palette.png" alt="The nineteen built-in waters. Every one of them is `minecraft:water`.">
  <br><sub><i>The nineteen built-in waters. Every one of them is `minecraft:water`.</i></sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/mixing.png" alt="A red stream and a blue stream meet, and everything downstream runs purple.">
  <br><sub><i>A red stream and a blue stream meet, and everything downstream runs purple.</i></sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/frozen.png" alt="Ice, packed ice, blue ice, snow, snow layers and powder snow, each keeping the colour of the water it froze from.">
  <br><sub><i>Ice, packed ice, blue ice, snow, snow layers and powder snow, each keeping the colour of the water it froze from.</i></sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/cauldrons.png" alt="A cauldron holds a colour, and fills bottles and buckets with it.">
  <br><sub><i>A cauldron holds a colour, and fills bottles and buckets with it.</i></sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/underwater.png" alt="Under the surface the fog goes with it — a purple pool does not look blue from inside.">
  <br><sub><i>Under the surface the fog goes with it — a purple pool does not look blue from inside.</i></sub>
</p>

<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/gallery/items.png" alt="Buckets, bottles, ice, snow blocks and snowballs all carry their water's colour.">
  <br><sub><i>Buckets, bottles, ice, snow blocks and snowballs all carry their water's colour.</i></sub>
</p>

---
<a id="dependencies"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/dependencies.png" alt="Dependencies" width="560">
</p>

**Required**

| Mod | Version | Notes |
| --- | --- | --- |
| [Fabric Loader](https://fabricmc.net/use/) | >=0.19.3 |  |
| [Fabric API](https://modrinth.com/mod/fabric-api) | — | Data attachments, fluid rendering, block colours, chunk lifecycle |
| [Java](https://adoptium.net/) | 25 |  |

---
<a id="incompatibilities"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/incompatibilities.png" alt="Incompatibilities" width="560">
</p>

| Mod | Conflict | Workaround |
| --- | --- | --- |
| Resource packs that retexture the water bucket | hydrarium overrides eight files under `assets/minecraft/items/` — the buckets and the frozen items — to give their colour somewhere to land. It is the only place this mod writes outside its own namespace.
 | Load hydrarium above the pack, or drop the pack's bucket item models. |
| Mods that replace water's fluid model | The colour arrives through `FluidRenderingRegistry`. A mod that registers its own model for `minecraft:water` after hydrarium wins, and tinted water draws plain.
 | None yet. Report it and it can be looked at. |

---
<a id="installation"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/installation.png" alt="Installation" width="560">
</p>

1. Install [Fabric Loader](https://fabricmc.net/use/) 0.19.3 or newer, on Java 25.
2. Drop `hydrarium.jar` and [Fabric API](https://modrinth.com/mod/fabric-api) into `mods/`.
3. Launch. There is nothing to configure.

Install it on **both sides** for a server: the field is stored and mixed server-side and drawn
client-side, and a client without it simply sees ordinary water.

---
<a id="building"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/building.png" alt="Building" width="560">
</p>

```bash
export JAVA_HOME=".../jdk-25"

./gradlew build                  # the jar, in build/libs/
./gradlew test                   # 40 assertions, no game bootstrap
./gradlew runClient              # playtest
./gradlew runServer -Psmoke      # 29 in-world checks, then it halts
```

Minecraft, loader and Fabric API versions come from the `com.mattmc.mcmod` convention plugin,
not from this repo. `CLAUDE.md` has the rest of the build surface, and what breaks silently.

---
<a id="faq"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/faq.png" alt="FAQ" width="560">
</p>

**Does this add a new fluid?**

No — and that is the whole design. Adding a fluid means adding a block, a bucket, tag memberships, and a compatibility surface with every mob, boat and sponge in the game. Tinted water is `minecraft:water` wearing a colour, so all of that keeps working for free.

**My water is the wrong colour. Why?**

Run `/water at <pos>`. It names the layer that answered — the per-position field, a biome row, or vanilla — and the block it is in, which matters because red ice and red water look nearly the same from above.

**I painted a stream and it went back to normal.**

Paint the source, not the stream. A source block *holds* a colour; a flowing cell *derives* one from whatever feeds it, and re-derives it the next time it ticks. Paint the source and let the wave carry it.

**What happens to a world if I remove the mod?**

Nothing breaks. Those positions are ordinary water, ice and snow, and they render as ordinary water, ice and snow. Put the mod back and the colour is still there.

**Two red and two blue snowballs made a grey snow block. Bug?**

A decision, and a pinned one. A crafting grid has no level and so no recipe book to ask about dye mixing, so the grid muddies to grey where two red and two blue *sources* would make purple.

---
<a id="credits"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/credits.png" alt="Credits" width="560">
</p>

Built on [Fabric](https://fabricmc.net/) and [Fabric API](https://github.com/FabricMC/fabric).
The sixteen dye colours are vanilla's own `DyeColor` values and the mixing table is vanilla's
own dye recipes — so red water is the same red as a red bed.

---
<a id="license"></a>
<p align="center">
  <img src="https://raw.githubusercontent.com/mattjesmc/hydrarium/main/assets/banners/license.png" alt="License" width="560">
</p>

**Source-available, not redistributable.** Play with it, modify it for your own use, send
patches. Re-uploading it or bundling it into a modpack needs written permission. See
[`LICENSE`](LICENSE) for the exact terms.

---

Issues and patches welcome.
