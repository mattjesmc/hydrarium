package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.Flow;
import com.mattjesmc.hydrarium.TintField;
import com.mattjesmc.hydrarium.WaterMix;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.FlowingFluid;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Where flowing water gets its colour, and the only mixin in the mod that is about behaviour rather
 * than about a container. The rule itself lives in {@link Flow}; this file is the two places
 * vanilla will let us ask for it.
 *
 * <h2>Two hooks, because a cell is filled once and re-evaluated many times</h2>
 *
 * <b>{@code spreadTo} is the funnel for every fill.</b> Sideways spread, falling, the drop into a
 * hole and — because it routes waterlogging through {@code LiquidBlockContainer.placeLiquid} —
 * flowing into a stair all go through it. There is no second path to miss.
 *
 * <p><b>But it fires ONCE per cell, not once per parent, and not again afterwards.</b> This is the
 * thing to know about this file and it is invisible in the method's signature. {@code getSpread}
 * ends with
 *
 * <pre>{@code
 * if (testFluidState.canBeReplacedWith(level, testPos, newFluid.getType(), direction)) {
 *     result.put(direction, newFluid);
 * }
 * }</pre>
 *
 * and water can never be replaced with water, so a cell that already holds water is never spread
 * into again. Two consequences, and v1 shipped with both:
 *
 * <ol>
 *   <li>An implementation that reads {@code direction} and takes that one parent's colour paints
 *       every cell with whichever front arrived first and never joins anything — while looking
 *       completely correct, because flow carries colour perfectly along any channel with one source
 *       in it. That is why {@code direction} is deliberately unused and {@link Flow} gathers the
 *       feeders from the world at fill time instead.</li>
 *   <li>A cell painted at fill time is painted <b>forever</b>. Change what feeds it — pour a
 *       different colour into the pool above it, or let a second front arrive a tick late — and
 *       nothing tells it. That reads to a player as "blending is broken", because the case where it
 *       shows is the case a player builds on purpose: two colours meeting.</li>
 * </ol>
 *
 * <p>So {@code tick} is the second hook. It is vanilla's own "this cell should re-evaluate itself"
 * — scheduled when a neighbour changes, not run every tick — and it is exactly the moment the
 * colour should re-evaluate too. {@link Flow#wake} is what schedules it for a colour change that
 * vanilla has no reason to care about, and the two together are the wave that carries a repaint
 * downstream. See {@link Flow} for why that terminates.
 *
 * <p>Sources are untouched by the second hook, which is the whole of the model: a source holds a
 * colour and a flowing cell derives one. {@code tick} skips its own re-evaluation for a source too,
 * so this is vanilla's shape rather than an exception to it.
 */
@Mixin(FlowingFluid.class)
public abstract class FlowingFluidMixin {

    /**
     * Carry the tint into the cell that is about to be filled.
     *
     * <p>At {@code HEAD}, so that {@code state} is still the destination's <em>old</em> state and
     * the neighbours are still the ones that fed it — both unanswerable after the write.
     */
    @Inject(method = "spreadTo", at = @At("HEAD"))
    private void hydrarium$carryTint(final LevelAccessor level, final BlockPos pos, final BlockState state,
            final Direction direction, final FluidState target, final CallbackInfo ci) {
        // Lava spreads through this same method. It has no tint field, and asking for one would
        // wipe any tint at the position it is about to destroy, which is at best pointless.
        if (level.isClientSide() || !target.is(FluidTags.WATER)) {
            return;
        }

        final Identifier arriving = Flow.tintOf(level, pos, target);

        // Joining with what is already there is belt and braces rather than the mechanism -- see
        // the class doc: a cell reached by spreadTo is almost always air. It stays because the
        // waterlogging path routes a block that is NOT air through here, and joining against an
        // existing colour is never the wrong answer.
        final Identifier result = TintField.holdsWater(state)
                ? WaterMix.join(level, TintField.id(level, pos), arriving)
                : arriving;

        TintField.set(level, pos, result);
    }

    /**
     * Re-derive the colour of a cell the game has just re-evaluated.
     *
     * <p>At {@code TAIL} rather than {@code HEAD} because {@code tick} is where the cell's own level
     * is recomputed and where {@code spread} runs: at the head the amount may be about to change and
     * the feeder test would be run against a stale one. At the tail the position has settled — or
     * has become air, in which case {@link Flow#repaint} finds no water and does nothing.
     *
     * <p>The state is re-read from the world rather than taken from the parameters for the same
     * reason: vanilla reassigns both locals on its way through.
     */
    @Inject(method = "tick", at = @At("TAIL"))
    private void hydrarium$repaint(final ServerLevel level, final BlockPos pos, final BlockState blockState,
            final FluidState fluidState, final CallbackInfo ci) {
        Flow.repaint(level, pos);
    }
}
