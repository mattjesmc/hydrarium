package com.mattjesmc.hydrarium;

import java.util.Collection;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.DyeColor;

/**
 * The table every other class reads: id to water, and biome to water.
 *
 * <p>Filled once during {@code onInitialize} and never written again. It is a static rather than a
 * registry because a water is not a game object — nothing holds a reference to one, nothing
 * serialises one, and the only thing that crosses the wire or the disk is its {@link Identifier}.
 * A vanilla registry would buy synchronisation and freezing for something with no registry-shaped
 * problem, and would drag registry timing into the client half for a lookup that is a hash map.
 *
 * <h2>An unknown id resolves to clear, everywhere, and that is the whole compatibility story</h2>
 *
 * A world saved with rocketeer installed and loaded without it holds tint entries naming
 * {@code rocketeer:lumewater}, which {@link #get} answers {@code null} to. Clear water is exactly
 * what those positions should be, the field entries are advisory ({@link TintField}) so they are
 * swept away rather than honoured, and reinstalling the mod brings the colour back because the
 * entry outlived the absence. Nothing had to be migrated and nothing was lost.
 */
public final class Waters {

    private static Map<Identifier, WaterType> byId = Map.of();
    private static Map<Identifier, WaterType> byBiome = Map.of();
    private static Map<DyeColor, WaterType> byDye = Map.of();
    private static int declaringMods;

    private Waters() {
    }

    /**
     * Resolve every catalogue into the two tables. Called once, before registry freeze, before any
     * client init.
     *
     * <p>Two passes and not one, because the second list may name the first list's entries across
     * mods: {@code rocketeer}'s biome row may point at a water declared by {@code hydrarium} or by
     * {@code menagerie}, and a single pass would resolve that only when the mod ids happened to
     * sort the right way. Nothing about a catalogue should depend on its author's mod id.
     */
    public static void load(final List<WaterCatalogue.Loaded> catalogues) {
        final Map<Identifier, WaterType> waters = new LinkedHashMap<>();
        for (final WaterCatalogue.Loaded loaded : catalogues) {
            for (final WaterCatalogue.Entry entry : loaded.catalogue().waters()) {
                final Identifier id = Identifier.tryBuild(loaded.modId(), entry.id());
                if (id == null) {
                    Hydrarium.LOG.error("hydrarium: {} declares a water called '{}', which is not a"
                            + " legal resource path; it was skipped", loaded.modId(), entry.id());
                    continue;
                }
                if (!Effect.known(entry.effect())) {
                    // Loud but not fatal, and it keeps the colour: see Effect. A water that has
                    // lost its behaviour is something a player can see and report; a catalogue that
                    // refused to load is a mod that lost every water it had over one string.
                    Hydrarium.LOG.warn("hydrarium: {} asks for effect '{}', which this build does"
                                    + " not have — {} keeps its colour and does nothing",
                            loaded.modId(), entry.effect(), id);
                }
                final WaterType previous = waters.put(id, new WaterType(
                        id, entry.tint(), Effect.byName(entry.effect()), entry.biomeStrength(),
                        dyeOf(loaded.modId(), entry)));
                if (previous != null) {
                    Hydrarium.LOG.warn("hydrarium: {} declares {} twice; the later row wins",
                            loaded.modId(), id);
                }
            }
        }

        final Map<Identifier, WaterType> biomes = new LinkedHashMap<>();
        for (final WaterCatalogue.Loaded loaded : catalogues) {
            for (final WaterCatalogue.BiomeEntry entry : loaded.catalogue().biomes()) {
                final Identifier biome = Identifier.tryParse(entry.biome());
                final Identifier water = Identifier.tryParse(entry.water());
                if (biome == null || water == null) {
                    Hydrarium.LOG.error("hydrarium: {} maps '{}' to '{}' and one of those is not a"
                            + " legal id; the row was skipped",
                            loaded.modId(), entry.biome(), entry.water());
                    continue;
                }
                final WaterType type = waters.get(water);
                if (type == null) {
                    // Not an error worth failing over: the named water may belong to a mod that is
                    // simply not installed, which is the ordinary state of a rocketeer catalogue in
                    // a pack without rocketeer's dependencies. The biome renders vanilla.
                    Hydrarium.LOG.warn("hydrarium: {} maps biome {} to {}, which no loaded"
                            + " catalogue declares; that biome stays vanilla",
                            loaded.modId(), biome, water);
                    continue;
                }
                final WaterType previous = biomes.put(biome, type);
                if (previous != null && !previous.id().equals(type.id())) {
                    Hydrarium.LOG.warn("hydrarium: biome {} is claimed by both {} and {}; {} wins",
                            biome, previous.id(), type.id(), type.id());
                }
            }
        }

        final Map<DyeColor, WaterType> dyes = new EnumMap<>(DyeColor.class);
        for (final WaterType water : waters.values()) {
            if (water.dye() != null && dyes.putIfAbsent(water.dye(), water) != null) {
                Hydrarium.LOG.warn("hydrarium: both {} and {} claim to be {} dye; {} is what dye"
                                + " mixing produces",
                        dyes.get(water.dye()).id(), water.id(), water.dye().getSerializedName(),
                        dyes.get(water.dye()).id());
            }
        }

        byId = Map.copyOf(waters);
        byBiome = Map.copyOf(biomes);
        byDye = Map.copyOf(dyes);
        declaringMods = catalogues.size();
    }

