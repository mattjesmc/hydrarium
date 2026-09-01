# CLAUDE.md

A Fabric **water library** for Minecraft 26.2. Mod id `hydrarium`, package `com.mattjesmc.hydrarium`.
Sibling of `..\herbarium` (flora) and `..\menagerie` (fauna). First consumer: `..\rocketeer`.

**`README.md` is what the mod is and how a consumer uses it. The javadoc is the record** — it is
long, it is current, and it explains each decision at the seam that decision lives at. This file
carries only what neither can: how to build and run, and what breaks silently here.

`DESIGN.md` — the pre-Java argument — was removed at release. Javadoc that names it (fourteen
places) is quoting what the argument predicted before saying what turned out to be true; each of
those sentences still stands on its own.

---

## The one idea

**The fluid is expensive. The colour is free.**

hydrarium registers **zero fluids and zero blocks**. Tinted water is `minecraft:water`, tinted ice is
`minecraft:ice`, tinted snow is `minecraft:snow` — same tags, same buckets, same swim, same sponge,
same waterlogged stair. The colour is a side-channel (`TintField`, keyed by position) that the
renderer reads and nothing else in the game has to know about. `FrozenWater` is the one table both
halves read.

**An "improvement" that prices a colour at a fluid is not one.**

---

## Commands

```bash
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-25.0.3.9-hotspot"

./gradlew compileJava compileClientJava        # the fast check
./gradlew test                                 # 40 assertions, no game bootstrap, ~5s
./gradlew runServer -Psmoke                    # the IN-WORLD self-check; 29 checks, then it halts
./gradlew art                                  # regenerate the built-in catalogue and its assets
./gradlew runClient -Pmcmod.toolkit=false      # playtest, no MCP bridge

python authoring/gen_water.py --check                  # the palette's own invariants
python authoring/gen_water.py --swatch /tmp/s.png      # every water in three real biomes
```

- **`runServer -Psmoke` is the one that matters** after touching the fluid hook, the mixing rule,
  `TintField.holdsAnyWater`, `Containers.place`, the drop hook or `Crafting.assembled`. It drives
  what `src/test` structurally cannot: real recipes out of a real recipe manager, vanilla's own loot
  tables, vanilla's own freezing, and the flow hooks in a real ticking world.
- **`/water at` is the fastest way to answer "why is this water the wrong colour".** It names the
  layer that answered (field / biome / vanilla) *and* the block — red ice and red water are nearly
  the same colour from above.
- Versions live in the `com.mattmc.mcmod` convention plugin. A version number written into this file
  is a number that lies.
- **This repo's dev game binds 25645** (mcmodding 25640, menagerie 25641, rocketeer 25642, nijntje
  25643, herbarium 25644) — a list precisely because nothing enforces it.
- **`src/client/` is a separate source set** and nothing in it is on the test classpath by
  construction. That is what makes "the field half is server-safe" a compiler-checked claim.

## Nothing under `assets/` is edited by hand

All of it is output of `authoring/gen_water.py`; `./gradlew art` regenerates it. hydrarium ships **no
block texture and no block model** — there is nothing to draw. The two sprites are grey *masks* over
vanilla's bucket pixels (an item has no `BlockPos`, so the position-shaped surfaces cannot reach
one). The eight files under `assets/minecraft/items/` are the only vanilla assets this mod overrides
and the only place it can collide with a resource pack.

---

## Traps that are silent

