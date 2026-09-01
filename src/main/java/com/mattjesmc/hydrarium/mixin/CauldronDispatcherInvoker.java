package com.mattjesmc.hydrarium.mixin;

import java.util.Map;
import net.minecraft.core.cauldron.CauldronInteraction;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * {@code CauldronInteraction.Dispatcher.put} is package-private, and this is the one line that
 * makes it reachable.
 *
 * <p>DESIGN.md expected the re-registrations to be "four re-{@code put}s during init. No mixin." —
 * the map is genuinely mutable and genuinely designed to be added to, and vanilla's own
 * {@code addDefaultInteractions} is public. It is only the {@code put} that is not, and Fabric's
 * transitive access wideners do not widen it (checked, not assumed). An {@code @Invoker} is the
 * smallest possible correction: no injection, no callback, no behaviour changed — it compiles a
 * call to an existing method that was already going to be called by vanilla's own bootstrap.
 *
 * <p>The {@code @Accessor} beside it is the other half of what
 * {@link com.mattjesmc.hydrarium.CauldronTint} needs — reading the interaction it is about to wrap.
 * {@code Dispatcher.get} <em>is</em> public and looks like the way to do that, and it is not:
 * <b>it takes an {@code ItemStack}, and an {@code ItemStack} cannot be constructed during mod
 * init.</b> Its constructor reads the item's component map, which is bound after entrypoints run,
 * so {@code new ItemStack(Items.POTION)} at that moment throws
 * {@code NullPointerException: Components not bound yet} — out of a stack that names neither items
 * nor components until the fourth frame. The map is keyed by {@code Item}, which needs no stack.
 */
@Mixin(CauldronInteraction.Dispatcher.class)
public interface CauldronDispatcherInvoker {

    @Invoker("put")
    void hydrarium$put(Item item, CauldronInteraction interaction);

    /** The by-item map, so an interaction can be read without building an {@code ItemStack}. */
    @Accessor("items")
    Map<Item, CauldronInteraction> hydrarium$items();
}
