package games.brennan.playermob.player;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;

import java.util.Collection;

/**
 * The {@code /playermob} command — the (op-gated) way to retrieve a player's
 * reincarnation and to inspect a live trait tally. Built once here and registered
 * by each loader's entry point via its native command-registration hook.
 *
 * <ul>
 *   <li>{@code /playermob reincarnate <player>} — grant the runner a spawn egg
 *       stamped with that player's last completed life.</li>
 *   <li>{@code /playermob life <player>} — read the in-progress life tally and the
 *       traits it would currently distil to (verify tracking without dying).</li>
 * </ul>
 */
public final class ReincarnateCommand {

    private ReincarnateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("playermob")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("reincarnate")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::reincarnate)))
                .then(Commands.literal("life")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::life))));
    }

    private static int reincarnate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        GameProfile profile = firstProfile(ctx);
        if (profile == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        ItemStack egg = PlayerReincarnation.reincarnationEgg(level, profile.getId());
        if (egg.isEmpty()) {
            source.sendFailure(Component.literal("No recorded past life for " + profile.getName() + "."));
            return 0;
        }
        ServerPlayer recipient = source.getPlayer();
        if (recipient == null) {
            source.sendFailure(Component.literal("Run this as a player to receive the egg."));
            return 0;
        }
        if (!recipient.getInventory().add(egg)) {
            recipient.drop(egg, false);
        }
        String name = profile.getName();
        source.sendSuccess(() -> Component.literal("Reincarnation egg for " + name + " granted."), false);
        return 1;
    }

    private static int life(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        GameProfile profile = firstProfile(ctx);
        if (profile == null) {
            return 0;
        }
        PlayerLifeStore store = PlayerLifeStore.get(source.getLevel());
        PlayerLifeRecord tally = store.current(profile.getId());
        DispositionTraits traits = tally.toTraits();
        boolean hasPast = store.hasLastLife(profile.getId());
        String summary = String.format(
            "%s — attacks %d, kills %d, kindness %.1f, harms %d → would reincarnate "
                + "Fight/Flight %d, Friendliness %d%s",
            profile.getName(), tally.attacks(), tally.kills(), tally.kindness(), tally.harms(),
            traits.fightFlight(), traits.friendliness(),
            hasPast ? " (has a stored past life)" : "");
        source.sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    /** Resolve the first matched profile, or send a failure and return {@code null}. */
    private static GameProfile firstProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No matching player."));
            return null;
        }
        return profiles.iterator().next();
    }
}