- **Never guess a 26.2 API.** Decompiled vanilla is at `..\mcmodding\vanilla-src\`; `javap` the
  Fabric jars under `~/.gradle/caches`. Everything below was found by reading one of those two.

- **`spreadTo` fires ONCE per cell, not once per parent, and never again** — water can never be
  replaced with water, so the only `spreadTo` a cell gets is the one that created it. `Flow.tintOf`
  therefore gathers feeders **from the world at fill time** (`direction` is deliberately unused), and
  a **second hook**, `FlowingFluid.tick` at TAIL (`Flow.repaint`), is what lets a settled cell change
  colour at all. **A source holds a colour; a flowing cell derives one** — so `/water fill` over
  moving water holds only until each cell next ticks.

- **The repaint wave's `scheduleTick` must name `fluid.getType()`.** `ServerLevel.tickFluid` drops a
  scheduled tick whose type does not match the state at that position, so `Fluids.WATER` on a flowing
  cell does nothing at all, silently.

- **The feeder test must stay strictly greater.** `>=` makes the parent relation cyclic and a pool
  degrades to grey a tick at a time. The price is that two fronts resting at the same level cannot
  see each other, which `Flow.tintOf` pays by letting an equal-level neighbour contribute *its
  feeders'* colour — one indirection, still acyclic. Smoke check 21 pins it.

- **Clear absorbs in a RIVER and is neutral in a POT.** `WaterMix.join` is for flow (any clear parent
  wins, which is how a red pool bleeds into an ocean); `WaterMix.stir` is for containers (clear is
  neutral, which is how a dye colours a cauldron). Calling `join` from a container is what once left
  every tinted bottle downstream of a cauldron dead, with nothing logged.

- **`DyeColor.getMixedColor` returns a random parent when no recipe exists.** Correct for breeding
  sheep, a coin flip in a fluid tick. `DyeRecipes` transcribes the private honest half and answers
  grey instead. Its cache keys on `recipeAccess()` identity and every entry point is `synchronized`,
  because `spreadTo` can run on a worldgen thread.

- **Removal is not hooked, so ARRIVAL is.** The field is advisory and every read validates against
  the block actually there — airtight while only water could wear a colour. Ice and snow arrive by
  routes that write nothing, so a stale entry becomes the colour of the next block anybody places.
  `Containers.place` is the rule (**a placed block wears the colour its ITEM declared**), reached
  from `BlockItemMixin` and `DispensibleContainerItemMixin`; `ServerLevelMixin` is the same rule for
  falling snow. A phase change is *not* an arrival — freezing, melting and Frost Walker all inherit.
  Smoke check 15 pins both halves.

- **`holdsWater` vs `holdsAnyWater` is a deliberate choice per caller.** Waterlogging is why the
  placement test is the narrow one: a stair placed into a red pool stands *in* that water, a block of
  ice does not. Widen it and every kelp bed in a tinted ocean goes blue. And **`holdsAnyWater` must
  widen BEFORE the renderer learns a surface, never after** — otherwise the field sweeps itself away
  under a renderer that is perfectly correct, in silence.

- **A DROP reads the RAW entry**, because `destroyBlock` removes the block before `getDrops` runs, so
  the validating read would answer clear at a position that is now air. It is **not** cleared
  afterwards: breaking ice without silk touch turns it back into the water it came from. The drop is
  keyed by **item** (`FrozenItem`), not by the block that broke — a snow block drops snowballs, which
  have no block at all.

- **A crafting result is a fresh stack.** `ShapedRecipe.matches` tests items and is blind to
  components, so vanilla matched perfectly and the colour just stopped at the grid.
  `Crafting.assembled` carries it across, and `assemble` is the funnel every craft path reaches,
  preview included. A grid has no level and so no recipe book, which is why **two red and two blue
  snowballs make a GREY snow block where two red and two blue sources make purple** — a decision,
  pinned by a smoke check so that changing it is a decision too.

- **Spell full mixin descriptors for `assemble` and `BreakingItemParticle$Provider.createParticle`.**
  Both have a compiler-emitted bridge beside the real method; a bare `method = "name"` is ambiguous,
  and vanilla's own call sites invoke the bridge.

- **Eleven render surfaces, and getting one and missing another is silent.** The fluid model
  (`FluidRenderingRegistry`), the particles (`BlockColorRegistry` via `colorAsTerrainParticle`,
  **not** `colorInWorld` — `BUBBLE_COLUMN` has a real model vanilla draws untinted), the cauldron,
  the fog (a mixin — miss it and a red pool goes blue the moment your head goes under), the frozen
  blocks, the frozen items, and the impact flecks (`BreakingItemParticleMixin`, guarded by
  `FrozenItem` rather than by "has the component", because a tinted bottle carries one too).

- **Vanilla puts NO `tintindex` on ice or snow**, so a `BlockColorRegistry` registration alone is
  invisible, and no child model can add one (elements are inherited wholesale). `FrozenModels` adds
  it **after the bake** — and implements `BlockStateModel` directly, because Fabric's
  `WrapperBlockStateModel` delegates `emitQuads`, which is the entry point Indigo uses *instead of*
  `collectParts`: override `collectParts` on top of it and ice tints under vanilla's renderer and not
  under Indigo, with nothing logged.

- **`tints` in an ITEM model is indexed by LAYER; in a BLOCK model by the face's `tintindex`.** They
  live a line apart in the same generated file and the difference is silent both ways: the buckets
  need a leading `minecraft:constant` to leave layer0 alone, the five frozen items carry exactly one
  entry. **A SPRITE item needs no model of ours at all** — vanilla's one-layer `item/snowball`
  already has somewhere for a colour to land.

- **Opaque every colour you hand back.** `ARGB.multiply` multiplies alpha, and an `ItemTintSource`
  returns a vertex colour — a plain `0xRRGGBB` has alpha 0 and renders as nothing, which looks
  exactly like a registration that never happened and logs exactly as much.

- **The frost knob belongs to the SURFACE, not to the water.** A tint is a multiply and a multiply
  can only darken, so how far a colour must be washed toward white first is a fact about the sprite
  (`block/snow` averages `#f9fefe`, so `0.0`; `block/ice` averages `#91b7fd`, so `0.15`). **Buy as
  little wash as the floor allows** — every step toward white is a step toward the blue the sprite
  multiplies back in, and `0.35` made red ice mauve. Wash toward *pure* white, never an icy
  blue-white, and never `modulate` against white (`tint × white` is `tint`, so it does nothing).

