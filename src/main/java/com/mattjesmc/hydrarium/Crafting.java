package com.mattjesmc.hydrarium;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;

/**
 * The fifth seam a colour can leave the world through, and the one nothing was watching: a
 * <b>crafting grid</b>.
 *
 * <p>Four snowballs make a snow block, three snow blocks make six snow layers, nine blocks of ice
 * make packed ice and nine of those make blue ice. Every one of those is a frozen water going in and
 * a frozen water coming out, and until this file the colour stopped at the grid — a stack of red
 * snowballs shovelled out of a red drift crafted into an ordinary white block, with nothing logged,
 * because a recipe result is a fresh stack and a fresh stack has no components to lose.
 *
 * <h2>Why this is a hook and not four recipes</h2>
 *
 * <p>The tempting shape is a recipe of hydrarium's own per pair — nineteen waters times four
 * recipes, generated into the catalogue beside the lang rows. It is wrong for the same reason
 * everything else here is: <b>a recipe is a registry entry and a colour is not.</b> Vanilla's four
 * recipes already match, because {@code ShapedRecipe.matches} tests <em>items</em> and is blind to
 * components — a grid of red snowballs is a grid of snowballs as far as the recipe book is
 * concerned, and it always was. What was missing is not a recipe. It is the one line that carries
 * the colour across the one that already fired.
 *
 * <p>Which is also why this cannot break a recipe that does not concern it: the rule declines unless
 * the <em>result</em> is a {@link FrozenItem} and at least one <em>ingredient</em> is, and both of
 * those are an identity-map lookup on items that are almost never frozen water.
 *
 * <h2>Which ingredients get a vote</h2>
 *
 * <p>Only the ones that could be holding a water. A recipe may mix frozen water with things that
 * are not — a datapack's ice-and-glass whatever — and a pane of glass has nothing to say about a
 * colour; counting it as clear would make every such recipe launder a tint away. So the fold is over
 * {@link FrozenItem} rows only, and an untinted <em>frozen</em> ingredient still votes, because a
 * plain snowball in the batch is plain water in the batch.
 *
 * <p>{@code minecraft:water_bucket} is deliberately not on that list even though it carries the same
 * component. No recipe in the game makes water, and a bucket in a recipe is consumed for the water
 * in it and hands back an empty one — the liquid phase is not a thing a grid produces, so it is not
 * a thing this seam has to carry.
 *
 * <h2>{@link WaterMix#join}, because a grid is a river and not a pot</h2>
 *
 * <p>Clear absorbs: three red snowballs and a plain one make a plain snow block, the same way a red
 * flow reaching the ocean bleeds out. That is the right half of the pair here — a grid has no
 * standing water for something to be <em>added</em> to, so {@code stir}'s asymmetry has nobody to
 * ask which of four snowballs was the one poured in. And {@code join} folds its parents
 * deduplicated and sorted, so the answer is a function of the <em>set</em> in the grid rather than
 * of which slot the player filled first — which is exactly the property a 2x2 needs, since a
 * shapeless recipe does not even preserve an order to depend on.
 *
 * <h2>The one thing this seam cannot do, and what it costs</h2>
 *
 * <p><b>A crafting grid has no level, so it has no recipe book.</b> {@code Recipe.assemble} takes a
 * {@code CraftingInput} and nothing else — no level, no registries, nothing that reaches a
 * {@code RecipeAccess} — so {@link DyeRecipes} cannot be consulted from here and the blender is
 * {@link WaterMix.Blender#NONE}. Every pair is therefore unmixable at this seam: <b>two red
 * snowballs and two blue ones make a GREY snow block, where two red water sources and two blue ones
 * would have made purple.</b> This is the one place in the mod where "vanilla has a recipe for the
 * pair" and "the pair blended" come apart, and it is deliberate rather than missed.
 *
 * <p>What it buys is the funnel. {@code assemble} is to crafting what
 * {@code BlockStateBase.getDrops} is to breaking: the one method every caller goes through, so the
 * crafting table, the inventory's 2x2, the recipe book's place-recipe, the crafter block, the
 * crafter's own preview slot and any mod's shaped or shapeless recipe are all one hook and cannot
 * disagree with each other. Reaching a level means hooking the three <em>callers</em> instead —
 * {@code CraftingMenu.slotChangedCraftingGrid}, {@code CrafterBlock.dispenseFrom} and
 * {@code CrafterMenu.refreshRecipeResult} — which is three hooks that can drift apart, and the first
 * of them casts its player to a {@code ServerPlayer}, so {@code SmokeCheck} could not drive it at
 * all and the seam a player actually uses would be the one seam with no in-world assertion on it.
 * Three hooks, one of them untestable, to buy purple snow for somebody who deliberately shovelled
 * two differently-coloured drifts into one grid.
 *
 * <p>Grey is at least the mod's own answer for <i>these did not combine</i> rather than a colour
 * going quietly missing, which is the whole reason {@link WaterMix#MURK} is grey and not clear. If a
 * future version finds a level here, this paragraph is the only thing that has to change.
 */
public final class Crafting {

    private Crafting() {
    }

    /**
     * Give a crafting result the water its ingredients were made of. Mutates the stack it is handed
     * and answers it, the way {@link HydrariumComponents#stamp} does.
     *
     * <p>Called from the tail of {@code assemble}, which is <b>every</b> craft and also every
     * result-slot preview, so the two guards below are the whole cost on a grid that has nothing to
     * do with water: a lookup in an {@link java.util.IdentityHashMap} keyed by item.
     */
    public static ItemStack assembled(final CraftingInput input, final ItemStack result) {
        if (result.isEmpty() || FrozenItem.of(result.getItem()) == null) {
            return result;
        }
        List<Identifier> batch = null;
        for (final ItemStack ingredient : input.items()) {
            if (ingredient.isEmpty() || FrozenItem.of(ingredient.getItem()) == null) {
                continue;
            }
            if (batch == null) {
                batch = new ArrayList<>();
            }
            // Null for a plain one, which is a vote and not an abstention: WaterMix.join answers
            // clear the moment any parent is clear, and a plain snowball in the batch is plain
            // water in the batch.
            batch.add(HydrariumComponents.tintOf(ingredient));
        }
        if (batch == null) {
            // A result that IS frozen water made out of ingredients that are not -- somebody's ice
            // out of glass and a wish. Nothing here has an opinion about its colour, and stamping
            // clear would be an opinion.
            return result;
        }
        return HydrariumComponents.stamp(result, WaterMix.join(WaterMix.Blender.NONE, batch));
    }
}
