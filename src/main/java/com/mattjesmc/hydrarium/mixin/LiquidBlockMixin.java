package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scooping open water into a bucket.
 *
 * <p>The hook is on the <em>block</em> rather than on {@code BucketItem} so that it catches every
 * caller — hand, dispenser, and any mod that picks fluid up through {@code BucketPickup} without
 * involving a player. That interface is the funnel; {@code BucketItem.use} is only its busiest
 * caller.
 *
 * <p>{@code RETURN} rather than {@code HEAD} because the return value is the bucket that needs
 * stamping, and because vanilla only produces one on the source-block branch — the guard against
 * stamping an empty stack lives in {@link Containers#take}, where both pickup mixins share it.
 */
@Mixin(LiquidBlock.class)
public abstract class LiquidBlockMixin {

    @Inject(method = "pickupBlock", at = @At("RETURN"), cancellable = true)
    private void hydrarium$takeTint(final LivingEntity user, final LevelAccessor level, final BlockPos pos,
            final BlockState state, final CallbackInfoReturnable<ItemStack> cir) {
        if (level.isClientSide()) {
            return;
        }
        cir.setReturnValue(Containers.take(level, pos, cir.getReturnValue()));
    }
}