- **An `ItemStack` cannot be constructed during mod init** — components are bound after entrypoints
  run, and the NPE names neither items nor components until the fourth frame. Hence
  `CauldronDispatcherInvoker` reaching the by-`Item` map and the dye mapping going through
  `Items.DYE.pick(colour)`.

- **A `static final` registration is not a registration until something loads the class.**
  `HydrariumComponents.install()` runs first in `onInitialize` for exactly this; without it
  `hydrarium:tint` lands in an already-frozen registry the first time a player scoops water, and the
  symptom is one ERROR during a resource reload plus a bucket that quietly renders untinted.

- **`ChunkTints.with` returning `this` unchanged is load-bearing** — `TintField.set` uses identity to
  avoid a sync packet *and* a section re-mesh on every tick over an already-red pool. And an empty
  field must be `removeAttached`, not `setAttached(EMPTY)`, or "an untinted world stores nothing"
  stops being true.

- **The client tint field is a mirror, and it is not duplication.** `colorInWorld` runs on the
  section-compile thread with no route back to the `ClientLevel`; `ClientTintField` makes the data
  update and the **re-mesh** one event instead of two, which matters because the tint is baked into
  vertex colour at compile time.

- **`BottleItem.use` needs MixinExtras** (`@ModifyExpressionValue` + `@Local`): the position it reads
  is a local, and the tint must be on the stack *before* `ItemUtils.createFilledResult` decides where
  that stack goes. Same reason the two cauldron interactions that draw water out are transcribed
  rather than wrapped, while `fillBucket` — which takes the new stack as a parameter — stays wrapped.

- **`BlockItem.place` ends by CONSUMING the stack**, so a TAIL injection reads a count of zero and the
  powder-snow bucket places tinted snow only while the player holds more than one. `BlockItemMixin`
  injects immediately before the `ItemStack.consume` call.

- **`Dispatcher.get` checks its TAG map first**, in `HashMap` order — the dyes are registered by
  `Item` so that "dye in a cauldron" versus "strip dye from leather" is not a coin flip.

- **`PotionBrewing.mix` returns a fresh stack**, so a tinted bottle's colour surviving brewing is
  free, unpinned by any test, and exactly the kind of free thing an update takes back. Re-read it
  every version — as with `FluidRenderer`'s tint source still being handed a `BlockPos`, which is the
  one line this whole design rests on.

- **26.2 names that are remembered wrong from older versions**: `ChunkPos` is a record (`x()`, `z()`,
  `pack()`); `ResourceKey.identifier()` (was `location()`); `Block.UPDATE_ALL` (not `Level.`);
  `IdentifierArgument.id()` (not `ResourceLocationArgument`); permission levels are
  `Commands.hasPermission(Commands.LEVEL_GAMEMASTERS)` objects, not ints; the creative-tab API is
  `api.creativetab.v1.CreativeModeTabEvents` with a `FabricCreativeModeTabOutput`.

- **The smoke rig lives at y=200 and forces nine chunks**, neither of which is arbitrary: at y=8 it is
  air in a superflat and stone in an ordinary world, and a trough in an unforced chunk may never get
  a fluid tick. Both failures read exactly like a broken spread hook.

---

## Deliberately not built

- **A tinted bucket item NAME**, and **tinted bottles in the creative menu**: both mean stamping a
  second component beside the tint and keeping it in step with the first. What to call a water is a
  question for whoever is drawing the screen, answered fresh in `WaterLabel`.
- **Tinted bubble columns, worldgen tinting by placement, per-seed variation, powder snow fog.**
- **The frozen items laid out in the tabs.** They are stamped and they place their colour, but they
  are `SEARCH_TAB_ONLY` — nineteen buckets is a row, a hundred and thirty-three frozen stacks is a
  wall. See `CreativeWaters.frozen`.

## Sharing this tree

Several Claude sessions may edit this checkout at once. The rules are `..\rocketeer`'s:

- **Commit by explicit path. Never `git add -A`, never `git add -u <dir>`.**
- **Two Gradle runs on one `build/` dir die with `EOFException`**, and it reads like a red suite.
- **Never compile or `:jar` while a dev game is running** — the jar swaps under the live JVM.
- **`gradlew --stop` is per-user** and takes other sessions' daemons and games with it.
- **`runServer` binds a port.** `run-server/server.properties` sets 25789 here.

## Housekeeping

- **This checkout is still `C:\Users\Matthijs\Watercraft` and is still NOT a git repository.**
  The published tree is a *separate clone* at `C:\Users\Matthijs\Documents\GitHub\hydrarium`
  (`github.com/mattjesmc/hydrarium`), and publishing means copying this tree into it and committing
  there. Nothing syncs the two — an edit made here is not published until it is copied across, and
  an edit made there is lost the next time it is.
- **`.gitattributes` pins `eol=lf`** and is not cosmetic: with Windows' default `core.autocrlf=true`
  a fresh clone rewrites `gradlew` to CRLF, and every POSIX shell then answers `bad interpreter`.
- Renaming this checkout to `hydrarium` is still the tidy end state — note that Claude sessions
  scope memory per opened directory, so the rename starts that memory over.
