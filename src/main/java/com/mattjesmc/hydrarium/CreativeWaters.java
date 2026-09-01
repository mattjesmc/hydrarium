package com.mattjesmc.hydrarium;

import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Every declared water, as a bucket, next to the bucket it is one of — and every frozen block that
 * can hold one, in the search.
 *
 * <p><b>No tab of its own.</b> A hydrarium tab would be a tab of water buckets — the same item
 * nineteen times — sitting beside the vanilla tab that already has that item in it, and a player
 * looking for a bucket of red water would have to know which of two bucket shelves to look on.
 * These go into {@code tools_and_utilities} immediately after {@code minecraft:water_bucket},
 * because that is where somebody looking for a bucket of water is already looking. It is the same
 * reasoning that put the sixteen dye rows in {@code DyeColor}'s own order rather than alphabetical:
 * fit the list the player has already learnt.
 *
 * <p>The count grows with the catalogue rather than with hydrarium — a pack with rocketeer installed
 * gets rocketeer's waters here too, from rocketeer's own catalogue, with no code on either side.
 * That is the whole point of the table being a table.
 *
 * <h2>The frozen half is SEARCH ONLY, and that is the whole of the compromise</h2>
 *
 * <p>Seven frozen items can carry a water — ice, packed ice, blue ice, snow blocks, snow layers,
 * snowballs and the powder snow bucket — which is a hundred and thirty-three stacks against nineteen
 * waters, and it would be more if a catalogue declared more. Laid into the tabs they would bury the
 * natural blocks behind seven rows of the same seven items; left out entirely, the answer to "how do
 * I get a block of red ice" is a command, which is not an answer for anybody playing rather than
 * building.
 *
 * <p>{@link CreativeModeTab.TabVisibility#SEARCH_TAB_ONLY} is vanilla's own name for that middle:
 * they are absent from every tab and present in the search, so typing "ice" finds all of them and
 * browsing finds none. The tab they are registered against is therefore only a carrier —
 * {@code natural_blocks} because that is where vanilla's own ice and snow live, and because a
 * carrier ought to be the one a reader would have guessed.
 *
 * <p>Two things fall out of that and both are the point: <b>they are stacks, not items</b>, so a
 * search-tab ice is the same {@code minecraft:ice} with the same component that pick-blocking a
 * tinted one gives you, and placing it writes the field through the same
 * {@link Containers#place} every other placement goes through. There is no creative-only item and
 * no creative-only path — and a search-tab snowball is the same {@code minecraft:snowball} that
 * falls out of a tinted drift, which throws and shatters the colour it says it is.
 *
 * <h2>The trap this class is one line away from</h2>
 *
 * <b>An {@link ItemStack} cannot be constructed during mod init.</b> Its constructor reads the
 * item's component map, which is bound <em>after</em> entrypoints run, so a stack built in
 * {@link #install} would throw {@code NullPointerException: Components not bound yet} out of a
 * stack trace that names neither items nor components. The stacks below are built inside the event
 * handler, which the game calls when it assembles a tab's contents — long after that binding, and
 * again every time the tabs are rebuilt.
 */
public final class CreativeWaters {

    private CreativeWaters() {
    }

    /** Called once from {@code onInitialize}, after {@link Waters#load}. */
    public static void install() {
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.TOOLS_AND_UTILITIES)
                .register(output -> output.insertAfter(Items.WATER_BUCKET, buckets()));

        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.NATURAL_BLOCKS)
                .register(output -> output.acceptAll(frozen(), CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY));
    }

    /**
     * One stamped bucket per declared water, in catalogue order.
     *
     * <p>Stamped through {@link HydrariumComponents#stamp} rather than by setting the component
     * here, so that a creative bucket is the same object a bucket scooped out of a pool is — same
     * component, same stacking behaviour, same everything. There is no creative-only item and no
     * creative-only path; if the tab shows a bucket, that bucket already worked.
     */
    private static List<ItemStack> buckets() {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final WaterType water : Waters.all()) {
            stacks.add(HydrariumComponents.stamp(new ItemStack(Items.WATER_BUCKET), water.id()));
        }
        return stacks;
    }

    /**
     * Every item that can carry a water, once per declared water.
     *
     * <p>Grouped by <b>item</b> rather than by water, because these are only ever reached by typing
     * a name: a player searching "packed ice" wants the nineteen packed ices together, and nobody
     * searches for "red" expecting seven different items.
     *
     * <p>The list is {@link FrozenItem#values()}, which is the same table the drop hook and the item
     * particle read — so an item added to it appears here without this method being touched. It used
     * to be derived from {@link FrozenWater} through {@code Block.asItem}, and the snowball is what
     * ended that: it is water that has stopped moving and there is no snowball <em>block</em> for a
     * derivation to find.
     */
    private static List<ItemStack> frozen() {
        final List<ItemStack> stacks = new ArrayList<>();
        for (final FrozenItem held : FrozenItem.values()) {
            for (final WaterType water : Waters.all()) {
                stacks.add(HydrariumComponents.stamp(new ItemStack(held.item()), water.id()));
            }
        }
        return stacks;
    }
}
