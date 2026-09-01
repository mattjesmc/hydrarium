package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.FrozenWater;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelLoadingPlugin;
import net.fabricmc.fabric.api.client.model.loading.v1.ModelModifier;
import net.minecraft.client.renderer.block.dispatch.BlockStateModel;
import net.minecraft.client.renderer.block.dispatch.BlockStateModelPart;
import net.minecraft.client.renderer.block.BlockAndTintGetter;
import net.minecraft.client.resources.model.geometry.BakedQuad;
import net.minecraft.client.resources.model.sprite.Material;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import org.jspecify.annotations.Nullable;

/**
 * Giving ice and snow the tint index vanilla never gave them.
 *
 * <h2>The open question DESIGN.md left, answered by reading the assets</h2>
 *
 * DESIGN.md set stage 3 down with one thing to be <em>checked and not guessed</em>: whether
 * vanilla's ice model can take a {@code tintindex} with its texture left alone. The answer is no,
 * and the reason is worth stating exactly, because it is not "vanilla forbids it":
 *
 * <pre>
 *   block/ice        →  parent block/cube_all  →  parent block/cube  →  six faces, no tintindex
 * </pre>
 *
 * <b>A {@code tintindex} lives on a face, and a child model inherits its parent's elements
 * wholesale.</b> {@code block/ice} declares one texture and nothing else, so there is no way to add
 * a tint index to it without also restating vanilla's geometry — which means shipping
 * {@code assets/minecraft/models/block/ice.json} and ten more like it, overriding a vanilla asset
 * per frozen block, going stale the day vanilla edits a snow height, and losing the tint entirely
 * for any resource pack that gives ice a model of its own. That is cheapness #1 spent on a phase
 * change, and DESIGN.md's rule against it is right.
 *
 * <p>So the tint index goes on <b>after the bake</b>, where a quad is a record with a
 * {@code tintIndex} field and vanilla's own JSON is never touched. hydrarium ships no block model
 * and no block texture, the untinted world is byte-identical to vanilla, and a pack that redraws
 * ice keeps its tint because the wrap is applied to whatever that pack baked.
 *
 * <h2>Why this is a plain BlockStateModel and not a WrapperBlockStateModel</h2>
 *
 * <p>Fabric ships {@code WrapperBlockStateModel} for exactly this shape and it is <b>the wrong base
 * class here</b>, in a way that would have been silent. It delegates every method to the wrapped
 * model, {@code emitQuads} included — and {@code emitQuads} is the Fabric renderer's entry point,
 * the one Indigo and every other renderer implementation calls instead of walking
 * {@code collectParts}. Overriding only {@code collectParts} on top of it would tint every quad
 * under vanilla's renderer and none at all under a renderer, with everything drawing and nothing
 * logging. The default {@code FabricBlockStateModel.emitQuads} routes through {@code collectParts},
 * so implementing the interface directly and delegating deliberately is what makes one override
 * cover both paths.
 *
 * <p>Two methods are then left un-delegated on purpose. {@code emitQuads} is the point of the
 * exercise. {@code createGeometryKey} is a renderer's cache key, and a key describing the untinted
 * model would invite a cached mesh built before the tint index existed; the interface default
 * ({@code null}, meaning "do not cache") is what every vanilla model answers anyway.
 */
public final class FrozenModels implements ModelLoadingPlugin {

    private FrozenModels() {
    }

    /**
     * Called once from client init, and it is exactly half of a surface.
     *
     * <p>The other half is {@code BlockColorRegistry} in {@code HydrariumClient}: a tint index with
     * no registered source resolves to {@code -1} and draws vanilla, and a registered source with no
     * tint index is never asked. Both loop over {@link FrozenWater#values()} so that the two halves
     * cannot disagree about which blocks are frozen.
     */
    public static void install() {
        ModelLoadingPlugin.register(new FrozenModels());
    }

    @Override
    public void initialize(final Context context) {
        // WRAP_PHASE rather than the default, because that is what the phase is for: a mod that
        // REPLACES ice's model should have done so by now, and this wraps whatever it left.
        context.modifyBlockModelAfterBake().register(ModelModifier.WRAP_PHASE, (model, modifierContext) -> {
            final FrozenWater frozen = FrozenWater.of(modifierContext.state());
            if (frozen == null || !frozen.needsTintIndex()) {
                return model;
            }
            return new Tinted(model);
        });
    }

    /**
     * The wrapped model: vanilla's, with every quad's tint index set to 0.
     *
     * <p><b>Every quad, and not a filtered subset.</b> All seven blocks this is applied to are made
     * of one material — six faces of ice, or a slab of snow — so "the frozen part of the model" and
     * "the model" are the same set. The one frozen block that is <em>not</em> uniform is the powder
     * snow cauldron, which is nine-tenths iron; it is excluded here by
     * {@link FrozenWater#needsTintIndex()} rather than by a rule about sprites, because vanilla's
     * cauldron template already tint-indexes its content face and nothing else.
     */
    private static final class Tinted implements BlockStateModel {

        private final BlockStateModel wrapped;

