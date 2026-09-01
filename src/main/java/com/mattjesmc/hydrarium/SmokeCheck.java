package com.mattjesmc.hydrarium;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.LayeredCauldronBlock;

/**
 * A headless in-world self-check, off unless you ask for it.
 *
 * <pre>
 *   ./gradlew runServer -Psmoke
 * </pre>
 *
 * <p><b>Why this exists at all.</b> {@code src/test} pins the algebra and the codec with no game,
 * which is the right posture and covers the parts that are pure. It cannot cover the parts that are
 * not: whether {@code spreadTo} actually carries a colour along a real flow driven by real fluid
 * ticks, whether the attachment survives a real chunk, and whether the advisory read really does
 * answer clear at a position a block was removed from. Those are the places this mod is most likely
 * to be wrong, and none of them can be reached without a world.
 *
 * <p>The workspace's own idiom, borrowed from rocketeer's {@code -Drocketeer.debugMeteors}: a
 * property nobody sets in a real game, guarding code that does nothing until they do. It is shipped
 * rather than kept in a test source set because it needs a running server, and a running server is
 * something this repo has and a test JVM does not.
 *
 * <h2>What it asserts</h2>
 *
 * <ol>
 *   <li><b>Round trip.</b> A tint written to a position reads back from it — the attachment, the
 *       chunk and the validating read agreeing at all.</li>
 *   <li><b>Advisory.</b> Remove the water and the read answers clear <em>while the entry is still
 *       there</em>. This is the whole "nothing is hooked on removal" design in one assertion, and
 *       it is the one that fails if somebody later "fixes" the read to trust the map.</li>
 *   <li><b>Flow carries colour.</b> A red source's flow is red two cells along. This is the
 *       {@code spreadTo} hook, driven by the game's own fluid ticks rather than by a call.</li>
 *   <li><b>Clear absorbs.</b> A red flow and a clear flow meet head-on and the meeting is clear —
 *       the dilution half of {@link WaterMix}, in a world, at whatever tick order the game chose.</li>
 *   <li><b>Blending.</b> A source born of a red source and a blue source is <em>purple</em>, because
 *       red dye and blue dye craft into purple dye. This is the recipe lookup, the dye indexing and
 *       the source-conversion branch at once.</li>
 *   <li><b>Muddying.</b> A source born of a red source and a green source is grey: the game has no
 *       red-plus-green recipe, and an unmixable pair has to land somewhere visible rather than
 *       vanishing.</li>
 *   <li><b>Inheritance.</b> A source born of two red sources is red.</li>
 *   <li><b>The cauldron.</b> A water cauldron holds a tint like any other position, and stirring a
 *       dye into clear water in one <em>colours</em> it. That second half is the bug this rig grew
 *       an assertion for: the cauldron used to call the join, {@code join(clear, red)} is clear, and
 *       so everything a cauldron could produce quietly did nothing at all.</li>
 *   <li><b>The phase change.</b> A tinted water source that becomes ice still answers red, and one
 *       that becomes air does not. That pair is the whole of the frozen half's server side: the
 *       colour of ice was already in the field and always had been, and what was missing was a
 *       predicate that would admit it. The second half of the pair is what keeps the first from
 *       being satisfied by a read that stopped validating.</li>
 *   <li><b>Snow, and the melt.</b> A tint sits on a snow layer; ice that melts back to water is
 *       still the colour it froze at. The second is the free win in {@link TintField} finally being
 *       observed rather than asserted about.</li>
 *   <li><b>The powder snow cauldron</b>, which is the water cauldron's assertion at the other
 *       phase.</li>
 *   <li><b>The sweep agrees with the read.</b> Run it, and the frozen entry survives while the dry
 *       one is dropped. This is the one that would have caught the frozen half's worst failure
 *       mode: a renderer taught to colour ice, reading a field that had already deleted itself on
 *       the last chunk load, with nothing logged because a swept entry is routine.</li>
 *   <li><b>The drop.</b> A red snow block shovels into red <em>snowballs</em>, a red block of ice
 *       silk-touches into red ice, and a plain snow block does neither. This one runs the game's own
 *       loot tables, which {@code src/test} cannot: a loot table needs a server, a reloadable
 *       registry and a random sequence, and the answer it gives is the point — the item that falls
 *       out of a drift is not the item the drift was, so "which block was this" cannot decide which
 *       stack may wear a colour. The plain control is what stops "the drop is untinted" from being
 *       satisfied by a hook that never fired.</li>
 *   <li><b>The craft.</b> Four red snowballs make a red snow block, nine blocks of red ice make red
 *       packed ice, and every one of vanilla's four frozen recipes is run through the <em>real</em>
 *       recipe manager rather than reasoned about. This is the same reason the drop is here: the
 *       recipes are data, they are reloadable, and {@code src/test} has no recipe book to look them
 *       up in. Its controls are a plain batch (which must stay plain) and a batch with one plain
 *       snowball in it (which must come out plain, because clear absorbs) — the second being the one
 *       that stops "the craft is tinted" from being satisfied by a hook that stamps whatever it
 *       finds first.</li>
 *   <li><b>The repaint.</b> Recolour a source whose flow has <em>already settled</em> and the flow
 *       follows. This is the only assertion here that needs two settles, and it is the only shape
 *       that can catch what v1 got wrong: a cell is filled once, {@code spreadTo} never visits it
 *       again, and so every colour a player changed after the fact stopped dead at the source it
 *       was written to. Its control is the same cell read a settle earlier, still red.</li>
 *   <li><b>The stalemate.</b> An <b>even</b>-length trough with a source at each end settles as
 *       {@code src 7 6 5 5 6 7 src}, and the two middle cells are adjacent at the same level with
 *       neither strictly higher than the other. Under the feeder test alone they cannot see each
 *       other and never blend, at any tick order, forever — so whether two colours meeting produce
 *       a blend was decided by the trough's <em>parity</em>. Both middle cells must come out
 *       purple; cell 1 must still be red, which is what stops a lateral rule from being satisfied
 *       by colour leaking uphill.</li>
 * </ol>
 *
 * <h2>Every assertion here is paired with a control, and that is not padding</h2>
 *
 * Half of these are satisfied by an empty world. "The meeting is clear" is true of water that was
 * never coloured, and so is "the field says nothing here". So each one shares its rig with an
 * assertion that can only hold if something really is coloured — 3 with 4, and 7 with 5 and 6.
 *
 * <p>That pairing is what caught the bug this file exists to have caught. {@code spreadTo} fires
 * <b>once</b> per cell rather than once per parent (see {@code FlowingFluidMixin}), so an
 * implementation that reads {@code direction} paints every cell with whichever front arrived first
 * and never joins anything. Assertion 4 passed against it. Assertion 3 is what failed.
 */
