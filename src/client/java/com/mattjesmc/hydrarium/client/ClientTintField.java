package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.ChunkTints;
import com.mattjesmc.hydrarium.Hydrarium;
import com.mattjesmc.hydrarium.TintField;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * The client's copy of the tint field, shaped for the one thread that reads it.
 *
 * <h2>Why this is a mirror and not a read of the chunk</h2>
 *
 * The field is already on the client — Fabric syncs the attachment onto the client's own
 * {@code LevelChunk} — so a mirror looks like duplication. It is not, and the reason is which
 * thread asks.
 *
 * <p>{@code BlockTintSource.colorInWorld} is called from the <b>section-compile thread</b>, and the
 * only level-shaped thing it is handed is a {@code BlockAndTintGetter}, which for that path is a
 * {@code RenderSectionRegion}: a deliberately thread-safe <em>snapshot</em> of block states with no
 * accessor for the {@code ClientLevel} behind it. Reaching a chunk attachment from there would mean
 * going through {@code Minecraft.getInstance().level} and doing a chunk-source lookup off-thread —
 * which is exactly the access pattern {@code SectionCopy} exists so that vanilla does not have to
 * do.
 *
 * <p>So the read path is a flat concurrent map that no game object owns, and the write path is the
 * client thread applying what the server said. That also makes the update and the <b>re-mesh</b>
 * one event instead of two, which matters because the tint is baked into vertex colour at compile
 * time: a colour that changes without a re-mesh is a colour that does not change until something
 * else happens to dirty the section.
 *
 * <h2>The mirror needs no validation and that is not an oversight</h2>
 *
 * {@link TintField} validates every read against the block actually at the position, because the
 * field is advisory and stale entries are expected. Nothing here does, because <b>the renderer only
 * asks about positions it is already drawing water at</b>. A stale entry at a position that a
 * sponge emptied is never consulted: no water, no fluid quad, no question. The validation the
 * server needs is bought here by the shape of the caller.
 */
public final class ClientTintField {

    /**
     * Chunk key to that chunk's snapshot. Both levels are immutable once installed, so a render
     * thread reading while the client thread swaps a chunk's entry sees the old snapshot or the new
     * one and never a half-built map.
     */
    private static final ConcurrentMap<Long, ChunkTints> CHUNKS = new ConcurrentHashMap<>();

    private ClientTintField() {
    }

    /** Called once from client init. */
    public static void install() {
        ClientChunkEvents.CHUNK_LOAD.register(ClientTintField::onLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register((level, chunk) -> CHUNKS.remove(chunk.getPos().pack()));
    }

    /**
     * The water at a position, or {@code null} for clear. Called from the section-compile thread,
     * once per water block per compile.
     *
     * <p>The {@code isEmpty} guard is what keeps the promise that a world with no hydrarium content
     * renders identically to vanilla at no cost: in that world this is one volatile read of a field
     * that is never written, and the whole three-layer resolution collapses to
     * {@code BiomeColors.getAverageWaterColor}.
     */
    public static Identifier at(final BlockPos pos) {
        if (CHUNKS.isEmpty()) {
            return null;
        }
        final ChunkTints tints = CHUNKS.get(ChunkPos.pack(
                SectionPos.blockToSectionCoord(pos.getX()), SectionPos.blockToSectionCoord(pos.getZ())));
        return tints == null ? null : tints.get(pos);
    }

    /**
     * Take the chunk's current field and subscribe to its changes.
     *
     * <p>Both halves are needed and neither subsumes the other. The initial read catches a field
     * that arrived with the chunk — Fabric's initial sync may have applied the attachment before
     * this event fires — and the listener catches every change after, including a first arrival
     * that lost the race. A chunk that is loaded and then re-loaded registers a second listener on
     * a second chunk object, which is fine: the old object and its listeners go with it.
     */
    private static void onLoad(final ClientLevel level, final LevelChunk chunk) {
        final ChunkTints present = chunk.getAttached(TintField.ATTACHMENT);
        if (present != null && !present.isEmpty()) {
            CHUNKS.put(chunk.getPos().pack(), present);
        }
        chunk.onAttachedSet(TintField.ATTACHMENT).register((was, now) -> apply(level, chunk, now));
    }

    /**
     * Install a new snapshot and dirty every section that could be showing it.
     *
     * <p><b>The whole chunk column, not the sections that changed.</b> Working out which sections a
     * diff touched is easy and wrong to spend effort on here: an attachment update is one packet
     * carrying one chunk's entire field, so the sections it touches are only knowable by diffing
     * two maps, and the answer is almost always "the one section the player just poured a bucket
     * into". Dirtying the column costs a handful of empty rebuilds — a section with no water in it
     * rebuilds to the same geometry — and it cannot be wrong. If a spreading front ever shows up in
     * a profile, this is the line to look at, and the fix is a diff rather than anything cleverer.
     *
     * <p>{@code WithNeighbors} because a fluid quad's shape is computed from the neighbouring
     * sections' fluid states, so the section border is drawn by both sides.
     */
    private static void apply(final ClientLevel level, final LevelChunk chunk, final ChunkTints now) {
        final ChunkPos pos = chunk.getPos();
        if (now == null || now.isEmpty()) {
            if (CHUNKS.remove(pos.pack()) == null) {
                return;
            }
        } else {
            CHUNKS.put(pos.pack(), now);
        }
        for (int y = level.getMinSectionY(); y <= level.getMaxSectionY(); y++) {
            level.setSectionDirtyWithNeighbors(pos.x(), y, pos.z());
        }
        Hydrarium.LOG.debug("hydrarium: chunk {} tint field updated ({} entries)",
                pos, now == null ? 0 : now.size());
    }
}
