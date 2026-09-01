package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.FrozenWater;
import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.Hydrarium;
import com.mattjesmc.hydrarium.WaterType;
import com.mattjesmc.hydrarium.Waters;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import com.mojang.serialization.Codec;
import net.minecraft.client.color.item.ItemTintSource;
import net.minecraft.client.color.item.ItemTintSources;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.ARGB;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

/**
 * A block of ice, or a snow layer, held in a hand — {@link WaterTintSource} at the frozen phase.
 *
 * <p>It is the same idea and the same component: an item has no {@code BlockPos}, so it reads
 * {@code hydrarium:tint} off the stack rather than the field, and there is no biome in it for the
 * same reason a bucket has none. What it adds is the one number the frozen phase always adds, and
 * it adds it from the <b>model file</b> rather than from here: {@code frost} is how much white the
 * surface being pictured has already spent, and one source serves all five items because each of
 * them says which surface it is. See {@link FrozenWater} for why that knob belongs to the sprite.
 *
 * <p>Which makes this the <em>fourth</em> place the same wash is written down — {@link FrozenTint}
 * for the block, {@code gen_water.py}'s {@code frost()} for the swatch and the floor check, the
 * generated item models for the number itself, and this for the item. Three of the four are held
 * together by {@code gen_water.py --check}; the fourth is this class calling
 * {@link FrozenTint#frost}, which is deliberate. An item that washed its colour by an arithmetic of
 * its own would put a block of ice in a hand at a different colour from the same block one second
 * later in the world, and nothing would log because both would be perfectly reasonable numbers.
 *
 * <h2>Why an unknown water is white and not a fitted fallback</h2>
 *
 * <p>{@link WaterTintSource} carries a {@code default} colour, fitted to vanilla's own pixels, so
 * that a bucket from an uninstalled mod still looks like the ordinary water it will pour. Here the
 * honest fallback is <b>white</b>, which is the identity for a multiply and therefore vanilla's own
 * ice exactly — because unlike the bucket, this model's sprite <em>is</em> vanilla's sprite, with
 * nothing painted over it. A stamp naming a water this build has never heard of gets ordinary ice,
 * pixel for pixel, and there is no number to fit.
 *
 * @param frost how far toward white this item's colour is washed before the sprite multiplies it
 */
public record FrozenTintSource(float frost) implements ItemTintSource {

    /** Vanilla's ice, for a stack stamped with a water no loaded catalogue declares. */
    private static final int UNKNOWN = 0xFFFFFF;

    public static final MapCodec<FrozenTintSource> MAP_CODEC = RecordCodecBuilder.mapCodec(
            i -> i.group(Codec.FLOAT.fieldOf("frost").forGetter(FrozenTintSource::frost))
                    .apply(i, FrozenTintSource::new));

    /** {@code hydrarium:frozen_tint}, as the five generated item overrides name it. */
    public static void install() {
        ItemTintSources.ID_MAPPER.put(Hydrarium.id("frozen_tint"), MAP_CODEC);
    }

    /**
     * The stack's water, washed and opaqued.
     *
     * <p>{@link ARGB#opaque} for the reason {@code WaterTintSource} documents: a tint is a plain
     * {@code 0xRRGGBB} everywhere in this mod and this number goes straight into a vertex colour,
     * alpha included. {@link FrozenTint#frost} already opaques, and this says so again rather than
     * relying on it, because the two are read together and a reader should not have to go and check.
     */
    @Override
    public int calculate(final ItemStack stack, final @Nullable ClientLevel level, final @Nullable LivingEntity owner) {
        final WaterType water = Waters.get(HydrariumComponents.tintOf(stack));
        return ARGB.opaque(water == null ? UNKNOWN : FrozenTint.frost(water.tint(), this.frost));
    }

    @Override
    public MapCodec<FrozenTintSource> type() {
        return MAP_CODEC;
    }
}
