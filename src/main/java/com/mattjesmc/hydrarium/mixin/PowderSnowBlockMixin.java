package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.PowderSnowBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scooping powder snow into a bucket, which is {@code LiquidBlockMixin} again at a different phase.
 *
 * <p>{@code PowderSnowBlock implements BucketPickup} — the same interface open water is picked up
 * through — so this is the same hook on the same seam and calls the same {@link Containers#take}.
 * That is the point of {@code take} being a method rather than a body: the frozen phase got its
 * container support by naming an existing one, and there is no second version of "move the tint out
 * of the world and onto the stack" to drift from the first.
 *
 * <p>{@code RETURN} rather than {@code HEAD}, because the return value is the bucket that needs
 * stamping — and because by then vanilla has already set the position to air, which is exactly why
 * {@code take} reads the <b>raw</b> entry. The validating read would answer clear at a position that
 * is now air, and every bucket of coloured powder snow in the game would come away plain.
 */
@Mixin(PowderSnowBlock.class)
public abstract class PowderSnowBlockMixin {

    @Inject(method = "pickupBlock", at = @At("RETURN"), cancellable = true)
    private void hydrarium$takeTint(final LivingEntity user, final LevelAccessor level, final BlockPos pos,
            final BlockState state, final CallbackInfoReturnable<ItemStack> cir) {
        if (level.isClientSide()) {
            return;
        }
        cir.setReturnValue(Containers.take(level, pos, cir.getReturnValue()));
    }
}
