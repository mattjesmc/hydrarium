package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.WaterEffects;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The entity-tick check, in the one place that sees every living entity.
 *
 * <p>{@code TAIL} rather than {@code HEAD} so that {@code isInWater()} has been recomputed for this
 * tick before {@link WaterEffects#tick} asks about it — at {@code HEAD} the answer is one tick
 * stale, which shows up as an effect that lingers for a tick after leaving the water and, more
 * visibly, as nothing happening on the tick you enter it.
 *
 * <p>All the cost that is not "is this entity in water" lives in {@code WaterEffects} and is
 * reached only after that test fails to exclude the entity. This method body is what runs for every
 * living entity in every loaded chunk, so it stays a call and a comment.
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {

    @Inject(method = "baseTick", at = @At("TAIL"))
    private void hydrarium$waterEffects(final CallbackInfo ci) {
        WaterEffects.tick((LivingEntity) (Object) this);
    }
}
