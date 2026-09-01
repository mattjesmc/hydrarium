package com.mattjesmc.hydrarium;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The frozen phase: which blocks are water that has stopped moving, and how much white each one's
 * sprite already has.
 *
 * <h2>Why this is a list and not a tag</h2>
 *
 * <b>Every entry here is read from two source sets, and getting one and missing the other is
 * silent.</b> The server reads it to decide whether a tint entry at a position is stale
 * ({@link TintField#holdsAnyWater}); the client reads it twice more, once to put a tint index on
 * the block's quads and once to register the colour those quads then ask for. Miss the first and
 * the field deletes itself under a renderer that is perfectly correct; miss the second and the
 * model asks for a tint nobody answers; miss the third and the model has nowhere to put the answer.
 * All three failures render, and none of them log.
 *
 * <p>So there is one table, in the half of the mod that both sides can see, and every one of those
 * three registrations is a loop over {@link #values()}. Adding a frozen block is adding a row here
 * and nothing else — which is the only arrangement where "the snow surface was forgotten" is not a
 * thing that can happen one file at a time.
 *
 * <h2>Ice is a phase, and hydrarium already survived it by accident</h2>
 *
 * {@link TintField} has always said that <i>water which freezes and later melts comes back the
 * colour it was</i>, because nothing is hooked on removal and the entry outlives the ice. Read as a
 * win that is a nice consequence; read as a defect it says there is a red pool in this mod that
 * turns vanilla blue and then turns red again. The entry was present, synced, persisted and correct
 * the whole time — the only thing missing was a renderer that read it.
 *
 * <p>Which is why this file registers <b>no block and no item</b>, for exactly the reason v1
 * registered no fluid. Tinted ice is {@code minecraft:ice}. The colour is still three integers a
 * renderer multiplies into a quad it was going to draw anyway.
 *
 * <h2>The frost strength belongs to the SURFACE, not to the water</h2>
 *
 * DESIGN.md budgeted for a per-water knob beside {@code biome_strength}. Measuring the sprites says
 * otherwise, and the measurement is the argument:
 *
 * <pre>
 *   block/snow         average #f9fefe   — white to within a rounding error
 *   block/ice          average #91b7fd   — blue, and only 72% as bright
 * </pre>
 *
 * A tint is a <b>multiply</b>, and a multiply can only darken. Against snow that is exact: red water
 * makes red snow and the arithmetic has nothing to do. Against ice every colour arrives dimmer than
 * it was, and the darkest of them arrive as holes — black water's ice is {@code #101521}. The
 * correction is to wash the colour toward white before it is multiplied, and <b>how much wash is
 * needed is a fact about the sprite</b>: it is the brightness that surface has already spent. A
 * water's author cannot know it, would have to guess it once per frozen block, and would get it
 * wrong for any block added after their catalogue was written.
 *
 * <p>So the knob lives here, one value per surface, and a catalogue row still declares exactly one
 * colour. That is the whole of what "one colour unlocks all" costs.
 *
 * <h2>This table is keyed by BLOCK, and that is not the whole question</h2>
 *
 * The item side used to be derived from here through {@code Block.asItem}, because every frozen
 * surface a player could hold was one they could also stand on. <b>The snowball ended that</b> — a
 * snow block does not drop a snow block, it drops four snowballs, and there is no
 * {@code minecraft:snowball} block for a derivation to find. So "which <em>items</em> may carry a
 * water" is {@link FrozenItem}'s list, and every row of it names a row of this one for its
 * {@link #frost()} rather than writing the number down twice.
 */
public enum FrozenWater {

    /**
     * Snow layers and the snow block. Vanilla's blockstate maps eight layer counts onto seven
     * height models plus the full block, all of them wearing {@code block/snow} — so one row here
     * covers all eight, because the row names the <em>block</em> and the tint index goes on
     * whatever model that block baked to.
     */
    SNOW(Blocks.SNOW, Frost.WHITE, true),
    SNOW_BLOCK(Blocks.SNOW_BLOCK, Frost.WHITE, true),

    /**
     * Powder snow, which is the one frozen block that is also a <b>container</b>: it is picked up
     * with a bucket and put down with one, so its colour has to survive leaving the world. See
     * {@code PowderSnowBlockMixin} and {@code SolidBucketItemMixin}.
     */
    POWDER_SNOW(Blocks.POWDER_SNOW, Frost.WHITE, true),

    /**
     * The powder snow cauldron, and the one row that needs <b>no model work at all</b>.
     *
     * <p>{@code block/template_cauldron_full} already ends with {@code "tintindex": 0} on its
     * content face, because the <em>water</em> cauldron needs one and every cauldron shares that
     * template. So the snow in a cauldron is already asking a tint source for a colour, and has
     * been all along; there simply was no source registered on this block to answer. Registering
     * one is the entire change, which is the same shape as the water cauldron in v1 — a surface
     * that needed nothing new because vanilla had already built the seam.
     */
    POWDER_SNOW_CAULDRON(Blocks.POWDER_SNOW_CAULDRON, Frost.WHITE, false),

    /**
     * The four ices. {@code block/ice} is translucent and {@code packed}/{@code blue} are not,
     * which changes nothing here: the tint is a multiply into whatever the sprite is, and the
     * sprite's own alpha is left alone.
     *
     * <p>Frosted ice is included for one reason and it is free: it is what water becomes under a
     * pair of Frost Walker boots, at a position that was water a tick ago and therefore carries the
     * entry already. Leaving it out would make a red lake walk across as vanilla-blue stepping
     * stones.
     */
    ICE(Blocks.ICE, Frost.ICY, true),
    PACKED_ICE(Blocks.PACKED_ICE, Frost.ICY, true),
    BLUE_ICE(Blocks.BLUE_ICE, Frost.ICY, true),
    FROSTED_ICE(Blocks.FROSTED_ICE, Frost.ICY, true);

    /**
     * The two numbers, in a holder class because an enum's own constants cannot be named from its
     * constant list.
     */
    private static final class Frost {

        /**
         * Snow, which needs none. The sprite is {@code #f9fefe}, so the multiply is exact and the
         * declared colour arrives on the block as itself. Black water really does make black snow,
         * and ash slurry really does make a grey drift, which is the answer a player expects and
         * the reason this number is zero rather than small.
         */
        private static final float WHITE = 0.0F;

        /**
         * Ice, which needs a little, and less than it looks like it should.
         *
         * <p><b>Tuned against the palette, not derived</b> — {@code python authoring/gen_water.py
         * --swatch <file>} draws all sixteen dyes against all four frozen sprites, and this number
         * is what looking at it settled. <b>The wash is bought with hue and it should therefore be
         * bought as sparingly as the floor allows.</b> Every step toward white pulls a colour toward
         * the sprite's own blue, because that is the only colour a multiply can leave behind: at
         * {@code 0.35} red water's ice is {@code #745571}, a mauve, and by {@code 0.5} orange is
         * {@code #8f8a8d} and the whole palette is converging on ordinary ice.
         *
         * <p>What the wash is <em>for</em> is one dye, at the bottom. Every colour above black
         * survives the multiply unaided — red lands on {@code #642126} and green on
         * {@code #355916}, dark but plainly themselves — while black lands on {@code #101521},
         * which is a hole in the world rather than a colour. A sixth of the way to white lifts that
         * to {@code #242d41}, a slate ice, and costs red only {@code #642126} to {@code #6b3746},
         * which is still a red.
         *
         * <p>These numbers are also the pessimistic ones. They are computed against the sprite's
         * <em>average</em> pixel, and {@code block/ice} runs up to {@code #c8dcff}, so real ice is
         * brighter and more varied than any of them — which is another reason to buy as little
         * wash as the darkest dye needs, rather than as much as the average makes look comfortable.
         */
        private static final float ICY = 0.15F;

        private Frost() {
        }
    }

    private static final Map<Block, FrozenWater> BY_BLOCK = byBlock();

    private final Block block;
    private final float frost;
    private final boolean needsTintIndex;

    FrozenWater(final Block block, final float frost, final boolean needsTintIndex) {
        this.block = block;
        this.frost = frost;
        this.needsTintIndex = needsTintIndex;
    }

    /** The vanilla block this row is about. hydrarium registers none of them. */
    public Block block() {
        return this.block;
    }

    /**
     * How far toward white this surface's colour is washed before it is multiplied into the sprite,
     * {@code 0} (the declared colour exactly) to {@code 1} (white, which is no colour at all).
     */
    public float frost() {
        return this.frost;
    }

    /**
     * Whether this block's baked model has to be given a tint index before it can be coloured.
     *
     * <p>True for every block whose model is a plain {@code cube_all} or a snow height — vanilla
     * puts a {@code tintindex} only on the faces it means to tint, and it means to tint none of
     * these. False for the cauldron, whose template already carries one for the water cauldron's
     * sake.
     *
     * <p>It is a declared field rather than something the client works out by inspecting the baked
     * quads, because "this model had no tint index and nobody noticed" and "this model already had
     * one and we added a second" are both silent, and a boolean written down beside the block is
     * the only version of this that a reader can check against the model JSON.
     */
    public boolean needsTintIndex() {
        return this.needsTintIndex;
    }

    /** The row for a block state, or {@code null} if that block is not frozen water. */
    public static FrozenWater of(final BlockState state) {
        return BY_BLOCK.get(state.getBlock());
    }

    private static Map<Block, FrozenWater> byBlock() {
        final Map<Block, FrozenWater> map = new IdentityHashMap<>();
        for (final FrozenWater frozen : values()) {
            map.put(frozen.block, frozen);
        }
        return Map.copyOf(map);
    }
}
