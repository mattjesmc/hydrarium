package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Crafting;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A craft carries the colour of what went into it: red snowballs make a red snow block, red ice
 * makes red packed ice.
 *
 * <p>The rule is {@code Crafting.assembled} and the argument for it is there. This file is only
 * about <b>where</b>, and there are two things to say about that.
 *
 * <h2>{@code assemble} is the funnel, exactly as {@code getDrops} is</h2>
 *
 * <p>Three vanilla call sites reach it — {@code CraftingMenu.slotChangedCraftingGrid} (which is the
 * crafting table, the inventory's 2x2 and the recipe book's place-recipe at once),
 * {@code CrafterBlock.dispenseFrom} and {@code CrafterMenu.refreshRecipeResult} — and the result
 * slot's <em>preview</em> is computed by the same call as the craft, so hooking here is also what
 * keeps the stack a player is looking at the same colour as the stack they get. Hooking the callers
 * instead is three hooks that can drift apart, and the crafter's preview would be the one that
 * quietly did not.
 *
 * <h2>Two targets, because there is no third and no shared method to hook</h2>
 *
 * <p>{@code assemble} is declared on the {@code Recipe} interface and implemented per class, so
 * there is no single class-side method to inject into: {@code NormalCraftingRecipe} does not
 * declare it. Shaped and shapeless are the two that vanilla's four frozen recipes and every
 * datapack's use, and a mod with a {@code CustomRecipe} of its own is a mod whose result comes out
 * untinted rather than a crash.
 *
 * <p><b>Both targets are load-bearing, and the data says so rather than the shape of the recipes
 * suggesting it.</b> {@code recipe/snow_block.json} and {@code recipe/snow.json} are
 * {@code crafting_shaped} — a 2x2 of snowballs, a row of snow blocks — but
 * {@code recipe/packed_ice.json} and {@code recipe/blue_ice.json} are
 * <b>{@code crafting_shapeless}</b>, nine loose ingredients each, which is not what a 3x3 packer
 * looks like in the recipe book. Target only {@code ShapedRecipe} and the snow half works
 * perfectly while both ices silently do not, which is exactly the kind of half-success this mod
 * keeps having to pin. {@code SmokeCheck} runs all four.
 *
 * <p><b>The descriptor is spelled out because there are two {@code assemble} methods here.</b>
 * {@code Recipe<T extends RecipeInput>} erases to {@code assemble(RecipeInput)}, so the compiler
 * emits a bridge beside the real method — {@code javap} shows both, and the call sites above all
 * invoke the <em>bridge</em>. A bare {@code method = "assemble"} is ambiguous, which is the same
 * trap {@code client.BreakingItemParticleMixin} documents from the other end.
 */
@Mixin({ShapedRecipe.class, ShapelessRecipe.class})
public abstract class CraftingRecipeMixin {

    /**
     * Stamp the result with the water its frozen ingredients were made of.
     *
     * <p>The stack is mutated rather than replaced, which needs no {@code cancellable}: every
     * implementation of this method builds a <b>fresh</b> stack from the recipe's own result
     * template ({@code this.result.create()}), so there is nothing shared to disturb.
     */
    @Inject(method = "assemble(Lnet/minecraft/world/item/crafting/CraftingInput;)"
            + "Lnet/minecraft/world/item/ItemStack;", at = @At("RETURN"))
    private void hydrarium$craftTint(final CraftingInput input, final CallbackInfoReturnable<ItemStack> cir) {
        Crafting.assembled(input, cir.getReturnValue());
    }
}
