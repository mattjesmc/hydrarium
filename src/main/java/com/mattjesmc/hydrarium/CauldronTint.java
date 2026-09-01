package com.mattjesmc.hydrarium;

import com.mattjesmc.hydrarium.mixin.CauldronDispatcherInvoker;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.core.cauldron.CauldronInteractions;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.DyeColor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LayeredCauldronBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;

/**
 * The cauldron: a tint that survives leaving the world in a third way, and the mixing station.
 *
 * <h2>Vanilla already points the cauldron at the field</h2>
 *
 * {@code BlockColors} registers {@code BlockTintSources.water()} for {@code Blocks.WATER_CAULDRON},
 * and that is the same pos-aware {@code colorInWorld} the fluid uses. A water cauldron sits at a
 * position, so it reads the same tint field as everything else, and <b>substituting the source is
 * the entire render change</b> — see the client half. Nothing here is about drawing.
 *
 * <h2>Wrapping vanilla, except where the result stack has to be built</h2>
 *
 * Most of these interactions are vanilla's own, wrapped: read the two colours, let vanilla do the
 * levels and the sounds and the statistics and the game events, then write the join. That keeps
 * hydrarium's share to the colour and leaves everything else vanilla's to change.
 *
 * <p>The two that draw water <em>out</em> cannot be wrapped, and the reason is worth writing down
 * because it is not obvious: {@code ItemUtils.createFilledResult} puts the new item in the player's
 * <em>inventory</em> rather than in their hand when the stack they used had more than one item in
 * it. So there is no reliable "the stack that just came out" to stamp after the fact — a player
 * holding a stack of three glass bottles would get two tinted bottles in hand and one plain one in
 * a slot, or worse. The tinted stack has to exist <em>before</em> vanilla decides where to put it,
 * which means constructing it here. Vanilla's {@code fillBucket} takes the new stack as a parameter
 * and so stays wrapped; the bottle has no such seam and is the one interaction transcribed.
 *
 * <h2>The powder snow cauldron is the same block wearing a different phase</h2>
 *
 * <p>Everything above is written about water and holds unchanged for snow, which is the frozen
 * half's whole claim arriving in a file that was not written for it. The tint field does not care
 * which of the two is in the pot ({@link TintField#holdsAnyWater}); {@link WaterMix#stir} does not
 * care either, because a stir is about colours; and vanilla's own
 * {@code block/template_cauldron_full} already carries the {@code tintindex} the snow needs, since
 * the water cauldron needed one first. So the powder snow cauldron cost four wrapped interactions,
 * one bucket factory and a second loop over the dyes — and no new idea.
 *
 * <p>It also happens to be the <b>only</b> way to make coloured snow on purpose. Everything else in
 * the frozen half is water that stopped moving: freeze a tinted pond and the ice is tinted, which
 * needs a tinted pond and a cold biome. A dye in a snowy cauldron needs neither.
 *
 * <h2>One block, two verbs</h2>
 *
 * {@code CauldronInteractions} already puts {@code ItemTags.CAULDRON_CAN_REMOVE_DYE} on the water
 * cauldron: vanilla's cauldron <em>strips</em> dye from leather and banners. Teaching it to
 * <em>add</em> dye to water gives one block both verbs, told apart only by what you are holding. It
 * plays fine and it documents badly, and it is a tension to know about rather than to fix.
 *
 * <p>The dyes are registered by item and not by tag, which matters for a reason that is invisible
 * until it bites: {@code Dispatcher.get} checks its tag map <b>first</b> and iterates it in
 * {@code HashMap} order, so two tags matching one item is a coin flip. Registering by item puts
 * these in the map that is only consulted after every tag has missed, which is both deterministic
 * and the correct precedence — a dyed leather cap in a cauldron should still be stripped.
 */
public final class CauldronTint {

    private CauldronTint() {
    }

