package com.mattjesmc.hydrarium;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

/**
 * The tier-2 API surface, and the whole of it: a JSON file naming waters and the biomes that are
 * made of them.
 *
 * <p>Every loaded mod is scanned for {@code assets/<its own mod id>/hydrarium/catalogue.json}, so a
 * consumer adds waters by shipping one file in their own jar. The path is keyed on the OWNER's id
 * rather than hydrarium's, which is what makes each catalogue's water ids land in that mod's
 * namespace without the file having to say so twice. It is herbarium's arrangement exactly, for
 * herbarium's reason.
 *
 * <h2>The sixteen dyes are not a special case</h2>
 *
 * hydrarium's own built-in waters are read through this same scanner from this same path, in
 * hydrarium's own jar. There is no hardcoded enum of dye colours anywhere in this mod, and that is
 * load-bearing rather than tidy: the moment the built-ins take a private path, the path a consumer
 * uses stops being the path this mod itself exercises, and it becomes possible for the built-ins to
 * work while tier 2 is quietly broken.
 *
 * <h2>Two lists, and they are independent</h2>
 *
 * {@code waters} declares colours. {@code biomes} says which biome is made of which colour, and it
 * may name a water some other mod's catalogue declared — that is the point of resolving the whole
 * scan before wiring any of it. A biome row is layer 2 of the resolution and it <b>stores
 * nothing</b>: an entire planet's green ocean is one row here and not one byte in any chunk.
 */
public record WaterCatalogue(List<WaterCatalogue.Entry> waters, List<WaterCatalogue.BiomeEntry> biomes) {

    /** Where a mod's own catalogue lives inside its jar; the {@code %s} is that mod's id. */
    public static final String PATH = "assets/%s/hydrarium/catalogue.json";

    public static final Codec<WaterCatalogue> CODEC = RecordCodecBuilder.create(
            i -> i.group(
                    Entry.CODEC.listOf().optionalFieldOf("waters", List.of()).forGetter(WaterCatalogue::waters),
                    BiomeEntry.CODEC.listOf().optionalFieldOf("biomes", List.of()).forGetter(WaterCatalogue::biomes))
                    .apply(i, WaterCatalogue::new));

    /**
     * One water: a path (the namespace comes from the mod that shipped the file), a colour, and
     * three optional knobs.
     *
     * <p><b>{@code effect} is a string here and an {@link Effect} in the runtime</b>, and the
     * conversion is lossy on purpose: an unknown name resolves to {@link Effect#NONE} with a log
     * line rather than failing the parse. See {@link Effect} for why the set is closed at all.
     *
     * <p>{@code tint} is a plain integer, which in JSON means a decimal — {@code 4210752}, not a
     * hex string. That is what {@code Codec.INT} accepts, and the generator writes decimals for the
     * same reason herbarium's does: a catalogue is a machine artifact, and the one place a human
     * reads the colour is the generator's palette.
     *
     * <p><b>{@code dye} is what makes a water mix.</b> Name one of the sixteen and this water takes
     * part in vanilla's dye recipes: {@code "dye": "red"} plus {@code "dye": "yellow"} is orange
     * water, because red dye plus yellow dye is orange dye. Leave it out and the water is a colour
     * and nothing else - it keeps itself where it is alone and muddies to grey against anything
     * else, which is the right default for an authored alien water that no recipe in the game has
     * an opinion about. An unknown name is a log line and a water that does not mix, never a failed
     * parse; the {@code effect} field is lossy the same way and for the same reason.
     */
    public record Entry(String id, int tint, String effect, float biomeStrength, String dye) {

        /** The {@code dye} of a water that is only a colour: none of them. */
        public static final String NO_DYE = "";

        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(
                i -> i.group(
                        Codec.STRING.fieldOf("id").forGetter(Entry::id),
                        Codec.INT.fieldOf("tint").forGetter(Entry::tint),
                        Codec.STRING.optionalFieldOf("effect", Effect.NONE.catalogueName())
                                .forGetter(Entry::effect),
                        Codec.FLOAT.optionalFieldOf("biome_strength", WaterType.DEFAULT_BIOME_STRENGTH)
                                .forGetter(Entry::biomeStrength),
                        Codec.STRING.optionalFieldOf("dye", NO_DYE).forGetter(Entry::dye))
                        .apply(i, Entry::new));
    }

    /**
     * One biome, and the water it is made of.
     *
     * <p>Both fields are fully qualified, and both may name something the declaring mod does not
     * own: rocketeer declaring that {@code minecraft:swamp} is green water is a legitimate (if rude)
     * thing for a catalogue to say, and hydrarium does not police it. The last catalogue to claim a
     * biome wins, in mod-id order, and {@link Waters} logs the collision.
     */
    public record BiomeEntry(String biome, String water) {
        public static final Codec<BiomeEntry> CODEC = RecordCodecBuilder.create(
                i -> i.group(
                        Codec.STRING.fieldOf("biome").forGetter(BiomeEntry::biome),
                        Codec.STRING.fieldOf("water").forGetter(BiomeEntry::water))
                        .apply(i, BiomeEntry::new));
    }

    /** One mod's catalogue, paired with the mod id its water ids are namespaced by. */
    public record Loaded(String modId, WaterCatalogue catalogue) {
    }

    /**
     * Every loaded mod's catalogue, in mod-id order.
     *
     * <p>Sorted because {@code getAllMods()} is not, and load order is observable here in exactly
     * one way: it decides who wins a duplicated biome row. A malformed or unreadable catalogue is
     * logged and skipped rather than thrown — one mod's typo should cost that mod's waters and
     * nobody else's.
     */
    public static List<Loaded> scanLoadedMods() {
        final List<Loaded> found = new ArrayList<>();
        for (final ModContainer mod : FabricLoader.getInstance().getAllMods()) {
            final String modId = mod.getMetadata().getId();
            final Optional<Path> path = mod.findPath(PATH.formatted(modId));
            if (path.isEmpty()) {
                continue;
            }
            read(modId, path.get()).ifPresent(catalogue -> found.add(new Loaded(modId, catalogue)));
        }
        found.sort(Comparator.comparing(Loaded::modId));
        return found;
    }

    /** Parse one catalogue from a path. Exposed for the contract test, which has no game to scan. */
    public static Optional<WaterCatalogue> read(final String modId, final Path path) {
        final JsonElement json;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader);
        } catch (final IOException | RuntimeException e) {
            Hydrarium.LOG.error("hydrarium: could not read {}'s catalogue at {}", modId, path, e);
            return Optional.empty();
        }

        final DataResult<WaterCatalogue> result = CODEC.parse(JsonOps.INSTANCE, json);
        final Optional<DataResult.Error<WaterCatalogue>> error = result.error();
        if (error.isPresent()) {
            Hydrarium.LOG.error("hydrarium: {}'s catalogue is malformed and was skipped: {}",
                    modId, error.get().message());
            return Optional.empty();
        }
        return result.result();
    }
}
