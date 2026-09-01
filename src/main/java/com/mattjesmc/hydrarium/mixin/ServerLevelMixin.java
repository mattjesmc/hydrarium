package com.mattjesmc.hydrarium.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mattjesmc.hydrarium.TintField;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Snow falling out of the sky, which is the second way a frozen block arrives at a position that
 * never asked for one.
 *
 * <p>{@code BlockItemMixin} states the rule: removal is not hooked, so <b>arrival</b> is where a
 * block's colour is decided, and a block that did not come from the water at that position wears
 * no colour. A hand and a dispenser are one such arrival; weather is the other, and it is the one
 * nobody is watching when it happens. Water that flowed over a hillside and drained away leaves its
 * entries behind by design — and a snowy biome will then quietly lay a red drift along the whole
 * channel, hours later, with nothing to connect it to the bucket that caused it.
 *
 * <p>{@code tickPrecipitation} makes exactly three block changes and the rule reads them apart by
 * what is being placed rather than by which call site it is, so there is no ordinal here to go
 * stale on the next vanilla refactor:
 *
 * <ul>
 *   <li><b>Ice</b>, over water that just froze — a phase change at a position the water never left.
 *       It inherits, which is the whole of the frozen half.</li>
 *   <li><b>A deeper snow layer</b>, over snow that is already there. Left alone: whatever that
 *       drift is, adding to it does not make it something else.</li>
 *   <li><b>A first snow layer</b>, over air. Fresh precipitation, made of nothing that was ever at
 *       this position, so the entry — if the map is still carrying one — is cleared before the
 *       block that would have worn it exists.</li>
 * </ul>
 *
 * <p>The clear is a {@link TintField#set} of {@code null}, which for the overwhelmingly common case
 * of a chunk with no tints at all is a chunk lookup and an early return; snowfall is already a
 * random tick that reads a heightmap and two block states.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelMixin {

    /**
     * Wrap all three of {@code tickPrecipitation}'s block changes, act on the one that is fresh
     * snow, and hand every one of them straight on.
     *
     * <p>The owner is left off the target so that the match survives {@code setBlockAndUpdate}
     * being resolved against {@code Level} rather than {@code ServerLevel} — it is declared on the
     * former and called on the latter, and which of the two the compiler writes into the call site
     * is not a thing to depend on.
     */
    @WrapOperation(method = "tickPrecipitation",
            at = @At(value = "INVOKE",
                    target = "setBlockAndUpdate(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;)Z"))
    private boolean hydrarium$precipitate(final ServerLevel level, final BlockPos pos, final BlockState state,
            final Operation<Boolean> original) {
        if (state.is(Blocks.SNOW) && !level.getBlockState(pos).is(Blocks.SNOW)) {
            TintField.set(level, pos, null);
        }
        return original.call(level, pos, state);
    }
}
