package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Scooping the water out of a waterlogged block, which is the same operation as
 * {@link LiquidBlockMixin}'s on a different implementation of the same interface.
 *
 * <p>This one exists because <b>a waterlogged stair can be tinted at all</b>, which is the free win
 * the whole no-new-fluid design was chosen for: the tint is positional and is not part of the block
 * state, so {@code waterlogged=true} carries a colour. A mod that had registered sixteen fluids
 * could not have done this at any price — {@code waterlogged} means {@code minecraft:water} and
 * nothing else, forever — and a tinted ocean would have had blue patches through every kelp bed and
 * coral fan in it.
 *
 * <p>Having got the colour in there, this is what gets it back out again. Missing it would give the
 * one asymmetry a player would actually notice: water that goes into a stair coloured and comes out
 * of it plain.
 */
@Mixin(SimpleWaterloggedBlock.class)
public interface SimpleWaterloggedBlockMixin {

    @Inject(method = "pickupBlock", at = @At("RETURN"), cancellable = true)
    private void hydrarium$takeTint(final LivingEntity user, final LevelAccessor level, final BlockPos pos,
            final BlockState state, final CallbackInfoReturnable<ItemStack> cir) {
        if (level.isClientSide()) {
            return;
        }
        cir.setReturnValue(Containers.take(level, pos, cir.getReturnValue()));
    }
}