public final class SmokeCheck {

    /** Set {@code -Dhydrarium.smoke=true} to arm it. Absent in every real game. */
    public static final String PROPERTY = "hydrarium.smoke";

    /**
     * Ticks to let the water settle before checking the flow assertions.
     *
     * <p>Water advances one cell per fluid tick and vanilla's water tick delay is 5, so four cells
     * is twenty ticks at best — and sixty, which sounds like ample headroom, is not: a run on a
     * freshly generated world spends its first seconds generating chunks and the channel was still
     * half empty at tick 60. Two hundred is chosen to be boring. The <b>real</b> guard is that
     * every assertion below refuses to look at a cell with no water in it, so a slow run fails
     * loudly instead of passing vacuously; this number only decides how often that happens.
     */
    private static final int SETTLE_TICKS = 200;

    /** Cells in the channel, {@code 0} and {@code LENGTH - 1} being the two sources. */
    private static final int CHANNEL_LENGTH = 9;

    /**
     * A patch of nothing, well away from spawn and <b>above any terrain in any world type</b>, so
     * the rig is always built into air. The rig grew past one chunk when the murk trough was added,
     * so {@code begin} forces the nine around it rather than the one it is in.
     *
     * <p>The height is not arbitrary and was paid for: an earlier version sat at y=8, which is air
     * in a superflat world and solid stone in an ordinary one. The rig carves its own channel
     * either way, so it looked like it should not matter — and the result was a world where the two
     * sources existed and nothing flowed between them, which reads exactly like a broken spread
     * hook. A harness that only works under one server.properties is a harness that will one day
     * report a bug that is not there.
     */
    private static final BlockPos ORIGIN = new BlockPos(512, 200, 512);

    private static final BlockPos ROUND_TRIP = ORIGIN.offset(0, 1, 6);
    private static final BlockPos ADVISORY = ORIGIN.offset(0, 1, 9);

    /**
     * The frozen row, three cells west of the channel so that nothing in it can be reached by water
     * flowing out of the rig. Every one of these is answered in {@code begin} — a phase change is a
     * {@code setBlock} and needs no ticks, which is why none of them wait for the settle.
     */
    private static final BlockPos FROZEN_X = ORIGIN.offset(-3, 1, 0);
    private static final BlockPos THAWED = ORIGIN.offset(-3, 1, 2);
    private static final BlockPos SNOWED = ORIGIN.offset(-3, 1, 4);
    private static final BlockPos MELTED = ORIGIN.offset(-3, 1, 6);
    private static final BlockPos SNOW_CAULDRON = ORIGIN.offset(-3, 1, 8);
    private static final BlockPos SWEPT_FROZEN = ORIGIN.offset(-3, 1, 10);
    private static final BlockPos SWEPT_DRY = ORIGIN.offset(-3, 1, 12);
    private static final BlockPos PLACED_PLAIN = ORIGIN.offset(-3, 1, 14);
    private static final BlockPos PLACED_TINTED = ORIGIN.offset(-3, 1, 16);
    private static final BlockPos FREEZE_TINTED = ORIGIN.offset(-3, 1, 18);
    private static final BlockPos FREEZE_PLAIN = ORIGIN.offset(-3, 1, 20);
    private static final BlockPos SHOVELLED = ORIGIN.offset(-3, 1, 22);
    private static final BlockPos SHOVELLED_PLAIN = ORIGIN.offset(-3, 1, 24);
    private static final BlockPos SILKED = ORIGIN.offset(-3, 1, 26);

