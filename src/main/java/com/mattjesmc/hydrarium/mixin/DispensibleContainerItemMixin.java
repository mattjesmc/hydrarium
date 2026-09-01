package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * A dispenser firing a bucket of powder snow.
 *
 * <p><b>{@code checkExtraContent} is the same seam the water bucket uses</b> — it is what the
 * tropical fish bucket puts its fish through, it receives the stack and the position the contents
 * actually went to, and the dispenser calls it right after {@code emptyContents} succeeds. The
 * water bucket reaches it through {@code BucketItem}'s own override ({@code BucketItemMixin}); the
 * powder snow bucket does not override it at all, so the call lands on the <b>empty default</b> in
 * this interface, and that default is what this file fills in.
 *
 * <p>Injecting into an interface default rather than into {@code SolidBucketItem} is what makes the
 * two paths distinct: {@code BucketItem} overrides {@code checkExtraContent} and never calls
 * {@code super}, so a water bucket cannot reach this hook and be stamped twice.
 *
 * <p>The hand path is <b>not</b> here — {@code SolidBucketItem.useOn} places through
 * {@code BlockItem.place} and never calls {@code emptyContents}, so it needs its own hook. See
 * {@code BlockItemMixin}, which also explains why it cannot simply be the tail of {@code useOn},
 * and which states the rule both of them apply.
 */
@Mixin(DispensibleContainerItem.class)
public interface DispensibleContainerItemMixin {

    /**
     * Stamp the dispensed position with the water this bucket was holding.
     *
     * <p>{@code HEAD}, because the default body is a single {@code return} and there is nothing to
     * be after. Through the same {@link Containers#place} the hand goes through, because a
     * dispenser is a hand that nobody is holding — and unconditionally, including for a bucket with
     * no tint, since an untinted bucket fired at a painted position has to clear it exactly as
     * pouring plain water into a red pool does.
     */
    @Inject(method = "checkExtraContent", at = @At("HEAD"))
    private void hydrarium$dispenseTint(final LivingEntity user, final Level level, final ItemStack itemStack,
            final BlockPos pos, final CallbackInfo ci) {
        if (level.isClientSide()) {
            return;
        }
        Containers.place(level, pos, itemStack);
    }
}
