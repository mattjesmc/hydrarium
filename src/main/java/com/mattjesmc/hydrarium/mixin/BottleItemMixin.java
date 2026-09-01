package com.mattjesmc.hydrarium.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import com.mattjesmc.hydrarium.Containers;
import com.mattjesmc.hydrarium.TintField;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BottleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * A glass bottle filled from open water comes away the colour of that water.
 *
 * <h2>Why this needs a mixin when the cauldron did not</h2>
 *
 * <p>The cauldron is a {@code Dispatcher} of interactions with a public-enough map, so hydrarium
 * fills a bottle there by putting its own interaction in. Open water has no such seam:
 * {@code BottleItem.use} ray-traces, checks the fluid and builds the stack inline, and there is no
 * registration anywhere in the middle of it.
 *
 * <p>It is worth the mixin because <b>this is the route a player actually takes.</b> A cauldron is
 * something you build; a tinted pond is something you find, and dipping a bottle in it is the first
 * thing anyone tries. Leaving this out would have made "hydrarium supports tinted water bottles"
 * true only of water that had already been through a cauldron.
 *
 * <h2>Where it hooks, and the two things that made every other hook worse</h2>
 *
 * <p>The tint has to be on the stack <b>before</b> {@code turnBottleIntoItem} runs, for the same
 * reason {@code CauldronTint} transcribes the bottle interaction rather than wrapping it:
 * {@code ItemUtils.createFilledResult} puts the new item in the player's <em>inventory</em> rather
 * than in their hand when the used stack held more than one bottle, so afterwards there is no
 * reliable "the stack that just came out" to stamp. Modifying the argument on its way in is the only
 * point where the stack is a thing this mod can name.
 *
 * <p>And the position is a local, not a parameter — {@code use} takes the level, the player and the
 * hand, and finds the block itself. {@code @Local} is what reaches it; a {@code @Redirect} could not,
 * and an {@code @Inject} that captured locals by shape would be one vanilla refactor away from
 * failing at load. This is the only MixinExtras in the mod, and it is here because the alternative
 * was worse rather than because it was to hand.
 *
 * <p>No {@code isClientSide} guard, deliberately: this <b>reads</b> the field rather than writing it,
 * the chunk attachment is synced, and a client that predicts the untinted bottle would show a plain
 * one for a tick before the server's answer replaced it.
 */
@Mixin(BottleItem.class)
public abstract class BottleItemMixin {

    @ModifyExpressionValue(
            method = "use",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/alchemy/PotionContents;createItemStack"
                            + "(Lnet/minecraft/world/item/Item;Lnet/minecraft/core/Holder;)"
                            + "Lnet/minecraft/world/item/ItemStack;"))
    private ItemStack hydrarium$fillTinted(final ItemStack bottle, final Level level, final Player player,
            final InteractionHand hand, @Local final BlockPos pos) {
        return Containers.bottle(bottle, TintField.id(level, pos));
    }
}
