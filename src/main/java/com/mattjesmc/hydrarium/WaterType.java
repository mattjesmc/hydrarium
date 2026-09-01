package com.mattjesmc.hydrarium;

import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/**
 * One water: what colour it is, what it does, how far its biome may push it, and whether it mixes.
 *
 * <p>The {@code id} is the atom {@link WaterMix} works over. It is deliberately the identity rather
 * than the tint: two waters that happen to share a colour are still two waters, so mixing them goes
 * through the blend rule rather than collapsing - which is right, because they may differ in
 * {@link #effect}, and a rule that looked only at the number would silently launder one behaviour
 * into another.
 *
 * @param id            namespaced; the namespace is the mod that declared it, never hydrarium's
 *                      unless hydrarium declared it
 * @param tint          plain {@code 0xRRGGBB}, opaqued on the way to the renderer and never here -
 *                      see the client half for why that matters more than it looks like it should
 * @param effect        chosen from {@link Effect}, which is a closed set in Java on purpose
 * @param biomeStrength how much of the biome's own water colour bleeds into this one, {@code 0}
 *                      (this colour exactly, everywhere) to {@code 1} (a straight multiply by the
 *                      biome, which is vanilla's own behaviour for untinted water)
 * @param dye           the dye this water <i>is</i>, or {@code null} for a water that is nothing but
 *                      a colour, and so the one thing that decides whether it blends. {@link WaterMix}
 *                      mixes two waters by mixing their dyes through vanilla's own recipes, so a
 *                      water with no dye has nothing to mix and comes out {@link WaterMix#MURK grey}
 *                      against anything but itself. hydrarium's sixteen say which dye they are; ash
 *                      slurry and lumewater say nothing, which is the honest answer - the game has
 *                      no recipe for "ash slurry plus lumewater", and inventing one here would be
 *                      hydrarium making up content on a consumer's behalf. It is a declared field
 *                      rather than a match on the id for the same reason: a consumer whose water
 *                      <i>should</i> take part in dye mixing writes {@code "dye": "cyan"} in its
 *                      catalogue row and is in, without being named {@code cyan} and without
 *                      hydrarium guessing from a path.
 */
public record WaterType(Identifier id, int tint, Effect effect, float biomeStrength, DyeColor dye) {

    /**
     * The default modulation, and it is low for a reason.
     *
     * <p>A straight multiply by the biome — {@code biomeStrength = 1} — is what "shaded per biome"
     * sounds like it should mean, and it is the wrong default: it crushes every saturated tint
     * towards the biome's own green-blue, so a red pool in a swamp comes out brown and a red pool
     * in an ocean comes out purple, and neither reads as red. A quarter is enough to keep the seam
     * between two biomes visible in tinted water without the colour stopping being itself.
     *
     * <p>Note which way round this knob is documented. {@code 0} is not "no biome shading is
     * possible", it is "this water is this colour" — which is the correct default for an authored
     * alien ocean, and is why rocketeer's rows will mostly leave it alone.
     */
    public static final float DEFAULT_BIOME_STRENGTH = 0.25F;
}
