package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.FrozenWater;
import com.mattjesmc.hydrarium.Hydrarium;
import com.mattjesmc.hydrarium.Waters;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.render.fluid.v1.FluidRenderingRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.BlockColorRegistry;
import net.minecraft.client.renderer.block.FluidModel;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.Fluids;

/**
 * The client half: every registration, and each one is a render surface that the others do not
 * cover.
 *
 * <p>They are listed together here rather than beside the code they configure precisely because
 * <b>getting one and missing another is the failure mode</b>, and it is a silent one — every
 * surface renders, one of them in the wrong colour.
 *
 * <p>The liquid phase:
 *
 * <ol>
 *   <li><b>The fluid</b>: still, flowing, falling, and the shoreline overlay.</li>
 *   <li><b>The particles</b>: splashes and drips, which come from a different registry and a
 *       different method on the same interface.</li>
 *   <li><b>The cauldron</b>: a block, not a fluid, that vanilla already points at the same
 *       position-aware source.</li>
 *   <li><b>The bucket</b>: an <em>item</em>, and therefore the one surface with no position to read
 *       — it goes to the stack's own component instead. See {@code WaterTintSource}.</li>
 *   <li><b>The fog</b>, which is a mixin rather than a registration and lives in
 *       {@code WaterFogEnvironmentMixin}. Named here so that this list is the whole list.</li>
 * </ol>
 *
 * <p>The frozen phase, which is water that stopped moving and is drawn by five more:
 *
 * <ol start="6">
 *   <li><b>The frozen blocks</b>: snow, powder snow and the four ices, coloured by the same
 *       {@code BlockColorRegistry} the cauldron uses.</li>
 *   <li><b>Their models</b>, which is the half that has no equivalent in the liquid phase at all:
 *       vanilla puts no {@code tintindex} on ice or snow, so there is nowhere for the colour above
 *       to land until {@code FrozenModels} adds one after the bake.</li>
 *   <li><b>The powder snow cauldron</b>, which needs neither a model wrap nor an idea — vanilla's
 *       cauldron template already tint-indexes its content face for the water cauldron's sake.</li>
 *   <li><b>The powder snow bucket</b>: the second item surface, reading the same component through
 *       the same {@code hydrarium:water_tint} source as the water bucket, with its own mask and its
 *       own fitted fallback.</li>
 *   <li><b>The frozen items</b>: ice, packed ice, blue ice, snow blocks, snow layers and
 *       <b>snowballs</b> in a hand, an inventory, a dropped stack or in flight. The third item
 *       surface and the one that needed no mask, because an ice item is vanilla's ice sprite and the
 *       block is that same sprite multiplied by the same number — so what was missing was a tint
 *       index rather than a picture. See {@code FrozenTintSource} and the generator's
 *       {@code FROZEN_ITEMS}.
 *       <p>A <em>thrown</em> snowball is on this surface and not on one of its own, which is worth
 *       saying because it looks like it should be: {@code ThrowableItemProjectile} carries the whole
 *       stack in synched entity data and {@code ThrownItemRenderer} draws it by resolving that
 *       stack's item model. Red goes in, red comes out, with no code of ours — the same free win the
 *       tinted bottle gets, down the same route.</li>
 *   <li><b>The flecks a snowball bursts into</b>, which is the one part of that not free. See
 *       {@code BreakingItemParticleMixin}: vanilla builds those particles out of the thrown stack
 *       and cuts them from the right sprite, and then never colours them.</li>
 * </ol>
 *
 * <p>Eleven surfaces and two tables. Six of them are a loop over {@link FrozenWater#values()},
 * {@code FrozenItem.values()} or {@link Waters}, which is the only arrangement in which "we forgot
 * the snow" is not a thing that can be true of one of them.
 *
 * <h2>The fluid model is a registration and not a mixin, which DESIGN.md did not expect</h2>
 *
 * {@code FluidStateModelSet.bake} is a static that hardcodes {@code BlockTintSources.water()} into a
 * private constant, so the design budgeted for mixing into it. It turns out
 * fabric-rendering-fluids-v1 already does exactly that ({@code FluidStateModelSetMixin}) and
 * exposes {@code FluidRenderingRegistry.register} over the top of it — the supported way to replace
 * a fluid's whole {@code FluidModel}, tint source included. Registering there rather than mixing in
 * ourselves means one mixin fewer, and it means hydrarium composes with any other mod that
 * re-registers a fluid model instead of racing it.
 */
