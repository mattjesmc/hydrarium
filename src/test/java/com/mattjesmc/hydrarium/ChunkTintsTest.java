package com.mattjesmc.hydrarium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import org.junit.jupiter.api.Test;

/**
 * The storage layer's own contract: what a chunk's field says, what it costs, and what it does with
 * a file that has been truncated or hand-edited.
 *
 * <p>The persistence codec is worth a test of its own because its failure is the quietest one in
 * the mod. A field whose palette and index lists disagree does not throw and does not log — it
 * decodes into a field where every position has drifted one place along its colours, which looks
 * exactly like a mod that mixes badly.
 */
class ChunkTintsTest {

    private static final Identifier RED = Identifier.fromNamespaceAndPath("hydrarium", "red");
    private static final Identifier BLUE = Identifier.fromNamespaceAndPath("hydrarium", "blue");

    private static ChunkTints of(final Map<BlockPos, Identifier> entries) {
        final Map<Long, Identifier> byPos = new LinkedHashMap<>();
        entries.forEach((pos, water) -> byPos.put(pos.asLong(), water));
        return new ChunkTints(byPos);
    }

    @Test
    void anEmptyFieldIsEmpty() {
        assertTrue(ChunkTints.EMPTY.isEmpty());
        assertEquals(0, ChunkTints.EMPTY.size());
    }

    /**
     * Clearing a position <b>removes</b> the entry rather than storing clear.
     *
     * <p>If it stored one, an untinted world would stop storing nothing the moment anybody emptied
     * a plain bucket anywhere, and every chunk that had ever held a colour would keep paying for it
     * in every save forever.
     */
    @Test
    void clearingRemovesRatherThanStores() {
        final BlockPos pos = new BlockPos(3, 64, 7);
        final ChunkTints red = ChunkTints.EMPTY.with(pos, RED);
        assertEquals(1, red.size());
        assertTrue(red.with(pos, null).isEmpty());
    }

    /**
     * A write that changes nothing returns the same object.
     *
     * <p>This is not a micro-optimisation: {@link TintField#set} uses identity here to decide
     * whether to touch the attachment at all, and touching it is what costs a sync packet and a
     * section re-mesh. Water ticking over an already-red pool would otherwise re-mesh it every
     * tick.
     */
    @Test
    void anUnchangedWriteIsTheSameObject() {
        final BlockPos pos = new BlockPos(3, 64, 7);
        final ChunkTints red = ChunkTints.EMPTY.with(pos, RED);
        assertSame(red, red.with(pos, RED));
        assertSame(ChunkTints.EMPTY, ChunkTints.EMPTY.with(pos, null));
    }

    @Test
    void readsBackWhatWasWritten() {
        final BlockPos here = new BlockPos(3, 64, 7);
        final BlockPos there = new BlockPos(-1200, -32, 45000);
        final ChunkTints tints = ChunkTints.EMPTY.with(here, RED).with(there, BLUE);
        assertEquals(RED, tints.get(here));
        assertEquals(BLUE, tints.get(there));
        assertNull(tints.get(new BlockPos(0, 0, 0)));
    }

    /** Negative and large coordinates survive the long packing, which is where they usually do not. */
    @Test
    void survivesTheWholeWorldsCoordinateRange() {
        final List<BlockPos> corners = List.of(
                new BlockPos(-29999984, -64, -29999984),
                new BlockPos(29999984, 319, 29999984),
                new BlockPos(-1, -1, -1),
                new BlockPos(0, 0, 0));
        ChunkTints tints = ChunkTints.EMPTY;
        for (final BlockPos pos : corners) {
            tints = tints.with(pos, RED);
        }
        assertEquals(corners.size(), tints.size());
        for (final BlockPos pos : corners) {
            assertEquals(RED, tints.get(pos), () -> pos + " did not survive packing");
        }
    }

    @Test
    void roundTripsThroughThePersistenceCodec() {
        final ChunkTints before = ChunkTints.EMPTY
                .with(new BlockPos(1, 2, 3), RED)
                .with(new BlockPos(4, 5, 6), RED)
                .with(new BlockPos(7, 8, 9), BLUE);

        final DataResult<JsonElement> encoded = ChunkTints.CODEC.encodeStart(JsonOps.INSTANCE, before);
        assertTrue(encoded.error().isEmpty(),
                () -> "encode failed: " + encoded.error().orElseThrow().message());

        final DataResult<ChunkTints> decoded =
                ChunkTints.CODEC.parse(JsonOps.INSTANCE, encoded.result().orElseThrow());
        assertTrue(decoded.error().isEmpty(),
                () -> "decode failed: " + decoded.error().orElseThrow().message());
        assertEquals(before.byPos(), decoded.result().orElseThrow().byPos());
    }

