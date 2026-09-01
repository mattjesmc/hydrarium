package com.mattjesmc.hydrarium;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.ServerLevelAccessor;

/**
 * "Which dye do these two craft into" - asked of the game rather than answered from a table here.
 *
 * <h2>Why a recipe lookup and not sixteen lines of Java</h2>
 *
 * <p>There are exactly nine two-item dye recipes in vanilla and it is tempting to write them down.
 * The lookup is better for one reason that outlives this build: <b>a datapack that changes what
 * dyes craft into changes what waters mix into, with no code and no catalogue edit.</b> A table here
 * would be a second copy of vanilla's recipe book, right until the first pack disagreed with it.
 *
 * <p>This mirrors {@code DyeColor.findColorMixInRecipes} deliberately - same {@code CraftingInput.of(2, 1, ...)},
 * same {@code RecipeType.CRAFTING} lookup, same read of the result's {@code minecraft:dye} component.
 * What it does <b>not</b> mirror is {@code DyeColor.getMixedColor}, whose public face falls back to
 * <i>one of the two parents at random</i> when no recipe exists. That is right for breeding sheep
 * and wrong for water: a coin flip is not order-independent, and hydrarium answers
 * {@link WaterMix#MURK grey} for an unmixable pair instead.
 *
 * <h2>The cache, and why it needs no event to invalidate it</h2>
 *
 * <p>A recipe lookup scans the recipe book, and water spreads on the server tick, so the answers are
 * cached in one flat 16x16 table filled lazily per pair. The table is stamped with the
 * {@code RecipeAccess} it was built from: a datapack reload builds a fresh recipe manager, the
 * identity check fails, and the table is thrown away. No {@code ServerLifecycleEvents} registration,
 * nothing to forget to hook, and no way for the cache to outlive the recipes it came from.
 *
 * <p>Every entry point is {@code synchronized} because {@code spreadTo} can run under a
 * {@code WorldGenRegion} on a worldgen thread as well as on the server thread. The lock is only ever
 * contended by a pair of <i>different</i> colours meeting, which is rare, and after the first meeting
 * it protects an array read.
 */
public final class DyeRecipes {

    private static final DyeColor[] COLOURS = DyeColor.values();
    private static final int PENDING = -2;
    private static final int NO_RECIPE = -1;

    private static Object owner;
    private static int[] table;

    private DyeRecipes() {
    }

    /**
     * The blender for this level, or {@link WaterMix.Blender#NONE} when there is no server behind it.
     *
     * <p>{@code NONE} means every unmixable pair - which is then every pair - comes out grey. The
     * client never needs a real one: tints are computed server-side and arrive as ids.
     */
    public static WaterMix.Blender of(final LevelAccessor level) {
        final ServerLevel server = level instanceof ServerLevelAccessor accessor ? accessor.getLevel() : null;
        return server == null ? WaterMix.Blender.NONE : (first, second) -> mix(server, first, second);
    }

    /** The dye {@code first} and {@code second} craft into, or {@code null} if they do not. */
    public static synchronized DyeColor mix(final ServerLevel level, final DyeColor first, final DyeColor second) {
        final Object recipes = level.recipeAccess();
        if (owner != recipes) {
            owner = recipes;
            table = new int[COLOURS.length * COLOURS.length];
            Arrays.fill(table, PENDING);
            Hydrarium.LOG.debug("hydrarium: dye mixing table cleared; recipes reloaded");
        }
        final int here = first.ordinal() * COLOURS.length + second.ordinal();
        if (table[here] == PENDING) {
            final DyeColor found = find(level, first, second);
            final int value = found == null ? NO_RECIPE : found.ordinal();
            table[here] = value;
            table[second.ordinal() * COLOURS.length + first.ordinal()] = value;
        }
        return table[here] == NO_RECIPE ? null : COLOURS[table[here]];
    }

    private static DyeColor find(final ServerLevel level, final DyeColor first, final DyeColor second) {
        final CraftingInput input = CraftingInput.of(2, 1, List.of(
                new ItemStack(Items.DYE.pick(first)), new ItemStack(Items.DYE.pick(second))));
        final Optional<RecipeHolder<CraftingRecipe>> recipe =
                level.recipeAccess().getRecipeFor(RecipeType.CRAFTING, input, level);
        if (recipe.isEmpty()) {
            return null;
        }
        return recipe.get().value().assemble(input).get(DataComponents.DYE);
    }
}