    /** Called once from {@code onInitialize}, after {@link Waters#load}. */
    public static void install() {
        final CauldronDispatcherInvoker water = (CauldronDispatcherInvoker) CauldronInteractions.WATER;
        final CauldronDispatcherInvoker empty = (CauldronDispatcherInvoker) CauldronInteractions.EMPTY;
        final CauldronDispatcherInvoker snow = (CauldronDispatcherInvoker) CauldronInteractions.POWDER_SNOW;

        // Water and powder snow coming OUT. The buckets keep vanilla's helper because that helper
        // accepts the stack it is going to hand over; the bottle is transcribed because nothing in
        // vanilla does. Two calls to one factory rather than two methods, because they really are
        // the same interaction with a different item and a different sound in it -- which is the
        // frozen half's claim about phases, at the one place in this mod where it is a line of code
        // rather than an argument.
        water.hydrarium$put(Items.BUCKET, fillBucket(Items.WATER_BUCKET, SoundEvents.BUCKET_FILL));
        snow.hydrarium$put(Items.BUCKET,
                fillBucket(Items.POWDER_SNOW_BUCKET, SoundEvents.BUCKET_FILL_POWDER_SNOW));
        water.hydrarium$put(Items.GLASS_BOTTLE, CauldronTint::fillBottle);

        // Water and powder snow going IN, in every combination of container and starting state that
        // vanilla has an interaction for. Each is vanilla's own, with the stir written after it.
        //
        // The four that are new are the frozen half's, and the last one is worth naming: pouring a
        // WATER bucket into a powder snow cauldron replaces the snow with water, which under the
        // stir is the two colours mixing. That is the honest reading -- both buckets hold water and
        // the phase is not what the stir is about -- and it is the same answer the pot would give
        // if the snow in it had melted first.
        wrap(water, Items.POTION);
        wrap(water, Items.WATER_BUCKET);
        wrap(water, Items.POWDER_SNOW_BUCKET);
        wrap(empty, Items.POTION);
        wrap(empty, Items.WATER_BUCKET);
        wrap(empty, Items.POWDER_SNOW_BUCKET);
        wrap(snow, Items.POWDER_SNOW_BUCKET);
        wrap(snow, Items.WATER_BUCKET);

        // And the dyes, which are the only interaction here that vanilla has no version of. The
        // water id is resolved NOW and captured, so the interaction itself is a closure over one
        // Identifier rather than a lookup: reading the dye's colour off the stack at use time would
        // work, and would put a component read on a hot-ish path to learn something that cannot
        // change.
        //
        // On the snow cauldron as well as the water one, and that is not symmetry for its own sake:
        // it is the only route by which a player who is nowhere near a freezing biome can make
        // coloured snow at all. Freezing a tinted pond needs a pond and a winter; this needs a
        // cauldron, a snowfall and a dye.
        int declared = 0;
        for (final DyeColor colour : DyeColor.values()) {
            final Identifier id = Hydrarium.id(colour.getSerializedName());
            if (Waters.get(id) == null) {
                continue;
            }
            water.hydrarium$put(Items.DYE.pick(colour), addDye(id));
            snow.hydrarium$put(Items.DYE.pick(colour), addDye(id));
            declared++;
        }
        Hydrarium.LOG.info("hydrarium: cauldron mixing installed on water and powder snow; {} of {}"
                + " dyes have a declared water", declared, DyeColor.values().length);
    }

    /**
     * Replace one dispatcher entry with vanilla's own, joined.
     *
     * <p>The existing interaction is read out of the by-item map rather than through the public
     * {@code Dispatcher.get}, because {@code get} takes an {@code ItemStack} and an
     * {@code ItemStack} cannot exist yet — see {@link CauldronDispatcherInvoker}. A missing entry
     * means vanilla changed which containers a cauldron accepts, so it is logged and skipped rather
     * than wrapped into a null.
     */
    private static void wrap(final CauldronDispatcherInvoker dispatcher, final Item item) {
        final CauldronInteraction vanilla = dispatcher.hydrarium$items().get(item);
        if (vanilla == null) {
            Hydrarium.LOG.warn("hydrarium: no cauldron interaction for {} to wrap; pouring it in"
                    + " will not carry a colour", item);
            return;
        }
        dispatcher.hydrarium$put(item, pourIn(vanilla));
    }

