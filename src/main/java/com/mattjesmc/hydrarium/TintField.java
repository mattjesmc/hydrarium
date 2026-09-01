package com.mattjesmc.hydrarium;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentSyncPredicate;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Layer 1 of the resolution: the per-position entries, and the only layer that costs bytes.
 *
 * <p>Server-authoritative, attached per chunk, sparse. <b>An untinted world stores nothing</b> — a
 * chunk with no tints carries no attachment, so it writes no NBT, syncs no packet and costs no
 * memory beyond the absent map. That is the claim {@link ChunkTints#isEmpty()} exists to keep true.
 *
 * <h2>Three problems, one Fabric API</h2>
 *
 * DESIGN.md budgeted for hand-rolled storage, hand-rolled persistence and a hand-rolled per-position
 * sync packet. Fabric's data-attachment API is all three: {@code persistent} writes the field into
 * the chunk's own NBT beside vanilla's blocks, and {@code syncWith} ships it to exactly the players
 * tracking that chunk, on chunk send and on every change, with no mixin and no packet id of ours.
 * The one thing it does not do is tell the renderer to re-mesh, which is why the client half still
 * has a listener — see {@code ClientTintField}.
 *
 * <h2>The field is advisory, not authoritative</h2>
 *
 * <b>Nothing is hooked to clear an entry when water leaves a position, and that is deliberate.</b>
 * Water leaves through sponges, explosions, pistons, {@code /fill}, worldgen, freezing, and mods
 * that have never heard of hydrarium. Enumerating those hooks is a losing game and every miss is a
 * stale colour haunting a position forever.
 *
 * <p>So a read <em>validates</em>: {@link #get} answers {@code null} for an entry whose position no
 * longer holds water, whatever the map says, and {@link #sweep} drops such entries when a chunk
 * loads. Nothing needs hooking for correctness — only for tidiness.
 *
 * <p>One consequence falls out for free: <b>water that freezes and later melts comes back the
 * colour it was</b>, because the entry outlives the ice. That used to be a curiosity, because
 * nothing read the entry while the ice stood there and the ice itself rendered vanilla blue. It is
 * now the whole foundation of the frozen half — see {@link FrozenWater} — and reading it is what
 * put {@link #holdsAnyWater} on the hook: an entry under a block that predicate does not recognise
 * is not merely left uncoloured, it is <b>swept</b>, and it is swept in silence, because dropping a
 * stale entry is an ordinary thing for this mod to do.
 *
 * <h2>The one real performance question in the mod</h2>
 *
 * A tint is baked into vertex colour at chunk-compile time, not evaluated per frame, so a colour
 * change is a <b>re-mesh</b>. Fine for a pond; a front of dye crossing an ocean is a section rebuild
 * per tick per section. Nothing here is cleverer than it needs to be yet, and the thing to measure
 * before making it so is section rebuilds per second under a spreading front — not the cost of the
 * map copy, which is smaller and more obvious and therefore the tempting wrong answer.
 */
public final class TintField {

    /**
     * Sparse, persistent, synced to whoever is watching the chunk.
     *
     * <p>{@code AttachmentSyncPredicate.all()} rather than something narrower because a tint is not
     * secret and every player who can see the water can see its colour. A per-player predicate here
     * would be a per-player <em>mesh</em>, which is not a thing the chunk renderer can do.
     */
    public static AttachmentType<ChunkTints> ATTACHMENT;

    private TintField() {
    }

    /** Called once from {@code onInitialize}, before any chunk exists. */
    public static void register() {
        ATTACHMENT = AttachmentRegistry.<ChunkTints>builder()
                .persistent(ChunkTints.CODEC)
                .syncWith(ChunkTints.STREAM_CODEC, AttachmentSyncPredicate.all())
                .buildAndRegister(Hydrarium.id("tints"));

        // The sweep's trigger. See sweep() for why chunk load is the right one and a tick is not.
        ServerChunkEvents.CHUNK_LOAD.register((level, chunk, newChunk) -> sweep(chunk));
    }

    /**
     * The water at this position, or {@code null} for clear — validated against the block actually
     * there.
     *
     * <p>This is the advisory read, and the validation is the whole of it. An entry at a position
     * that a sponge emptied ten minutes ago is not an error to be repaired here; it is simply not
     * an answer.
     */
    public static WaterType get(final LevelReader level, final BlockPos pos) {
        return Waters.get(id(level, pos));
    }

    /**
     * As {@link #get}, but the raw id — so that the join can work over waters this build lost.
     *
     * <p>Still validated. An id hydrarium does not recognise is a perfectly good atom to join over
     * (it is equal to itself and to nothing else, which is all the join asks of it); an id at a
     * position with no water in it is not an answer at all.
     */
    public static Identifier id(final LevelReader level, final BlockPos pos) {
        return holdsAnyWater(level.getBlockState(pos)) ? rawId(level, pos) : null;
    }

    /**
     * The entry at a position with <b>no</b> validation against the block there.
     *
     * <p>There is exactly one situation that wants this, and it is picking water up: by the time a
     * bucket has taken the water, the position no longer holds any, so the validating read would
     * answer clear and the bucket would come away untinted. What the caller is asking here is not
     * "what colour is the water at this position" but "what colour was the water that was just
     * here", and only the raw entry can say.
     *
     * <p>Anything else calling this is reintroducing the stale-entry bug the validation exists to
     * prevent.
     */
    public static Identifier rawId(final LevelReader level, final BlockPos pos) {
        final ChunkAccess chunk = chunkAt(level, pos);
        if (chunk == null) {
            return null;
        }
        final ChunkTints tints = chunk.getAttached(ATTACHMENT);
        return tints == null ? null : tints.get(pos);
    }

    /**
     * Write one position. {@code null} clears it.
     *
     * <p>Setting the attachment is what syncs it — Fabric watches {@code setAttached}, not the map —
     * so this must always go through {@code setAttached} even when the map object it installs is
     * mostly the old one. {@link ChunkTints#with} returning {@code this} unchanged is the guard
     * that keeps a no-op write from costing a packet and a re-mesh, and it is why that method
     * compares before it copies.
     *
     * <p><b>A real change wakes the moving water around it</b>, which is what makes a colour
     * <em>travel</em> rather than sit at the position it was written to. This is the one funnel for
     * that: a bucket, a command, a cauldron drained into a pool and every path added later all
     * start the wave without knowing they did. It hangs off the same identity guard, so an
     * unchanged write still costs nothing at all — see {@link Flow}.
     */
    public static void set(final LevelAccessor level, final BlockPos pos, final Identifier water) {
        final ChunkAccess chunk = chunkAt(level, pos);
        if (chunk == null) {
            return;
        }
        final ChunkTints existing = chunk.getAttached(ATTACHMENT);
        final ChunkTints current = existing == null ? ChunkTints.EMPTY : existing;
        final ChunkTints next = current.with(pos, water);
        if (next == current) {
            return;
        }
        install(chunk, next);
        Flow.wake(level, pos);
    }

    /**
     * Whether a block state is <b>liquid</b> water — the phase that flows.
     *
     * <p>Water, including <b>waterlogged blocks</b> — which is the free win over the fluid-per-colour
     * design and reads here as one line that says nothing about stairs. A waterlogged stair has a
     * water {@code FluidState}, so it holds water, so it holds a tint. A custom fluid could never
     * have done this: {@code waterlogged=true} means vanilla water and nothing else, forever.
     *
     * <p>And the water cauldron, which is not a fluid at all but is a position vanilla already
     * paints with {@code BlockTintSources.water()} — see the client half.
     *
     * <p><b>This is the narrow one, and the callers that want it want it narrowly.</b> The spread
     * hook joins a colour into the water already at a position; the cauldron asks whether there is
     * still water in the pot to colour. Neither question has an answer for a block of ice, and
     * neither should quietly acquire one — a flow that ate a snow layer must not inherit that
     * layer's colour, because the snow did not feed the flow, it was in its way.
     */
    public static boolean holdsWater(final BlockState state) {
        return state.getFluidState().is(FluidTags.WATER) || state.is(Blocks.WATER_CAULDRON);
    }

    /**
     * Whether a block state is water in <b>any</b> phase — the predicate the field validates
     * against.
     *
     * <p>Adding the frozen half to this one method is the server-side whole of DESIGN.md's stage 1,
     * and it is the half that is not the renderer. Before it, a tint entry at a position that froze
     * read as stale: {@link #id} answered clear and {@link #sweep} threw the entry away on the next
     * chunk load. So a renderer taught to colour ice would have been <em>correct</em> and would
     * still have drawn blue ice, because the entry it was reading had already deleted itself
     * underneath — with nothing logged, since a swept entry is a routine event here.
     *
     * <p><b>The order the two halves land in is therefore not free.</b> This predicate goes first;
     * the tint sources go second. The reverse order is a session spent debugging a renderer that
     * was never wrong.
     */
    public static boolean holdsAnyWater(final BlockState state) {
        return holdsWater(state) || FrozenWater.of(state) != null;
    }

    /**
     * Drop entries whose positions no longer hold water.
     *
     * <p><b>Chunk load, not a tick.</b> The stale entries that matter are the ones made while
     * nobody was looking — worldgen, {@code /fill}, another mod's bulk edit, a world opened in an
     * editor — and every one of those is observed on the next load. Sweeping on a tick would spend
     * a budget every tick to find, almost always, nothing; sweeping on load spends it once per
     * chunk per session and catches the same entries. Reads are validated anyway, so the sweep is
     * only ever tidying: skipping it entirely would cost bytes, never correctness.
     */
    public static void sweep(final ChunkAccess chunk) {
        final ChunkTints tints = chunk.getAttached(ATTACHMENT);
        if (tints == null || tints.isEmpty()) {
            return;
        }
        final List<Long> stale = new ArrayList<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (final Map.Entry<Long, Identifier> entry : tints.byPos().entrySet()) {
            pos.set(BlockPos.of(entry.getKey()));
            if (!holdsAnyWater(chunk.getBlockState(pos))) {
                stale.add(entry.getKey());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        Hydrarium.LOG.debug("hydrarium: swept {} stale tint(s) from chunk {}", stale.size(), chunk.getPos());
        install(chunk, tints.without(stale));
    }

    /**
     * Attach, or detach if there is nothing left to say.
     *
     * <p>The empty case is a {@code removeAttached} rather than a {@code setAttached(EMPTY)} so that
     * a chunk which was once tinted and is now clear goes back to costing exactly what a chunk that
     * was never tinted costs. Otherwise a world where somebody once emptied a dye bucket keeps
     * paying for it in every save, forever.
     */
    private static void install(final ChunkAccess chunk, final ChunkTints tints) {
        if (tints.isEmpty()) {
            chunk.removeAttached(ATTACHMENT);
        } else {
            chunk.setAttached(ATTACHMENT, tints);
        }
    }

    /**
     * The chunk at a position, without generating one.
     *
     * <p>{@code loadOrGenerate = false} matters on both sides. On the server a fluid spreading at a
     * chunk border must not drag an ungenerated chunk into existence to ask it about a colour; on
     * the client there is nothing to generate and a miss simply means the chunk has not arrived.
     * Either way the answer to "what colour is water in a chunk that is not here" is clear water.
     */
    private static ChunkAccess chunkAt(final LevelReader level, final BlockPos pos) {
        return level.getChunk(SectionPos.blockToSectionCoord(pos.getX()),
                SectionPos.blockToSectionCoord(pos.getZ()), ChunkStatus.FULL, false);
    }
}
