package com.mattjesmc.hydrarium.mixin;

import com.mattjesmc.hydrarium.FrozenItem;
import com.mattjesmc.hydrarium.FrozenWater;
import com.mattjesmc.hydrarium.HydrariumComponents;
import com.mattjesmc.hydrarium.TintField;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The two ways a frozen block leaves the world holding a colour: <b>picked</b> and <b>broken</b>.
 *
 * <p>Water leaves through a bucket and the bucket remembers what it held ({@code Containers.take}).
 * Ice and snow have no bucket, so they leave by the two routes below, and both of them are methods
 * on {@code BlockStateBase} — which is why one file answers both.
 *
 * <h2>On the state rather than on the block</h2>
 *
 * <p>{@code BlockBehaviour.getCloneItemStack} and {@code BlockBehaviour.getDrops} are both
 * {@code protected}, and every block may override either. The {@code BlockStateBase} versions are
 * the public ones that call them, and they are the funnel every caller — the client's pick key, the
 * creative-mode packet, {@code Block.dropResources}, an explosion, another mod — actually goes
 * through. Hooking the funnel means an override cannot slip past, and both guards start with an
 * identity-map lookup on a block that is almost never frozen water.
 *
 * <h2>Two reads of the same field, and they are deliberately different reads</h2>
 *
 * <p>Pick block asks the <b>validating</b> question. Nothing is taken — the ice is still standing
 * there when the stack is handed over — so a stale entry at a position that has become something
 * else answers nothing, exactly as it does for the renderer. A pick block cannot launder a colour
 * out of a position the field is only wrong about.
 *
 * <p>A drop asks the <b>raw</b> one, for the reason {@code Containers.take} does: by the time
 * anything drops, the block is gone. {@code ServerPlayerGameMode.destroyBlock} calls
 * {@code removeBlock} and only then {@code playerDestroy}, so the validating read would answer clear
 * at a position that is now air and every block of red ice in the game would break into plain ice.
 * See {@link #hydrarium$dropTint} for why that raw read is nonetheless safe here.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class BlockStateBaseMixin {

    /**
     * Stamp the picked stack with the water this frozen block is made of.
     *
     * <p>Only for a row in {@link FrozenWater}: everything else in the game reaches this method too,
     * and a stack of dirt has nothing to say about a colour. And only when the field actually
     * answers, so an ordinary block of ice picked out of an ordinary glacier is the same plain
     * {@code minecraft:ice} it was before this mod existed — the stack keeps no component and
     * therefore keeps stacking with every other ice in the game.
     */
    @Inject(method = "getCloneItemStack", at = @At("RETURN"), cancellable = true)
    private void hydrarium$cloneTint(final LevelReader level, final BlockPos pos, final boolean includeData,
            final CallbackInfoReturnable<ItemStack> cir) {
        final ItemStack picked = cir.getReturnValue();
        if (picked.isEmpty() || FrozenWater.of(level.getBlockState(pos)) == null) {
            return;
        }
        final Identifier water = TintField.id(level, pos);
        if (water == null) {
            return;
        }
        cir.setReturnValue(HydrariumComponents.stamp(picked.copy(), water));
    }

    /**
     * Stamp what falls out of a frozen block with the water that block was made of.
     *
     * <p>A block of red ice silk-touched into a stack of red ice, a red drift shovelled into red
     * snowballs, a red snow block into four of them. This is the last seam where a colour could
     * leave the world and not come back — and it is the one that made "a tinted DROP" a thing this
     * mod deliberately did not build, back when the answer would have been a loot-table override per
     * block. It is one hook on one funnel instead.
     *
     * <h2>Why the RAW read is safe here, when it usually is not</h2>
     *
     * <p>{@link TintField} is advisory: an entry outlives its water and a read validates against the
     * block actually there. Reading past that validation is how the stale-entry bug comes back, so
     * every raw read owes an argument. This one's is the arrival rule, stated by
     * {@code BlockItemMixin} and enforced by {@code Containers.place}: <b>a frozen block that did
     * not come from the water at its position cleared the entry on the way in.</b> A placed one
     * wears its item's colour or none; snowfall clears; worldgen never wrote anything. So an entry
     * still sitting under a block this method has just confirmed is frozen water is that block's own
     * colour — the same one the renderer was drawing a tick ago. <b>A drop comes out the colour the
     * block was</b>, which is the only rule a player can check by looking.
     *
     * <p>And it is emphatically not cleared afterwards, unlike {@code Containers.take}'s. A bucket
     * takes the water away; breaking ice without silk touch turns it straight back into the water it
     * came from, and clearing here would make red ice melt into plain water on the one path where
     * the colour most obviously should survive.
     *
     * <h2>The colour is layer 1 only, exactly as a bucket's is</h2>
     *
     * <p>A biome may declare a water, and ice standing in such a biome renders that colour with no
     * entry anywhere ({@code FrozenTint} layer 2). It does not drop tinted, and that is the rule
     * {@code Containers.take} already set for buckets: <b>layer 2 is a property of the place, not of
     * the substance.</b> Carry it into a stack and a block of ice mined on one planet would insist
     * it was that planet's water on every other one.
     */
    @Inject(method = "getDrops", at = @At("RETURN"))
    private void hydrarium$dropTint(final LootParams.Builder params,
            final CallbackInfoReturnable<List<ItemStack>> cir) {
        final List<ItemStack> drops = cir.getReturnValue();
        if (drops.isEmpty() || FrozenWater.of((BlockState) (Object) this) == null) {
            return;
        }
        // Optional rather than required: all three of vanilla's callers set it, and a fourth from
        // some other mod that does not is a mod whose drops simply go untinted rather than a crash
        // in the middle of somebody else's loot.
        final Vec3 origin = params.getOptionalParameter(LootContextParams.ORIGIN);
        if (origin == null) {
            return;
        }
        final Identifier water = TintField.rawId(params.getLevel(), BlockPos.containing(origin));
        if (water == null) {
            return;
        }
        for (final ItemStack drop : drops) {
            // Only what can show a colour. A frozen block may drop things that cannot -- and a
            // component on one of those would be an invisible stamp that split the stack.
            if (FrozenItem.of(drop.getItem()) != null) {
                HydrariumComponents.stamp(drop, water);
            }
        }
    }
}
