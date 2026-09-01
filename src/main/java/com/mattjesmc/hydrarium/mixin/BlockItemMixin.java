package com.mattjesmc.hydrarium.mixin;

import com.llamalad7.mixinextras.sugar.Local;
import com.mattjesmc.hydrarium.Containers;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Putting a block down by hand, which is where the tint field learns that a position has stopped
 * being the water it used to be.
 *
 * <h2>Nothing is hooked on removal, so ARRIVAL is where the colour is decided</h2>
 *
 * <p>{@code TintField} is advisory: an entry outlives the water that made it, and a read validates
 * against the block actually at the position. That was airtight while only <em>water</em> could
 * wear a colour, because water only ever arrives at a position through {@code spreadTo} or a
 * bucket, and both of those write the field on the way in. The frozen half widened the predicate to
 * ice and snow — and ice and snow arrive by a route that had no such hook. <b>So a stale entry
 * stopped being invisible and started being a colour on the next block anybody put there.</b>
 *
 * <p>That is one bug wearing three faces, and all three were reported as separate ones: plain ice
 * placed <em>into</em> coloured water came out coloured (the water's entry was still at that
 * position); plain ice placed <em>beside</em> coloured ice came out coloured (the water that made
 * the neighbour had flowed through there too); and a colour left behind by water that went away
 * showed up on whatever was built there later. The field was doing exactly what it was documented
 * to do in all three.
 *
 * <p>The rule this file adds is therefore the mirror image of the one {@code TintField} refuses:
 * <b>removal is not hooked and arrival is.</b> A block that a player put down wears the colour its
 * <em>item</em> declared — which for an ordinary block of ice is none, and clearing is a real
 * answer rather than nothing to do, exactly as it is for a plain bucket poured into a red pool.
 * Freezing and Frost Walker still inherit, because they are a phase change at a position the water
 * never left; a hand and a dispenser do not, because they are not. See
 * {@link Containers#place} for the one exception (waterlogging) and why it is not one.
 *
 * <h2>Why the hook is on BlockItem and not on the bucket</h2>
 *
 * <p>A powder snow bucket is a {@code SolidBucketItem}, which is a {@code BlockItem} that plays a
 * different sound: it overrides {@code useOn} to empty the player's hand afterwards and inherits
 * <b>everything about placement</b>, {@code place} included. So there is no method on
 * {@code SolidBucketItem} that knows where the block went — the position is computed inside
 * {@code BlockItem.place}, out of a {@code BlockPlaceContext} that method builds for itself.
 *
 * <p>Reconstructing that context at {@code useOn}'s tail does not work, and it is worth naming
 * because it is the obvious thing to try: {@code new BlockPlaceContext(context)} decides whether it
 * is <em>replacing</em> the clicked block by looking at the world, and by the tail of {@code useOn}
 * the world already has the snow in it. The second context answers a different question from the
 * first, at a position that is right almost always — which is the worst kind of almost.
 *
 * <p>So the hook is here, on the one method that has the answer. It was already the busiest path in
 * the game before it stopped being about powder snow, and it is now one field read and a chunk
 * lookup on every block placement anybody makes. That is a real cost and honestly the largest this
 * mod adds; the alternatives were a position that is sometimes wrong, or a mod in which every
 * frozen block a player places is haunted by whatever used to be at that spot.
 *
 * <h2>Before the consume, not at the tail</h2>
 *
 * <b>{@code place} ends by calling {@code itemStack.consume(1, player)}, and that empties the very
 * stack the tint has to be read from.</b> A {@code TAIL} injection would read a stack of count zero
 * and find no component on it, so tinted powder snow would place tinted only while the player was
 * holding more than one bucket — a bug with a shape almost designed to survive testing.
 *
 * <p>Injecting immediately before that call gets a position that is assigned and a stack that is
 * still there. It also sidesteps the other reason {@code TAIL} was wrong: {@code place} has five
 * early {@code FAIL} returns before {@code pos} exists at all, and that call is on the one path
 * that placed something.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMixin {

    /**
     * Tell the position what colour the thing just put there is, which is usually none.
     *
     * <p>Unconditionally, for every block item rather than only for the buckets that carry a tint —
     * because the write that matters most is the {@code null} one. A guard that skipped stacks with
     * no {@code hydrarium:tint} component would leave every ordinary block placement free to
     * inherit a stale colour, which is the whole of what this hook exists to stop.
     */
    @Inject(method = "place",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;consume"
                            + "(ILnet/minecraft/world/entity/LivingEntity;)V"))
    private void hydrarium$placeTint(final BlockPlaceContext placeContext,
            final CallbackInfoReturnable<InteractionResult> cir, @Local final BlockPos pos) {
        if (placeContext.getLevel().isClientSide()) {
            return;
        }
        Containers.place(placeContext.getLevel(), pos, placeContext.getItemInHand());
    }
}
