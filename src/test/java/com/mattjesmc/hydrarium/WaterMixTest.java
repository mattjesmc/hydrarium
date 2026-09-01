package com.mattjesmc.hydrarium;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * The mixing rule, with the recipe book faked.
 *
 * <p>The split that makes this testable is {@link WaterMix.Blender}: the algebra is a pure function
 * of "which dye do these two craft into", and only the answer to <em>that</em> needs a server. The
 * fake below is vanilla's nine two-item dye recipes written out, which is a second copy of something
 * {@link DyeRecipes} deliberately does not keep — and that is the point of it being here and only
 * here. If vanilla's recipes change, this file goes stale and the game does not, which is the right
 * way round: the test is checking the fold, not the recipe book.
 *
 * <p>What this suite <b>cannot</b> reach is unchanged from the file it replaces, and worth repeating
 * because the last bug in this area lived exactly there: it says nothing about when the game calls
 * {@code spreadTo}, and a rule that is perfect here can still paint a river wrong. {@code SmokeCheck}
 * is the half that needs a world.
 */
class WaterMixTest {

    private static final Identifier RED = Hydrarium.id("red");
    private static final Identifier YELLOW = Hydrarium.id("yellow");
    private static final Identifier BLUE = Hydrarium.id("blue");
    private static final Identifier GREEN = Hydrarium.id("green");
    private static final Identifier ORANGE = Hydrarium.id("orange");
    private static final Identifier PURPLE = Hydrarium.id("purple");
    private static final Identifier CYAN = Hydrarium.id("cyan");
    private static final Identifier GRAY = Hydrarium.id("gray");
    private static final Identifier LUME = Identifier.fromNamespaceAndPath("rocketeer", "lumewater");

    /** Clear, two dyes that mix, a dye that mixes with neither, and a water that has no dye at all. */
    private static final List<Identifier> ALPHABET =
            Arrays.asList(null, RED, YELLOW, BLUE, GREEN, LUME);

    /**
     * Vanilla's two-item dye recipes, keyed the way {@link WaterMix.Blender} promises to call: lower
     * {@link DyeColor#ordinal()} first.
     */
    private static final Map<List<DyeColor>, DyeColor> RECIPES = Map.of(
            List.of(DyeColor.YELLOW, DyeColor.RED), DyeColor.ORANGE,
            List.of(DyeColor.WHITE, DyeColor.RED), DyeColor.PINK,
            List.of(DyeColor.WHITE, DyeColor.BLUE), DyeColor.LIGHT_BLUE,
            List.of(DyeColor.WHITE, DyeColor.GREEN), DyeColor.LIME,
            List.of(DyeColor.WHITE, DyeColor.BLACK), DyeColor.GRAY,
            List.of(DyeColor.GRAY, DyeColor.WHITE), DyeColor.LIGHT_GRAY,
            List.of(DyeColor.PINK, DyeColor.PURPLE), DyeColor.MAGENTA,
            List.of(DyeColor.BLUE, DyeColor.GREEN), DyeColor.CYAN,
            List.of(DyeColor.BLUE, DyeColor.RED), DyeColor.PURPLE);

    private static final WaterMix.Blender VANILLA = (first, second) -> RECIPES.get(
            first.ordinal() <= second.ordinal() ? List.of(first, second) : List.of(second, first));

    /**
     * The sixteen dyes plus one water that declares none, loaded through the real
     * {@link Waters#load} so that the dye indexing under test is the one the game gets.
     */
    @BeforeAll
    static void loadWaters() {
        final List<WaterCatalogue.Entry> dyes = new ArrayList<>();
        for (final DyeColor colour : DyeColor.values()) {
            dyes.add(new WaterCatalogue.Entry(colour.getSerializedName(), 0xFFFFFF,
                    Effect.NONE.catalogueName(), WaterType.DEFAULT_BIOME_STRENGTH,
                    colour.getSerializedName()));
        }
        Waters.load(List.of(
                new WaterCatalogue.Loaded("hydrarium", new WaterCatalogue(dyes, List.of())),
                new WaterCatalogue.Loaded("rocketeer", new WaterCatalogue(List.of(
                        new WaterCatalogue.Entry("lumewater", 0x40E0D0, "glow", 0.1F,
                                WaterCatalogue.Entry.NO_DYE)),
                        List.of()))));
    }

    @Test
    void theFixtureIsWhatItClaimsToBe() {
        assertEquals(16, DyeColor.values().length);
        assertEquals(DyeColor.RED, Waters.get(RED).dye());
        assertNull(Waters.get(LUME).dye(), "lumewater declares no dye; it must not have acquired one");
        assertEquals(RED, Waters.byDye(DyeColor.RED).id());
    }

    @Test
    void isCommutative() {
        for (final Identifier a : ALPHABET) {
            for (final Identifier b : ALPHABET) {
                assertEquals(WaterMix.join(VANILLA, a, b), WaterMix.join(VANILLA, b, a),
                        () -> "join(" + a + ", " + b + ") disagrees with the other order");
                assertEquals(WaterMix.stir(VANILLA, a, b), WaterMix.stir(VANILLA, b, a),
                        () -> "stir(" + a + ", " + b + ") disagrees with the other order");
            }
        }
    }

    @Test
    void isIdempotent() {
        for (final Identifier a : ALPHABET) {
            assertEquals(a, WaterMix.join(VANILLA, a, a), () -> a + " does not survive meeting itself");
            assertEquals(a, WaterMix.stir(VANILLA, a, a), () -> a + " does not survive being stirred into itself");
        }
    }

