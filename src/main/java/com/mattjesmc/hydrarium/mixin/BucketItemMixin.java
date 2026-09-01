package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.TintField;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Pouring a tinted bucket out, from a hand or from a dispenser, in one hook.
 *
 * <p><b>{@code checkExtraContent} is the seam vanilla built for exactly this.</b> It is what the
 * tropical fish bucket uses to put its fish in the water it just placed, it receives the stack and
 * the position the fluid actually went to, and — the part that matters here — it is called from
 * both {@code BucketItem.use} and {@code DispenseItemBehavior}, so hand use, dispensers and
 * droppers are one method between them.
 *
 * <p>It is also called <em>after</em> the water is placed, which is the correct order: the field
 * entry lands on a position that already holds water, so the very next validating read agrees with
 * it. Writing the tint first would leave a stale entry behind on the one path where placement then
 * fails.
 */
@Mixin(BucketItem.class)
public abstract class BucketItemMixin {

    @Shadow
    protected Fluid content;

    /**
     * Stamp the poured water's colour onto the position it was poured into.
     *
     * <p>Unconditionally, including when the bucket carries no tint — because {@code null} is a
     * real answer here and not a "nothing to do". Pouring an ordinary bucket of ordinary water into
     * a red pool must clear that position, exactly as the join would if the water had flowed in;
     * skipping the write would let a player launder plain water into whatever colour used to be at
     * that block.
     */
    @Inject(method = "checkExtraContent", at = @At("TAIL"))
    private void hydrarium$pourTint(final LivingEntity user, final Level level, final ItemStack stack,
            final BlockPos pos, final CallbackInfo ci) {
        if (level.isClientSide() || this.content != Fluids.WATER) {
            return;
        }
        final Identifier water = HydrariumComponents.tintOf(stack);
        TintField.set(level, pos, water);
    }
}
