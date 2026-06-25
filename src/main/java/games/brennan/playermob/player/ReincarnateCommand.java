package games.brennan.playermob.player;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.PlayerMobSummon;
import games.brennan.playermob.skin.PlayerSkinResolver;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.phys.Vec3;
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
 *   <li>{@code /playermob summon <displayName> [<pos>] [<friendliness>] [<fightFlight>]} — spawn a
 *       PlayerMob wearing that player's resolved skin, at an optional position (default: the command
 *       source), with optional locked traits (default: random).</li>
 *   <li>{@code /playermob debug spawnlog [on|off]} — toggle (or report) the colour-coded
 *       Dungeon-Train auto-spawn chat log for this session.</li>
 *   <li>{@code /playermob unlimitedammo [on|off]} — toggle (or report) global unlimited ammo for
 *       this session: {@code on} = ranged weapons never run out; {@code off} = they consume inventory
 *       ammo (the {@code requireArrows} default). A session override of the config flag.</li>
 *   <li>{@code /playermob naturalspawn [on|off]} — toggle (or report) the natural-spawn master
 *       switch for this session.</li>
 *   <li>{@code /playermob naturalspawn <mob> on|off|<chance>} — set a mob's companion chance
 *       (the chance a PlayerMob spawns <em>beside</em> it on a natural spawn) for this session.</li>
 *   <li>{@code /playermob naturalspawn group <group> on|off|<chance>} — set every mob in a group
 *       (hostile / nether / animals / friendly / water / villager) for this session.</li>
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
                .then(Commands.literal("summon")
                    .then(Commands.argument("displayName", StringArgumentType.word())
                        .executes(ctx -> summon(ctx, null, null, null))
                        // Position chains before the traits (and a number alone would be ambiguous with
                        // a vec3 coord), so use `~ ~ ~` to set traits "here" — mirrors /summon's [pos].
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                            .executes(ctx -> summon(ctx, Vec3Argument.getVec3(ctx, "pos"), null, null))
                            .then(Commands.argument("friendliness", IntegerArgumentType.integer(0, 10))
                                .executes(ctx -> summon(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                    IntegerArgumentType.getInteger(ctx, "friendliness"), null))
                                .then(Commands.argument("fightFlight", IntegerArgumentType.integer(0, 10))
                                    .executes(ctx -> summon(ctx, Vec3Argument.getVec3(ctx, "pos"),
                                        IntegerArgumentType.getInteger(ctx, "friendliness"),
                                        IntegerArgumentType.getInteger(ctx, "fightFlight"))))))))
                .then(Commands.literal("debug")
                    .then(Commands.literal("spawnlog")
                        .executes(ReincarnateCommand::querySpawnLog)
                        .then(Commands.literal("on").executes(ctx -> setSpawnLog(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setSpawnLog(ctx, false)))))
                .then(Commands.literal("unlimitedammo")
                    .executes(ReincarnateCommand::queryUnlimitedAmmo)
                    .then(Commands.literal("on").executes(ctx -> setUnlimitedAmmo(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setUnlimitedAmmo(ctx, false))))
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
                            .executes(ReincarnateCommand::setMobOn))
                        .then(Commands.literal("off")
                            .executes(ctx -> setMobScale(ctx, 0.0F)))
                        .then(Commands.argument("chance", FloatArgumentType.floatArg(0.0F, 1.0F))
                            .executes(ctx -> setMobScale(ctx, FloatArgumentType.getFloat(ctx, "chance")))))
                    .then(Commands.literal("group")
                        .then(Commands.argument("group", StringArgumentType.word())
                            .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(GROUP_NAMES, builder))
                            .then(Commands.literal("on").executes(ctx -> setGroup(ctx, null)))
                            .then(Commands.literal("off").executes(ctx -> setGroup(ctx, 0.0F)))
                            .then(Commands.argument("chance", FloatArgumentType.floatArg(0.0F, 1.0F))
                                .executes(ctx -> setGroup(ctx, FloatArgumentType.getFloat(ctx, "chance"))))))));
    }

    /** Lower-case group names for {@code /playermob naturalspawn group <group>} tab-completion. */
    private static final java.util.List<String> GROUP_NAMES = groupNames();

    private static java.util.List<String> groupNames() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (PlayerMobConfig.SpawnGroup g : PlayerMobConfig.SpawnGroup.values()) {
            names.add(g.name().toLowerCase(java.util.Locale.ROOT));
        }
        return java.util.List.copyOf(names);
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

    /** {@code /playermob unlimitedammo} — report whether global unlimited ammo is on (the inverse of requireArrows). */
    private static int queryUnlimitedAmmo(CommandContext<CommandSourceStack> ctx) {
        boolean unlimited = !PlayerMobConfig.requireArrows();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob unlimited ammo is "
            + (unlimited ? "ON — ranged weapons never run out"
                         : "OFF — ranged weapons consume inventory ammo") + "."), false);
        return 1;
    }

    /** {@code /playermob unlimitedammo on|off} — flip global unlimited ammo for this session. */
    private static int setUnlimitedAmmo(CommandContext<CommandSourceStack> ctx, boolean unlimited) {
        PlayerMobConfig.setRequireArrows(!unlimited);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob unlimited ammo "
            + (unlimited ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** {@code /playermob naturalspawn} — report the master switch + the group defaults. */
    private static int reportNaturalSpawn(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.naturalSpawnEnabled();
        StringBuilder groups = new StringBuilder();
        for (PlayerMobConfig.SpawnGroup g : PlayerMobConfig.SpawnGroup.values()) {
            if (groups.length() > 0) {
                groups.append(", ");
            }
            groups.append(g.name().toLowerCase(java.util.Locale.ROOT)).append(' ').append(g.defaultScale);
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob natural spawning is " + (on ? "ON" : "OFF") + ". Group defaults: " + groups + "."), false);
        return 1;
    }

    /** {@code /playermob naturalspawn on|off} — flip the natural-spawn master switch for this session. */
    private static int setNaturalSpawnMaster(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setNaturalSpawnEnabled(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob natural spawning " + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** Resolve the {@code "mob"} resource-location argument to its id string (26.x renames the arg type). */
    private static String mobArg(CommandContext<CommandSourceStack> ctx) {
        //? if >=26 {
        /*return net.minecraft.commands.arguments.IdentifierArgument.getId(ctx, "mob").toString();*///?} else {
        return ResourceLocationArgument.getId(ctx, "mob").toString();
        //?}
    }

    /** {@code /playermob naturalspawn <mob> on} — set one mob to its group default for this session. */
    private static int setMobOn(CommandContext<CommandSourceStack> ctx) {
        String mob = mobArg(ctx);
        PlayerMobConfig.SpawnGroup group = PlayerMobConfig.groupOf(mob);
        return setMobScale(ctx, group == null ? 0.0F : group.defaultScale);
    }

    /**
     * {@code /playermob naturalspawn <mob> off|<chance>} — set one mob's companion chance for this session.
     * Notes when the master switch is off (so the change won't take effect until it's turned on).
     */
    private static int setMobScale(CommandContext<CommandSourceStack> ctx, float chance) {
        String mob = mobArg(ctx);
        PlayerMobConfig.setNaturalSpawnScale(mob, chance);
        String suffix = PlayerMobConfig.naturalSpawnEnabled()
            ? "" : " (natural spawning is OFF — /playermob naturalspawn on to apply)";
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob companion chance for " + mob + " set to " + chance + suffix + "."), false);
        return 1;
    }

    /**
     * {@code /playermob naturalspawn group <group> on|off|<chance>} — set every mob in a group for this
     * session. {@code chance == null} means "on" → the group's default. Fails on an unknown group name.
     */
    private static int setGroup(CommandContext<CommandSourceStack> ctx, Float chance) {
        String name = StringArgumentType.getString(ctx, "group");
        PlayerMobConfig.SpawnGroup group = PlayerMobConfig.SpawnGroup.byName(name);
        if (group == null) {
            ctx.getSource().sendFailure(Component.literal(
                "Unknown group '" + name + "'. Valid: " + String.join(", ", GROUP_NAMES) + "."));
            return 0;
        }
        float applied = chance != null ? chance : group.defaultScale;
        PlayerMobConfig.setGroupScale(group, applied);
        String suffix = PlayerMobConfig.naturalSpawnEnabled()
            ? "" : " (natural spawning is OFF — /playermob naturalspawn on to apply)";
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob companion chance for group " + name.toLowerCase(java.util.Locale.ROOT)
                + " (" + group.mobs.size() + " mobs) set to " + applied + suffix + "."), false);
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

    /**
     * {@code /playermob summon <displayName> [<pos>] [<friendliness>] [<fightFlight>]} — spawn a
     * PlayerMob wearing the named player's skin. The mob appears immediately (with a rolled skin)
     * at {@code pos} (or the command source); the named player's real skin is resolved off-thread
     * and applied a moment later, falling back to the rolled skin if the name can't be resolved
     * (unknown name / offline server). Traits are pinned when supplied, else rolled.
     */
    private static int summon(CommandContext<CommandSourceStack> ctx, Vec3 pos,
                              Integer friendliness, Integer fightFlight) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "displayName");
        ServerLevel level = source.getLevel();
        Vec3 at = pos != null ? pos : source.getPosition();
        float yRot = source.getRotation().y;
        PlayerMobEntity mob = PlayerMobSummon.summon(level, at.x, at.y, at.z, yRot, fightFlight, friendliness);
        if (mob == null) {
            source.sendFailure(Component.literal("Could not create a PlayerMob."));
            return 0;
        }
        // Resolve the named player's skin off-thread; apply on the server thread when it lands.
        MinecraftServer server = source.getServer();
        PlayerSkinResolver.resolveAsync(server, name, opt -> {
            if (mob.isRemoved()) {
                return;
            }
            opt.ifPresent(resolved -> {
                mob.setSkinTextureUrl(resolved.url());
                mob.setSkinSlim(resolved.slim());
            });
        });
        source.sendSuccess(() -> Component.literal("Summoned a PlayerMob for " + name
            + " — resolving skin…"), true);
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