    /**
     * The palette is a palette: three entries over two colours write two identifiers, not three.
     *
     * <p>The compression is the reason the disk form differs from the memory form at all, so it is
     * worth asserting rather than assuming — an encoder that quietly stopped deduplicating would
     * still round-trip perfectly and would cost an authored pool its whole size in repeated strings.
     */
    @Test
    void thePaletteDeduplicates() {
        final ChunkTints tints = ChunkTints.EMPTY
                .with(new BlockPos(1, 2, 3), RED)
                .with(new BlockPos(4, 5, 6), RED)
                .with(new BlockPos(7, 8, 9), BLUE);
        final JsonElement json = ChunkTints.CODEC
                .encodeStart(JsonOps.INSTANCE, tints).result().orElseThrow();
        assertEquals(2, json.getAsJsonObject().getAsJsonArray("palette").size());
        assertEquals(3, json.getAsJsonObject().getAsJsonArray("pos").size());
        assertEquals(3, json.getAsJsonObject().getAsJsonArray("water").size());
    }

    /**
     * A field whose two parallel lists disagree is rejected rather than decoded askew.
     *
     * <p>This is the quiet failure the check exists for: without it a truncated chunk file decodes
     * into a field where every position has slid one place along its colours — every tint wrong,
     * nothing logged, and nothing to distinguish it from a mod that mixes badly.
     */
    @Test
    void rejectsAFieldWhoseListsDisagree() {
        final JsonElement json = com.google.gson.JsonParser.parseString(
                "{\"palette\":[\"hydrarium:red\"],\"pos\":[1,2,3],\"water\":[0,0]}");
        assertTrue(ChunkTints.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent(),
                "a field with 3 positions and 2 colours decoded as if it were fine");
    }

    /** And one whose index is outside its palette, which is the other half of the same corruption. */
    @Test
    void rejectsAnIndexOutsideThePalette() {
        final JsonElement json = com.google.gson.JsonParser.parseString(
                "{\"palette\":[\"hydrarium:red\"],\"pos\":[1],\"water\":[7]}");
        assertTrue(ChunkTints.CODEC.parse(JsonOps.INSTANCE, json).error().isPresent(),
                "colour 7 of a one-colour palette decoded as if it were fine");
    }

    @Test
    void anEmptyFieldRoundTrips() {
        final JsonElement json = ChunkTints.CODEC
                .encodeStart(JsonOps.INSTANCE, ChunkTints.EMPTY).result().orElseThrow();
        assertTrue(ChunkTints.CODEC.parse(JsonOps.INSTANCE, json).result().orElseThrow().isEmpty());
    }

    /** The bulk removal the sweep uses, including the no-op case it takes most often. */
    @Test
    void withoutDropsListedPositions() {
        final BlockPos kept = new BlockPos(1, 2, 3);
        final BlockPos dropped = new BlockPos(4, 5, 6);
        final ChunkTints tints = ChunkTints.EMPTY.with(kept, RED).with(dropped, BLUE);
        assertSame(tints, tints.without(List.of()));
        final ChunkTints swept = tints.without(List.of(dropped.asLong()));
        assertEquals(1, swept.size());
        assertEquals(RED, swept.get(kept));
    }

    /** The record is immutable however it was built, so a render thread can read one without a lock. */
    @Test
    void isImmutableAgainstItsSourceMap() {
        final Map<Long, Identifier> source = new LinkedHashMap<>();
        source.put(new BlockPos(1, 2, 3).asLong(), RED);
        final ChunkTints tints = new ChunkTints(source);
        source.clear();
        assertEquals(1, tints.size());
        assertEquals(RED, tints.get(new BlockPos(1, 2, 3)));
    }

    @Test
    void ofHelperMatchesTheBuilder() {
        assertEquals(ChunkTints.EMPTY.with(new BlockPos(1, 2, 3), RED).byPos(),
                of(Map.of(new BlockPos(1, 2, 3), RED)).byPos());
    }
}