    /**
     * The two source-conversion troughs, three cells long: a source at each end and the cell
     * between them converting to a source of its own under vanilla's {@code neighbourSources >= 2}
     * rule. One trough is fed two different colours and one is fed the same colour twice.
     */
    private static final int BLEND_Z = 12;
    private static final int INHERIT_Z = 14;
    private static final int MURK_Z = 16;

    /**
     * The repaint trough: one red source and four cells of flow, whose source is recoloured
     * <em>after</em> the water has already settled.
     *
     * <p>This is the only rig here that needs a second settle, and it is the only one that can
     * catch the bug it exists for: a cell is filled once and v1 painted it once, so every colour a
     * player changed after the fact stopped at the source it was written to. Nothing in the
     * single-settle rig can see that, because in a single settle the fill and the colour are the
     * same event.
     */
    private static final int REPAINT_Z = 18;
    private static final int REPAINT_LENGTH = 5;

    /**
     * The stalemate trough, and its length is the assertion.
     *
     * <p>Eight cells, sources at each end, so the flow settles as {@code src 7 6 5 5 6 7 src}: the
     * two middle cells are adjacent, at the <b>same</b> level, and neither is strictly higher than
     * the other. Under a feeder test that is strictly greater — which it must be, see {@link Flow}
     * — they cannot see each other at all, and two colours meeting head-on never blend at any tick
     * order, forever.
     *
     * <p>Nine cells, which is what the main channel is, has a unique lowest cell fed from both
     * sides and blends without any of this. That the answer depends on the channel's parity is the
     * whole reason this trough is here next to that one.
     */
    private static final int STALEMATE_Z = 20;
    private static final int STALEMATE_LENGTH = 8;

    private static final BlockPos CAULDRON = ORIGIN.offset(4, 1, 6);

    private static final Identifier RED = Hydrarium.id("red");
    private static final Identifier BLUE = Hydrarium.id("blue");
    private static final Identifier GREEN = Hydrarium.id("green");
    private static final Identifier PURPLE = Hydrarium.id("purple");
    private static final Identifier GRAY = Hydrarium.id("gray");

    private static final List<String> RESULTS = new ArrayList<>();
    private static int ticks = -1;

    /**
     * Which settle the rig is on. Everything except the repaint is answered in the first; the
     * repaint needs a second, because its claim is about what happens to water that has already
     * stopped moving.
     */
    private static int phase = 1;

    private SmokeCheck() {
    }

    /** Wire it, if it was asked for. Called from {@code onInitialize}. */
    public static void installIfRequested() {
        if (!Boolean.getBoolean(PROPERTY)) {
            return;
        }
        Hydrarium.LOG.warn("hydrarium: -D{} is set; this server will build a test rig, check itself"
                + " and shut down. Do not do this to a world you care about.", PROPERTY);
        ServerLifecycleEvents.SERVER_STARTED.register(SmokeCheck::begin);
        ServerTickEvents.END_SERVER_TICK.register(SmokeCheck::tick);
    }

