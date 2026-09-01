package com.mattjesmc.hydrarium.client;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mattjesmc.hydrarium.Hydrarium;
import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.WaterType;
import com.mattjesmc.hydrarium.Waters;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * The fifth render surface: a bucket in a hand, in an inventory, on the ground or in a frame.
 *
 * <p>It is a <b>different kind</b> of surface from the other four and that is the only interesting
 * thing about it. The world's four ({@code WaterTint}) all answer from a {@code BlockPos}, which is
 * what makes hydrarium a tint table rather than sixteen fluids. An item has no position, so this
 * one reads the {@code hydrarium:tint} component off the stack instead — the same {@link
 * net.minecraft.resources.Identifier} the bucket has carried since {@code Containers.take} stamped
 * it, resolved through the same {@link Waters} table.
 *
 * <p>Two consequences follow from having no position, and both are correct rather than
 * regrettable:
 *
 * <ul>
 *   <li><b>No biome modulation.</b> {@code biomeStrength} is a blend towards the biome the water is
 *       standing in, and a bucket is not standing in one. A red bucket is the declared red, and it
 *       stays that red as you walk from a swamp to an ocean — which is what a container ought to
 *       do.</li>
 *   <li><b>No tint field.</b> The stack knows what it holds; nothing has to be looked up in a
 *       chunk. This surface is the one that works in the inventory screen with no level at all,
 *       which is why {@code level} being {@code null} needs no handling here.</li>
 * </ul>
 *
 * <h2>Registered rather than mixed in, and this one DESIGN.md got right</h2>
 *
 * {@code ItemTintSources.ID_MAPPER} is a {@code LateBoundIdMapper} that
 * fabric-transitive-access-wideners-v1 widens to public, and {@code ClientBootstrap.bootstrap()}
 * fills it from {@code client/main/Main} <em>before</em> {@code Minecraft} exists — so a
 * {@code ClientModInitializer} adding to it is adding to a live map, well before the first resource
 * reload parses an item model that names us. No mixin, and no race with any other mod that adds a
 * source of its own.
 *
 * @param defaultColor what to answer for a water this build does not have — see the generator's
 *                     {@code bucket_default()}, which fits it to vanilla's own pixels so that a
 *                     bucket from an uninstalled mod looks like the ordinary water it will pour
 */
public record WaterTintSource(int defaultColor) implements ItemTintSource {

    public static final MapCodec<WaterTintSource> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(ExtraCodecs.RGB_COLOR_CODEC.fieldOf("default").forGetter(WaterTintSource::defaultColor))
                    .apply(i, WaterTintSource::new));

    /** {@code hydrarium:water_tint}, as {@code assets/minecraft/items/water_bucket.json} names it. */
    public static void install() {
        ItemTintSources.ID_MAPPER.put(Hydrarium.id("water_tint"), MAP_CODEC);
    }

    /**
     * The stack's water, opaqued.
     *
     * <p>{@link ARGB#opaque} is not decoration. A tint is a plain {@code 0xRRGGBB} everywhere in
     * this mod — {@link WaterType#tint()} says so — and this number goes straight into a vertex
     * colour, alpha included. Hand it over unopaqued and the overlay renders at alpha zero, which
     * looks exactly like a mod that failed to register its tint source and logs just as little.
     */
    @Override
    public int calculate(final ItemStack stack, final @Nullable ClientLevel level, final @Nullable LivingEntity owner) {
        final WaterType water = Waters.get(HydrariumComponents.tintOf(stack));
        return ARGB.opaque(water == null ? this.defaultColor : water.tint());
    }

    @Override
    public MapCodec<WaterTintSource> type() {
        return MAP_CODEC;
    }
}