    /**
     * Fill a bucket from a tinted cauldron, whichever phase is standing in it.
     *
     * <p>The colour is read before vanilla is called, because vanilla replaces the block with an
     * empty cauldron and the validating read would then answer clear. The field entry is dropped
     * afterwards for the same reason {@link Containers#take} drops it: the read would ignore it
     * anyway, and leaving it costs a map entry per cauldron anyone has ever emptied.
     *
     * <p>This is the interaction that <b>can</b> stay wrapped, and the bottle below is the one that
     * cannot. {@code CauldronInteractions.fillBucket} takes the new stack as a parameter, so the
     * tinted stack can be built here and handed over already stamped - which is the whole
     * requirement, because {@code ItemUtils.createFilledResult} decides for itself whether that
     * stack goes into the hand or into a spare inventory slot, and there is no finding it again
     * afterwards.
     *
     * <p><b>The stack is built inside the interaction rather than captured by the closure</b>,
     * unlike the dye's {@link Identifier} below. An {@code ItemStack} cannot be constructed during
     * mod init at all - its constructor reads a component map that is bound after entrypoints run -
     * and even if it could, one stack shared by every cauldron in the world is one stack for a
     * player to mutate.
     */
    private static CauldronInteraction fillBucket(final Item filled, final SoundEvent sound) {
        return (state, level, pos, player, hand, itemInHand) -> {
            final Identifier water = TintField.id(level, pos);
            final InteractionResult result = CauldronInteractions.fillBucket(state, level, pos, player, hand,
                    itemInHand, HydrariumComponents.stamp(new ItemStack(filled), water),
                    full -> full.getValue(LayeredCauldronBlock.LEVEL) == 3, sound);
            if (result.consumesAction() && !level.isClientSide()) {
                TintField.set(level, pos, null);
            }
            return result;
        };
    }

    /**
     * Fill a glass bottle from a tinted cauldron: one third, and the bottle needs no mod code to
     * draw itself.
     *
     * <p><b>A tinted water bottle is a vanilla water bottle with {@code custom_color} set.</b>
     * Vanilla's {@code minecraft:potion} item tint source reads {@code PotionContents.getColorOr}
     * which prefers {@code custom_color} over everything else, so the bottle renders tinted with
     * zero registration on our side. And it is still water: {@code PotionContents.is} checks the
     * potion and the custom <em>effects</em> and says nothing about the colour, so
     * {@code is(Potions.WATER)} stays true and the return trip through this same cauldron works
     * unchanged.
     *
     * <p>The bottle carries the water's <em>id</em> as well, in hydrarium's own component, because
     * the colour alone cannot say which water it is — and it is the id, not the number, that the
     * join works over.
     *
     * <p>The obvious bug this invites — brewing a red water bottle into a red-looking Potion of
     * Poison — <b>cannot happen</b>: {@code PotionBrewing.mix} returns
     * {@code PotionContents.createItemStack(...)}, a fresh stack, so both the colour and the
     * component are dropped the instant a water bottle stops being water. That is free, and it is
     * exactly the kind of free thing a future Minecraft version takes back — and it is <b>not</b>
     * pinned by a test, because pinning it needs a brewing registry and this repo's suite boots no
     * game. It is a line to re-read in {@code PotionBrewing.mix} on every Minecraft update, which
     * is why the file and the method are named here rather than the behaviour just asserted.
     */
    private static InteractionResult fillBottle(final BlockState state, final Level level, final BlockPos pos,
            final Player player, final InteractionHand hand, final ItemStack itemInHand) {
        if (!level.isClientSide()) {
            final Identifier water = TintField.id(level, pos);
            final ItemStack bottle = Containers.bottle(
                    PotionContents.createItemStack(Items.POTION, Potions.WATER), water);
            final Item used = itemInHand.getItem();
            player.setItemInHand(hand, ItemUtils.createFilledResult(itemInHand, player, bottle));
            player.awardStat(Stats.USE_CAULDRON);
            player.awardStat(Stats.ITEM_USED.get(used));
            LayeredCauldronBlock.lowerFillLevel(state, level, pos);
            level.playSound(null, pos, SoundEvents.BOTTLE_FILL, SoundSource.BLOCKS, 1.0F, 1.0F);
            level.gameEvent(null, GameEvent.FLUID_PICKUP, pos);
            // The last third: the block is a plain cauldron now and there is nothing left to colour.
            if (!TintField.holdsWater(level.getBlockState(pos))) {
                TintField.set(level, pos, null);
            }
        }
        return InteractionResult.SUCCESS;
    }

