package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.WaterType;
import com.mattjesmc.hydrarium.Waters;
import net.fabricmc.fabric.api.blockgetter.v2.FabricBlockGetter;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.BiomeColors;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.resources.Identifier;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The three-layer resolution, and the one line in 26.2 that makes the whole mod possible.
 *
 * <pre>{@code
 * FluidRenderer.java:96
 *   int tintColor = model.tintSource() != null
 *       ? model.tintSource().colorInWorld(blockState, level, pos) : -1;
 * }</pre>
 *
 * <b>The fluid's tint source is handed the position.</b> Vanilla only ever answers
 * {@code BiomeColors.getAverageWaterColor} from it, so vanilla water is per-biome and looks it —
 * but the seam is already per-block, and it is the same seam herbarium rides for flowers. That one
 * line is the reason this mod is a tint table and not sixteen fluids, and it is worth re-reading on
 * every Minecraft update: <b>if it ever loses {@code pos}, this design dies with it.</b>
 *
 * <p>Note what this seam is <em>not</em>: it is not {@code ColorResolver}, whose {@code (biome, x, z)}
 * signature and {@code BlockTintCache} would have forced one colour per column. It is also not
 * usable as one — {@code ClientLevel.getBlockTint} looks a resolver up in a fixed four-entry map
 * built in a field initialiser and dereferences the result, so a custom {@code ColorResolver} is
 * not an extension point at all, it is a {@code NullPointerException}. The tint source gets a real
 * {@code BlockPos}, Y included, and needs none of that.
 *
 * <h2>Cheapest first, and the last one is byte-for-byte vanilla</h2>
 *
 * <ol>
 *   <li><b>The tint field</b> — a per-position entry, if one exists at {@code pos}. Player-made
 *       water, flow, anything that was mixed. The only layer that costs bytes.</li>
 *   <li><b>The biome's declared water</b> — one catalogue row for a whole planet's green ocean,
 *       storing nothing at all.</li>
 *   <li><b>Vanilla</b> — {@code BiomeColors.getAverageWaterColor(level, pos)}, returned unchanged.
 *       <b>A world with no hydrarium content renders pixel-identical to vanilla</b>, and the
 *       early return below is what makes that a fact rather than an aspiration: not "we compute
 *       the same number", but "we return vanilla's number without touching it".</li>
 * </ol>
 */
public final class WaterTint {

    private WaterTint() {
    }

    /**
     * The fluid surface: still, flowing, falling, and the shoreline overlay.
     *
     * <p>Installed by replacing water's whole {@code FluidModel} — see {@code HydrariumClient} —
     * because the tint source is a field of that record and {@code FluidStateModelSet} builds it
     * from a static constant.
     */
    public static final BlockTintSource SURFACE = new BlockTintSource() {
        @Override
        public int color(final BlockState state) {
            // In hand and in an inventory there is no position and no biome, so there is no
            // hydrarium answer either. Vanilla's water source returns -1 (white, the identity for a
            // multiply) here and so does this.
            return -1;
        }

        @Override
        public int colorInWorld(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
            return resolve(level, pos);
        }
    };

    /**
     * Splash and drip particles, which are a <b>separate registration</b> and are the thing a
     * session that swapped only the fluid model will spend an afternoon on.
     *
     * <p>{@code BlockColors} puts {@code BlockTintSources.waterParticles()} on {@code Blocks.WATER}
     * and {@code Blocks.BUBBLE_COLUMN}, read through {@code colorAsTerrainParticle} — a different
     * method on the same interface, reached from a different place. Swap the fluid model alone and
     * red water throws blue splashes.
     *
     * <p>Vanilla's version answers <b>only</b> {@code colorAsTerrainParticle} and leaves
     * {@code colorInWorld} at {@code -1}, and this copies that exactly. Answering
     * {@code colorInWorld} here would tint the water <em>block</em>'s own model — which for
     * {@code minecraft:water} is nothing, but for {@code BUBBLE_COLUMN} is a real surface that
     * vanilla draws untinted.
     */
    public static final BlockTintSource PARTICLES = new BlockTintSource() {
        @Override
        public int color(final BlockState state) {
            return -1;
        }

        @Override
        public int colorAsTerrainParticle(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
            return resolve(level, pos);
        }
    };