        /**
         * Wrapped parts, by identity of the part they wrap.
         *
         * <p>A plain array scanned with {@code ==} rather than a hash map, and that is deliberate:
         * vanilla's part is {@code SimpleModelWrapper}, a <b>record</b> whose {@code hashCode} walks
         * its whole quad collection. Hashing one to look it up would cost more per block than the
         * work being cached. Every model here has one part, most have exactly one, and a linear
         * scan of one element is as fast as a lookup gets.
         *
         * <p>Volatile and copy-on-write because {@code collectParts} runs on every section-compile
         * thread at once. A reader sees either the old array or the new one, never a half-filled
         * one, and a lost race costs one duplicated wrapper rather than a wrong colour.
         */
        private volatile Entry[] parts = new Entry[0];

        private Tinted(final BlockStateModel wrapped) {
            this.wrapped = wrapped;
        }

        /**
         * Collect vanilla's parts, then swap each one for its tinted twin in place.
         *
         * <p>In place, on the caller's own list, because this runs once per frozen block per section
         * rebuild and a scratch list per call would be an allocation per ice block in the world.
         */
        @Override
        public void collectParts(final RandomSource random, final List<BlockStateModelPart> output) {
            final int first = output.size();
            this.wrapped.collectParts(random, output);
            for (int i = first; i < output.size(); i++) {
                output.set(i, this.tinted(output.get(i)));
            }
        }

        private BlockStateModelPart tinted(final BlockStateModelPart part) {
            final Entry[] known = this.parts;
            for (final Entry entry : known) {
                if (entry.plain() == part) {
                    return entry.tinted();
                }
            }
            final TintedPart made = new TintedPart(part);
            final Entry[] grown = new Entry[known.length + 1];
            System.arraycopy(known, 0, grown, 0, known.length);
            grown[known.length] = new Entry(part, made);
            this.parts = grown;
            return made;
        }

        @Override
        public Material.Baked particleMaterial() {
            return this.wrapped.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return this.wrapped.materialFlags();
        }

        // The position-aware trio, delegated explicitly rather than left to the interface defaults,
        // so that a model which overrides them -- a pack's, a mod's -- keeps its answers.

        @Override
        public Material.Baked particleMaterial(final BlockAndTintGetter level, final BlockPos pos,
                final BlockState state) {
            return this.wrapped.particleMaterial(level, pos, state);
        }

        @Override
        public int materialFlags(final BlockAndTintGetter level, final BlockPos pos, final BlockState state,
                final RandomSource random) {
            return this.wrapped.materialFlags(level, pos, state, random);
        }

        @Override
        public boolean hasMaterialFlag(final BlockAndTintGetter level, final BlockPos pos, final BlockState state,
                final RandomSource random, final int flag) {
            return this.wrapped.hasMaterialFlag(level, pos, state, random, flag);
        }

        private record Entry(BlockStateModelPart plain, BlockStateModelPart tinted) {
        }
    }

    /**
     * One part's quads, re-recorded with a tint index, computed once at wrap time.
     *
     * <p>The seven lists are the six directions plus the unculled {@code null} bucket, which is the
     * whole of what {@code getQuads} can be asked for. Building them eagerly makes this object
     * immutable and therefore safe to hand to every section-compile thread at once, and it is the
     * reason the cache above is worth having: without it this work would happen per block rather
     * than per model.
     */
    private static final class TintedPart implements BlockStateModelPart {

        private static final Direction[] DIRECTIONS = Direction.values();

        private final BlockStateModelPart wrapped;
        private final List<BakedQuad>[] quads;

        @SuppressWarnings("unchecked")
        private TintedPart(final BlockStateModelPart wrapped) {
            this.wrapped = wrapped;
            this.quads = new List[DIRECTIONS.length + 1];
            for (final Direction direction : DIRECTIONS) {
                this.quads[direction.ordinal()] = tint(wrapped.getQuads(direction));
            }
            this.quads[DIRECTIONS.length] = tint(wrapped.getQuads(null));
        }

        /**
         * A quad is a record and so is its {@code MaterialInfo}, so "the same quad with a tint
         * index" is a constructor call and nothing else — no reflection, no mutable baked state,
         * and the sprite, the layer, the shading and the light emission carried over by name rather
         * than by position.
         */
        private static List<BakedQuad> tint(final List<BakedQuad> plain) {
            final List<BakedQuad> tinted = new ArrayList<>(plain.size());
            for (final BakedQuad quad : plain) {
                final BakedQuad.MaterialInfo material = quad.materialInfo();
                tinted.add(new BakedQuad(
                        quad.position0(), quad.position1(), quad.position2(), quad.position3(),
                        quad.packedUV0(), quad.packedUV1(), quad.packedUV2(), quad.packedUV3(),
                        quad.direction(),
                        new BakedQuad.MaterialInfo(material.sprite(), material.layer(),
                                material.itemRenderType(), 0, material.shade(),
                                material.lightEmission())));
            }
            return List.copyOf(tinted);
        }

        @Override
        public List<BakedQuad> getQuads(final @Nullable Direction direction) {
            return this.quads[direction == null ? DIRECTIONS.length : direction.ordinal()];
        }

        @Override
        public boolean useAmbientOcclusion() {
            return this.wrapped.useAmbientOcclusion();
        }

        @Override
        public Material.Baked particleMaterial() {
            return this.wrapped.particleMaterial();
        }

        @Override
        public int materialFlags() {
            return this.wrapped.materialFlags();
        }
    }

    /** What the wrap covers, for the client's own startup line. */
    public static int wrapped() {
        int count = 0;
        for (final FrozenWater frozen : FrozenWater.values()) {
            if (frozen.needsTintIndex()) {
                count++;
            }
        }
        return count;
    }
}
