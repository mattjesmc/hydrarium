package com.mattjesmc.hydrarium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * The generator and the runtime are one vocabulary in two languages, and this is what holds them
 * together.
 *
 * <p>{@code gen_water.py} writes the catalogue; {@link WaterCatalogue} reads it; {@link Effect}
 * decides what an effect name means; {@link CauldronTint} reaches for a water named after each dye
 * colour. Four places, two languages, and every pairing between them can drift with everything
 * still compiling and running — an effect name the generator invented costs that water its
 * behaviour and logs one line, and a dye whose water the catalogue stopped declaring simply stops
 * working in a cauldron.
 *
 * <p>Reads {@code src/main/resources} directly and boots no game, which is herbarium's posture and
 * the right one: what is being tested is the DATA contract, not the runtime.
 */
class CatalogueContractTest {

    private static final Path CATALOGUE =
            Path.of("src/main/resources/assets/hydrarium/hydrarium/catalogue.json");
    private static final Path LANG =
            Path.of("src/main/resources/assets/hydrarium/lang/en_us.json");

    /** The bucket's three generated files, in the order one names the next. */
    private static final Path BUCKET_OVERRIDE =
            Path.of("src/main/resources/assets/minecraft/items/water_bucket.json");
    private static final Path BUCKET_MODEL =
            Path.of("src/main/resources/assets/hydrarium/models/item/water_bucket_tinted.json");
    private static final Path BUCKET_SPRITE =
            Path.of("src/main/resources/assets/hydrarium/textures/item/water_bucket_tint.png");

    /**
     * The sixteen dye colours, as {@code DyeColor.getSerializedName()} produces them.
     *
     * <p>Written out rather than read from {@code DyeColor.values()} deliberately. This test boots
     * no game, and touching a vanilla enum whose static initialiser pulls in map colours, byte-buf
     * codecs and an id map is exactly the sort of thing that turns a seven-second suite into a
     * class-initialisation puzzle. The names are stable, and the risk being guarded against here is
     * the generator's palette drifting — not Mojang renaming magenta.
     */
    private static final List<String> DYE_NAMES = List.of(
            "white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
            "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black");

