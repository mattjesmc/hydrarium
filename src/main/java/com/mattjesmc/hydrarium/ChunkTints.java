package com.mattjesmc.hydrarium;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.Identifier;

/**
 * One chunk's worth of the tint field: a sparse map from packed {@link BlockPos} to the id of the
 * water at it.
 *
 * <p><b>Immutable.</b> Every write copies, and that is not laziness — it is what lets the client's
 * render thread read a snapshot with no lock and no chance of tearing while the network thread
 * installs a new one. The cost is real and is bounded by the number of tinted positions in ONE
 * chunk, which for authored content is a pool and for an untinted world is zero. A change also
 * forces a section re-mesh, which is the larger of the two costs by a wide margin, so this is not
 * the thing to optimise first. See {@link TintField} for the sentence that says what to measure.
 *
 * <h2>An empty field is no field</h2>
 *
 * A chunk with no tints holds no attachment at all rather than an empty map, so an untinted world
 * stores nothing, syncs nothing and writes nothing to disk. {@link #isEmpty()} is what
 * {@link TintField} uses to decide between setting and removing, and getting that wrong would cost
 * every chunk in an untinted world an NBT compound apiece for the privilege of saying "nothing
 * here".
 *
 * <h2>Why the wire and disk forms are palettes and the memory form is not</h2>
 *
 * In memory a position looks up an {@link Identifier} in one hash. On disk and on the wire the same
 * data would repeat that identifier once per position — a pool of two hundred red blocks writing
 * {@code "hydrarium:red"} two hundred times. The palette is built at encode time and thrown away at
 * decode time, so the compression is entirely a property of the codec and no caller has to know it
 * exists.
 */
public record ChunkTints(Map<Long, Identifier> byPos) {

    /** The value a chunk with no entries would have. Never actually attached — see the class doc. */
    public static final ChunkTints EMPTY = new ChunkTints(Map.of());

    public ChunkTints {
        byPos = Map.copyOf(byPos);
    }

    public boolean isEmpty() {
        return byPos.isEmpty();
    }

    public int size() {
        return byPos.size();
    }

    /** The water at this position, or {@code null} for clear. */
    public Identifier get(final BlockPos pos) {
        return byPos.get(pos.asLong());
    }

    /**
     * This field with one position changed. A {@code null} water removes the entry rather than
     * storing a "clear" one, because clear is the absence of an entry and storing it would make an
     * untinted pool cost bytes for having once been red.
     */
    public ChunkTints with(final BlockPos pos, final Identifier water) {
        final long key = pos.asLong();
        if (water == null) {
            if (!byPos.containsKey(key)) {
                return this;
            }
            final Map<Long, Identifier> next = new LinkedHashMap<>(byPos);
            next.remove(key);
            return new ChunkTints(next);
        }
        if (water.equals(byPos.get(key))) {
            return this;
        }
        final Map<Long, Identifier> next = new LinkedHashMap<>(byPos);
        next.put(key, water);
        return new ChunkTints(next);
    }

    /** This field with every listed position removed. The sweep's bulk form. */
    public ChunkTints without(final List<Long> keys) {
        if (keys.isEmpty()) {
            return this;
        }
        final Map<Long, Identifier> next = new LinkedHashMap<>(byPos);
        keys.forEach(next::remove);
        return new ChunkTints(next);
    }

    /**
     * The palette form: distinct ids, then one position and one palette index per entry.
     *
     * <p>The two lists are parallel and the decoder checks that, because a truncated or hand-edited
     * chunk file that disagrees about their lengths would otherwise decode into a field whose
     * positions have drifted one place along their colours — every tint wrong, nothing logged, and
     * no way to tell it from a mod that mixes badly.
     */
    public static final Codec<ChunkTints> CODEC = RecordCodecBuilder.<Encoded>create(
            i -> i.group(
                    Identifier.CODEC.listOf().fieldOf("palette").forGetter(Encoded::palette),
                    Codec.LONG.listOf().fieldOf("pos").forGetter(Encoded::positions),
                    Codec.INT.listOf().fieldOf("water").forGetter(Encoded::indices))
                    .apply(i, Encoded::new))
            .comapFlatMap(Encoded::decode, ChunkTints::encode);

    /**
     * The wire form, which is the same palette written by hand.
     *
     * <p>Hand-written rather than derived from {@link #CODEC} through NBT because this runs on
     * every tint change of every watched chunk, and a codec round trip through a tag tree to move
     * what is fundamentally a long array and an int array is work for nothing.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, ChunkTints> STREAM_CODEC = StreamCodec.of(
            (buf, tints) -> {
                final Encoded encoded = tints.encode();
                buf.writeVarInt(encoded.palette().size());
                for (final Identifier id : encoded.palette()) {
                    buf.writeIdentifier(id);
                }
                buf.writeVarInt(encoded.positions().size());
                for (int i = 0; i < encoded.positions().size(); i++) {
                    buf.writeLong(encoded.positions().get(i));
                    buf.writeVarInt(encoded.indices().get(i));
                }
            },
            buf -> {
                final int paletteSize = buf.readVarInt();
                final List<Identifier> palette = new ArrayList<>(paletteSize);
                for (int i = 0; i < paletteSize; i++) {
                    palette.add(buf.readIdentifier());
                }
                final int count = buf.readVarInt();
                final Map<Long, Identifier> byPos = new LinkedHashMap<>(Math.max(4, count));
                for (int i = 0; i < count; i++) {
                    final long pos = buf.readLong();
                    final int index = buf.readVarInt();
                    if (index < 0 || index >= palette.size()) {
                        // A packet from a server whose palette disagrees with its indices. Dropping
                        // the entry loses one block's colour; trusting it would throw out of the
                        // network thread, which drops the connection.
                        continue;
                    }
                    byPos.put(pos, palette.get(index));
                }
                return new ChunkTints(byPos);
            });

    /** The palette, as both codecs see it. Never escapes this class. */
    private record Encoded(List<Identifier> palette, List<Long> positions, List<Integer> indices) {

        DataResult<ChunkTints> decode() {
            if (positions.size() != indices.size()) {
                return DataResult.error(() -> "hydrarium tint field: " + positions.size()
                        + " positions but " + indices.size() + " colours");
            }
            final Map<Long, Identifier> byPos = new LinkedHashMap<>(Math.max(4, positions.size()));
            for (int i = 0; i < positions.size(); i++) {
                final int index = indices.get(i);
                if (index < 0 || index >= palette.size()) {
                    final int shown = index;
                    return DataResult.error(() -> "hydrarium tint field: colour " + shown
                            + " is outside a palette of " + palette.size());
                }
                byPos.put(positions.get(i), palette.get(index));
            }
            return DataResult.success(new ChunkTints(byPos));
        }
    }

    private Encoded encode() {
        final Map<Identifier, Integer> indexOf = new HashMap<>();
        final List<Identifier> palette = new ArrayList<>();
        final List<Long> positions = new ArrayList<>(byPos.size());
        final List<Integer> indices = new ArrayList<>(byPos.size());
        for (final Map.Entry<Long, Identifier> entry : byPos.entrySet()) {
            final int index = indexOf.computeIfAbsent(entry.getValue(), id -> {
                palette.add(id);
                return palette.size() - 1;
            });
            positions.add(entry.getKey());
            indices.add(index);
        }
        return new Encoded(palette, positions, indices);
    }
}
