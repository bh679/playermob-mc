package games.brennan.playermob.player;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.DispositionTraits;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
//? if >=26 {
/*// 26.x renamed ResourceLocation → Identifier, so the namespaced-id argument is IdentifierArgument
// (same id()/getId() shape). Referenced fully-qualified at the two call sites below.
*///?} else {
import net.minecraft.commands.arguments.ResourceLocationArgument;
//?}
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
 *   <li>{@code /playermob debug spawnlog [on|off]} — toggle (or report) the colour-coded
 *       Dungeon-Train auto-spawn chat log for this session.</li>
 *   <li>{@code /playermob naturalspawn [on|off]} — toggle (or report) the natural-spawn master
 *       switch for this session.</li>
 *   <li>{@code /playermob naturalspawn <mob> on|off|<chance>} — set a mob's replacement chance
 *       (the chance a PlayerMob spawns instead of it on a natural spawn) for this session.</li>
 * </ul>
 *
 * <p>Like {@code spawnlog}, the {@code naturalspawn} edits are session overrides — they take effect
 * immediately without a restart but are not written back to {@code config/playermob.properties}.</p>
 */
public final class ReincarnateCommand {

    private ReincarnateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
            Commands.literal("playermob")
                //? if >=26 {
                /*// 26.x replaced the int permission level with PermissionCheck constants;
                // LEVEL_GAMEMASTERS is the old op-level 2.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                *///?} else {
                .requires(source -> source.hasPermission(2))
                //?}
                .then(Commands.literal("reincarnate")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::reincarnate)))
                .then(Commands.literal("life")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::life)))
                .then(Commands.literal("debug")
                    .then(Commands.literal("spawnlog")
                        .executes(ReincarnateCommand::querySpawnLog)
                        .then(Commands.literal("on").executes(ctx -> setSpawnLog(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setSpawnLog(ctx, false)))))
                .then(Commands.literal("naturalspawn")
                    .executes(ReincarnateCommand::reportNaturalSpawn)
                    .then(Commands.literal("on").executes(ctx -> setNaturalSpawnMaster(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setNaturalSpawnMaster(ctx, false)))
                    // Literals bind before the argument, so `on`/`off` above are unambiguous.
                    .then(Commands.argument("mob",
                            //? if >=26 {
                            /*net.minecraft.commands.arguments.IdentifierArgument.id()*///?} else {
                            ResourceLocationArgument.id()
                            //?}
                            )
                        .suggests((ctx, builder) ->
                            SharedSuggestionProvider.suggest(PlayerMobConfig.NATURAL_SPAWN_MOBS, builder))
                        .then(Commands.literal("on")
                            .executes(ctx -> setMobScale(ctx, PlayerMobConfig.naturalSpawnDefaultScale())))
                        .then(Commands.literal("off")
                            .executes(ctx -> setMobScale(ctx, 0.0F)))
                        .then(Commands.argument("chance", FloatArgumentType.floatArg(0.0F, 1.0F))
                            .executes(ctx -> setMobScale(ctx, FloatArgumentType.getFloat(ctx, "chance")))))));
    }

    /** {@code /playermob debug spawnlog} — report whether the DT-spawn debug log is on. */
    private static int querySpawnLog(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.debugSpawnLog();
        ctx.getSource().sendSuccess(
            () -> Component.literal("PlayerMob DT-spawn debug log is " + (on ? "ON" : "OFF") + "."), false);
        return 1;
    }

    /** {@code /playermob debug spawnlog on|off} — flip the DT-spawn debug log for this session. */
    private static int setSpawnLog(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setDebugSpawnLog(enabled);
        ctx.getSource().sendSuccess(
            () -> Component.literal("PlayerMob DT-spawn debug log " + (enabled ? "enabled" : "disabled")
                + " for this session."), false);
        return 1;
    }

    /** {@code /playermob naturalspawn} — report the master switch + how many per-mob overrides are set. */
    private static int reportNaturalSpawn(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.naturalSpawnEnabled();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob natural spawning is " + (on ? "ON" : "OFF") + " (default chance "
                + PlayerMobConfig.naturalSpawnDefaultScale() + ")."), false);
        return 1;
    }

    /** {@code /playermob naturalspawn on|off} — flip the natural-spawn master switch for this session. */
    private static int setNaturalSpawnMaster(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setNaturalSpawnEnabled(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob natural spawning " + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /**
     * {@code /playermob naturalspawn <mob> on|off|<chance>} — set one mob's replacement chance for this
     * session. Notes when the master switch is off (so the change won't take effect until it's turned on).
     */
    private static int setMobScale(CommandContext<CommandSourceStack> ctx, float chance) {
        String mob =
            //? if >=26 {
            /*net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "mob").toString();*///?} else {
            ResourceLocationArgument.getId(ctx, "mob").toString();
            //?}
        PlayerMobConfig.setNaturalSpawnScale(mob, chance);
        String suffix = PlayerMobConfig.naturalSpawnEnabled()
            ? "" : " (natural spawning is OFF — /playermob naturalspawn on to apply)";
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob replacement chance for " + mob + " set to " + chance + suffix + "."), false);
        return 1;
    }

    private static int reincarnate(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var profile = firstProfile(ctx);
        if (profile == null) {
            return 0;
        }
        ServerLevel level = source.getLevel();
        ItemStack egg = PlayerReincarnation.reincarnationEgg(level, idOf(profile));
        if (egg.isEmpty()) {
            source.sendFailure(Component.literal("No recorded past life for " + nameOf(profile) + "."));
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
        String name = nameOf(profile);
        source.sendSuccess(() -> Component.literal("Reincarnation egg for " + name + " granted."), false);
        return 1;
    }

    private static int life(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        var profile = firstProfile(ctx);
        if (profile == null) {
            return 0;
        }
        PlayerLifeRecord tally = PlayerLifeStore.get(source.getLevel()).current(idOf(profile));
        DispositionTraits traits = tally.toTraits();
        boolean hasPast = GlobalLifeStore.get(source.getServer()).hasAnyForPlayer(idOf(profile));
        String summary = String.format(
            "%s — attacks %d, kills %d, kindness %.1f, harms %d → would reincarnate "
                + "Fight/Flight %d, Friendliness %d%s",
            nameOf(profile), tally.attacks(), tally.kills(), tally.kindness(), tally.harms(),
            traits.fightFlight(), traits.friendliness(),
            hasPast ? " (has a stored past life)" : "");
        source.sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    /** Resolve the first matched profile, or send a failure and return {@code null}.
     *  26.x's {@code GameProfileArgument.getGameProfiles} returns {@code NameAndId} records
     *  ({@code .id()}/{@code .name()}) instead of authlib {@code GameProfile}s. */
    //? if >=26 {
    /*private static net.minecraft.server.players.NameAndId firstProfile(
            CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<net.minecraft.server.players.NameAndId> profiles =
            GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No matching player."));
            return null;
        }
        return profiles.iterator().next();
    }

    private static java.util.UUID idOf(net.minecraft.server.players.NameAndId p) { return p.id(); }
    private static String nameOf(net.minecraft.server.players.NameAndId p) { return p.name(); }
    *///?} else {
    private static GameProfile firstProfile(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        Collection<GameProfile> profiles = GameProfileArgument.getGameProfiles(ctx, "player");
        if (profiles.isEmpty()) {
            ctx.getSource().sendFailure(Component.literal("No matching player."));
            return null;
        }
        return profiles.iterator().next();
    }

    private static java.util.UUID idOf(GameProfile p) { return p.getId(); }
    private static String nameOf(GameProfile p) { return p.getName(); }
    //?}
}
