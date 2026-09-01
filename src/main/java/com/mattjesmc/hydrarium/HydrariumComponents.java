package com.mattjesmc.hydrarium;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;

/**
 * The one component, and the reason there is no new item.
 *
 * <p>{@code hydrarium:tint} rides on the <b>vanilla</b> {@code minecraft:water_bucket}. That is the
 * whole container story and it is worth being explicit about what it buys:
 *
 * <ul>
 *   <li>Every recipe that consumes a water bucket still works. Recipes match by item and ignore
 *       components, so cake, mushroom stew, and every modded recipe anyone has ever written keep
 *       taking a tinted bucket without knowing one exists.</li>
 *   <li>Two differently-tinted buckets do not stack. That is correct behaviour rather than a bug —
 *       they hold different things — and it is behaviour vanilla's own stacking rules produce for
 *       free from the components differing.</li>
 *   <li>A dispenser, a dropper and a hand all place through {@code BucketItem.checkExtraContent},
 *       so hooking one method covers all three.</li>
 * </ul>
 *
 * <p><b>The value is an {@link Identifier}, not a colour.</b> A bucket carries the name of the water
 * it holds, so the water it pours out is the same water — same effect, same biome strength, same
 * atom for {@link WaterMix} — rather than a number that happens to match. It also means a
 * bucket filled with a mod's water and carried into a world without that mod pours clear, which is
 * the same graceful loss {@link Waters} gives every other holder of an unknown id.
 *
 * <h2>Making the bucket LOOK tinted is a separate question, and v1 does not answer it</h2>
 *
 * The behaviour above is the required half. The cosmetic half needs
 * {@code assets/minecraft/items/water_bucket.json} overridden to add a tinted layer, which would
 * make this the one place hydrarium collides with a resource pack, and it needs a water-only
 * overlay sprite that lines up with vanilla's bucket pixels — real art, not a table row, and the
 * one thing in this mod that would be. It is deliberately out of v1 and is not blocked by anything:
 * {@code ItemTintSources.ID_MAPPER} is widened to public by fabric-transitive-access-wideners-v1,
 * so registering a {@code hydrarium:water_tint} source stays a supported extension rather than a
 * mixin whenever the sprite exists.
 *
 * <p>Note that the <em>bottle</em> needs none of this. A tinted water bottle is a vanilla water
 * bottle with {@code custom_color} set, and vanilla's {@code minecraft:potion} item tint source
 * already prefers {@code custom_color} over everything else — so bottles render tinted with zero
 * mod code. See {@link CauldronTint}.
 */
public final class HydrariumComponents {

    /**
     * The water an item is holding, by id.
     *
     * <p>{@code persistent} and {@code networkSynchronized} both, because the bucket has to survive
     * a save and has to arrive at the client that will draw it. It is a {@code static final}
     * initialised by the registration itself so that nothing can ever observe it half-built — but
     * <b>that alone does not decide WHEN it is registered</b>, which is what {@link #install} is
     * for. See there; it is not a formality.
     */
    public static final DataComponentType<Identifier> TINT = Registry.register(
            BuiltInRegistries.DATA_COMPONENT_TYPE,
            Hydrarium.id("tint"),
            DataComponentType.<Identifier>builder()
                    .persistent(Identifier.CODEC)
                    .networkSynchronized(Identifier.STREAM_CODEC)
                    .build());

    private HydrariumComponents() {
    }

    /**
     * Load this class while the registry is still open. Called first from {@code onInitialize}.
     *
     * <p><b>A static initialiser does not run until something loads the class, and nothing here
     * loads it.</b> Every other reference to {@link #TINT} in this mod is inside a lambda, a mixin
     * body or a method that only runs once a player has done something — a bucket used, a cauldron
     * clicked, a creative tab opened. So without this line the component registered itself the first
     * time somebody scooped up water, which is long after {@code BuiltInRegistries} froze, and
     * {@code Registry.register} on a frozen registry throws.
     *
     * <p>It hid for as long as it did because nothing <em>asked</em> for the component early. The
     * bucket's item model does: {@code minecraft:has_component} resolves {@code hydrarium:tint}
     * through the registry while models are being parsed, and an absent component type is not a
     * warning there — the whole model fails to parse and the bucket silently falls back, with one
     * ERROR line in a log nobody reads during a resource reload:
     *
     * <pre>
     *   Couldn't parse item model 'minecraft:water_bucket' from pack 'hydrarium':
     *     Unknown registry key in ResourceKey[... / data_component_type]: hydrarium:tint
     * </pre>
     *
     * <p>So the ordering rule is: <b>this runs before anything else in {@code onInitialize}</b>, and
     * it stays a call rather than becoming a comment saying the class is loaded somehow.
     */
    public static void install() {
        Hydrarium.LOG.debug("hydrarium: component {} registered before freeze",
                BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(TINT));
    }

    /** The water this stack holds, or {@code null} for ordinary clear water. */
    public static Identifier tintOf(final ItemStack stack) {
        return stack.get(TINT);
    }

    /**
     * Stamp a water onto a stack, or leave it alone for clear.
     *
     * <p>A {@code null} water <b>removes</b> the component rather than storing a "clear" one, which
     * is what keeps an ordinary bucket of ordinary water stacking with every other ordinary bucket
     * in the game. Storing clear explicitly would give every bucket that ever touched a dye pool a
     * component of its own and split the stack for no visible reason.
     */
    public static ItemStack stamp(final ItemStack stack, final Identifier water) {
        if (water == null) {
            stack.remove(TINT);
        } else {
            stack.set(TINT, water);
        }
        return stack;
    }
}
