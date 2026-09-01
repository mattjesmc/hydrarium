package com.mattjesmc.hydrarium.mixin.client;

import com.mattjesmc.hydrarium.client.ClientTintField;
import com.mattjesmc.hydrarium.client.WaterTint;
import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.fog.environment.WaterFogEnvironment;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The second render surface, and the one that breaks the effect the moment your head goes under.
 *
 * <p>Underwater fog is {@code visual/water_fog_color}, a <b>biome-level</b> environment attribute
 * computed from the camera rather than from a block. So layer 2 gets this almost for free — a
 * consumer declaring an alien ocean sets the biome's fog colour in the same biome JSON it already
 * writes — but <b>layer 1 has no biome to set</b>. A player-made red pool would be red from outside
 * and ordinary blue from inside it, which is worse than not tinting it at all, because it reads as
 * a bug rather than as a limitation.
 *
 * <h2>The fog colour is derived, not chosen</h2>
 *
 * The obvious implementation is to use the water's tint as the fog colour, and it is wrong: vanilla
 * water fog is far darker and greyer than vanilla water, by an amount that differs per biome — a
 * swamp's fog is not its water dimmed by the same factor an ocean's is. Picking a number here means
 * picking one that is right in one biome.
 *
 * <p>So the <b>ratio</b> is what is preserved. Whatever relationship the biome's fog bears to the
 * biome's water, the tinted fog bears to the tinted water. That has the property this file most
 * needs: when the water is untinted, the tinted water equals vanilla's water, the ratio is one, and
 * <b>the fog comes back byte-identical</b> — the same "pixel-identical to vanilla" claim the tint
 * source makes, kept by the same trick of dividing by the thing you are about to multiply.
 */
@Mixin(WaterFogEnvironment.class)
public abstract class WaterFogEnvironmentMixin {

    @Inject(method = "getBaseColor", at = @At("RETURN"), cancellable = true)
    private void hydrarium$tintFog(final ClientLevel level, final Camera camera, final int renderDistance,
            final float partialTicks, final CallbackInfoReturnable<Integer> cir) {
        // Layer 1 only. A biome-declared water already coloured its own fog through the biome's own
        // attribute, and re-deriving it here would apply the shift twice.
        final BlockPos pos = camera.blockPosition();
        if (ClientTintField.at(pos) == null) {
            return;
        }
        final int vanillaWater = BiomeColors.getAverageWaterColor(level, pos);
        final int tintedWater = WaterTint.resolve(level, pos);
        cir.setReturnValue(hydrarium$rescale(cir.getReturnValue(), vanillaWater, tintedWater));
    }

    /**
     * {@code fog * tinted / vanilla}, per channel, alpha untouched.
     *
     * <p>The {@code max(1, ...)} is not paranoia: a biome whose water colour has a zero channel is
     * ordinary — several vanilla biomes are close — and dividing by it would give a fog channel of
     * infinity, which as an integer is whatever the clamp says and as a picture is a coloured
     * flash on entering the water.
     */
    @Unique
    private static int hydrarium$rescale(final int fog, final int from, final int to) {
        return ARGB.color(ARGB.alpha(fog),
                hydrarium$channel(ARGB.red(fog), ARGB.red(from), ARGB.red(to)),
                hydrarium$channel(ARGB.green(fog), ARGB.green(from), ARGB.green(to)),
                hydrarium$channel(ARGB.blue(fog), ARGB.blue(from), ARGB.blue(to)));
    }

    @Unique
    private static int hydrarium$channel(final int fog, final int from, final int to) {
        return Math.clamp(Math.round(fog * (float) to / Math.max(1, from)), 0, 255);
    }
}
