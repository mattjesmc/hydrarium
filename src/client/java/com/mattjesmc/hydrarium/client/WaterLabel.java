package com.mattjesmc.hydrarium.client;

import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.Waters;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.Identifier;

/**
 * One grey line under a tinted container, saying what it holds.
 *
 * <p>It is here because of the creative menu. Twenty stacks that are all called "Water Bucket" and
 * differ only by a colour are twenty stacks a player cannot search for, cannot name in a bug report
 * and cannot tell apart at a glance in a row of blues — and the creative search tab matches against
 * tooltip text, so this line is also what makes typing "red" find the red one.
 *
 * <p><b>A tooltip and not a rename</b>, which is a deliberate choice and not a smaller one. Stamping
 * {@code minecraft:item_name} onto the stack would put "Red Water Bucket" in the title, and would do
 * it by adding a second component that has to be kept in step with the first — so a bucket from a
 * mod that is no longer installed would keep insisting it holds a water this game has never heard
 * of, in bold, forever. The id is the only thing the stack should carry; what to call it is a
 * question for whoever is drawing the screen, and can be answered fresh every time.
 *
 * <p>An unknown water gets its raw id here rather than nothing. That is the same graceful loss
 * {@link Waters} gives every other holder of an id this build lost — the bucket will pour clear, and
 * the tooltip says why.
 */
public final class WaterLabel {

    private WaterLabel() {
    }

    public static void install() {
        ItemTooltipCallback.EVENT.register((stack, context, flag, lines) -> {
            final Identifier water = HydrariumComponents.tintOf(stack);
            if (water != null) {
                lines.add(Waters.name(water).copy().withStyle(ChatFormatting.GRAY));
            }
        });
    }
}