    private static WaterCatalogue parse(final Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing; run ./gradlew art");
        final JsonElement json;
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader);
        }
        final DataResult<WaterCatalogue> result = WaterCatalogue.CODEC.parse(JsonOps.INSTANCE, json);
        assertTrue(result.error().isEmpty(),
                () -> "the built-in catalogue does not parse: " + result.error().orElseThrow().message());
        return result.result().orElseThrow();
    }

    /**
     * Opens with an emptiness check, because every other assertion below is a loop and a loop over
     * nothing passes by saying nothing.
     */
    @Test
    void catalogueIsNotEmpty() throws IOException {
        final WaterCatalogue catalogue = parse(CATALOGUE);
        assertFalse(catalogue.waters().isEmpty(), "the built-in catalogue declares no waters");
        assertTrue(catalogue.waters().size() >= 16,
                "fewer waters than there are dye colours: " + catalogue.waters().size());
    }

    /**
     * The built-in catalogue is read through the same scanner and the same codec a consumer's is.
     *
     * <p>If this ever stops being true — if hydrarium's own waters take a private path — then the
     * path tier 2 uses stops being the path this mod itself exercises, and the sixteen dyes can
     * work while every consumer's catalogue is quietly broken.
     */
    @Test
    void builtInsParseThroughTheConsumerCodec() throws IOException {
        assertNotNull(parse(CATALOGUE));
    }

    /**
     * Every dye colour has a water named after it.
     *
     * <p>{@link CauldronTint} derives the mapping from {@code DataComponents.DYE} rather than
     * listing it, so a missing row is not a crash and not a log line: that dye simply does nothing
     * in a cauldron, in a mod whose headline feature is putting dye in a cauldron.
     */
    @Test
    void everyDyeHasAWater() throws IOException {
        final Set<String> declared = parse(CATALOGUE).waters().stream()
                .map(WaterCatalogue.Entry::id).collect(Collectors.toSet());
        for (final String dye : DYE_NAMES) {
            assertTrue(declared.contains(dye),
                    () -> "no water called '" + dye + "'; that dye would do nothing in a cauldron");
        }
    }

    /**
     * Every effect a catalogue names is one {@link Effect} has.
     *
     * <p>An unknown name is not a parse failure by design — see {@link Effect} — so the runtime's
     * response is one warning and a water that keeps its colour and does nothing. That is the right
     * behaviour for a <em>consumer's</em> typo and the wrong outcome for hydrarium's own catalogue,
     * where it would mean shipping a water whose whole point is its behaviour with the behaviour
     * silently absent. This is the difference between the two.
     */
    /**
     * The dye rows are the sixteen and nothing else, and each one names itself.
     *
     * <p>The {@code dye} field is what makes a water take part in vanilla's dye mixing, and it fails
     * in silence twice over: a row that forgets it ships a water that will not blend, and a row that
     * names a <em>different</em> dye makes the cauldron produce a colour nobody asked for. Neither
     * logs, because both are legal for a consumer's catalogue.
     */
    @Test
    void theSixteenDyeWatersDeclareTheirDyeAndNothingElseDoes() throws IOException {
        for (final WaterCatalogue.Entry entry : parse(CATALOGUE).waters()) {
            if (DYE_NAMES.contains(entry.id())) {
                assertEquals(entry.id(), entry.dye(),
                        () -> entry.id() + " water does not declare itself to be " + entry.id()
                                + " dye, so a cauldron of it will not mix");
            } else {
                assertEquals(WaterCatalogue.Entry.NO_DYE, entry.dye(),
                        () -> entry.id() + " claims to be a dye; hydrarium's effect waters are"
                                + " colours the game has no recipe for, on purpose");
            }
        }
    }

    @Test
    void everyEffectIsOneJavaHas() throws IOException {
        for (final WaterCatalogue.Entry entry : parse(CATALOGUE).waters()) {
            assertTrue(Effect.known(entry.effect()),
                    () -> entry.id() + " asks for effect '" + entry.effect() + "', which Effect.java"
                            + " does not have; it would ship as a water that does nothing");
        }
    }

    /** Colours are plain {@code 0xRRGGBB}: the alpha byte is the client's to add, never the data's. */
    @Test
    void tintsArePlainRgb() throws IOException {
        for (final WaterCatalogue.Entry entry : parse(CATALOGUE).waters()) {
            assertTrue(entry.tint() >= 0 && entry.tint() <= 0xFFFFFF,
                    () -> entry.id() + " has tint " + entry.tint() + ", which is not 0xRRGGBB");
        }
    }

    /** The modulation knob is a fraction, and the client's blend assumes it. */
    @Test
    void biomeStrengthsAreFractions() throws IOException {
        for (final WaterCatalogue.Entry entry : parse(CATALOGUE).waters()) {
            assertTrue(entry.biomeStrength() >= 0.0F && entry.biomeStrength() <= 1.0F,
                    () -> entry.id() + " has biome_strength " + entry.biomeStrength());
        }
    }

    @Test
    void idsAreUnique() throws IOException {
        final Set<String> seen = new HashSet<>();
        for (final WaterCatalogue.Entry entry : parse(CATALOGUE).waters()) {
            assertTrue(seen.add(entry.id()), () -> entry.id() + " is declared twice");
        }
    }

    /**
     * hydrarium declares no biome to be one of its waters.
     *
     * <p>Not a stylistic rule: a library that repainted the vanilla ocean the moment it was
     * installed would be a library nobody could put in a modpack. Which biome is which water is a
     * consumer's decision about a consumer's world.
     */
    @Test
    void builtInsClaimNoBiome() throws IOException {
        assertTrue(parse(CATALOGUE).biomes().isEmpty(),
                "hydrarium's own catalogue claims a biome; that is a consumer's decision");
    }

    /**
     * Every water has a name and every name has a water.
     *
     * <p>Both halves matter, which is why this counts as well as looks up: a name with no water
     * behind it is a water somebody deleted from the palette and forgot, and it will sit in the lang
     * file looking authoritative until something goes looking for the row it names.
     *
     * <p>Only the {@code water.} keys are counted. The same file also carries the command's own
     * strings, which are chrome rather than content and are not derived from the palette — they are
     * in the generator (see its {@code CHROME}) because the lang file is written in one piece and a
     * key added by hand is a key the next {@code ./gradlew art} deletes, not because they are
     * waters.
     */
    @Test
    void everyWaterHasALangEntry() throws IOException {
        assertTrue(Files.exists(LANG), LANG + " is missing; run ./gradlew art");
        final JsonElement json;
        try (Reader reader = Files.newBufferedReader(LANG, StandardCharsets.UTF_8)) {
            json = JsonParser.parseReader(reader);
        }
        final var lang = json.getAsJsonObject();
        final List<WaterCatalogue.Entry> waters = parse(CATALOGUE).waters();
        final long named = lang.keySet().stream().filter(key -> key.startsWith("water.")).count();
        assertEquals(waters.size(), named,
                "the lang file and the catalogue disagree about how many waters there are");
        for (final WaterCatalogue.Entry entry : waters) {
            assertTrue(lang.has("water.hydrarium." + entry.id()),
                    () -> "no name for " + entry.id());
        }
    }

    /**
     * The bucket's chain of names holds, end to end.
     *
     * <p>Four files point at each other to draw one item: vanilla's model slot names hydrarium's
     * model, which names hydrarium's sprite, and the tinted branch names a tint source that only
     * exists because {@code WaterTintSource} registers it under that id. <b>Every link in that chain
     * fails silently.</b> A model that names a texture nobody wrote renders as the missing-texture
     * chequer; a tint source id nobody registered fails the model parse and leaves the bucket as
     * whatever the fallback model is — and both of those look, to somebody who has not looked
     * closely, like a bucket.
     *
     * <p>The tint source's id comes from {@link Hydrarium#id} rather than from a literal, which is
     * what makes this a test of the JSON against the Java rather than of the JSON against itself.
     * {@code WaterTintSource} itself cannot be named here — it is {@code @Environment(CLIENT)} and
     * so is not on this source set's classpath by construction, which is the same rule that makes
     * "the field half is server-safe" a claim the compiler checks.
     */
    @Test
    void theBucketsAssetsNameEachOther() throws IOException {
        assertTrue(Files.exists(BUCKET_SPRITE), BUCKET_SPRITE + " is missing; run ./gradlew art");

        final var model = read(BUCKET_MODEL).getAsJsonObject();
        assertEquals("hydrarium:item/water_bucket_tint",
                model.getAsJsonObject("textures").get("layer1").getAsString(),
                "the tinted model does not name the overlay sprite the generator writes");
        assertEquals("minecraft:item/water_bucket",
                model.getAsJsonObject("textures").get("layer0").getAsString(),
                "layer0 is not vanilla's own bucket sprite, so hydrarium is drawing a bucket");

        final var tinted = read(BUCKET_OVERRIDE).getAsJsonObject()
                .getAsJsonObject("model").getAsJsonObject("on_true");
        assertEquals("hydrarium:item/water_bucket_tinted", tinted.get("model").getAsString(),
                "the override does not reach the tinted model");

        final var tints = tinted.getAsJsonArray("tints");
        assertEquals(2, tints.size(),
                "tints is indexed by layer; two layers need two entries or layer1 goes untinted");
        assertEquals(Hydrarium.id("water_tint").toString(),
                tints.get(1).getAsJsonObject().get("type").getAsString(),
                "the overlay's tint source is not the one WaterTintSource registers");
    }

    /**
     * An untinted bucket takes vanilla's own model, not a copy of it.
     *
     * <p>This is the bucket's version of the claim the whole mod is arranged around — a world with
     * no hydrarium content renders pixel-identical to vanilla — and it is kept the same way the
     * renderer keeps it: by returning vanilla's answer rather than by computing one that matches.
     * The day this branch names a hydrarium model instead, an ordinary water bucket in an ordinary
     * world starts being hydrarium's approximation of itself.
     */
    @Test
    void anUntintedBucketIsStillVanillas() throws IOException {
        final var model = read(BUCKET_OVERRIDE).getAsJsonObject().getAsJsonObject("model");
        assertEquals("minecraft:has_component", model.get("property").getAsString());
        assertEquals(Hydrarium.id("tint").toString(), model.get("component").getAsString(),
                "the condition does not test the component the bucket actually carries");
        assertEquals("minecraft:item/water_bucket",
                model.getAsJsonObject("on_false").get("model").getAsString(),
                "a bucket with no tint no longer takes vanilla's own model");
    }

    private static JsonElement read(final Path path) throws IOException {
        assertTrue(Files.exists(path), path + " is missing; run ./gradlew art");
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return JsonParser.parseReader(reader);
        }
    }
}