    /**
     * Vanilla's interaction, with the join written after it.
     *
     * <p>Both colours are read <em>before</em> vanilla runs, because vanilla is about to change the
     * block and consume the item. The validating read is right for the cauldron in both starting
     * states without a branch: an empty cauldron does not hold water, so it reads clear, which is
     * exactly what an empty cauldron contains.
     *
     * <p>It is a {@linkplain WaterMix#stir stir} and not a {@linkplain WaterMix#join join}, and that
     * is the whole of the bug this had. Under the join, clear absorbed — so pouring a red bucket
     * into an empty cauldron read {@code join(clear, red)} and wrote <b>clear</b>, and a cauldron
     * could not be given a colour by any route at all. Every tinted thing downstream of a cauldron
     * (the bottle, the bucket back out, the dye) was dead behind that one call, and nothing logged,
     * because writing clear over clear is a perfectly ordinary thing for this mod to do.
     */
    private static CauldronInteraction pourIn(final CauldronInteraction inner) {
        return (state, level, pos, player, hand, itemInHand) -> {
            final Identifier poured = tintOfContainer(itemInHand);
            final Identifier standing = TintField.id(level, pos);
            final InteractionResult result = inner.interact(state, level, pos, player, hand, itemInHand);
            if (result.consumesAction() && !level.isClientSide()) {
                TintField.set(level, pos, WaterMix.stir(level, standing, poured));
            }
            return result;
        };
    }

    /**
     * A dye in a water cauldron: the water becomes that dye's water, or blends with what is already
     * in there.
     *
     * <p>This is the mixing station, and it is vanilla's own recipe book doing the mixing — red then
     * blue is purple in a cauldron for the same reason it is on a crafting grid, and a pair with no
     * recipe muddies to grey. See {@link WaterMix}.
     *
     * <p>The dye is consumed even when the answer is the colour that was already there. Stirring red
     * into red water is a no-op the player can see, and a cauldron that silently refused the item
     * would be a cauldron that looked broken instead.
     */
    private static CauldronInteraction addDye(final Identifier dye) {
        return (state, level, pos, player, hand, itemInHand) -> {
            if (!level.isClientSide()) {
                final Identifier standing = TintField.id(level, pos);
                TintField.set(level, pos, WaterMix.stir(level, standing, dye));
                final Item used = itemInHand.getItem();
                itemInHand.consume(1, player);
                player.awardStat(Stats.USE_CAULDRON);
                player.awardStat(Stats.ITEM_USED.get(used));
                level.playSound(null, pos, SoundEvents.BOTTLE_EMPTY, SoundSource.BLOCKS, 1.0F, 1.0F);
                level.gameEvent(null, GameEvent.FLUID_PLACE, pos);
            }
            return InteractionResult.SUCCESS;
        };
    }

    /**
     * The water a container is holding: hydrarium's component on a bucket, or the same component on
     * a bottle. One method because both containers carry the same component for the same reason.
     */
    private static Identifier tintOfContainer(final ItemStack stack) {
        return HydrariumComponents.tintOf(stack);
    }
}
