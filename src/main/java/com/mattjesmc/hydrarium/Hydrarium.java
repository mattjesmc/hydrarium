package com.mattjesmc.hydrarium;

import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A water library. Waters are rows in a table; the fluid they ride on is vanilla's.
 *
 * <h2>The asymmetry this whole mod is arranged around</h2>
 *
 * A <b>fluid</b> is a registry entry, a block, a bucket, a tag membership, and a compatibility
 * surface with every mob, boat, sponge, fishing rod and waterlogged stair in the game — sixteen
 * fluids is sixteen times that surface and sixteen places for a mod that never heard of you to
 * break. A <b>colour</b> is three integers a renderer multiplies into a quad it was going to draw
 * anyway.
 *
 * <p>So <b>hydrarium registers zero fluids.</b> Tinted water <em>is</em> {@code minecraft:water} —
 * same tag, same bucket, same swim, same sponge, same waterlogged stair — and the colour is a
 * side-channel ({@link TintField}) that the renderer reads and nothing else in the game has to know
 * about. This is herbarium's {@code shape is expensive, colour is free} pointed at a different
 * noun, and the same regression test applies: an "improvement" that prices a colour at a fluid is
 * not one.
 *
 * <h2>The free win that is easy to miss</h2>
 *
 * Because the tint is positional and not part of the block state, <b>a waterlogged stair can be
 * tinted</b>. A custom fluid never can: {@code waterlogged=true} means vanilla water and nothing
 * else, forever. The fluid-per-colour design would have left blue patches through every kelp bed
 * and coral fan in a tinted ocean.
 *
 * <h2>Nothing here may reference the client half</h2>
 *
 * {@code BlockTintSource} is {@code @Environment(CLIENT)} and lives in {@code src/client}. This
 * class and everything it touches runs on a dedicated server too, so a water's <em>field entry</em>
 * and a water's <em>colour</em> are deliberately different objects reached from different source
 * sets — which is what makes "the field half is server-safe" a claim the compiler checks rather
 * than one a reader has to trust.
 */
public final class Hydrarium implements ModInitializer {

    public static final String MOD_ID = "hydrarium";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    public static Identifier id(final String path) {
        return Identifier.fromNamespaceAndPath(MOD_ID, path);
    }

    /**
     * Read every loaded mod's catalogue, then wire the seams that need the table to exist.
     *
     * <p>Order matters in one direction only: {@link Waters#load} must run before any
     * {@code ClientModInitializer}, so that the client half finds the table filled. That is
     * guaranteed by Fabric — {@code main} entrypoints all run before {@code client} ones — and is
     * the same window herbarium uses to register blocks.
     */
    @Override
    public void onInitialize() {
        // First, and it is load-bearing rather than tidy: this is what loads the class whose static
        // initialiser registers hydrarium:tint, and the registry it registers into is only open
        // during this method. See HydrariumComponents.install.
        HydrariumComponents.install();
        Waters.load(WaterCatalogue.scanLoadedMods());
        TintField.register();
        CauldronTint.install();
        WaterEffects.install();
        CreativeWaters.install();
        WaterCommand.install();
        SmokeCheck.installIfRequested();
        LOG.info("hydrarium: {} waters over {} declaring mods, {} biomes declared, 0 fluids registered",
                Waters.all().size(), Waters.declaringMods(), Waters.biomeCount());
    }
}
