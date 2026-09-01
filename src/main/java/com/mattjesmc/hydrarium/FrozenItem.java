package com.mattjesmc.hydrarium;

import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * The frozen phase in a hand: which <b>items</b> may carry a water, and which surface each one is a
 * picture of.
 *
 * <p>{@link FrozenWater} is the same table asked about blocks, and for a long time it was the only
 * one — the item list was derived from it by {@code Block.asItem}, because every frozen surface a
 * player could hold was a surface they could also stand on. <b>The snowball is what broke that.</b>
 * It is water that has stopped moving, it is what a snow layer becomes when you break one, and there
 * is no {@code minecraft:snowball} block for a derivation to find. So the item side is its own row
 * list, and this is it.
 *
 * <h2>Three callers, and none of them could ask {@link FrozenWater}</h2>
 *
 * <ol>
 *   <li><b>The drop</b> ({@code BlockStateBaseMixin}). A block of ice knows what colour it was; the
 *       stack that falls out of it has to be told, and only for stacks that can show a colour. Snow
 *       drops snowballs, so the question "which items can wear a tint" is not answerable from the
 *       block that dropped them.</li>
 *   <li><b>The item particle</b> ({@code BreakingItemParticleMixin}), which needs the {@link #frost}
 *       of the item it is a fleck of.</li>
 *   <li><b>The creative search</b> ({@link CreativeWaters#install}), which lists exactly these,
 *       once per declared water.</li>
 * </ol>
 *
 * <h2>The frost is NAMED, never repeated</h2>
 *
 * Every row points at the {@link FrozenWater} surface whose sprite it pictures and reads that row's
 * {@link FrozenWater#frost()}. Writing the number here instead would put the same wash in two
 * tables in one language, and the failure that invites is the quiet one: a block of ice in a hand at
 * a different colour from the same block one second later in the world, with both numbers perfectly
 * reasonable and nothing logged. There are already four places this wash is written down (see
 * {@code FrozenTintSource}); this is deliberately not a fifth.
 *
 * <p>Which leaves exactly one row that has to choose a surface rather than inherit an obvious one,
 * and it is the whole reason this file exists. See {@link #SNOWBALL}.
 */
public enum FrozenItem {

    /**
     * A snow layer, which is the item {@code minecraft:snow} — not to be confused with the snowball
     * below, which is what the same block drops when it is broken without silk touch.
     */
    SNOW(Items.SNOW, FrozenWater.SNOW),
    SNOW_BLOCK(Items.SNOW_BLOCK, FrozenWater.SNOW_BLOCK),

    /**
     * The one row with no block of its own, and therefore the one that has to say which surface it
     * is a picture of rather than being handed one.
     *
     * <p>It says <b>snow</b>, and takes snow's frost, which is none. That is a claim about the
     * sprite and it is worth the paragraph, because {@code item/snowball} averages {@code #c7dede}
     * and {@code block/snow} averages {@code #f9fefe} — the snowball is a dimmer white, so the
     * multiply is not the exact one snow gets, and the {@code --check} floor that exempts a
     * zero-wash surface would have exempted a wash this one needed.
     *
     * <p>It does not need one. The wash exists to rescue dark colours from a sprite that is
     * <em>blue</em>: {@code block/ice} is 72% as bright as white <b>and</b> blue, so it drags every
     * water toward its own colour and turns the darkest into holes. {@code #c7dede} is barely
     * tinted at all — it darkens every water by the same eighth and turns none of them a different
     * hue — and a snowball is a lump of the snow it came from and ought to look like one. Washing it
     * would make a red snowball paler than the red drift it was shovelled out of, for no reason a
     * player could name, and would buy that in the one currency {@link FrozenWater} says the wash is
     * paid in.
     *
     * <p>The consequence is the same one snow already has and means: black water makes a black
     * snowball, and that is the answer the catalogue asked for rather than a hole to be corrected.
     */
    SNOWBALL(Items.SNOWBALL, FrozenWater.SNOW),

    /**
     * Powder snow's item, which is a <b>bucket</b> — and the one row here that is not drawn by
     * {@code hydrarium:frozen_tint} at all.
     *
     * <p>It is a container, so it is stamped by {@code Containers.take} rather than by a drop (a
     * powder snow block drops nothing), and it is drawn through the same masked-sprite path as the
     * water bucket, so its {@link #frost} is never consulted. It is listed anyway because the third
     * caller — the creative search — is a list of every item that can hold a water, and leaving the
     * bucket out of that list would silently drop nineteen stacks out of the search the day this
     * replaced the derivation it grew from.
     *
     * <p>Its frost is {@link FrozenWater#POWDER_SNOW}'s, which is zero, which is also the right
     * answer for a grey mask if anything ever does ask.
     */
    POWDER_SNOW_BUCKET(Items.POWDER_SNOW_BUCKET, FrozenWater.POWDER_SNOW),

    ICE(Items.ICE, FrozenWater.ICE),
    PACKED_ICE(Items.PACKED_ICE, FrozenWater.PACKED_ICE),
    BLUE_ICE(Items.BLUE_ICE, FrozenWater.BLUE_ICE);

    private static final Map<Item, FrozenItem> BY_ITEM = byItem();

    private final Item item;
    private final FrozenWater surface;

    FrozenItem(final Item item, final FrozenWater surface) {
        this.item = item;
        this.surface = surface;
    }

    /** The vanilla item this row is about. hydrarium registers none of them. */
    public Item item() {
        return this.item;
    }

    /** The block surface this item is a picture of. */
    public FrozenWater surface() {
        return this.surface;
    }

    /**
     * How far toward white this item's colour is washed before its sprite multiplies it — read from
     * {@link #surface()} rather than declared, so that a held block and a placed one cannot drift.
     */
    public float frost() {
        return this.surface.frost();
    }

    /** The row for an item, or {@code null} if that item cannot carry a water. */
    public static FrozenItem of(final Item item) {
        return BY_ITEM.get(item);
    }

    private static Map<Item, FrozenItem> byItem() {
        final Map<Item, FrozenItem> map = new IdentityHashMap<>();
        for (final FrozenItem held : values()) {
            map.put(held.item, held);
        }
        return Map.copyOf(map);
    }
}