    /**
     * The water cauldron, which needs nothing new at all.
     *
     * <p>{@code BlockColors} already registers {@code BlockTintSources.water()} for
     * {@code Blocks.WATER_CAULDRON}, and that is the same pos-aware {@code colorInWorld}. A cauldron
     * sits at a position, so it reads the same field as everything else and substituting the source
     * is the entire render change. It is {@link #SURFACE} by another name and is aliased rather than
     * rebuilt to say so.
     */
    public static final BlockTintSource CAULDRON = SURFACE;

    /**
     * Layers 1, 2 and 3, in that order.
     *
     * <p>Runs on the section-compile thread for the fluid surface and on the client thread for
     * particles and fog, so it touches nothing mutable that it does not own: the field mirror is
     * concurrent and {@link Waters} is written once before either thread exists.
     *
     * <p>Public because the fog is a second render surface that has to agree with this one — see
     * {@code WaterFogEnvironmentMixin}, which derives the fog colour from the ratio this answer
     * bears to vanilla's.
     */
    public static int resolve(final BlockAndTintGetter level, final BlockPos pos) {
        WaterType water = Waters.get(ClientTintField.at(pos));
        if (water == null) {
            water = declaredForBiome(level, pos);
        }

        final int vanilla = BiomeColors.getAverageWaterColor(level, pos);
        // The claim, kept by returning rather than by recomputing.
        return water == null ? vanilla : modulate(water.tint(), vanilla, water.biomeStrength());
    }

    /**
     * Layer 2: what this biome is declared to be made of.
     *
     * <p>The biome comes from {@code FabricBlockGetter.getBiomeFabric}, which
     * fabric-block-getter-api-v2 implements on {@code RenderSectionRegion} specifically so that a
     * renderer can ask — vanilla's own {@code BlockAndTintGetter} exposes biomes only through
     * {@code ColorResolver}, which is the door this class's header explains is shut.
     *
     * <p>{@code hasBiomes()} is checked rather than assumed because the contract allows a getter to
     * have none, and the honest answer for a region with no biomes is "no declared water" rather
     * than a crash in a chunk-build thread.
     *
     * <p>Package-private rather than private because {@link FrozenTint} is layer 2 as well: a biome
     * declared to be made of green water has green ice in it, and the two surfaces have to agree
     * about that from the same method. What they do <em>not</em> share is layer 3 — see there.
     */
    static WaterType declaredForBiome(final BlockAndTintGetter level, final BlockPos pos) {
        if (!Waters.anyBiomeDeclared() || !(level instanceof FabricBlockGetter getter) || !getter.hasBiomes()) {
            return null;
        }
        final Holder<Biome> biome = getter.getBiomeFabric(pos);
        if (biome == null) {
            return null;
        }
        final Identifier id = biome.unwrapKey().map(key -> key.identifier()).orElse(null);
        return Waters.forBiome(id);
    }

    /**
     * Shade a declared colour by the biome, by as much as that water asked for.
     *
     * <p><b>A straight multiply is the wrong default and this is the arithmetic that says why.</b>
     * Multiplying a saturated red by a swamp's murky green gives brown; by an ocean's blue, near
     * black. Both are "shaded by biome" and neither reads as red. So the result is a blend between
     * the colour as declared and the colour as multiplied, and {@code strength} is how far along
     * that line it sits: {@code 0} is the declared colour exactly, {@code 1} is vanilla's own
     * behaviour for untinted water.
     *
     * <p>Per channel, and in integers, because the alternative is a float round trip per water
     * block per section compile for a difference no eye can see.
     */
    private static int modulate(final int tint, final int biome, final float strength) {
        return ARGB.color(
                channel(ARGB.red(tint), ARGB.red(biome), strength),
                channel(ARGB.green(tint), ARGB.green(biome), strength),
                channel(ARGB.blue(tint), ARGB.blue(biome), strength));
    }

    private static int channel(final int tint, final int biome, final float strength) {
        final float multiplied = tint * biome / 255.0F;
        return Math.clamp(Math.round(tint + (multiplied - tint) * strength), 0, 255);
    }
}
