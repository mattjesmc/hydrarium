package com.mattjesmc.hydrarium.mixin.client;

import com.mattjesmc.hydrarium.FrozenItem;
import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.WaterType;
import com.mattjesmc.hydrarium.Waters;
import com.mattjesmc.hydrarium.client.FrozenTint;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.BreakingItemParticle;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.SingleQuadParticle;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.util.ARGB;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStackTemplate;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A thrown snowball shattering, which is the one render surface the frozen items do not already
 * cover — and the frozen half's version of "red water throws blue splashes".
 *
 * <h2>Everything else about a thrown snowball was already free</h2>
 *
 * <p>{@code ThrowableItemProjectile} carries the whole {@link net.minecraft.world.item.ItemStack} in
 * synched entity data, and {@code ThrownItemRenderer} draws it by resolving the item model for
 * {@code entity.getItem()}. So a red snowball <em>flies</em> red with no code of ours, for exactly
 * the reason a tinted water bottle renders tinted with none: the colour is on the stack and the
 * stack is what gets drawn. Only the impact is not.
 *
 * <p>And vanilla very nearly hands us that too. {@code Snowball.getParticle} spawns eight
 * {@code ParticleTypes.ITEM} particles built from the thrown stack rather than the flat
 * {@code ITEM_SNOWBALL} type, and {@code BreakingItemParticle.ItemParticleProvider.getSprite}
 * resolves that stack's item model to pick which sprite the flecks are cut from. What it does
 * <b>not</b> do is read the model's tint — {@code SingleQuadParticle} starts white and no item
 * particle in the game has ever been coloured — so a red snowball would burst into white flecks,
 * with everything rendering and nothing logged.
 *
 * <h2>Why the tint is recomputed rather than read off the resolved model</h2>
 *
 * <p>{@code ItemStackRenderState.LayerRenderState.tintLayers()} does hold the answer the model
 * computed, and reaching it would be the tidier-looking fix. It is not reachable from here:
 * {@code pickParticleMaterial} chooses a layer at random and returns only that layer's
 * <em>material</em>, so there is no way to ask which layer was picked, and a second resolve to find
 * out would run the whole item model again per fleck. The colour is three integers out of a table;
 * computing it is cheaper than looking it up.
 *
 * <h2>The guard is the table, not the component</h2>
 *
 * <p>This method is on the path of every {@code minecraft:item} particle in the game, so it has to
 * refuse almost all of them, and it refuses by asking {@link FrozenItem} rather than by asking
 * whether the stack has a {@code hydrarium:tint}. A tinted water <em>bottle</em> carries that
 * component and spawns item particles when it is drunk; its colour lives in
 * {@code minecraft:potion_contents} and vanilla has never tinted those flecks either. Widening this
 * to "anything stamped" would be inventing a shading for a surface this mod does not otherwise
 * claim.
 */
@Mixin(BreakingItemParticle.Provider.class)
public abstract class BreakingItemParticleMixin {

    /**
     * Colour the fleck the way the item it came off is coloured.
     *
     * <p>The full descriptor rather than a bare name, because {@code Provider} implements
     * {@code ParticleProvider<ItemParticleOption>} and the compiler emits a bridge with the erased
     * {@code ParticleOptions} parameter beside the real method. Both are called
     * {@code createParticle}.
     *
     * <p>{@link FrozenItem#frost()} is read rather than assumed to be zero. It is zero for every row
     * that can reach here today — a snowball is a lump of snow, and snow takes no wash — but the
     * number belongs to the surface and reading it is what keeps a fleck the same colour as the
     * icon it broke off.
     */
    @Inject(method = "createParticle(Lnet/minecraft/core/particles/ItemParticleOption;"
            + "Lnet/minecraft/client/multiplayer/ClientLevel;DDDDDD"
            + "Lnet/minecraft/util/RandomSource;)Lnet/minecraft/client/particle/Particle;",
            at = @At("RETURN"))
    private void hydrarium$tintFleck(final ItemParticleOption options, final ClientLevel level,
            final double x, final double y, final double z,
            final double xAux, final double yAux, final double zAux, final RandomSource random,
            final CallbackInfoReturnable<Particle> cir) {
        final ItemStackTemplate stack = options.getItem();
        final FrozenItem held = FrozenItem.of(stack.item().value());
        if (held == null) {
            return;
        }
        final WaterType water = Waters.get(stack.get(HydrariumComponents.TINT));
        if (water == null || !(cir.getReturnValue() instanceof SingleQuadParticle quad)) {
            return;
        }
        final int tint = FrozenTint.frost(water.tint(), held.frost());
        quad.setColor(ARGB.red(tint) / 255.0F, ARGB.green(tint) / 255.0F, ARGB.blue(tint) / 255.0F);
    }
}