    /**
     * Read a catalogue row's {@code dye}, or {@code null} for a water that is only a colour.
     *
     * <p>An unknown name warns and mixes nothing, rather than failing the row. It is the same trade
     * the {@code effect} field makes: losing one water's ability to blend is a thing a player sees
     * and reports; losing a whole catalogue over one misspelt string is not.
     */
    private static DyeColor dyeOf(final String modId, final WaterCatalogue.Entry entry) {
        if (entry.dye().equals(WaterCatalogue.Entry.NO_DYE)) {
            return null;
        }
        final DyeColor dye = DyeColor.byName(entry.dye(), null);
        if (dye == null) {
            Hydrarium.LOG.warn("hydrarium: {} says {} is '{}' dye, which is not one of the sixteen;"
                            + " it keeps its colour and will not blend",
                    modId, entry.id(), entry.dye());
        }
        return dye;
    }

    /** The water with this id, or {@code null} if no loaded catalogue declares one. */
    public static WaterType get(final Identifier id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * The water that <i>is</i> this dye - what a cauldron makes of it, and what a blend resolves to.
     *
     * <p>First declared wins, and catalogues are scanned in mod-id order, so hydrarium's own sixteen
     * hold the sixteen dyes unless a consumer loads before {@code hydrarium} alphabetically and
     * claims one. That is a warning at load and not an error: a pack that deliberately replaces
     * "red water" is doing something legitimate, and a library that refused it would be a library
     * that owned a colour.
     *
     * @return the water, or {@code null} if nothing declares this dye - in which case a blend that
     *         lands on it falls back to grey, and a grey that is also missing falls back to clear
     */
    public static WaterType byDye(final DyeColor dye) {
        return dye == null ? null : byDye.get(dye);
    }

    /**
     * What to call a water in front of a player: {@code water.<namespace>.<path>}.
     *
     * <p>Derived from the id rather than looked up, which is what lets a consumer's water be named
     * without hydrarium knowing it exists — rocketeer ships {@code water.rocketeer.lumewater} in its
     * own lang file and the bucket tooltip finds it. Both places a name is shown (the tooltip and
     * {@code /water at}) come through here so there is one key and not two conventions.
     *
     * <p>It takes an {@link Identifier} and not a {@link WaterType} on purpose: the id is the thing
     * that always exists. A bucket filled in a world with a mod installed and opened in one without
     * it still carries {@code rocketeer:lumewater}, and the honest label for that is the id itself —
     * which is exactly what the fallback makes it, rather than the raw translation key a missing
     * entry would otherwise show.
     */
    public static Component name(final Identifier id) {
        return Component.translatableWithFallback(
                "water." + id.getNamespace() + "." + id.getPath(), id.toString());
    }

    /** The water a biome is declared to be made of, or {@code null} for "whatever vanilla says". */
    public static WaterType forBiome(final Identifier biome) {
        return biome == null ? null : byBiome.get(biome);
    }

    /** Every declared water, in the order the catalogues declared them. */
    public static Collection<WaterType> all() {
        return byId.values();
    }

    /** Whether any biome is declared at all — the client uses it to skip layer 2 entirely. */
    public static boolean anyBiomeDeclared() {
        return !byBiome.isEmpty();
    }

    public static int biomeCount() {
        return byBiome.size();
    }

    public static int declaringMods() {
        return declaringMods;
    }
}
