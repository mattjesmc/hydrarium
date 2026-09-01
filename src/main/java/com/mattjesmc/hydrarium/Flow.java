package com.mattjesmc.hydrarium;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;

/**
 * What colour a cell of <em>moving</em> water is, and how a change to that colour travels.
 *
 * <h2>The rule, in one sentence</h2>
 *
 * <b>A source holds a colour; a flowing cell derives one.</b> A source is painted by a bucket, a
 * command, a cauldron or vanilla's two-source conversion, and it keeps what it was given. Every
 * cell below a source is a function of what feeds it, recomputed whenever the game re-evaluates
 * that cell — which is what makes a colour change at a source travel down the channel instead of
 * stopping at the cells that happened to exist already.
 *
 * <p>That second half is new, and it is the whole of this class. v1 painted a cell <b>once</b>, at
 * {@code spreadTo}, and never again — because {@code spreadTo} fires once per cell (see
 * {@code FlowingFluidMixin}) and there was no second hook. The consequences were all one bug wearing
 * different clothes:
 *
 * <ul>
 *   <li>Pour red into a standing blue pool and nothing happens. The pool's cells already exist, so
 *       none of them is ever spread into again, so none of them ever hears about the red.</li>
 *   <li>Two fronts that meet asymmetrically never blend. The cell where they meet was created by
 *       whichever front got there first, and by the time the second front arrives that cell can no
 *       longer be filled.</li>
 *   <li>{@code /water fill} on a source did not reach the flow it feeds.</li>
 * </ul>
 *
 * <p>All three read as "blending is broken", and none of them is a bug in {@link WaterMix}: the
 * algebra was being asked exactly once, at the one moment the answer was least informed.
 *
 * <h2>Feeders: strictly higher, and why that must stay strict</h2>
 *
 * {@link #feeders} is v1's rule and is unchanged: the cell above (water falls) plus every horizontal
 * neighbour standing <em>strictly</em> higher, because vanilla's flow model is "take the highest
 * neighbour and lose one level". Relaxing that to {@code >=} is the obvious way to make two equal
 * cells see each other, and it is a trap — the relation stops being a partial order, two neighbours
 * become each other's parents, and a repaint wave bounces between them forever. Worse, it would not
 * even oscillate cleanly: {@code red * blue} is purple but {@code purple * red} has no recipe, so a
 * cyclic dependency degrades a whole pool to grey a tick at a time.
 *
 * <h2>The stalemate, which is why blending looked broken even in a symmetric rig</h2>
 *
 * With a strict test, two fronts that come to rest at the <b>same level</b> are invisible to each
 * other. Red at {@code x=0} and blue at {@code x=7} settle as {@code 7 6 5 | 5 6 7}: the two middle
 * cells are adjacent, equal, and neither is the other's feeder. Nothing ever blends, at any tick
 * order, forever. Whether a channel blends is decided by its <em>parity</em> — {@code SmokeCheck}'s
 * nine-cell channel has a unique lowest cell fed from both sides and blends; an eight-cell one does
 * not — which is not a rule anybody could have predicted from the outside.
 *
 * <p>So an equal-level neighbour contributes too, but it contributes <b>its feeders' colour rather
 * than its own</b>. That one indirection is what keeps the dependency acyclic: a cell's colour is a
 * function of the tints of cells strictly above it in {@code (amount, y)} order, never of a cell at
 * its own level. Red's middle cell reads "blue's side is fed by blue" and blue's reads "red's side
 * is fed by red", so both land on purple, symmetrically, and stay there.
 *
 * <p>A neighbour with no feeders at all is skipped rather than counted as clear — it is a cell about
 * to dry up, and letting it wash the colour out would be reading drought as dilution. A neighbour
 * fed by an ocean is <em>not</em> skipped: its feeders are clear, so clear is what it contributes,
 * and a tinted stream still bleeds out where it meets plain water. That was the best line in
 * {@link WaterMix} and it survives here intact.
 *
 * <h2>The wave</h2>
 *
 * {@link #wake} is how a change travels: writing a tint schedules a fluid tick on the neighbouring
 * moving water, and {@link #repaint} — hung off vanilla's own {@code tick} — recomputes that cell
 * and writes, which wakes <em>its</em> neighbours in turn. It terminates for the reason above: a
 * cell recomputes to a value that depends only on cells strictly higher than it, so a wave can only
 * run downhill, and a cell already holding its derived colour writes nothing
 * ({@link TintField#set} is a no-op on an unchanged value) and so wakes nobody.
 *
 * <p>It costs nothing on plain water. An untinted world never changes a tint, so it never wakes
 * anything, and a lake is all sources and never repaints at all. What it does cost is a fluid tick
 * per moving cell per colour change, on cells vanilla would mostly have ticked anyway.
 *
 * <p>One consequence is worth stating plainly: <b>a flowing cell painted by hand does not stay
 * painted.</b> {@code /water fill} over a stretch of moving water will hold until each cell next
 * ticks and re-derives itself from what feeds it. Paint the source, not the stream — that is the
 * same sentence as the one at the top of this file, seen from the command line.
 */
public final class Flow {

    /**
     * Vanilla's water tick delay, which is what {@code WaterFluid.getTickDelay} answers.
     *
     * <p>Copied rather than asked for because {@code Fluid.getTickDelay} is protected. Being wrong
     * about it costs a wave that travels at the wrong speed, never a wrong colour.
     */
    private static final int SPREAD_DELAY = 5;

    private Flow() {
    }

