package com.mattjesmc.hydrarium;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import java.util.Collection;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.IdentifierArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * {@code /water} — get a water, paint one, or ask what one is.
 *
 * <pre>
 *   /water give &lt;targets&gt; &lt;water&gt; [&lt;count&gt;]   buckets, stamped
 *   /water fill &lt;from&gt; &lt;to&gt; &lt;water&gt;           repaint the water already in a box
 *   /water clear &lt;from&gt; &lt;to&gt;                  take the paint off it again
 *   /water at &lt;pos&gt;                           what water is here, and which layer said so
 * </pre>
 *
 * <h2>{@code fill} paints; it does not place</h2>
 *
 * It writes the tint field at positions that <b>already hold water</b> and skips every other block,
 * which makes it a companion to vanilla's {@code /fill} rather than a competitor: fill the pool with
 * {@code /fill ... water}, then colour it with this. That division is not tidiness, it is the mod's
 * one idea showing through at the command line — <b>the water is vanilla's and only the colour is
 * ours</b>, so the command that places water is vanilla's too and there is nothing here to keep in
 * step with it. A version of this that placed blocks would need its own answers for waterlogging,
 * for block updates, for {@code /fill}'s own modes, and every one of those answers would be a
 * slightly worse copy of one the game already has.
 *
 * <p><b>Paint the source, not the stream.</b> A source, a block of ice and a snow layer keep what
 * this gives them; a <em>flowing</em> cell does not, because a flowing cell derives its colour from
 * what feeds it and re-derives it the next time the game ticks it — see {@link Flow}. So a fill
 * over a stretch of moving water holds for a few ticks and then goes back to the colour of whatever
 * is upstream. That is not this command failing: painting the source is the shorter command anyway,
 * and the wave carries it the whole length of the channel.
 *
 * <p>The same reasoning is why {@code clear} is a subcommand rather than a magic water id. "Clear"
 * is the <em>absence</em> of an entry everywhere else in this mod — {@link ChunkTints#with} removes
 * rather than stores it, {@link HydrariumComponents#stamp} removes rather than stores it — and
 * giving it a name at the command line would be the one place it was a thing.
 *
 * <h2>{@code at} answers in layers, because that is the question actually being asked</h2>
 *
 * Nobody types this to learn a colour; they type it because water is the wrong colour and they want
 * to know <em>who decided</em>. So it reports which of the client's three layers answered — the
 * per-position field, the biome's declared water, or vanilla — in the same order
 * {@code WaterTint.resolve} consults them. An answer of "ordinary water" over a biome the pack
 * believes it declared is the bug report, in one line, with no log level to raise.
 */
public final class WaterCommand {

    /**
     * Vanilla's own {@code /fill} limit, borrowed rather than invented.
     *
     * <p>The cost here is not the map writes; it is that every touched section re-meshes on every
     * client watching, and a tint change is a re-mesh (see {@link TintField}). A player who types a
     * region big enough to matter should get the same refusal they would get from the command they
     * learnt this syntax from.
     */
    private static final int MAX_FILL = 32768;

    /** As {@code /give}: a hundred stacks, which for a bucket is a hundred buckets. */
    private static final int MAX_GIVE = 100;

    private static final DynamicCommandExceptionType UNKNOWN_WATER = new DynamicCommandExceptionType(
            id -> Component.translatable("commands.hydrarium.unknown", id));

    /**
     * Every declared water, from every loaded catalogue.
     *
     * <p>Which means a consumer's waters are suggested here with no code on either side — the
     * command knows about {@code rocketeer:lumewater} for the same reason the renderer does, which
     * is that both of them only ever read {@link Waters}.
     */
    private static final SuggestionProvider<CommandSourceStack> WATERS = (context, builder) ->
            SharedSuggestionProvider.suggestResource(Waters.all().stream().map(WaterType::id), builder);

    private WaterCommand() {
    }

    /** Called once from {@code onInitialize}, after {@link Waters#load}. */
    public static void install() {
        CommandRegistrationCallback.EVENT.register((dispatcher, context, selection) -> register(dispatcher));
    }

    private static void register(final CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("water")
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                .then(Commands.literal("give")
                        .then(Commands.argument("targets", EntityArgument.players())
                                .then(Commands.argument("water", IdentifierArgument.id())
                                        .suggests(WATERS)
                                        .executes(c -> give(c, 1))
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, MAX_GIVE))
                                                .executes(c -> give(c, IntegerArgumentType.getInteger(c, "count")))))))
                .then(Commands.literal("fill")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .then(Commands.argument("water", IdentifierArgument.id())
                                                .suggests(WATERS)
                                                .executes(c -> fill(c, declared(c).id()))))))
                .then(Commands.literal("clear")
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos())
                                        .executes(c -> fill(c, null)))))
                .then(Commands.literal("at")
                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                .executes(WaterCommand::at))));
    }

    /**
     * The water named by the {@code water} argument, refusing an id no catalogue declares.
     *
     * <p>This is the one place in hydrarium that treats an unknown id as an <b>error</b>, and the
     * asymmetry is on purpose. Everywhere else — a saved bucket, a stored field entry, a biome row —
     * an unknown id is a mod that is not installed, and answering "clear" is the graceful thing to
     * do with it. Here it is a typo, being made right now, by somebody watching; telling them is
     * strictly better than handing them a bucket that pours nothing.
     */
    private static WaterType declared(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final Identifier id = IdentifierArgument.getId(context, "water");
        final WaterType water = Waters.get(id);
        if (water == null) {
            throw UNKNOWN_WATER.create(id);
        }
        return water;
    }

    private static int give(final CommandContext<CommandSourceStack> context, final int count)
            throws CommandSyntaxException {
        final WaterType water = declared(context);
        final Collection<ServerPlayer> players = EntityArgument.getPlayers(context, "targets");

        for (final ServerPlayer player : players) {
            for (int i = 0; i < count; i++) {
                // Stamped through the same method a bucket scooped out of a pool goes through, so a
                // command-made bucket is not a special kind of bucket -- it is the same stack, and
                // there is no second path for it to be broken on its own.
                final ItemStack stack = HydrariumComponents.stamp(new ItemStack(Items.WATER_BUCKET), water.id());
                player.getInventory().add(stack);
                if (!stack.isEmpty()) {
                    final ItemEntity drop = player.drop(stack, false);
                    if (drop != null) {
                        drop.setNoPickUpDelay();
                        drop.setTarget(player.getUUID());
                    }
                }
            }
            player.containerMenu.broadcastChanges();
        }

        final Component name = Waters.name(water.id());
        if (players.size() == 1) {
            final Component who = players.iterator().next().getDisplayName();
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.hydrarium.give.single", count, name, who), true);
        } else {
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.hydrarium.give.multiple", count, name, players.size()), true);
        }
        return players.size();
    }

    /**
     * Repaint every water block in the box, and nothing else in it.
     *
     * <p>The skip is {@link TintField#holdsAnyWater}, which is the same predicate the validating
     * read uses — so this cannot write an entry the next read would refuse to honour. Waterlogged
     * blocks are included by that predicate and therefore by this command, for free and without a
     * word about stairs anywhere in this file; so, since the frozen half, are ice and snow, which
     * makes this the one way to paint a frozen lake that never was tinted water.
     */
    private static int fill(final CommandContext<CommandSourceStack> context, final Identifier water)
            throws CommandSyntaxException {
        final ServerLevel level = context.getSource().getLevel();
        final BlockPos from = BlockPosArgument.getLoadedBlockPos(context, "from");
        final BlockPos to = BlockPosArgument.getLoadedBlockPos(context, "to");

        final long volume = (Math.abs((long) to.getX() - from.getX()) + 1)
                * (Math.abs((long) to.getY() - from.getY()) + 1)
                * (Math.abs((long) to.getZ() - from.getZ()) + 1);
        if (volume > MAX_FILL) {
            context.getSource().sendFailure(
                    Component.translatable("commands.hydrarium.fill.toobig", volume, MAX_FILL));
            return 0;
        }

        int painted = 0;
        for (final BlockPos pos : BlockPos.betweenClosed(from, to)) {
            if (!TintField.holdsAnyWater(level.getBlockState(pos))) {
                continue;
            }
            TintField.set(level, pos, water);
            painted++;
        }

        final int total = painted;
        if (total == 0) {
            context.getSource().sendFailure(Component.translatable("commands.hydrarium.fill.none"));
            return 0;
        }
        if (water == null) {
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.hydrarium.fill.cleared", total), true);
        } else {
            final Component name = Waters.name(water);
            context.getSource().sendSuccess(
                    () -> Component.translatable("commands.hydrarium.fill.success", total, name), true);
        }
        return total;
    }

    /**
     * Layer 1, then layer 2, then vanilla — the client's own order, asked on the server.
     *
     * <p>It reads through {@link TintField#id} rather than the raw entry, so a stale entry at a
     * position a sponge emptied answers "nothing there holds water" exactly as the renderer does. An
     * {@code at} that reported the map would be a diagnostic that disagreed with the thing it was
     * diagnosing.
     */
    private static int at(final CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        final ServerLevel level = context.getSource().getLevel();
        final BlockPos pos = BlockPosArgument.getLoadedBlockPos(context, "pos");

        if (!TintField.holdsAnyWater(level.getBlockState(pos))) {
            context.getSource().sendFailure(Component.translatable("commands.hydrarium.at.dry"));
            return 0;
        }

        // The block, named, because the layer is only half of what anybody types this to find out.
        // "Coloured water does not freeze" was reported as a bug and could not be checked in the
        // game: red ice and red water are nearly the same colour from above, and every line this
        // command printed was true of both. Vanilla's own name for the block answers it in a word,
        // and it is the same word for a waterlogged stair, a cauldron and a snow layer.
        final Component block = level.getBlockState(pos).getBlock().getName();

        final Identifier painted = TintField.id(level, pos);
        if (painted != null) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "commands.hydrarium.at.field", Waters.name(painted), block), false);
            return 1;
        }

        final Identifier biome = level.getBiome(pos).unwrapKey().map(ResourceKey::identifier).orElse(null);
        final WaterType declared = Waters.forBiome(biome);
        if (declared != null) {
            context.getSource().sendSuccess(() -> Component.translatable(
                    "commands.hydrarium.at.biome", Waters.name(declared.id()), biome.toString(), block), false);
            return 1;
        }

        // The one place this command has to know about phases, and it is a wording question rather
        // than a resolution one: layers 1 and 2 answer the same for ice as for water, but "Ordinary
        // water" said of a block of ice is a diagnostic disagreeing with the thing it is
        // diagnosing, which is the exact sin the header warns about.
        final boolean frozen = FrozenWater.of(level.getBlockState(pos)) != null;
        context.getSource().sendSuccess(() -> Component.translatable(
                frozen ? "commands.hydrarium.at.frozen" : "commands.hydrarium.at.vanilla"), false);
        return 1;
    }
}
