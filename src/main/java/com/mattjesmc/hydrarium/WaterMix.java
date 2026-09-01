package com.mattjesmc.hydrarium;

import java.util.Collection;
import java.util.SortedSet;
import java.util.TreeSet;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.LevelAccessor;

/**
 * What happens when two waters meet.
 *
 * <h2>This replaces the flat lattice, and the trade DESIGN.md made is now the other way round</h2>
 *
 * <p>v1 was a semilattice: two different colours dissolved to clear. That rule was chosen because it
 * is associative, and associativity is what makes a pool render the same however its fluid ticks
 * happened to be ordered. It is still the only rule with that property, and giving it up is a real
 * cost rather than a free improvement, so it is worth writing down what was bought.
 *
 * <p>What was bought is that <b>colours combine the way the game already says they combine</b>: red
 * water meeting blue water is purple, because a red dye next to a blue dye on a crafting grid is a
 * purple dye. Two colours vanilla has no recipe for come out {@link #MURK grey} - muddied, which is
 * what mixing arbitrary paint actually does. The rule is not hydrarium's invention and it is not a
 * table in this file; it is a recipe lookup, so a datapack that adds a dye recipe adds a water
 * recipe with it. See {@link DyeRecipes}.
 *
 * <h2>What is kept, and what is given up</h2>
 *
 * <p><b>Kept: clear absorbs, in flow.</b> {@link #join} still answers clear the moment any parent is
 * clear, so a red flow reaching the ocean still bleeds out at the boundary while its source stays
 * red, and no infinite-water trick launders a colour into an ocean. That was the best line in the
 * model and it survives.
 *
 * <p><b>Kept: commutative and idempotent.</b> {@code a * a = a} exactly as before, and the pair rule
 * sorts its two dyes before asking for a recipe, so no answer depends on which parent is named
 * first.
 *
 * <p><b>Given up: associativity.</b> {@code (red * yellow) * yellow} is {@code orange * yellow},
 * which is grey; {@code red * (yellow * yellow)} is {@code red * yellow}, which is orange. There is
 * no way to have vanilla's mixing and this property at once, and a rule that blends cannot be a
 * join. What is put in its place is <b>determinism</b>, which is what the associativity was being
 * used <i>for</i>: {@link #join} folds over its parents <b>deduplicated and sorted by id</b>, so the
 * same set of parents always produces the same answer no matter what order the world offered them
 * in. Deduplicating is what kills the example above - a cell fed by red, yellow and yellow is fed by
 * {red, yellow}, and comes out orange from either end. A pool still renders the same after a chunk
 * reload; what is no longer guaranteed is that two <i>different</i> routes to the same cell agree,
 * and in a world where flow determines the parent set, they mostly do.
 *
 * <p>This is why {@code FlowingFluidMixin} gathering the parents from the world at fill time went
 * from "the only version that works" to "the only version that works, twice over": a per-parent
 * implementation would fold one parent in at a time in tick order, which under a blending rule is
 * order-dependent in a way no amount of care in this file could fix.
 *
 * <h2>Two operators, because a cauldron is not a river</h2>
 *
 * <p>{@link #join} is for flow, where the parents arrive at once and nobody chose their order.
 * {@link #stir} is for containers, where a player added one thing to another and the order is the
 * whole point. They differ in exactly one place: <b>clear absorbs in a river and is neutral in a
 * pot.</b> Dye dropped in a cauldron of plain water colours it; that is what a cauldron of plain
 * water is for. Dye dropped in a river dissolves; that is what a river is for.
 *
 * <p>That difference is not a wart, it is the bug this file was written to fix. Both paths used to
 * call the join, so {@code join(clear, red)} was clear - and adding a dye to a cauldron, pouring a
 * tinted bucket into an empty one, and therefore <i>every</i> tinted bottle drawn back out of one,
 * all quietly did nothing at all.
 */
public final class WaterMix {

    /**
     * The colour of two waters that will not blend.
     *
     * <p>Grey rather than clear, so that "these did not mix" is something a player can see standing
     * over the pool rather than something they infer from a colour going missing.
     */
    public static final DyeColor MURK = DyeColor.GRAY;

    /**
     * The recipe half of the rule, kept behind an interface so the algebra can be tested without a
     * game.
     *
     * <p>{@link DyeRecipes#of} supplies the real one, which asks the server's recipe manager. It is
     * always called with {@code first.ordinal() <= second.ordinal()}, so an implementation may cache
     * one triangle and need not think about order.
     */
    @FunctionalInterface
    public interface Blender {