    /**
     * The colour a cell of water at this position should be, given what currently feeds it.
     *
     * <p>{@code target} is passed rather than read because the one caller that matters —
     * {@code spreadTo} — asks before the block exists.
     */
    public static Identifier tintOf(final LevelAccessor level, final BlockPos pos, final FluidState target) {
        if (target.isSource()) {
            return WaterMix.join(level, sources(level, pos));
        }

        final List<Identifier> parents = feeders(level, pos, target);

        // The stalemate. Only between two ordinary flowing cells at the same level: a falling
        // column is not fed sideways, and a source at the same amount as a falling cell is a
        // coincidence of numbers rather than a neighbour that feeds it.
        if (!target.getValue(FlowingFluid.FALLING)) {
            for (final Direction side : Direction.Plane.HORIZONTAL) {
                final BlockPos neighbour = pos.relative(side);
                final FluidState fluid = level.getFluidState(neighbour);
                if (!fluid.is(FluidTags.WATER) || fluid.isSource() || fluid.getValue(FlowingFluid.FALLING)
                        || fluid.getAmount() != target.getAmount()) {
                    continue;
                }
                final List<Identifier> theirs = feeders(level, neighbour, fluid);
                if (!theirs.isEmpty()) {
                    parents.add(WaterMix.join(level, theirs));
                }
            }
        }

        return WaterMix.join(level, parents);
    }

    /**
     * Recompute one moving cell and write the answer. The hook is vanilla's {@code tick}.
     *
     * <p>Sources are left alone on purpose: a source is the thing that <em>holds</em> a colour, and
     * a source that re-derived one would be a bucket that forgets what was poured out of it. It is
     * also what stops the two halves of a two-source conversion from feeding each other.
     */
    public static void repaint(final LevelAccessor level, final BlockPos pos) {
        final FluidState fluid = level.getFluidState(pos);
        if (!fluid.is(FluidTags.WATER) || fluid.isSource()) {
            return;
        }
        TintField.set(level, pos, tintOf(level, pos, fluid));
    }

    /**
     * Ask the game to tick the moving water next to this position, so it can re-derive its colour.
     *
     * <p>Called from {@link TintField#set} on a real change, which makes it the one funnel: a
     * bucket, a command, a cauldron drained into a pool and every path added later all start a wave
     * without knowing they did.
     *
     * <p>All six directions rather than the four the colour can travel in, because sorting out which
     * neighbours are downhill is exactly the work {@link #repaint} is about to do properly, and an
     * uphill neighbour recomputes to the value it already had and stops. Vanilla deduplicates a
     * scheduled tick by position and type, so waking a cell that was going to tick anyway is free.
     */
    public static void wake(final LevelAccessor level, final BlockPos pos) {
        if (level.isClientSide()) {
            return;
        }
        for (final Direction side : Direction.values()) {
            final BlockPos neighbour = pos.relative(side);
            final FluidState fluid = level.getFluidState(neighbour);
            if (fluid.is(FluidTags.WATER) && !fluid.isSource()) {
                // The fluid's own type, not Fluids.WATER: ServerLevel.tickFluid drops a scheduled
                // tick whose type does not match the state at the position, and a moving cell is
                // FLOWING_WATER. Scheduling the source type would silently do nothing at all.
                level.scheduleTick(neighbour, fluid.getType(), SPREAD_DELAY);
            }
        }
    }

    /**
     * The cells that feed this one: the cell above, plus every horizontal neighbour standing
     * strictly higher.
     *
     * <p>Read from the world rather than from the {@code direction} {@code spreadTo} was given,
     * because that names one feeder and a cell may have four. Under a blending rule this is
     * load-bearing twice over — a blend is not associative, so the whole set has to be in hand at
     * once for the answer to be a function of the set rather than of the tick order.
     */
    private static List<Identifier> feeders(final LevelAccessor level, final BlockPos pos,
            final FluidState target) {
        final List<Identifier> parents = new ArrayList<>(5);

        // Water falls, so the cell above always qualifies. This is not an optimisation of the level
        // test below but a replacement for it: falling water is level 8, the same as a source, so
        // "higher than me" would find nothing and every waterfall would run clear.
        final BlockPos above = pos.above();
        if (level.getFluidState(above).is(FluidTags.WATER)) {
            parents.add(TintField.id(level, above));
        }

        for (final Direction side : Direction.Plane.HORIZONTAL) {
            final BlockPos neighbour = pos.relative(side);
            final FluidState fluid = level.getFluidState(neighbour);
            // Strictly higher -- see the class doc for why this must not become >=.
            if (fluid.is(FluidTags.WATER) && fluid.getAmount() > target.getAmount()) {
                parents.add(TintField.id(level, neighbour));
            }
        }

        return parents;
    }

    /**
     * The neighbouring sources a converted source was born of.
     *
     * <p>Horizontal only, matching vanilla's {@code neighbourSources} count exactly: a source below
     * is a support for the conversion, not a parent of it, and vanilla does not count it either.
     * Taking only one of them would let a red source and a blue source together produce a red
     * source, which is colour manufactured out of nothing at one block per tick forever.
     */
    private static List<Identifier> sources(final LevelAccessor level, final BlockPos pos) {
        final List<Identifier> parents = new ArrayList<>(4);
        for (final Direction side : Direction.Plane.HORIZONTAL) {
            final BlockPos neighbour = pos.relative(side);
            final FluidState fluid = level.getFluidState(neighbour);
            if (fluid.isSource() && fluid.is(FluidTags.WATER)) {
                parents.add(TintField.id(level, neighbour));
            }
        }
        return parents;
    }
}
