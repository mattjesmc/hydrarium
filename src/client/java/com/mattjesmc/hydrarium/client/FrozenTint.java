package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.FrozenWater;
import com.mattjesmc.hydrarium.WaterType;
import com.mattjesmc.hydrarium.Waters;
import net.minecraft.client.color.block.BlockTintSource;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.core.BlockPos;
import net.minecraft.util.ARGB;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The colour of water that has stopped moving: snow, powder snow and the four ices.
 *
 * <p>It is {@link WaterTint} at a different phase and shares its first two layers exactly — the
 * per-position field, then the biome's declared water — because a frozen position is a position and
 * the field never cared what block was standing on it. What it does <b>not</b> share is the third
 * layer and the arithmetic, and both differences are the same fact seen twice: <b>vanilla does not
 * tint ice or snow at all.</b>
 *
 * <h2>Layer 3 is not vanilla's answer, it is no answer</h2>
 *
 * {@code WaterTint.resolve} ends by returning {@code BiomeColors.getAverageWaterColor} unchanged,
 * which is what makes "an untinted world renders pixel-identical to vanilla" a fact about the
 * <em>return statement</em> rather than an aspiration about the arithmetic. Here the equivalent
 * return is {@code -1}: white, the identity for a multiply, which is precisely what a quad with no
 * tint source would have got. The claim is kept the same way and is if anything stronger — there is
 * no number to compute wrongly.
 *
 * <p>The same fact removes the biome from the arithmetic. {@link WaterType#biomeStrength} blends a
 * declared colour toward the <em>biome's own water colour</em>, and ice has none: vanilla's ice is
 * the same blue in a swamp and in a warm ocean. Modulating against a water colour the surface does
 * not otherwise show would be inventing a shading vanilla never applies, so a frozen surface is not
 * biome-shaded, in exactly the way a bucket is not.
 *
 * <h2>The wash, and why it is not a multiply of a multiply</h2>
 *
 * DESIGN.md asked for {@code modulate}'s shape "against a fixed icy white". Written out, that is
 * {@code blend(tint, tint * white)} — and {@code tint * white} is {@code tint}, so it is a blend of
 * a colour with itself and does nothing whatever. The intent behind the sentence is right and the
 * arithmetic in it is not, so the operation here is a plain per-channel lerp <b>toward</b> white:
 * {@link #frost}.
 *
 * <p>And the target is pure white rather than an icy blue-white, which is the second correction and
 * the more useful one. The blue an icy white would add is <em>already in the sprite</em> — that is
 * what {@code block/ice} is — so blending toward it and then multiplying by it applies ice's own
 * colour twice, and the frozen surfaces would drift blue exactly as fast as they drift pale. White
 * is the only target that leaves the sprite in sole charge of what the material looks like, which
 * is the same division of labour that lets the water bucket's mask be grey.
 */
public final class FrozenTint {

    private FrozenTint() {
    }

    /**
     * The one source, registered on every block in {@link FrozenWater} and answering both of the
     * methods that matter.
     *
     * <p><b>One source for all seven blocks, not one per family.</b> The wash strength is read from
     * the row rather than from the source, so snow and ice differ by a number in a table instead of
     * by a second implementation of the same three lines — which is what keeps them from acquiring
     * different resolution rules by accident, and is the render-side half of the claim that they
     * are one substance in two phases.
     *
     * <p>Unlike {@link WaterTint#PARTICLES}, this answers {@code colorAsTerrainParticle} <b>and</b>
     * {@code colorInWorld} — the first by inheriting it from the second, which is
     * {@code BlockTintSource}'s own default. Water had to keep them apart because
     * {@code BUBBLE_COLUMN} shares the water particle source and has a real block model vanilla
     * draws untinted. Nothing here shares anything with anything, so a red ice block breaking into
     * red shards is the default falling out correctly rather than a decision.
     */
    public static final BlockTintSource SOURCE = new BlockTintSource() {

        /**
         * In hand, in an inventory, in an item frame. A block of ice as an <em>item</em> carries no
         * water id — it is not a container and there is nothing on it to read — so it is vanilla's
         * ice, and {@code -1} is how a tint source says so.
         */
        @Override
        public int color(final BlockState state) {
            return -1;
        }

        @Override
        public int colorInWorld(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
            return resolve(state, level, pos);
        }
    };

    /**
     * Layers 1 and 2, then white.
     *
     * <p>Runs on the section-compile thread, and reads only the concurrent mirror and the table
     * {@link Waters} filled before either thread existed.
     *
     * <p>The {@code frozen == null} branch cannot be reached through {@link #SOURCE}, which is only
     * ever registered on blocks that have a row — it is here because the alternative to a branch is
     * a {@code NullPointerException} in a chunk-build thread if that ever stops being true.
     */
    public static int resolve(final BlockState state, final BlockAndTintGetter level, final BlockPos pos) {
        final FrozenWater frozen = FrozenWater.of(state);
        if (frozen == null) {
            return -1;
        }
        WaterType water = Waters.get(ClientTintField.at(pos));
        if (water == null) {
            water = WaterTint.declaredForBiome(level, pos);
        }
        return water == null ? -1 : frost(water.tint(), frozen.frost());
    }

    /**
     * A colour washed toward white, per channel, in integers.
     *
     * <p>{@code strength} is how far: {@code 0} is the declared colour exactly, which is what snow
     * takes because a near-white sprite multiplies it faithfully; {@code 1} is white, which is no
     * colour at all. See {@link FrozenWater} for why the number belongs to the surface.
     *
     * <p>Opaqued on the way out, and that is not decoration. The block path applies this through
     * {@code ARGB.multiply}, which multiplies <b>alpha as well</b>: a plain {@code 0xRRGGBB} handed
     * over here has alpha zero, and every tinted ice block in the world would render as nothing at
     * all. It is the same trap {@code WaterTintSource} documents for items, reached down a different
     * path, and it fails the same silent way.
     */
    public static int frost(final int tint, final float strength) {
        return ARGB.color(
                channel(ARGB.red(tint), strength),
                channel(ARGB.green(tint), strength),
                channel(ARGB.blue(tint), strength));
    }

    private static int channel(final int value, final float strength) {
        return Math.clamp(Math.round(value + (255 - value) * strength), 0, 255);
    }
}