        /** No recipes at all - every pair of different colours comes out {@link #MURK}. */
        Blender NONE = (first, second) -> null;

        /** The dye these two craft into, or {@code null} if the game has no recipe for the pair. */
        DyeColor mix(DyeColor first, DyeColor second);
    }

    private WaterMix() {
    }

    /**
     * Flow: the colour of a cell fed by these parents. Clear absorbs; the rest blend.
     *
     * <p>The parents are deduplicated and sorted before folding, which is what makes the answer a
     * function of the <i>set</i> of parents rather than of the order the caller happened to walk the
     * neighbours in. A {@code null} anywhere in the collection is clear water and ends it.
     */
    public static Identifier join(final LevelAccessor level, final Collection<Identifier> parents) {
        return join(DyeRecipes.of(level), parents);
    }

    /** Flow, two parents. Agrees with {@link #join(LevelAccessor, Collection)} on a pair. */
    public static Identifier join(final LevelAccessor level, final Identifier a, final Identifier b) {
        return join(DyeRecipes.of(level), a, b);
    }

    /**
     * A container: what {@code standing} becomes when {@code added} is poured into it.
     *
     * <p>Clear is neutral here, in both directions. Adding plain water to red water leaves red -
     * there is no dilution in this model, and a ramp is the thing DESIGN.md keeps shut.
     */
    public static Identifier stir(final LevelAccessor level, final Identifier standing, final Identifier added) {
        return stir(DyeRecipes.of(level), standing, added);
    }

    /** @see #join(LevelAccessor, Collection) */
    public static Identifier join(final Blender blender, final Collection<Identifier> parents) {
        if (parents.isEmpty()) {
            return null;
        }
        final SortedSet<Identifier> distinct = new TreeSet<>();
        for (final Identifier parent : parents) {
            if (parent == null) {
                return null;
            }
            distinct.add(parent);
        }
        Identifier result = null;
        boolean first = true;
        for (final Identifier parent : distinct) {
            result = first ? parent : blend(blender, result, parent);
            first = false;
            if (result == null) {
                return null;
            }
        }
        return result;
    }

    /** @see #join(LevelAccessor, Identifier, Identifier) */
    public static Identifier join(final Blender blender, final Identifier a, final Identifier b) {
        if (a == null || b == null) {
            return null;
        }
        return a.equals(b) ? a : blend(blender, a, b);
    }

    /** @see #stir(LevelAccessor, Identifier, Identifier) */
    public static Identifier stir(final Blender blender, final Identifier standing, final Identifier added) {
        if (standing == null) {
            return added;
        }
        if (added == null || standing.equals(added)) {
            return standing;
        }
        return blend(blender, standing, added);
    }

    /**
     * The pair rule, for two waters that are both real and not the same one.
     *
     * <p>A water blends only if it declares a {@code dye} in its catalogue row. That is what keeps
     * this honest for tier 2: rocketeer's ash slurry is not secretly a shade of grey dye, so ash
     * slurry meeting lumewater muddies rather than inventing a recipe neither mod wrote. A consumer
     * that <i>wants</i> its water to mix says so in one field.
     */
    public static Identifier blend(final Blender blender, final Identifier a, final Identifier b) {
        final DyeColor first = dyeOf(a);
        final DyeColor second = dyeOf(b);
        if (first == null || second == null) {
            return murk();
        }
        final boolean inOrder = first.ordinal() <= second.ordinal();
        final DyeColor mixed = blender.mix(inOrder ? first : second, inOrder ? second : first);
        if (mixed == null) {
            return murk();
        }
        final WaterType water = Waters.byDye(mixed);
        return water == null ? murk() : water.id();
    }

    private static DyeColor dyeOf(final Identifier id) {
        final WaterType water = Waters.get(id);
        return water == null ? null : water.dye();
    }

    /**
     * Grey, if anything has declared a water for it.
     *
     * <p>If nothing has - a catalogue that dropped the dye rows - this answers clear rather than
     * throwing, and two colours dissolve the way they did in v1. That is the right failure: a
     * library missing its own built-ins should get quieter, not louder.
     */
    private static Identifier murk() {
        final WaterType water = Waters.byDye(MURK);
        return water == null ? null : water.id();
    }
}