    @Test
    void coloursBlendByVanillasOwnRecipes() {
        assertEquals(ORANGE, WaterMix.join(VANILLA, RED, YELLOW), "red + yellow is orange dye");
        assertEquals(PURPLE, WaterMix.join(VANILLA, RED, BLUE), "red + blue is purple dye");
        assertEquals(CYAN, WaterMix.join(VANILLA, BLUE, GREEN), "blue + green is cyan dye");
    }

    @Test
    void anUnmixablePairMuddies() {
        assertEquals(GRAY, WaterMix.join(VANILLA, RED, GREEN),
                "the game has no red + green recipe, so the water muddies");
        assertEquals(GRAY, WaterMix.join(VANILLA, ORANGE, YELLOW),
                "orange dye is not made from orange and yellow");
    }

    @Test
    void aWaterWithNoDyeMixesWithNothing() {
        assertEquals(GRAY, WaterMix.join(VANILLA, LUME, RED),
                "lumewater declares no dye, so there is no recipe to reach for");
        assertEquals(LUME, WaterMix.join(VANILLA, LUME, LUME), "...but it is still itself");
    }

    @Test
    void clearAbsorbsInARiver() {
        assertNull(WaterMix.join(VANILLA, RED, null), "a colour meeting clear water must dissolve");
        assertNull(WaterMix.join(VANILLA, null, null));
        final List<Identifier> withClear = new ArrayList<>();
        withClear.add(RED);
        withClear.add(null);
        withClear.add(RED);
        assertNull(WaterMix.join(VANILLA, withClear), "one clear parent ends it however many others agree");
    }

    /**
     * The bug that made every cauldron feature dead: {@code join(clear, red)} is clear, and the
     * cauldron was calling the join.
     */
    @Test
    void clearIsNeutralInAPot() {
        assertEquals(RED, WaterMix.stir(VANILLA, null, RED), "dye added to plain water must colour it");
        assertEquals(RED, WaterMix.stir(VANILLA, RED, null), "plain water added to red water does not dilute it");
        assertNull(WaterMix.stir(VANILLA, null, null));
        assertEquals(PURPLE, WaterMix.stir(VANILLA, RED, BLUE), "a cauldron blends by the same rule");
    }

    @Test
    void foldsOverManyParents() {
        assertNull(WaterMix.join(VANILLA, List.of()), "no parents is clear water");
        assertEquals(RED, WaterMix.join(VANILLA, List.of(RED, RED, RED)));
        assertEquals(PURPLE, WaterMix.join(VANILLA, List.of(RED, RED, BLUE)));
        assertEquals(PURPLE, WaterMix.join(VANILLA, List.of(BLUE, RED, RED)));
    }

    /**
     * The property that replaced associativity, and the one the fold has to earn.
     *
     * <p>A blend is not associative — {@code (red * yellow) * yellow} is grey while
     * {@code red * (yellow * yellow)} is orange — so {@link WaterMix#join} deduplicates and sorts
     * before folding. That makes the answer a function of the <em>set</em> of parents, which is what
     * "a pool renders the same after a chunk reload" actually needs.
     */
    @Test
    void theAnswerDependsOnTheSetOfParentsAndNotTheirOrder() {
        assertEquals(ORANGE, WaterMix.join(VANILLA, List.of(RED, YELLOW, YELLOW)));
        assertEquals(ORANGE, WaterMix.join(VANILLA, List.of(YELLOW, YELLOW, RED)));
        assertEquals(ORANGE, WaterMix.join(VANILLA, List.of(YELLOW, RED, YELLOW)));
        assertEquals(
                WaterMix.join(VANILLA, List.of(RED, BLUE, GREEN)),
                WaterMix.join(VANILLA, List.of(GREEN, RED, BLUE)),
                "three colours must fold the same way from any starting order");
    }

    @Test
    void foldAgreesWithPairwise() {
        for (final Identifier a : ALPHABET) {
            for (final Identifier b : ALPHABET) {
                final List<Identifier> pair = new ArrayList<>();
                pair.add(a);
                pair.add(b);
                assertEquals(WaterMix.join(VANILLA, a, b), WaterMix.join(VANILLA, pair),
                        () -> "fold and pairwise disagree at (" + a + ", " + b + ")");
            }
        }
    }

    @Test
    void oneParentSurvives() {
        final List<Identifier> one = new ArrayList<>();
        one.add(RED);
        assertSame(RED, WaterMix.join(VANILLA, one));
    }

    @Test
    void mixesByValueAndNotByIdentity() {
        final Identifier other = Identifier.fromNamespaceAndPath("hydrarium", "red");
        assertEquals(RED, WaterMix.join(VANILLA, RED, other));
    }

    /**
     * With no recipes at all — a client, or a level with no server behind it — every pair of
     * different colours muddies, and nothing throws.
     */
    @Test
    void withoutARecipeBookEverythingMuddies() {
        assertEquals(GRAY, WaterMix.join(WaterMix.Blender.NONE, RED, YELLOW));
        assertEquals(RED, WaterMix.join(WaterMix.Blender.NONE, RED, RED));
        assertNull(WaterMix.join(WaterMix.Blender.NONE, RED, null));
    }

    /**
     * An id no loaded catalogue declares — a water from a mod that was uninstalled — muddies rather
     * than throwing. It is the same answer as a water with no dye, for the same reason.
     */
    @Test
    void anUnknownWaterMuddies() {
        final Identifier ghost = Identifier.fromNamespaceAndPath("nobody", "ghostwater");
        assertEquals(GRAY, WaterMix.join(VANILLA, ghost, RED));
        assertEquals(ghost, WaterMix.join(VANILLA, ghost, ghost));
    }
}
