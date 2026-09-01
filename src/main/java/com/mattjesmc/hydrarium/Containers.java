package com.mattjesmc.hydrarium;

import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.LevelAccessor;

/**
 * The two-line half of "the tint has to survive leaving the world, and say so when it comes back",
 * shared by every way water is picked up and every way a block is put down.
 *
 * <p>It exists as its own class rather than as a body inside each mixin because there are three
 * {@code pickupBlock} implementations in vanilla — {@code LiquidBlock}'s, the default method on
 * {@code SimpleWaterloggedBlock} that every waterloggable block inherits, and
 * {@code PowderSnowBlock}'s — and they are the same operation seen three times. Duplicating it into
 * each mixin is how the waterlogged case ends up subtly different from the open-water case a year
 * from now, and how the frozen phase ends up with container rules of its own.
 *
 * <p>Note what the third one costs, which is nothing: powder snow is picked up through
 * {@code BucketPickup}, the same interface open water is, so the frozen half's container support is
 * one mixin that calls {@link #take} and no new idea at all.
 */
public final class Containers {

    private Containers() {
    }

    /**
     * Move the tint from a position into the bucket that just emptied it.
     *
     * <p>Reads the <b>raw</b> entry, not the validated one, and that is the single subtle thing
     * here: by the time a {@code pickupBlock} has returned, the position no longer holds water, so
     * the validating read would answer clear and every bucket in the game would come away plain.
     * See {@link TintField#rawId}.
     *
     * <p>Then clears the entry — which is tidiness rather than correctness, since the validating
     * read would ignore it anyway, but it is free here and it keeps a heavily-used dye pool from
     * accumulating a map entry per block anyone ever scooped out of it.
     */
    public static ItemStack take(final LevelAccessor level, final BlockPos pos, final ItemStack taken) {
        if (taken.isEmpty()) {
            return taken;
        }
        final Identifier water = TintField.rawId(level, pos);
        if (water == null) {
            return taken;
        }
        TintField.set(level, pos, null);
        return HydrariumComponents.stamp(taken, water);
    }

    /**
     * Move the tint the other way: from a stack that has just placed a block into the position it
     * placed it at. Answers nothing; the write is the point.
     *
     * <p><b>The {@code null} write is the reason this exists</b>, and it is the mirror of the rule
     * {@link TintField} states about removal. Nothing is hooked when water leaves a position, so an
     * entry outlives its water on purpose and the read validates against the block that is actually
     * there. That was airtight while only water could wear a colour — water arrives through
     * {@code spreadTo} or a bucket, and both write the field on the way in — and the frozen half
     * broke it, because ice and snow arrive by routes that wrote nothing. A stale entry stopped
     * being invisible and became the colour of the next block anybody put down: plain ice placed in
     * a red pool came out red, and so did plain ice placed anywhere red water had once flowed.
     *
     * <p>So a placed block wears the colour its <em>item</em> declared, and an ordinary block
     * declares none. What this does <b>not</b> touch is a phase change — water that freezes, ice
     * that melts, a Frost Walker's stepping stones — because those arrive at a position the water
     * never left, and inheriting there is the whole of the frozen half.
     *
     * <h2>The one exception, which is waterlogging and is not really an exception</h2>
     *
     * <p>A stair placed into a red pool is a stair standing <em>in</em> that water, not a stair
     * that replaced it: {@link TintField#holdsWater} still answers for the position, the water is
     * still the water that was always there, and clearing it would put a blue patch through every
     * kelp bed in a tinted ocean. The test is the narrow, liquid-only predicate for exactly that
     * reason — a position that has become ice or snow is a position whose water is gone, whatever
     * phase it left in, and it is the placed block's turn to say what colour it is.
     */
    public static void place(final LevelAccessor level, final BlockPos pos, final ItemStack stack) {
        if (TintField.holdsWater(level.getBlockState(pos))) {
            return;
        }
        TintField.set(level, pos, HydrariumComponents.tintOf(stack));
    }

    /**
     * Make a freshly-filled water bottle hold this water. Answers the stack it was given.
     *
     * <p>Two ways to fill a bottle and one method, for the same reason {@link #take} is one method:
     * a cauldron ({@code CauldronTint#fillBottle}) and open water ({@code BottleItemMixin}) are the
     * same operation seen twice, and the second one is the one a player finds first — a pond is
     * where tinted water actually is.
     *
     * <p><b>Two components, and neither is redundant.</b> {@code minecraft:potion_contents} carries
     * the {@code custom_color}, which is what makes the bottle <i>render</i> tinted with no code of
     * ours: vanilla's {@code minecraft:potion} item tint source prefers {@code custom_color} over
     * everything else. {@code hydrarium:tint} carries the <i>id</i>, which is what makes the bottle
     * pourable back into a cauldron as the water it came from — a colour cannot say which water it
     * is, and two waters may share one.
     *
     * <p>It stays a water bottle. {@code PotionContents.is} checks the potion and the custom
     * <em>effects</em> and says nothing about the colour, so {@code is(Potions.WATER)} is still true
     * and every vanilla path that gates on it — the cauldron, drinking, the dispenser — is unchanged.
     * The empty effects list is what keeps that so.
     */
    public static ItemStack bottle(final ItemStack bottle, final Identifier water) {
        final WaterType type = Waters.get(water);
        if (type == null) {
            return bottle;
        }
        // The canonical constructor, because there is no withCustomColor: potion, colour, no custom
        // effects, no custom name.
        bottle.set(DataComponents.POTION_CONTENTS, new PotionContents(
                Optional.of(Potions.WATER), Optional.of(type.tint()), List.of(), Optional.empty()));
        return HydrariumComponents.stamp(bottle, water);
    }
}