public final class HydrariumClient implements ClientModInitializer {

    /**
     * Vanilla's own three sprites, named again because a {@code FluidModel.Unbaked} is a whole
     * record and there is no way to replace one field of the one vanilla built.
     *
     * <p>hydrarium ships <b>no water texture</b>. Tinted water is {@code minecraft:water} wearing
     * {@code minecraft:block/water_still}, which is the entire point: a colour is a multiply into a
     * quad the renderer was going to draw anyway, and a mod that shipped its own water sprite would
     * have to ship sixteen of them or tint one — and tinting one is what this already is.
     */
    private static final Material STILL = vanilla("block/water_still");
    private static final Material FLOWING = vanilla("block/water_flow");
    private static final Material OVERLAY = vanilla("block/water_overlay");

    @Override
    public void onInitializeClient() {
        ClientTintField.install();

        // Both fluids, one model -- which is vanilla's own arrangement (FluidStateModelSet.bake
        // maps WATER and FLOWING_WATER to the same baked object) and is why flowing water and still
        // water cannot come out different colours by accident.
        FluidRenderingRegistry.register(Fluids.WATER, Fluids.FLOWING_WATER,
                new FluidModel.Unbaked(STILL, FLOWING, OVERLAY, WaterTint.SURFACE));

        // The particle surface. Vanilla registers waterParticles() on exactly these two blocks and
        // reads it through colorAsTerrainParticle; ours answers the same method and leaves
        // colorInWorld alone for the same reason vanilla does. Swap the fluid model and forget this
        // line and red water throws blue splashes -- everything renders, nothing logs.
        BlockColorRegistry.register(List.of(WaterTint.PARTICLES), Blocks.WATER, Blocks.BUBBLE_COLUMN);

        // The cauldron. Vanilla already registers water() here, so this is a substitution and not
        // an addition: a water cauldron sits at a position and therefore reads the same field as
        // everything else, with no cauldron-specific code anywhere on the render path.
        BlockColorRegistry.register(List.of(WaterTint.CAULDRON), Blocks.WATER_CAULDRON);

        // The fifth surface, and the only one that is not a position. A bucket has no BlockPos, so
        // it reads the component off the stack -- see WaterTintSource for why that means no biome
        // modulation and why that is right. The item model that names it is generated:
        // assets/minecraft/items/water_bucket.json, one of the eight vanilla assets this mod
        // overrides -- two buckets, the five frozen blocks that are also items, and the snowball.
        WaterTintSource.install();

        // And the one line that says which water a bucket holds. Not a render surface at all, but
        // it is what makes twenty otherwise identical creative stacks tellable apart. It reads the
        // component rather than the item, so the powder snow bucket is named by it for free.
        WaterLabel.install();

        // The frozen phase, and the two halves of it that are useless apart. First the models: ice
        // and snow have no tintindex, so without this the colour below has nowhere to land.
        FrozenModels.install();

        // The tenth surface, and the second half of the frozen phase's item support: a stamped
        // block of ice in a hand, and a stamped snowball in a hand or in flight. Same component as
        // the buckets, a different source only because the frost belongs to the surface and the
        // model file is where each item declares its own. (The eleventh, the flecks a snowball
        // bursts into, is a mixin rather than a registration -- BreakingItemParticleMixin -- and is
        // named in the list above so that this list is the whole list.)
        FrozenTintSource.install();

        // Then the colour itself, on every block in the table -- including the powder snow cauldron,
        // whose model needed no wrapping because vanilla's cauldron template already carries a tint
        // index for the water cauldron's sake.
        for (final FrozenWater frozen : FrozenWater.values()) {
            BlockColorRegistry.register(List.of(FrozenTint.SOURCE), frozen.block());
        }

        Hydrarium.LOG.info("hydrarium client: {} waters over 11 render surfaces (fluid, particles,"
                        + " cauldron, fog, bucket; {} frozen blocks, {} of them model-wrapped,"
                        + " their items and the flecks those break into); {} biomes declared",
                Waters.all().size(), FrozenWater.values().length, FrozenModels.wrapped(),
                Waters.biomeCount());
    }

    private static Material vanilla(final String path) {
        return new Material(Identifier.withDefaultNamespace(path));
    }
}