    private static void begin(final MinecraftServer server) {
        final ServerLevel level = server.overworld();

        // The chunk has to be loaded for any of this to mean anything: TintField.set declines to
        // create one, which is correct behaviour and would make every assertion below vacuous.
        // Every chunk the rig touches, and not just the one ORIGIN is in. The troughs now reach
        // z+17, which is over the chunk border, and a trough in an unforced chunk is a trough whose
        // water may never get a fluid tick -- which reads exactly like a broken spread hook, in the
        // same way y=8 once did.
        for (int cx = -1; cx <= 1; cx++) {
            for (int cz = -1; cz <= 1; cz++) {
                level.setChunkForced((ORIGIN.getX() >> 4) + cx, (ORIGIN.getZ() >> 4) + cz, true);
            }
        }

        channel(level, 0, CHANNEL_LENGTH);
        channel(level, BLEND_Z, 3);
        channel(level, INHERIT_Z, 3);
        channel(level, MURK_Z, 3);
        channel(level, REPAINT_Z, REPAINT_LENGTH);
        channel(level, STALEMATE_Z, STALEMATE_LENGTH);
        bowl(level, ROUND_TRIP);
        bowl(level, ADVISORY);
        level.setBlock(CAULDRON, Blocks.WATER_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);

        // (1) round trip.
        put(level, ROUND_TRIP, RED);
        check("round trip: a tint written to a water source reads back",
                RED.equals(TintField.id(level, ROUND_TRIP)));

        // (2) advisory. Take the water away WITHOUT clearing the entry -- which is exactly what a
        // sponge, a piston or another mod does -- and the read must answer clear anyway, while the
        // raw entry is still sitting there.
        put(level, ADVISORY, RED);
        level.setBlock(ADVISORY, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        check("advisory: a stale entry at a dry position answers clear",
                TintField.id(level, ADVISORY) == null && RED.equals(TintField.rawId(level, ADVISORY)));

        // (3) and (4) need water to actually flow, so they wait.
        //
        // THE CHANNEL'S ARITHMETIC IS THE TEST. A source is level 8 and water loses one level per
        // cell, so from x=0 the levels run 7, 6, 5, 4 and from x=8 they run the same way back:
        // cell 4 is the ONLY cell both fronts reach, and they reach it at the same level from
        // opposite sides. That makes it a genuine two-parent cell rather than a boundary between
        // two one-parent cells -- which is what an asymmetric channel gives, and which would let
        // the dissolve assertion pass with nothing ever having joined.
        //
        // Cell 2 is fed only from the red side and the clear front never reaches it. It is the
        // control: both assertions holding at once means colour is being carried AND colours are
        // dissolving, rather than everything quietly being clear.
        // (7): the cauldron. Its first half is the same round trip as (1) at a block that is not
        // water -- TintField.holdsWater answers for a water cauldron too, and if it ever stopped,
        // every cauldron in the game would go clear with nothing logged. Its second half is a call
        // rather than a right-click, because a headless server has no player to hold the dye: what
        // it pins is the RULE the interaction reaches for, which is the half that was wrong, next to
        // the river rule that must stay the other way round.
        TintField.set(level, CAULDRON, RED);
        check("cauldron: a water cauldron holds a tint at its own position",
                RED.equals(TintField.id(level, CAULDRON)));
        check("stir vs join: a dye added to clear water colours it, and a river still dissolves",
                RED.equals(WaterMix.stir(level, null, RED)) && WaterMix.join(level, null, RED) == null);

        put(level, ORIGIN.offset(0, 1, 0), RED);
        put(level, ORIGIN.offset(CHANNEL_LENGTH - 1, 1, 0), null);

        // (5), (6) and (7): the infinite water spreader. Two sources one apart over a solid floor
        // make a THIRD source in the gap, forever, at no cost, so whatever colour that new source
        // takes is a colour a player can manufacture out of nothing. Under the flat lattice the
        // danger was inheriting ONE parent -- a red bucket and a blue bucket making free red water.
        // Under blending it is milder and still worth pinning: free purple is as free as free red,
        // and the branch that computes it is the same one.
        //
        // The three troughs have to be read together. On its own, "the blended cell is purple" says
        // nothing about the source-conversion branch being reached at all -- purple could be the
        // answer to anything -- so the third trough, same colour both sides and MUST come out that
        // colour, is what proves the other two are measuring the parents rather than a constant.
        frozen(level);
        crafted(level);

        put(level, ORIGIN.offset(0, 1, BLEND_Z), RED);
        put(level, ORIGIN.offset(2, 1, BLEND_Z), BLUE);
        put(level, ORIGIN.offset(0, 1, INHERIT_Z), RED);
        put(level, ORIGIN.offset(2, 1, INHERIT_Z), RED);
        put(level, ORIGIN.offset(0, 1, MURK_Z), RED);
        put(level, ORIGIN.offset(2, 1, MURK_Z), GREEN);

        // (20) and (21): the two troughs about flow that has already stopped moving. Both are
        // filled here and read after the settle; the repaint's source is recoloured in between.
        put(level, ORIGIN.offset(0, 1, REPAINT_Z), RED);
        put(level, ORIGIN.offset(0, 1, STALEMATE_Z), RED);
        put(level, ORIGIN.offset(STALEMATE_LENGTH - 1, 1, STALEMATE_Z), BLUE);

        ticks = SETTLE_TICKS;
    }

    private static void tick(final MinecraftServer server) {
        if (ticks < 0 || --ticks > 0) {
            return;
        }
        final ServerLevel level = server.overworld();

        if (phase == 2) {
            repainted(level);
            report(server, level);
            return;
        }

        // Both of these check that the cell HOLDS WATER before checking its colour, and that is not
        // belt and braces -- it is the assertion. "The meeting cell is clear" is trivially true of a
        // cell the water has not reached yet, and a run on a freshly generated world produced
        // exactly that: a green tick for a channel that was still half empty. An assertion that
        // passes when nothing happened is worse than no assertion, because it is believed.
        final BlockPos downstream = ORIGIN.offset(2, 1, 0);
        check("flow: a red source colours its flow two cells downstream",
                filled(level, downstream) && RED.equals(TintField.id(level, downstream)));

        final BlockPos meeting = ORIGIN.offset(4, 1, 0);
        check("join: a red flow meeting a clear flow head-on dissolves",
                filled(level, meeting) && TintField.id(level, meeting) == null);

        final BlockPos blended = ORIGIN.offset(1, 1, BLEND_Z);
        check("blend: a source born of a red and a blue source is purple",
                level.getFluidState(blended).isSource() && PURPLE.equals(TintField.id(level, blended)));

        final BlockPos muddied = ORIGIN.offset(1, 1, MURK_Z);
        check("murk: a source born of a red and a green source is grey",
                level.getFluidState(muddied).isSource() && GRAY.equals(TintField.id(level, muddied)));


        final BlockPos inherited = ORIGIN.offset(1, 1, INHERIT_Z);
        check("inheritance: a source born of two red sources is red",
                level.getFluidState(inherited).isSource() && RED.equals(TintField.id(level, inherited)));

        // (21) the stalemate. Two fronts that come to rest at the SAME level, which is what an
        // even-length trough gives and what a player pouring two buckets gets about half the time.
        // Neither middle cell is strictly higher than the other, so under the feeder test alone
        // they are invisible to each other and never blend -- at any tick order, forever. Both must
        // come out purple, and BOTH is the assertion: one of them being purple is also what a rule
        // that painted the boundary from whichever side won the race would say.
        //
        // Cell 1 is the control. It is fed by the red source alone and must stay red, because "the
        // trough is purple" is also what a lateral rule that leaked colour uphill would produce.
        final BlockPos stalemateRed = ORIGIN.offset(3, 1, STALEMATE_Z);
        final BlockPos stalemateBlue = ORIGIN.offset(4, 1, STALEMATE_Z);
        final BlockPos stalemateControl = ORIGIN.offset(1, 1, STALEMATE_Z);
        check("stalemate: two fronts meeting at the same level blend, and upstream stays put",
                filled(level, stalemateRed) && filled(level, stalemateBlue)
                        && PURPLE.equals(TintField.id(level, stalemateRed))
                        && PURPLE.equals(TintField.id(level, stalemateBlue))
                        && RED.equals(TintField.id(level, stalemateControl)));

        // (20), first half: the flow is red before anything is recoloured. Without this the second
        // half is satisfied by a trough that was blue all along.
        final BlockPos repainted = ORIGIN.offset(3, 1, REPAINT_Z);
        check("repaint: the flow is the colour of its source before the source changes",
                filled(level, repainted) && RED.equals(TintField.id(level, repainted)));

        // ...and now recolour the source, with the water already settled and not one cell of it due
        // to be spread into ever again. Everything below waits another settle for the wave.
        TintField.set(level, ORIGIN.offset(0, 1, REPAINT_Z), BLUE);
        phase = 2;
        ticks = SETTLE_TICKS;
    }

    /**
     * (20), second half: the wave arrived.
     *
     * <p>This is the assertion the whole two-phase rig exists for, and it is the one that fails for
     * v1: a cell is filled once, {@code spreadTo} never visits it again, and a colour written to a
     * source after the fact stayed at that source. Its control is the first half, run a settle ago
     * against the same cell.
     */
    private static void repainted(final ServerLevel level) {
        final BlockPos pos = ORIGIN.offset(3, 1, REPAINT_Z);
        check("repaint: recolouring a source repaints the flow that has already settled below it",
                filled(level, pos) && BLUE.equals(TintField.id(level, pos)));
    }

    /**
     * Print the channel, print the tally, stop.
     *
     * <p>The channel is dumped always rather than on failure, because several assertions are each a
     * claim about one cell of it and this is the only way to see whether a failure is the mod being
     * wrong or the rig not saying what it meant to. The stalemate trough is dumped beside it for
     * the same reason: its whole point is the shape of the levels, and a wrong shape is a rig bug
     * wearing a mod bug's clothes.
     */
    private static void report(final MinecraftServer server, final ServerLevel level) {
        Hydrarium.LOG.info("hydrarium smoke: channel {}", dump(level, 0, CHANNEL_LENGTH));
        Hydrarium.LOG.info("hydrarium smoke: stalemate {}", dump(level, STALEMATE_Z, STALEMATE_LENGTH));
        Hydrarium.LOG.info("hydrarium smoke: repaint {}", dump(level, REPAINT_Z, REPAINT_LENGTH));

        final long failed = RESULTS.stream().filter(line -> line.startsWith("FAIL")).count();
        Hydrarium.LOG.info("hydrarium smoke: {} checks, {} failed", RESULTS.size(), failed);
        RESULTS.forEach(line -> Hydrarium.LOG.info("hydrarium smoke:   {}", line));
        ticks = -1;
        server.halt(false);
    }

    /** One trough, cell by cell, as {@code level/colour}. */
    private static String dump(final ServerLevel level, final int z, final int length) {
        final StringBuilder line = new StringBuilder();
        for (int x = 0; x < length; x++) {
            final BlockPos pos = ORIGIN.offset(x, 1, z);
            final var fluid = level.getFluidState(pos);
            final Identifier water = TintField.id(level, pos);
            line.append(x).append(':')
                    .append(fluid.isEmpty() ? "-" : (fluid.isSource() ? "src" : fluid.getAmount()))
                    .append('/').append(water == null ? "clear" : water.getPath()).append("  ");
        }
        return line.toString().trim();
    }

    /**
     * The frozen half, which is eight assertions and no ticks.
     *
     * <p>All of it is {@link TintField#holdsAnyWater} being right about which blocks water can have
     * turned into, and every assertion here is paired with one that can only hold if the predicate
     * is <em>narrow</em> as well as wide. That pairing is not padding: "the entry is still there"
     * is trivially true of a read that stopped validating altogether, which is the exact regression
     * a wider predicate invites.
     */
    private static void frozen(final ServerLevel level) {
        // (10) the phase change, and its control. Both start as a tinted water source in a sealed
        // cup; one becomes ice and one becomes air. The point is that the FIELD is untouched in
        // both cases -- nothing here clears an entry -- so the only thing that can tell them apart
        // is the predicate the read validates against.
        bowl(level, FROZEN_X);
        bowl(level, THAWED);
        put(level, FROZEN_X, RED);
        put(level, THAWED, RED);
        level.setBlock(FROZEN_X, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(THAWED, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        check("phase: water that freezes keeps its colour, and water that vanishes does not",
                RED.equals(TintField.id(level, FROZEN_X)) && TintField.id(level, THAWED) == null);

        // (11) snow, which is the other half of stage 1 and is NOT reached by freezing: snow falls
        // out of the sky onto whatever is there. So this is the path a player actually has -- paint
        // it -- and it is the same TintField.set /water fill uses.
        level.setBlock(SNOWED.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(SNOWED, Blocks.SNOW.defaultBlockState(), Block.UPDATE_ALL);
        TintField.set(level, SNOWED, RED);
        check("snow: a snow layer holds a tint at its own position",
                RED.equals(TintField.id(level, SNOWED)));

        // (12) the melt. The claim TintField has made since v1 -- "water that freezes and later
        // melts comes back the colour it was" -- observed rather than argued, now that something
        // reads the entry in between.
        bowl(level, MELTED);
        put(level, MELTED, BLUE);
        level.setBlock(MELTED, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(MELTED, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        check("melt: ice that thaws is the colour the water froze at",
                BLUE.equals(TintField.id(level, MELTED)));

        // (13) the powder snow cauldron, which is (3) at the other phase and needed nothing new to
        // render: vanilla's cauldron template already tint-indexes its content face.
        level.setBlock(SNOW_CAULDRON, Blocks.POWDER_SNOW_CAULDRON.defaultBlockState()
                .setValue(LayeredCauldronBlock.LEVEL, 3), Block.UPDATE_ALL);
        TintField.set(level, SNOW_CAULDRON, RED);
        check("powder snow cauldron: a pot of snow holds a tint at its own position",
                RED.equals(TintField.id(level, SNOW_CAULDRON)));

        // (14) the sweep, called directly because its real trigger is a chunk load and this rig
        // never unloads one. THIS IS THE ASSERTION THE FROZEN HALF EXISTS TO HAVE: the renderer and
        // the sweep read the same predicate, and if they ever stop agreeing, the field deletes
        // entries a perfectly correct renderer is about to ask for -- silently, because dropping a
        // stale entry is an ordinary thing for this mod to do.
        level.setBlock(SWEPT_FROZEN.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(SWEPT_FROZEN, Blocks.PACKED_ICE.defaultBlockState(), Block.UPDATE_ALL);
        level.setBlock(SWEPT_DRY, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        TintField.set(level, SWEPT_FROZEN, RED);
        TintField.set(level, SWEPT_DRY, RED);
        TintField.sweep(level.getChunk(SWEPT_FROZEN));
        check("sweep: it keeps what the read would answer and drops what it would not",
                RED.equals(TintField.rawId(level, SWEPT_FROZEN))
                        && TintField.rawId(level, SWEPT_DRY) == null);

        // (15) placement, which is the rule that says a stale entry is not an inheritance. Ice put
        // down BY HAND into a red pool is a block a player placed, not water that froze, so it
        // wears what its item declared -- nothing. Its control is the same call with a bucket that
        // does declare a colour, because "the position came out clear" is also what a rule that
        // fired on everything, or on nothing, would say.
        //
        // A call rather than a right-click, for the reason the cauldron's stir is one: a headless
        // server has no hand to place with. What it pins is the RULE, next to the phase change two
        // assertions up that must stay the other way round -- and those two together are the whole
        // of the distinction, since a placement and a freeze reach the position with the same
        // setBlock and can only be told apart by who is asking.
        bowl(level, PLACED_PLAIN);
        put(level, PLACED_PLAIN, RED);
        level.setBlock(PLACED_PLAIN, Blocks.ICE.defaultBlockState(), Block.UPDATE_ALL);
        Containers.place(level, PLACED_PLAIN, new ItemStack(Items.ICE));

        bowl(level, PLACED_TINTED);
        level.setBlock(PLACED_TINTED, Blocks.POWDER_SNOW.defaultBlockState(), Block.UPDATE_ALL);
        Containers.place(level, PLACED_TINTED,
                HydrariumComponents.stamp(new ItemStack(Items.POWDER_SNOW_BUCKET), RED));

        check("placement: plain ice in a red pool is plain, and a tinted bucket's snow is not",
                TintField.id(level, PLACED_PLAIN) == null
                        && RED.equals(TintField.id(level, PLACED_TINTED)));

        // (16) the freeze itself, which is entirely vanilla's and is pinned here anyway because
        // "coloured water does not freeze" was reported as a bug -- and reading the freeze path is
        // not the same as watching it. Biome.shouldFreeze is the whole of vanilla's decision: the
        // temperature, the block light, and "is there a LiquidBlock full of water here". Nothing in
        // this mod is on that path, which is a claim, and this is the claim being checked.
        //
        // Asked of a SNOWY PLAINS from the registry rather than of whatever biome the rig landed
        // in, because the rig's biome is a property of server.properties: in a plains superflat
        // both answers would be false and the assertion would pass having measured nothing. The
        // tinted answer is compared with the plain one AND required to be true, which is that same
        // vacuity guard said twice.
        //
        // checkNeighbors is false because these two are sealed in stone rather than sat in a lake,
        // and the neighbour rule is about lake interiors freezing last -- a real reason coloured
        // water can look like it is not freezing, and not one this mod could change.
        final Biome snowy = level.registryAccess().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.SNOWY_PLAINS).value();
        bowl(level, FREEZE_TINTED);
        bowl(level, FREEZE_PLAIN);
        put(level, FREEZE_TINTED, RED);
        put(level, FREEZE_PLAIN, null);
        check("freeze: a tinted source freezes exactly when a plain one does",
                snowy.shouldFreeze(level, FREEZE_TINTED, false)
                        && snowy.shouldFreeze(level, FREEZE_PLAIN, false));

        // (17) the drop, which is the last seam where a colour could leave the world and not come
        // back. THE LOOT TABLE IS THE TEST and it is why this is here rather than in src/test: a
        // snow block does not drop a snow block, it drops four SNOWBALLS, and no amount of reasoning
        // about FrozenWater would have found that out. The rule the hook needs is "which items can
        // wear a colour" (FrozenItem), and the rule it would have been tempting to write is "the
        // item of the block that broke" -- which is right for ice, wrong for snow, and looks right
        // in both cases until somebody shovels a drift.
        //
        // Bare-handed, so the snowball branch is the one the table takes.
        check("shovel: a red snow block drops red snowballs",
                stamped(drops(level, SHOVELLED, Blocks.SNOW_BLOCK, RED, ItemStack.EMPTY),
                        Items.SNOWBALL, RED));

        // The control, and it is the one that matters: "the drop is untinted" is also what a hook
        // that never fired says, and "the drop is tinted" is also what a hook that fired on
        // everything says. Only the pair rules both out.
        check("shovel: a plain snow block drops plain snowballs",
                stamped(drops(level, SHOVELLED_PLAIN, Blocks.SNOW_BLOCK, null, ItemStack.EMPTY),
                        Items.SNOWBALL, null));

        // (18) silk touch, which is the only way a block of ice comes out of the world as ice --
        // break one without it and it turns straight back into the water it was, keeping its entry.
        // The enchantment is looked up rather than assumed, because a tool without it takes the
        // other branch of the same table and drops nothing at all, which stamped() reads as a fail.
        final ItemStack silk = new ItemStack(Items.DIAMOND_PICKAXE);
        silk.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                .getOrThrow(Enchantments.SILK_TOUCH), 1);
        check("silk touch: a red block of ice drops red ice",
                stamped(drops(level, SILKED, Blocks.ICE, RED, silk), Items.ICE, RED));
    }

    /**
     * The crafting grid, which is seven assertions, no ticks and no world at all.
     *
     * <p>Vanilla's four frozen recipes are looked up in the <b>real</b> recipe manager and really
     * assembled, which is the whole reason this is here rather than in {@code src/test}: the recipes
     * are datapack data, they are reloaded, and nothing outside a server can ask for one. It also
     * means these run the hook — {@code CraftingRecipeMixin} injects into the tail of the same
     * {@code assemble} this calls — so a green tick here is the seam working end to end and not the
     * rule being right in isolation.
     *
     * <p>No player and no menu, for the reason the cauldron's stir is a call: a headless server has
     * no hand to craft with. What is lost by that is nothing, because the hook is on the recipe
     * rather than on the menu, and that is most of why it is on the recipe.
     */
    private static void crafted(final ServerLevel level) {
        // (19) the reported one. Four snowballs shovelled out of a red drift, pressed into a block.
        check("craft: four red snowballs make a red snow block",
                stamped(List.of(crafts(level, 2, 2, Items.SNOWBALL, RED, RED, RED, RED)),
                        Items.SNOW_BLOCK, RED));

        // The control that says the hook is not stamping everything it sees, and the one that says
        // it is not stamping whatever it found first. The second is WaterMix.join's clear-absorbs
        // arriving at a crafting grid: a plain snowball in the batch is plain water in the batch,
        // exactly as a clear flow meeting a red one is what assertion (4) pins in the channel.
        check("craft: four plain snowballs make a plain snow block",
                stamped(List.of(crafts(level, 2, 2, Items.SNOWBALL, null, null, null, null)),
                        Items.SNOW_BLOCK, null));
        check("craft: one plain snowball in the batch makes a plain snow block",
                stamped(List.of(crafts(level, 2, 2, Items.SNOWBALL, RED, RED, RED, null)),
                        Items.SNOW_BLOCK, null));

        // The documented limitation, pinned so that it is a decision rather than a surprise: a grid
        // has no level, so it has no recipe book, so no pair can blend here. Two red and two blue
        // muddy to grey where two red and two blue SOURCES make purple -- see Crafting. If a future
        // version finds a level at that seam, this is the assertion that has to change with it.
        check("craft: two waters in one batch muddy rather than one of them winning",
                stamped(List.of(crafts(level, 2, 2, Items.SNOWBALL, RED, RED, BLUE, BLUE)),
                        Items.SNOW_BLOCK, GRAY));

        // Still (19): the other three recipes, which is the "every one of them" claim rather than three
        // more copies of the first: each is a different recipe, a different result item and a
        // different FrozenItem row, and any one of them could be the one missing from the table.
        check("craft: nine blocks of red ice make red packed ice",
                stamped(List.of(crafts(level, 3, 3, Items.ICE,
                        RED, RED, RED, RED, RED, RED, RED, RED, RED)), Items.PACKED_ICE, RED));
        check("craft: nine blocks of red packed ice make red blue ice",
                stamped(List.of(crafts(level, 3, 3, Items.PACKED_ICE,
                        RED, RED, RED, RED, RED, RED, RED, RED, RED)), Items.BLUE_ICE, RED));

        // The one that goes the other way -- three blocks into six LAYERS -- and the one whose
        // result item is not the item of any ingredient, which is the same shape the snowball drop
        // is and the same shape a rule keyed on "the block that broke" would have got wrong.
        check("craft: three red snow blocks make red snow layers",
                stamped(List.of(crafts(level, 3, 1, Items.SNOW_BLOCK, RED, RED, RED)),
                        Items.SNOW, RED));
    }

    /**
     * Fill a grid with one item, each cell carrying the water named for it, and answer what the
     * game's own recipe book makes of it.
     *
     * <p>An unmatched grid answers {@link ItemStack#EMPTY}, which {@link #stamped} reads as a
     * <b>fail</b> — so a recipe that vanilla renamed or removed fails loudly here instead of
     * reporting that the stamping worked perfectly on nothing.
     */
    private static ItemStack crafts(final ServerLevel level, final int width, final int height,
            final Item item, final Identifier... waters) {
        final List<ItemStack> grid = new ArrayList<>();
        for (final Identifier water : waters) {
            grid.add(HydrariumComponents.stamp(new ItemStack(item), water));
        }
        final CraftingInput input = CraftingInput.of(width, height, grid);
        return level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level)
                .map(recipe -> recipe.value().assemble(input))
                .orElse(ItemStack.EMPTY);
    }

    /**
     * Stand a frozen block up, paint it or do not, and ask the game what it drops.
     *
     * <p>{@code Block.getDrops} rather than a broken block, for the reason the cauldron's stir is a
     * call: a headless server has no hand to swing. What it pins is the funnel — the same
     * {@code BlockStateBase.getDrops} that a pickaxe, an explosion and a piston all reach — running
     * the same loot table off the same reloadable registry.
     *
     * <p>The block is left standing, which is the one way this differs from a real break and does
     * not matter: the hook reads the state it was handed and the <b>raw</b> entry, and the raw entry
     * is there either way. What a real break adds is that the position is already air by then, which
     * is exactly what makes the raw read necessary rather than optional.
     */
    private static List<ItemStack> drops(final ServerLevel level, final BlockPos pos, final Block block,
            final Identifier water, final ItemStack tool) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pos, block.defaultBlockState(), Block.UPDATE_ALL);
        TintField.set(level, pos, water);
        return Block.getDrops(block.defaultBlockState(), level, pos, null, null, tool);
    }

    /**
     * Whether a drop is that item and only that item, carrying that water — {@code null} for none.
     *
     * <p>An empty drop is a <b>fail</b> and not a pass, which is this file's rule about vacuity
     * applied to a list: every one of these tables has a branch that drops nothing, and a rig that
     * accidentally took it would otherwise report that the stamping worked perfectly on no stacks.
     */
    private static boolean stamped(final List<ItemStack> drops, final Item item, final Identifier water) {
        if (drops.isEmpty()) {
            return false;
        }
        for (final ItemStack drop : drops) {
            if (!drop.is(item) || !Objects.equals(water, HydrariumComponents.tintOf(drop))) {
                return false;
            }
        }
        return true;
    }

    /**
     * A one-wide stone trough at a given z: floor, two side walls, two end caps, air between.
     *
     * <p>The floor is solid everywhere, which is load-bearing for the two conversion troughs:
     * vanilla only converts a cell to a source when the block below it is solid or is itself a
     * source of the same fluid.
     */
    private static void channel(final ServerLevel level, final int z, final int length) {
        for (int x = -1; x <= length; x++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(ORIGIN.offset(x, 0, z + dz), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            }
            level.setBlock(ORIGIN.offset(x, 1, z - 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            level.setBlock(ORIGIN.offset(x, 1, z + 1), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
            final boolean cap = x < 0 || x >= length;
            level.setBlock(ORIGIN.offset(x, 1, z),
                    (cap ? Blocks.STONE : Blocks.AIR).defaultBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    /**
     * A sealed one-block cup for the two static checks.
     *
     * <p>Sealed rather than open because those two are about the field and not about flow: a lone
     * source left to spread would wander across the rig over the sixty ticks the flow checks need,
     * and would arrive at the channel carrying a colour of its own.
     */
    private static void bowl(final ServerLevel level, final BlockPos pos) {
        level.setBlock(pos.below(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        level.setBlock(pos.above(), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            level.setBlock(pos.relative(side), Blocks.STONE.defaultBlockState(), Block.UPDATE_CLIENTS);
        }
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS);
    }

    /** A water source with a colour, or without one. */
    private static void put(final ServerLevel level, final BlockPos pos, final Identifier water) {
        level.setBlock(pos, Blocks.WATER.defaultBlockState(), Block.UPDATE_ALL);
        TintField.set(level, pos, water);
    }

    /** Whether the water has actually arrived. See the call sites: this is what stops vacuity. */
    private static boolean filled(final ServerLevel level, final BlockPos pos) {
        return level.getFluidState(pos).is(net.minecraft.tags.FluidTags.WATER);
    }

    private static void check(final String what, final boolean passed) {
        RESULTS.add((passed ? "ok   " : "FAIL ") + what);
    }
}
