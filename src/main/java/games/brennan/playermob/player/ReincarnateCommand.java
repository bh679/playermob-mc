package games.brennan.playermob.player;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import games.brennan.playermob.PlayerMobConfig;
import games.brennan.playermob.entity.AutoNameMode;
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.PlayerMobSummon;
import games.brennan.playermob.entity.StayAnchor;
import games.brennan.playermob.entity.StayNearPolicy;
import games.brennan.playermob.entity.goal.AttackOrder;
import games.brennan.playermob.entity.goal.Order;
import games.brennan.playermob.entity.goal.OrderType;
import games.brennan.playermob.skin.LocalSkinFolder;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.SkinNameApplier;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.CompoundTagArgument;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;
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
 *   <li>{@code /playermob summon <displayName> [<pos>] [<friendliness>] [<fightFlight>] [named [<customName>]]}
 *       — spawn a PlayerMob wearing that skin, at an optional position (default: the command source), with
 *       optional locked traits (default: random). {@code displayName} is a local-folder skin name (a PNG in
 *       {@code config/playermob/skins}) if one matches, otherwise a player name whose skin is resolved.
 *       Append {@code named} to also give the mob a nameplate — {@code customName} if supplied, otherwise
 *       {@code displayName}.</li>
 *   <li>{@code /playermob stay <name> ( off | here | <pos> | <target> ) [<radius>]} — tether a
 *       PlayerMob near an anchor (a fixed position, or a live player / named PlayerMob) so it won't
 *       wander more than {@code radius} blocks (default 32) away; {@code off} releases it. The anchor
 *       persists in the mob's NBT (also authorable via {@code /summon …{PlayerMobData:{StayNear:{…}}}}).</li>
 *   <li>{@code /playermob debug spawnlog [on|off]} — toggle (or report) the colour-coded
 *       Dungeon-Train auto-spawn chat log for this session.</li>
 *   <li>{@code /playermob exactnames [on|off]} — toggle (or report) exact-name command matching for
 *       this session: when {@code on}, a subcommand whose {@code <name>} matches no loaded PlayerMob is
 *       cancelled instead of falling back to the nearest one. A session override of the config flag.</li>
 *   <li>{@code /playermob unlimitedammo [on|off]} — toggle (or report) global unlimited ammo for
 *       this session: {@code on} = ranged weapons never run out; {@code off} = they consume inventory
 *       ammo (the {@code requireArrows} default). A session override of the config flag.</li>
 *   <li>{@code /playermob tntcombat [on|off]} — toggle (or report) TNT-bombing combat for this
 *       session: when {@code on}, a PlayerMob carrying TNT + a way to light it bombs its target
 *       instead of fighting with bow/melee. A session override of the config flag.</li>
 *   <li>{@code /playermob endcrystalcombat [on|off]} — toggle (or report) end-crystal bombing combat for
 *       this session: when {@code on}, a PlayerMob carrying end crystals + obsidian + solid cover blocks
 *       bombs its target with end crystals instead of fighting with bow/melee. A session override of the
 *       config flag.</li>
 *   <li>{@code /playermob huntforfood [on|off]} — toggle (or report) animal-hunting for this session:
 *       when {@code on}, a hungry PlayerMob hunts a nearby adult food animal (cow/pig/chicken/sheep/rabbit)
 *       for meat; {@code off} leaves animals alone entirely. A session override of the config flag.</li>
 *   <li>{@code /playermob naturalspawn [on|off]} — toggle (or report) the natural-spawn master
 *       switch for this session.</li>
 *   <li>{@code /playermob naturalspawn <mob> on|off|<chance>} — set a mob's companion chance
 *       (the chance a PlayerMob spawns <em>beside</em> it on a natural spawn) for this session.</li>
 *   <li>{@code /playermob naturalspawn group <group> on|off|<chance>} — set every mob in a group
 *       (hostile / nether / animals / friendly / water / villager) for this session.</li>
 *   <li>{@code /playermob skin sources} — report which skin sources (bundled / online / local) a random
 *       PlayerMob may draw from, with counts.</li>
 *   <li>{@code /playermob skin source <bundled|online|local> on|off} — toggle one skin source for this
 *       session.</li>
 *   <li>{@code /playermob skin chance <0.0-1.0>} — set the chance a spawn wears a custom (online/local)
 *       skin instead of a bundled default, for this session.</li>
 * </ul>
 *
 * <p>Like {@code spawnlog}, the {@code naturalspawn} edits are session overrides — they take effect
 * immediately without a restart but are not written back to {@code config/playermob.properties}.</p>
 */
public final class ReincarnateCommand {

    private ReincarnateCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext buildContext) {
        dispatcher.register(
            Commands.literal("playermob")
                //? if >=26 {
                /*// 26.x replaced the int permission level with PermissionCheck constants;
                // LEVEL_GAMEMASTERS is the old op-level 2.
                .requires(Commands.hasPermission(Commands.LEVEL_GAMEMASTERS))
                *///?} else {
                .requires(source -> source.hasPermission(2))
                //?}
                .then(orderTree(buildContext))
                .then(stayTree())
                .then(Commands.literal("reincarnate")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::reincarnate)))
                .then(Commands.literal("life")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::life)))
                .then(Commands.literal("summon")
                    .then(Commands.argument("displayName", StringArgumentType.word())
                        // Suggest local-folder skin names (you can also type any player name).
                        .suggests(LOCAL_SKIN_SUGGESTIONS)
                        .executes(ctx -> summon(ctx, false))
                        // `named [<customName>]`, `stay …`, and `{NBT}` are mutually-exclusive tails
                        // attached at every level so any of them can follow any arg combo. `named`/`stay`
                        // are literals (bind before the vec3/int args) and `{NBT}` starts with `{`, so
                        // none clashes with `pos`/the trait numbers.
                        .then(namedFlag())
                        .then(summonStaySubtree())
                        .then(summonNbtArg())
                        // Position chains before the traits (and a number alone would be ambiguous with
                        // a vec3 coord), so use `~ ~ ~` to set traits "here" — mirrors /summon's [pos].
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                            .executes(ctx -> summon(ctx, false))
                            .then(namedFlag())
                            .then(summonStaySubtree())
                            .then(summonNbtArg())
                            .then(Commands.argument("friendliness", IntegerArgumentType.integer(0, 10))
                                .executes(ctx -> summon(ctx, false))
                                .then(namedFlag())
                                .then(summonStaySubtree())
                                .then(summonNbtArg())
                                .then(Commands.argument("fightFlight", IntegerArgumentType.integer(0, 10))
                                    .executes(ctx -> summon(ctx, false))
                                    .then(namedFlag())
                                    .then(summonStaySubtree())
                                    .then(summonNbtArg()))))))
                .then(Commands.literal("debug")
                    .then(Commands.literal("spawnlog")
                        .executes(ReincarnateCommand::querySpawnLog)
                        .then(Commands.literal("on").executes(ctx -> setSpawnLog(ctx, true)))
                        .then(Commands.literal("off").executes(ctx -> setSpawnLog(ctx, false)))))
                .then(Commands.literal("exactnames")
                    .executes(ReincarnateCommand::queryExactNames)
                    .then(Commands.literal("on").executes(ctx -> setExactNames(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setExactNames(ctx, false))))
                .then(Commands.literal("unlimitedammo")
                    .executes(ReincarnateCommand::queryUnlimitedAmmo)
                    .then(Commands.literal("on").executes(ctx -> setUnlimitedAmmo(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setUnlimitedAmmo(ctx, false))))
                .then(Commands.literal("tntcombat")
                    .executes(ReincarnateCommand::queryTntCombat)
                    .then(Commands.literal("on").executes(ctx -> setTntCombat(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setTntCombat(ctx, false))))
                .then(Commands.literal("endcrystalcombat")
                    .executes(ReincarnateCommand::queryEndCrystalCombat)
                    .then(Commands.literal("on").executes(ctx -> setEndCrystalCombat(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setEndCrystalCombat(ctx, false))))
                .then(Commands.literal("extinguishwithbucket")
                    .executes(ReincarnateCommand::queryExtinguishWithBucket)
                    .then(Commands.literal("on").executes(ctx -> setExtinguishWithBucket(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setExtinguishWithBucket(ctx, false))))
                .then(Commands.literal("huntforfood")
                    .executes(ReincarnateCommand::queryHuntForFood)
                    .then(Commands.literal("on").executes(ctx -> setHuntForFood(ctx, true)))
                    .then(Commands.literal("off").executes(ctx -> setHuntForFood(ctx, false))))
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
                                .executes(ctx -> setGroup(ctx, FloatArgumentType.getFloat(ctx, "chance")))))))
                .then(Commands.literal("autoname")
                    .executes(ReincarnateCommand::queryAutoName)
                    .then(Commands.literal("off").executes(ctx -> setAutoName(ctx, AutoNameMode.OFF)))
                    .then(Commands.literal("natural").executes(ctx -> setAutoName(ctx, AutoNameMode.NATURAL)))
                    .then(Commands.literal("egg").executes(ctx -> setAutoName(ctx, AutoNameMode.EGG)))
                    .then(Commands.literal("all").executes(ctx -> setAutoName(ctx, AutoNameMode.ALL))))
                .then(skinTree()));
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

    // ---- /playermob skin ... --------------------------------------------------------------------

    /** Tab-completion for {@code /playermob skin spawn <file>}: the base names in config/playermob/skins. */
    private static final SuggestionProvider<CommandSourceStack> LOCAL_SKIN_SUGGESTIONS = (ctx, builder) ->
        SharedSuggestionProvider.suggest(LocalSkinFolder.list(), builder);

    /**
     * The {@code /playermob skin} subtree:
     * <ul>
     *   <li>{@code skin sources} — report which of the three skin sources (bundled / online / local)
     *       a random PlayerMob may draw from, with counts.</li>
     *   <li>{@code skin source <bundled|online|local> on|off} — toggle one source for this session.</li>
     *   <li>{@code skin chance <0.0-1.0>} — set the custom-skin override chance for this session.</li>
     * </ul>
     *
     * <p>To <em>spawn</em> a mob wearing a specific local skin, use {@code /playermob summon <file>}
     * (same command as a named-player summon — a local-folder name takes precedence over a player name).</p>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> skinTree() {
        return Commands.literal("skin")
            .then(Commands.literal("sources").executes(ReincarnateCommand::reportSkinSources))
            .then(Commands.literal("source")
                .then(skinSourceToggle("bundled"))
                .then(skinSourceToggle("online"))
                .then(skinSourceToggle("local")))
            .then(Commands.literal("chance")
                .then(Commands.argument("chance", FloatArgumentType.floatArg(0.0f, 1.0f))
                    .executes(ReincarnateCommand::setSkinChance)));
    }

    /** One {@code <source> on|off} branch for {@link #skinTree()} (built fresh per call site). */
    private static LiteralArgumentBuilder<CommandSourceStack> skinSourceToggle(String source) {
        return Commands.literal(source)
            .then(Commands.literal("on").executes(ctx -> setSkinSource(ctx, source, true)))
            .then(Commands.literal("off").executes(ctx -> setSkinSource(ctx, source, false)));
    }

    /** {@code /playermob skin sources} — report each source's on/off state and how many skins it has. */
    private static int reportSkinSources(CommandContext<CommandSourceStack> ctx) {
        boolean bundled = PlayerMobConfig.skinSourceBundled();
        boolean online = PlayerMobConfig.skinSourceOnline();
        boolean local = PlayerMobConfig.skinSourceLocal();
        int onlineCount = PlayerMobSkinRegistry.size();
        int localCount = LocalSkinFolder.list().size();
        float chance = PlayerMobConfig.customSkinChance();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob skin sources — bundled " + onOff(bundled)
                + ", online " + onOff(online) + " (" + onlineCount + ")"
                + ", local " + onOff(local) + " (" + localCount + " in config/playermob/skins)"
                + ", custom-skin chance " + String.format(java.util.Locale.ROOT, "%.2f", chance) + "."), false);
        return 1;
    }

    /** {@code /playermob skin source <source> on|off} — flip one skin source for this session. */
    private static int setSkinSource(CommandContext<CommandSourceStack> ctx, String source, boolean enabled) {
        switch (source) {
            case "bundled" -> PlayerMobConfig.setSkinSourceBundled(enabled);
            case "online" -> PlayerMobConfig.setSkinSourceOnline(enabled);
            case "local" -> PlayerMobConfig.setSkinSourceLocal(enabled);
            default -> {
                ctx.getSource().sendFailure(Component.literal("Unknown skin source '" + source + "'."));
                return 0;
            }
        }
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob skin source " + source + " " + (enabled ? "enabled" : "disabled")
                + " for this session."), false);
        return 1;
    }

    /** {@code /playermob skin chance <0.0-1.0>} — set the custom-skin override chance for this session. */
    private static int setSkinChance(CommandContext<CommandSourceStack> ctx) {
        float chance = FloatArgumentType.getFloat(ctx, "chance");
        PlayerMobConfig.setCustomSkinChance(chance);
        float applied = PlayerMobConfig.customSkinChance();
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob custom-skin chance set to "
                + String.format(java.util.Locale.ROOT, "%.2f", applied) + " for this session."), false);
        return 1;
    }

    /** "ON"/"OFF" label for source-state reporting. */
    private static String onOff(boolean on) {
        return on ? "ON" : "OFF";
    }

    // ---- /playermob order <name> <action> <target> ----------------------------------------------

    /** World-sized box so {@code getEntitiesOfClass} scans every loaded PlayerMob in the dimension. */
    private static final AABB EVERYWHERE = new AABB(-3.0E7, -2.0E7, -3.0E7, 3.0E7, 2.0E7, 3.0E7);

    /** Tab-completion for the {@code <name>} arg: the custom names of loaded PlayerMobs. */
    private static final SuggestionProvider<CommandSourceStack> MOB_NAME_SUGGESTIONS = (ctx, builder) -> {
        for (PlayerMobEntity m : ctx.getSource().getLevel().getEntitiesOfClass(PlayerMobEntity.class, EVERYWHERE)) {
            Component cn = m.getCustomName();
            if (cn != null) {
                builder.suggest(cn.getString());
            }
        }
        return builder.buildFuture();
    };

    /** Tab-completion for a {@code <target>} arg: online player names + loaded PlayerMob names. */
    private static final SuggestionProvider<CommandSourceStack> TARGET_SUGGESTIONS = (ctx, builder) -> {
        for (String n : ctx.getSource().getServer().getPlayerList().getPlayerNamesArray()) {
            builder.suggest(n);
        }
        for (PlayerMobEntity m : ctx.getSource().getLevel().getEntitiesOfClass(PlayerMobEntity.class, EVERYWHERE)) {
            Component cn = m.getCustomName();
            if (cn != null) {
                builder.suggest(cn.getString());
            }
        }
        return builder.buildFuture();
    };

    /**
     * Builds the {@code order} subtree: {@code /playermob order <name> <action> <target...>}.
     * {@code <name>} selects a PlayerMob by custom name (nearest one as a fallback, unless
     * {@code /playermob exactnames on} — then an unmatched name cancels the order); a
     * {@code <target>} resolves to an online player or a named PlayerMob; positions accept
     * {@code ~ ~ ~}. {@code place} conjures the given block from air.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> orderTree(
            CommandBuildContext buildContext) {
        return Commands.literal("order")
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests(MOB_NAME_SUGGESTIONS)
                .then(Commands.literal("walk")
                    .then(orderFlags(Commands.argument("pos", Vec3Argument.vec3()),
                        ReincarnateCommand::orderWalkPos))
                    .then(orderFlags(Commands.argument("target", StringArgumentType.word())
                        .suggests(TARGET_SUGGESTIONS), ReincarnateCommand::orderWalkEntity)))
                .then(entityAction("punch", OrderType.PUNCH))
                .then(entityAction("punchat", OrderType.PUNCH_AT))
                .then(attackSubtree(buildContext))
                .then(stealSubtree())
                .then(giftSubtree(buildContext))
                .then(entityAction("greet", OrderType.GREET))
                .then(useSubtree(buildContext))
                .then(Commands.literal("place")
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(orderFlags(Commands.argument("block", BlockStateArgument.block(buildContext)),
                            ReincarnateCommand::orderPlacePos)))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(TARGET_SUGGESTIONS)
                        .then(orderFlags(Commands.argument("block", BlockStateArgument.block(buildContext)),
                            ReincarnateCommand::orderPlaceEntity)))));
    }

    // ---- /playermob stay <name> ( off | here | <pos> | <target> ) [<radius>] --------------------

    /**
     * Builds the {@code stay} subtree: {@code /playermob stay <name> ( off | here | <pos> | <target> )
     * [<radius>]}. Tethers a PlayerMob so it won't wander more than {@code radius} blocks (default
     * {@value StayNearPolicy#DEFAULT_RADIUS}) from an anchor — {@code here}/{@code <pos>} pin a fixed
     * spot, {@code <target>} pins a live player or named PlayerMob, {@code off} releases the tether.
     * The {@code <pos>} (vec3) branch binds before the {@code <target>} (word) branch, exactly like
     * the {@code walk} subtree, so {@code ~ ~ ~} sets an anchor "here" and a bare word is a target.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> stayTree() {
        return Commands.literal("stay")
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests(MOB_NAME_SUGGESTIONS)
                .then(Commands.literal("off").executes(ReincarnateCommand::stayOff))
                .then(Commands.literal("here")
                    .executes(ReincarnateCommand::stayHere)
                    .then(radiusArg().executes(ReincarnateCommand::stayHere)))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                    .executes(ReincarnateCommand::stayPos)
                    .then(radiusArg().executes(ReincarnateCommand::stayPos)))
                .then(Commands.argument("target", StringArgumentType.word())
                    .suggests(TARGET_SUGGESTIONS)
                    .executes(ReincarnateCommand::stayEntity)
                    .then(radiusArg().executes(ReincarnateCommand::stayEntity))));
    }

    /** A fresh {@code radius} argument node bounded to the policy's settable range (consumed per use). */
    private static RequiredArgumentBuilder<CommandSourceStack, Integer> radiusArg() {
        return Commands.argument("radius",
            IntegerArgumentType.integer(StayNearPolicy.MIN_RADIUS, StayNearPolicy.MAX_RADIUS));
    }

    /** The supplied radius, or {@link StayNearPolicy#DEFAULT_RADIUS} when the optional arg is absent. */
    private static int stayRadius(CommandContext<CommandSourceStack> ctx) {
        return has(ctx, "radius")
            ? IntegerArgumentType.getInteger(ctx, "radius")
            : StayNearPolicy.DEFAULT_RADIUS;
    }

    /** {@code /playermob stay <name> off} — release the tether. */
    private static int stayOff(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        mob.clearStayAnchor();
        String who = label(mob);
        source.sendSuccess(() -> Component.literal(who + " will now roam freely."), true);
        return 1;
    }

    /** {@code /playermob stay <name> here [<radius>]} — tether to the mob's current position. */
    private static int stayHere(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        return anchorAt(source, mob, mob.blockPosition(), stayRadius(ctx));
    }

    /** {@code /playermob stay <name> <pos> [<radius>]} — tether to a fixed position. */
    private static int stayPos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(Vec3Argument.getVec3(ctx, "pos"));
        return anchorAt(source, mob, pos, stayRadius(ctx));
    }

    /** Set a position tether and report it. */
    private static int anchorAt(CommandSourceStack source, PlayerMobEntity mob, BlockPos pos, int radius) {
        mob.setStayAnchor(StayAnchor.ofPosition(pos, radius));
        String who = label(mob);
        source.sendSuccess(() -> Component.literal(who + " will stay within " + radius + " blocks of "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), true);
        return 1;
    }

    /** {@code /playermob stay <name> <target> [<radius>]} — tether to a live player or named PlayerMob. */
    private static int stayEntity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        int radius = stayRadius(ctx);
        mob.setStayAnchor(StayAnchor.ofEntity(target.getUUID(), radius));
        String who = label(mob);
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(
            who + " will stay within " + radius + " blocks of " + targetName + "."), true);
        return 1;
    }

    /** An entity-directed action literal (punch / gift / greet) taking a {@code <target>}. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> entityAction(
            String name, OrderType type) {
        return Commands.literal(name)
            .then(orderFlags(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS), (ctx, t, i) -> orderEntity(ctx, type, t, i)));
    }

    // ---- attack subtree: attack <target> [kill|forever|return|<n> s|<n> hearts] [with <weapon> [spawn]] ----

    /** Resolves the chosen attack plan (single strike vs a limited sustained attack) from a command ctx. */
    @FunctionalInterface
    private interface AttackPlanFn {
        AttackPlan resolve(CommandContext<CommandSourceStack> ctx);
    }

    /** A resolved attack: a single strike, or a sustained attack with a stop limit. */
    private record AttackPlan(boolean once, AttackOrder.Limit limit, int amount) {
        static AttackPlan single() {
            return new AttackPlan(true, AttackOrder.Limit.KILL, 0);
        }

        static AttackPlan sustained(AttackOrder.Limit limit, int amount) {
            return new AttackPlan(false, limit, amount);
        }
    }

    /**
     * Builds {@code attack <target> [kill|forever|return|<n> s|<n> hearts] [with <weapon> [spawn]]}.
     * The bare target and every limit terminal share the same {@code with <weapon> [spawn]} tail, so
     * the limit and the weapon compose (e.g. {@code attack Steve 10 s with diamond_sword spawn}).
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> attackSubtree(
            CommandBuildContext buildContext) {
        AttackPlanFn seconds = ctx ->
            AttackPlan.sustained(AttackOrder.Limit.SECONDS, IntegerArgumentType.getInteger(ctx, "amount"));
        AttackPlanFn hearts = ctx ->
            AttackPlan.sustained(AttackOrder.Limit.HEARTS, IntegerArgumentType.getInteger(ctx, "amount"));

        RequiredArgumentBuilder<CommandSourceStack, Integer> amount =
            Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000));
        amount.then(attackTail(Commands.literal("s"), buildContext, seconds));
        amount.then(attackTail(Commands.literal("seconds"), buildContext, seconds));
        amount.then(attackTail(Commands.literal("hearts"), buildContext, hearts));
        amount.then(attackTail(Commands.literal("heart"), buildContext, hearts));

        RequiredArgumentBuilder<CommandSourceStack, String> target = attackTail(
            Commands.argument("target", StringArgumentType.word()).suggests(TARGET_SUGGESTIONS),
            buildContext, ctx -> AttackPlan.single());
        target.then(attackTail(Commands.literal("kill"), buildContext, ctx -> AttackPlan.sustained(AttackOrder.Limit.KILL, 0)));
        target.then(attackTail(Commands.literal("forever"), buildContext, ctx -> AttackPlan.sustained(AttackOrder.Limit.KILL, 0)));
        target.then(attackTail(Commands.literal("return"), buildContext, ctx -> AttackPlan.sustained(AttackOrder.Limit.UNTIL_HIT, 0)));
        target.then(amount);

        return Commands.literal("attack").then(target);
    }

    /**
     * Attaches the shared tail to {@code node}: an {@code .executes} (no weapon) plus a
     * {@code with <weapon> [spawn]} branch. {@code plan} resolves the limit from the live ctx.
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T attackTail(
            T node, CommandBuildContext buildContext, AttackPlanFn plan) {
        node.executes(ctx -> orderAttack(ctx, plan.resolve(ctx), ItemStack.EMPTY, false));
        node.then(Commands.literal("with")
            .then(Commands.argument("weapon", ItemArgument.item(buildContext))
                .executes(ctx -> orderAttack(ctx, plan.resolve(ctx), itemStack(ctx, "weapon"), false))
                .then(Commands.literal("spawn")
                    .executes(ctx -> orderAttack(ctx, plan.resolve(ctx), itemStack(ctx, "weapon"), true)))));
        return node;
    }

    /** The named item argument as a single-item stack (empty if unresolvable). */
    private static ItemStack itemStack(CommandContext<CommandSourceStack> ctx, String argName) {
        try {
            //? if >=26 {
            /*// 26.x dropped the allow-overstack boolean from ItemInput.createItemStack.
            return ItemArgument.getItem(ctx, argName).createItemStack(1);
            *///?} else {
            return ItemArgument.getItem(ctx, argName).createItemStack(1, false);
            //?}
        } catch (CommandSyntaxException e) {
            return ItemStack.EMPTY;
        }
    }

    // ---- shared order flags: [for <n> (s|seconds) | forever] [nonstop] on every movement order ----

    /** Sentinel timeout meaning "no flag given — fall back to the mob's NBT default". */
    private static final int ORDER_TIMEOUT_UNSET = Integer.MIN_VALUE;

    /** A movement-order executor that also carries the resolved order flags (sentinels = use defaults). */
    @FunctionalInterface
    private interface OrderExec {
        int run(CommandContext<CommandSourceStack> ctx, int timeoutTicks, Boolean interruptible);
    }

    /**
     * Place {@code base} on {@code mob}, applying the flag-resolved lifetime / interruptibility — or the
     * mob's NBT defaults when a flag was omitted ({@code ORDER_TIMEOUT_UNSET} / {@code null}).
     * {@code timeoutTicks < 0} means "never time out" (the {@code forever} flag).
     */
    private static void placeOrder(PlayerMobEntity mob, Order base, int timeoutTicks, Boolean interruptible) {
        int t = timeoutTicks == ORDER_TIMEOUT_UNSET ? mob.getOrderTimeoutDefaultTicks() : timeoutTicks;
        boolean i = interruptible == null ? mob.isOrderInterruptibleDefault() : interruptible;
        mob.setOrder(base.withSettings(t, i));
    }

    /**
     * Attach the optional trailing order flags to a terminal {@code node}, all routing back to
     * {@code exec}: {@code for <n> (s|seconds)} sets the timeout, {@code forever} disables it, and
     * {@code nonstop} makes the order non-interruptible. Composable (e.g. {@code … for 30 s nonstop}).
     * Mirrors the {@link #attackTail} pattern — the executor lambdas carry the settings explicitly, so
     * no read-back of matched literals is needed.
     */
    private static <T extends ArgumentBuilder<CommandSourceStack, T>> T orderFlags(T node, OrderExec exec) {
        node.executes(ctx -> exec.run(ctx, ORDER_TIMEOUT_UNSET, null));
        node.then(Commands.literal("nonstop").executes(ctx -> exec.run(ctx, ORDER_TIMEOUT_UNSET, false)));
        RequiredArgumentBuilder<CommandSourceStack, Integer> secs =
            Commands.argument("timeoutSecs", IntegerArgumentType.integer(1, 1_000_000));
        for (String unit : new String[]{"s", "seconds"}) {
            secs.then(Commands.literal(unit)
                .executes(ctx -> exec.run(ctx, IntegerArgumentType.getInteger(ctx, "timeoutSecs") * 20, null))
                .then(Commands.literal("nonstop")
                    .executes(ctx -> exec.run(ctx, IntegerArgumentType.getInteger(ctx, "timeoutSecs") * 20, false))));
        }
        node.then(Commands.literal("for").then(secs));
        node.then(Commands.literal("forever")
            .executes(ctx -> exec.run(ctx, -1, null))
            .then(Commands.literal("nonstop").executes(ctx -> exec.run(ctx, -1, false))));
        return node;
    }

    /** Execute an attack order: resolve mob + target, handle the optional weapon, then strike or sustain. */
    private static int orderAttack(CommandContext<CommandSourceStack> ctx, AttackPlan plan,
                                   ItemStack weapon, boolean spawn) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        String who = label(mob);
        String weaponNote = "";
        if (weapon != null && !weapon.isEmpty()) {
            weaponNote = " with " + weapon.getHoverName().getString();
            if (spawn) {
                mob.spawnWeapon(weapon.copy());
            } else if (!mob.equipWeapon(weapon.getItem())) {
                source.sendFailure(Component.literal(who + " doesn't have "
                    + weapon.getHoverName().getString() + " — add 'spawn' to give it one."));
                return 0;
            }
        }
        String targetName = target.getName().getString();
        String note = weaponNote;
        if (plan.once()) {
            // A single strike with the (now-)equipped weapon — reuse the punch goal.
            mob.setOrder(Order.toward(OrderType.PUNCH, target));
            source.sendSuccess(() -> Component.literal(who + " strikes " + targetName + note + "."), true);
            return 1;
        }
        mob.orderAttack(target, plan.limit(), plan.amount());
        String limitText = switch (plan.limit()) {
            case KILL -> "until dead";
            case SECONDS -> "for " + plan.amount() + "s";
            case HEARTS -> "until " + plan.amount() + " hearts";
            case UNTIL_HIT -> "until hit back";
        };
        source.sendSuccess(() -> Component.literal(
            who + " attacks " + targetName + note + " " + limitText + "."), true);
        return 1;
    }

    /**
     * Resolve the {@code <name>} arg to a PlayerMob: an exact (case-insensitive) custom-name match
     * among loaded PlayerMobs, else the nearest PlayerMob to the command source. Sends a failure and
     * returns {@code null} when the dimension holds no PlayerMob at all — and, when
     * {@link PlayerMobConfig#exactNames()} is on, whenever no name matches (the nearest-mob fallback
     * is skipped so a stale or misspelled name cancels the command instead of hitting the wrong mob).
     */
    private static PlayerMobEntity resolveMob(CommandSourceStack source, String name) {
        List<PlayerMobEntity> mobs = source.getLevel().getEntitiesOfClass(PlayerMobEntity.class, EVERYWHERE);
        if (mobs.isEmpty()) {
            source.sendFailure(Component.literal("No PlayerMob is loaded in this dimension."));
            return null;
        }
        for (PlayerMobEntity m : mobs) {
            Component cn = m.getCustomName();
            if (cn != null && cn.getString().equalsIgnoreCase(name)) {
                return m;
            }
        }
        if (PlayerMobConfig.exactNames()) {
            source.sendFailure(Component.literal(
                "No PlayerMob named '" + name + "' is loaded (exact names is on)."));
            return null;
        }
        Vec3 origin = source.getPosition();
        PlayerMobEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (PlayerMobEntity m : mobs) {
            double d = m.distanceToSqr(origin);
            if (d < best) {
                best = d;
                nearest = m;
            }
        }
        return nearest;
    }

    /**
     * Resolve a {@code <target>} arg to an entity: an online player by name, else a PlayerMob by
     * (case-insensitive) custom name. Sends a failure and returns {@code null} if neither matches.
     */
    private static LivingEntity resolveTarget(CommandSourceStack source, PlayerMobEntity mob, String name) {
        ServerPlayer player = source.getServer().getPlayerList().getPlayerByName(name);
        if (player != null) {
            return player;
        }
        for (PlayerMobEntity m : source.getLevel().getEntitiesOfClass(PlayerMobEntity.class, EVERYWHERE)) {
            Component cn = m.getCustomName();
            if (cn != null && cn.getString().equalsIgnoreCase(name)) {
                return m;
            }
        }
        LivingEntity nearestOfType = nearestOfType(mob, name);
        if (nearestOfType != null) {
            return nearestOfType;
        }
        source.sendFailure(Component.literal(
            "No player, named PlayerMob, or nearby mob of type '" + name + "' found."));
        return null;
    }

    /** Radius (blocks) searched around the ordered mob for the nearest entity of a given type. */
    private static final double TYPE_SEARCH_RADIUS = 128.0;

    /**
     * The nearest living entity to {@code mob} whose entity-type id matches {@code name} — a bare
     * path like {@code sheep} (namespace assumed {@code minecraft}) or a full {@code ns:path}.
     * Matched case-insensitively; the ordered mob itself is excluded. {@code null} if none nearby.
     */
    private static LivingEntity nearestOfType(PlayerMobEntity mob, String name) {
        String wanted = name.toLowerCase(java.util.Locale.ROOT);
        AABB box = mob.getBoundingBox().inflate(TYPE_SEARCH_RADIUS);
        LivingEntity nearest = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity e : mob.level().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != mob && e.isAlive())) {
            String key = net.minecraft.world.entity.EntityType.getKey(e.getType()).toString();
            if (key.equals(wanted) || key.endsWith(":" + wanted)) {
                double d = e.distanceToSqr(mob.position());
                if (d < best) {
                    best = d;
                    nearest = e;
                }
            }
        }
        return nearest;
    }

    /** A short label for command feedback — the mob's custom name, or "PlayerMob" if unnamed. */
    private static String label(PlayerMobEntity mob) {
        Component cn = mob.getCustomName();
        return cn != null ? cn.getString() : "PlayerMob";
    }

    /** {@code /playermob order <name> (punch|gift|greet) <target>} (attack has its own subtree). */
    private static int orderEntity(CommandContext<CommandSourceStack> ctx, OrderType type,
                                   int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        String who = label(mob);
        String targetName = target.getName().getString();
        placeOrder(mob, Order.toward(type, target), timeoutTicks, interruptible);
        source.sendSuccess(() -> Component.literal(who + " ordered to " + verb(type) + " " + targetName + "."), true);
        return 1;
    }

    /**
     * Builds {@code steal <target> [<n> s | <n> blocks]} — grab the target's held item, then flee
     * (for {@code n} seconds / blocks, or a short default when no limit is given).
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> stealSubtree() {
        RequiredArgumentBuilder<CommandSourceStack, Integer> amount =
            Commands.argument("amount", IntegerArgumentType.integer(1, 1_000_000));
        amount.then(orderFlags(Commands.literal("s"), (ctx, t, i) -> orderSteal(ctx, Order.FleeUnit.SECONDS, t, i)));
        amount.then(orderFlags(Commands.literal("seconds"), (ctx, t, i) -> orderSteal(ctx, Order.FleeUnit.SECONDS, t, i)));
        amount.then(orderFlags(Commands.literal("b"), (ctx, t, i) -> orderSteal(ctx, Order.FleeUnit.BLOCKS, t, i)));
        amount.then(orderFlags(Commands.literal("blocks"), (ctx, t, i) -> orderSteal(ctx, Order.FleeUnit.BLOCKS, t, i)));
        RequiredArgumentBuilder<CommandSourceStack, String> target =
            Commands.argument("target", StringArgumentType.word()).suggests(TARGET_SUGGESTIONS);
        orderFlags(target, (ctx, t, i) -> orderSteal(ctx, Order.FleeUnit.NONE, t, i));
        target.then(amount);
        return Commands.literal("steal").then(target);
    }

    /**
     * Builds {@code use ( <pos> | <target> ) <item> [spawn]} — walk up and right-click the item at a
     * position or on a target entity (via the fake player). Without {@code spawn} the mob must hold
     * or carry the item; {@code spawn} conjures it into the mob's hand first.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> useSubtree(
            CommandBuildContext buildContext) {
        return Commands.literal("use")
            .then(Commands.argument("pos", Vec3Argument.vec3())
                .then(orderFlags(Commands.argument("item", ItemArgument.item(buildContext)),
                        (ctx, t, i) -> orderUsePos(ctx, false, t, i))
                    .then(orderFlags(Commands.literal("spawn"), (ctx, t, i) -> orderUsePos(ctx, true, t, i)))))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .then(orderFlags(Commands.argument("item", ItemArgument.item(buildContext)),
                        (ctx, t, i) -> orderUseEntity(ctx, false, t, i))
                    .then(orderFlags(Commands.literal("spawn"), (ctx, t, i) -> orderUseEntity(ctx, true, t, i)))));
    }

    /**
     * Give the mob the item (conjure it on {@code spawn}, else require it carry one) before a USE.
     * Returns {@code false} and reports failure when it's needed but absent.
     */
    private static boolean provisionItem(CommandSourceStack source, PlayerMobEntity mob, ItemStack item, boolean spawn) {
        if (spawn) {
            mob.spawnWeapon(item.copy());
            return true;
        }
        if (!mob.equipWeapon(item.getItem())) {
            source.sendFailure(Component.literal(label(mob) + " doesn't have "
                + item.getHoverName().getString() + " — add 'spawn' to give it one."));
            return false;
        }
        return true;
    }

    /** {@code /playermob order <name> use <pos> <item> [spawn]}. */
    private static int orderUsePos(CommandContext<CommandSourceStack> ctx, boolean spawn,
                                   int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(Vec3Argument.getVec3(ctx, "pos"));
        ItemStack item = itemStack(ctx, "item");
        if (!provisionItem(source, mob, item, spawn)) {
            return 0;
        }
        placeOrder(mob, Order.use(pos, item), timeoutTicks, interruptible);
        String who = label(mob);
        String itemName = item.getHoverName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to use " + itemName + " at "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> use <target> <item> [spawn]}. */
    private static int orderUseEntity(CommandContext<CommandSourceStack> ctx, boolean spawn,
                                      int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        ItemStack item = itemStack(ctx, "item");
        if (!provisionItem(source, mob, item, spawn)) {
            return 0;
        }
        placeOrder(mob, Order.use(target, item), timeoutTicks, interruptible);
        String who = label(mob);
        String itemName = item.getHoverName().getString();
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to use " + itemName + " on " + targetName + "."), true);
        return 1;
    }

    /** Builds {@code gift <target> [<item>]} — toss a chosen (conjured) item, or auto-pick from the pack. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> giftSubtree(
            CommandBuildContext buildContext) {
        return Commands.literal("gift")
            .then(orderFlags(Commands.argument("target", StringArgumentType.word())
                    .suggests(TARGET_SUGGESTIONS), (ctx, t, i) -> orderGift(ctx, false, t, i))
                .then(orderFlags(Commands.argument("item", ItemArgument.item(buildContext)),
                    (ctx, t, i) -> orderGift(ctx, true, t, i))));
    }

    /** {@code /playermob order <name> gift <target> [<item>]}. */
    private static int orderGift(CommandContext<CommandSourceStack> ctx, boolean withItem,
                                 int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        ItemStack item = withItem ? itemStack(ctx, "item") : ItemStack.EMPTY;
        placeOrder(mob, item.isEmpty() ? Order.toward(OrderType.GIFT, target) : Order.gift(target, item),
            timeoutTicks, interruptible);
        String who = label(mob);
        String targetName = target.getName().getString();
        String what = item.isEmpty() ? "a gift" : item.getHoverName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to gift " + what + " to " + targetName + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> steal <target> [<n> s|blocks]}. */
    private static int orderSteal(CommandContext<CommandSourceStack> ctx, Order.FleeUnit unit,
                                  int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        int amount = unit == Order.FleeUnit.NONE ? 0 : IntegerArgumentType.getInteger(ctx, "amount");
        placeOrder(mob, Order.steal(target, amount, unit), timeoutTicks, interruptible);
        String who = label(mob);
        String targetName = target.getName().getString();
        String fleeNote = switch (unit) {
            case SECONDS -> ", fleeing for " + amount + "s";
            case BLOCKS -> ", fleeing for " + amount + " blocks";
            case NONE -> "";
        };
        source.sendSuccess(() -> Component.literal(who + " steals from " + targetName + fleeNote + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> walk <pos>}. */
    private static int orderWalkPos(CommandContext<CommandSourceStack> ctx, int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        placeOrder(mob, Order.walkTo(BlockPos.containing(pos)), timeoutTicks, interruptible);
        String who = label(mob);
        source.sendSuccess(() -> Component.literal(who + " ordered to walk to "
            + (int) pos.x + " " + (int) pos.y + " " + (int) pos.z + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> walk <target>}. */
    private static int orderWalkEntity(CommandContext<CommandSourceStack> ctx, int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        placeOrder(mob, Order.walkTo(target), timeoutTicks, interruptible);
        String who = label(mob);
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to walk to " + targetName + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> place <pos> <block>}. */
    private static int orderPlacePos(CommandContext<CommandSourceStack> ctx, int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(Vec3Argument.getVec3(ctx, "pos"));
        BlockState state = BlockStateArgument.getBlock(ctx, "block").getState();
        return issuePlace(source, mob, pos, state, timeoutTicks, interruptible);
    }

    /** {@code /playermob order <name> place <target> <block>} — chase the live target, place at its feet. */
    private static int orderPlaceEntity(CommandContext<CommandSourceStack> ctx, int timeoutTicks, Boolean interruptible) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        BlockState state = BlockStateArgument.getBlock(ctx, "block").getState();
        placeOrder(mob, Order.placeAt(target, state), timeoutTicks, interruptible);
        String who = label(mob);
        String block = state.getBlock().getName().getString();
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to place " + block + " by " + targetName + "."), true);
        return 1;
    }

    private static int issuePlace(CommandSourceStack source, PlayerMobEntity mob, BlockPos pos, BlockState state,
                                  int timeoutTicks, Boolean interruptible) {
        placeOrder(mob, Order.place(pos, state), timeoutTicks, interruptible);
        String who = label(mob);
        String block = state.getBlock().getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to place " + block + " at "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), true);
        return 1;
    }

    /** Human-readable verb for order feedback. */
    private static String verb(OrderType type) {
        return switch (type) {
            case WALK -> "walk to";
            case PUNCH -> "punch";
            case PUNCH_AT -> "feint at";
            case ATTACK -> "attack";
            case GIFT -> "gift";
            case GREET -> "greet";
            case STEAL -> "steal from";
            case USE -> "use an item on";
            case PLACE -> "place a block at";
        };
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

    /** {@code /playermob exactnames} — report whether a non-matching {@code <name>} cancels the command. */
    private static int queryExactNames(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.exactNames();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob exact names is "
            + (on ? "ON — a <name> that matches no loaded PlayerMob cancels the command"
                  : "OFF — a <name> that matches nothing falls back to the nearest PlayerMob") + "."), false);
        return 1;
    }

    /** {@code /playermob exactnames on|off} — flip exact-name command matching for this session. */
    private static int setExactNames(CommandContext<CommandSourceStack> ctx, boolean exact) {
        PlayerMobConfig.setExactNames(exact);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob exact names "
            + (exact ? "enabled — an unmatched <name> now cancels the command"
                     : "disabled — an unmatched <name> falls back to the nearest PlayerMob")
            + " for this session."), false);
        return 1;
    }

    /** {@code /playermob tntcombat} — report whether PlayerMobs bomb their targets with TNT. */
    private static int queryTntCombat(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.tntCombat();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob TNT combat is "
            + (on ? "ON — a mob with TNT + a way to light it bombs its target"
                  : "OFF — mobs fight with bow/melee only") + "."), false);
        return 1;
    }

    /** {@code /playermob tntcombat on|off} — flip TNT-bombing combat for this session. */
    private static int setTntCombat(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setTntCombat(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob TNT combat "
            + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** {@code /playermob endcrystalcombat} — report whether PlayerMobs bomb their targets with end crystals. */
    private static int queryEndCrystalCombat(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.endCrystalCombat();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob end-crystal combat is "
            + (on ? "ON — a mob with end crystals + obsidian + cover blocks bombs its target"
                  : "OFF — mobs fight with bow/melee only") + "."), false);
        return 1;
    }

    /** {@code /playermob endcrystalcombat on|off} — flip end-crystal bombing combat for this session. */
    private static int setEndCrystalCombat(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setEndCrystalCombat(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob end-crystal combat "
            + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** {@code /playermob extinguishwithbucket} — report whether PlayerMobs douse themselves with a held water bucket. */
    private static int queryExtinguishWithBucket(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.extinguishWithBucket();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob bucket self-extinguish is "
            + (on ? "ON — a burning mob holding a water bucket empties it and jumps in"
                  : "OFF — mobs just burn") + "."), false);
        return 1;
    }

    /** {@code /playermob extinguishwithbucket on|off} — flip bucket self-extinguishing for this session. */
    private static int setExtinguishWithBucket(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setExtinguishWithBucket(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob bucket self-extinguish "
            + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** {@code /playermob huntforfood} — report whether hungry PlayerMobs hunt nearby food animals. */
    private static int queryHuntForFood(CommandContext<CommandSourceStack> ctx) {
        boolean on = PlayerMobConfig.huntForFood();
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob animal-hunting is "
            + (on ? "ON — hungry mobs hunt nearby food animals for meat"
                  : "OFF — mobs leave animals alone") + "."), false);
        return 1;
    }

    /** {@code /playermob huntforfood on|off} — flip animal-hunting for this session. */
    private static int setHuntForFood(CommandContext<CommandSourceStack> ctx, boolean enabled) {
        PlayerMobConfig.setHuntForFood(enabled);
        ctx.getSource().sendSuccess(() -> Component.literal("PlayerMob animal-hunting "
            + (enabled ? "enabled" : "disabled") + " for this session."), false);
        return 1;
    }

    /** {@code /playermob autoname} — report which spawns are auto-named from their skin source. */
    private static int queryAutoName(CommandContext<CommandSourceStack> ctx) {
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob auto-name is " + autoNameDescription(PlayerMobConfig.autoNameMode()) + "."), false);
        return 1;
    }

    /** {@code /playermob autoname <off|natural|egg|all>} — set the skin-source auto-name mode for this session. */
    private static int setAutoName(CommandContext<CommandSourceStack> ctx, AutoNameMode mode) {
        PlayerMobConfig.setAutoNameMode(mode);
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob auto-name set to " + autoNameDescription(mode) + " for this session."), false);
        return 1;
    }

    /** Human-readable description of an auto-name mode for command feedback. */
    private static String autoNameDescription(AutoNameMode mode) {
        return switch (mode) {
            case OFF -> "OFF — PlayerMobs spawn unnamed";
            case NATURAL -> "NATURAL — wild / chunk-generation spawns wear their skin source";
            case EGG -> "EGG — spawn eggs, spawners, /summon and dispensers wear their skin source";
            case ALL -> "ALL — every spawn (except Dungeon-Train events) wears its skin source";
        };
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
            "%s — attacks %d, kills %d (%d in self-defence), damage %.1f (%.1f in self-defence), "
                + "kindness %.1f, harms %d, timidity %.1f "
                + "→ would reincarnate Fight/Flight %d, Friendliness %d%s",
            nameOf(profile), tally.attacks(), tally.kills(), tally.defensiveKills(),
            tally.damageDealt(), tally.defensiveDamage(), tally.kindness(), tally.harms(),
            tally.timidity(),
            traits.fightFlight(), traits.friendliness(),
            hasPast ? " (has a stored past life)" : "");
        source.sendSuccess(() -> Component.literal(summary), false);
        return 1;
    }

    /**
     * The {@code named [<customName>]} flag node for the {@code summon} subcommand, built fresh for
     * each call site (a Brigadier builder is consumed when passed to {@code .then(...)}). Appending
     * {@code named} sets the mob's nameplate; an optional trailing {@code customName} (greedy, so it
     * may contain spaces) overrides the name — otherwise it defaults to {@code displayName}.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> namedFlag() {
        return Commands.literal("named")
            .executes(ctx -> summon(ctx, true))
            .then(Commands.argument("customName", StringArgumentType.greedyString())
                .executes(ctx -> summon(ctx, true)));
    }

    /**
     * The {@code stay ( off | here | <anchorPos> | <anchorTarget> ) [<radius>]} tail on
     * {@code /playermob summon} — spawn the mob, then tether it exactly as {@code /playermob stay} would.
     * The anchor nodes are named {@code anchorPos}/{@code anchorTarget} (not {@code pos}/{@code target})
     * so they never collide with the summon chain's own {@code pos}. {@code off}/{@code here} literals
     * bind before the vec3, and the vec3 ({@code anchorPos}) binds before the word ({@code anchorTarget}),
     * mirroring {@link #stayTree()}.
     */
    private static LiteralArgumentBuilder<CommandSourceStack> summonStaySubtree() {
        return Commands.literal("stay")
            .then(Commands.literal("off").executes(ReincarnateCommand::summonStayOff))
            .then(Commands.literal("here")
                .executes(ReincarnateCommand::summonStayHere)
                .then(radiusArg().executes(ReincarnateCommand::summonStayHere)))
            .then(Commands.argument("anchorPos", Vec3Argument.vec3())
                .executes(ReincarnateCommand::summonStayPos)
                .then(radiusArg().executes(ReincarnateCommand::summonStayPos)))
            .then(Commands.argument("anchorTarget", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .executes(ReincarnateCommand::summonStayTarget)
                .then(radiusArg().executes(ReincarnateCommand::summonStayTarget)));
    }

    /**
     * The {@code {NBT}} tail on {@code /playermob summon} — spawn the mob, then merge the given compound
     * as PlayerMob custom data via {@link PlayerMobEntity#applyCustomData}. Same layout as vanilla
     * {@code /summon} entity_data <em>for the running MC version</em> (flat {@code StayNear:{…}} on
     * ≤1.21.1; {@code PlayerMobData:{StayNear:{…}}} on 26.x). Applies PlayerMob custom keys + inventory
     * only — not arbitrary vanilla entity fields (Health/Motion); use {@code /data merge entity} for those.
     */
    private static RequiredArgumentBuilder<CommandSourceStack, CompoundTag> summonNbtArg() {
        return Commands.argument("nbt", CompoundTagArgument.compoundTag())
            .executes(ReincarnateCommand::summonNbt);
    }

    /** Builds the anchor for a summon {@code stay} branch given the freshly-spawned mob, or {@code null}. */
    @FunctionalInterface
    private interface SummonAnchorFactory {
        StayAnchor build(PlayerMobEntity mob, CommandContext<CommandSourceStack> ctx);
    }

    private static int summonStayOff(CommandContext<CommandSourceStack> ctx) {
        return summonWithAnchor(ctx, (m, c) -> null);
    }

    private static int summonStayHere(CommandContext<CommandSourceStack> ctx) {
        return summonWithAnchor(ctx, (m, c) -> StayAnchor.ofPosition(m.blockPosition(), stayRadius(c)));
    }

    private static int summonStayPos(CommandContext<CommandSourceStack> ctx) {
        return summonWithAnchor(ctx, (m, c) ->
            StayAnchor.ofPosition(BlockPos.containing(Vec3Argument.getVec3(c, "anchorPos")), stayRadius(c)));
    }

    private static int summonStayTarget(CommandContext<CommandSourceStack> ctx) {
        return summonWithAnchor(ctx, (m, c) -> {
            LivingEntity target = resolveTarget(c.getSource(), m, StringArgumentType.getString(c, "anchorTarget"));
            return target == null ? null : StayAnchor.ofEntity(target.getUUID(), stayRadius(c));
        });
    }

    /**
     * Spawn the mob (untethered on failure), then set the anchor the factory builds (a {@code null}
     * factory result leaves it roaming — the {@code stay off} case, or a {@code <target>} that couldn't
     * be resolved). Reports the tether when one is set.
     */
    private static int summonWithAnchor(CommandContext<CommandSourceStack> ctx, SummonAnchorFactory factory) {
        PlayerMobEntity mob = createSummon(ctx, false, m -> {
            StayAnchor anchor = factory.build(m, ctx);
            if (anchor != null) {
                m.setStayAnchor(anchor);
            }
        });
        if (mob == null) {
            return 0;
        }
        StayAnchor set = mob.getStayAnchor();
        if (set != null && mob.level() instanceof ServerLevel level) {
            String who = label(mob);
            ctx.getSource().sendSuccess(() -> Component.literal(
                who + " will stay within " + set.radius() + " blocks of " + set.label(level) + "."), true);
        }
        return 1;
    }

    private static int summonNbt(CommandContext<CommandSourceStack> ctx) {
        CompoundTag tag = CompoundTagArgument.getCompoundTag(ctx, "nbt");
        return createSummon(ctx, false, mob -> mob.applyCustomData(tag)) != null ? 1 : 0;
    }

    /**
     * Whether the parsed command actually supplied the named (optional) argument. Brigadier's
     * {@code getArgument} throws when an argument node wasn't part of the matched path, so a thrown
     * {@link IllegalArgumentException} means "absent" (the per-version {@code getArguments()} map is
     * not consistently public).
     */
    private static boolean has(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            ctx.getArgument(name, Object.class);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * {@code /playermob summon <displayName> [<pos>] [<friendliness>] [<fightFlight>] [named [<customName>]]}
     * — spawn a PlayerMob wearing the named player's skin. The mob appears immediately (with a rolled
     * skin) at {@code pos} (or the command source); the named player's real skin is resolved off-thread
     * and applied a moment later, falling back to the rolled skin if the name can't be resolved
     * (unknown name / offline server). Traits are pinned when supplied, else rolled.
     *
     * <p>Optional arguments are read from the parsed context so a single handler serves every terminal
     * in the chain. When {@code nameMob} is set (the {@code named} flag was appended) the mob is given a
     * visible nameplate — {@code customName} if supplied, otherwise {@code displayName}.</p>
     */
    private static int summon(CommandContext<CommandSourceStack> ctx, boolean nameMob) {
        return createSummon(ctx, nameMob, null) != null ? 1 : 0;
    }

    /**
     * Spawn a PlayerMob and return it (or {@code null} after reporting the failure). Shared by the plain
     * {@code summon}, the {@code stay} tails, and the {@code {NBT}} tail. The optional {@code beforeSkin}
     * hook runs after the mob exists and has its nameplate but <em>before</em> the async skin resolution
     * kicks off — the right window to apply spawn NBT or a tether so an NBT-supplied skin still wins.
     */
    private static PlayerMobEntity createSummon(CommandContext<CommandSourceStack> ctx, boolean nameMob,
                                                java.util.function.Consumer<PlayerMobEntity> beforeSkin) {
        CommandSourceStack source = ctx.getSource();
        String name = StringArgumentType.getString(ctx, "displayName");
        Vec3 pos = has(ctx, "pos") ? Vec3Argument.getVec3(ctx, "pos") : null;
        Integer friendliness = has(ctx, "friendliness")
            ? IntegerArgumentType.getInteger(ctx, "friendliness") : null;
        Integer fightFlight = has(ctx, "fightFlight")
            ? IntegerArgumentType.getInteger(ctx, "fightFlight") : null;
        String customName = has(ctx, "customName")
            ? StringArgumentType.getString(ctx, "customName")
            : (nameMob ? name : null);

        ServerLevel level = source.getLevel();
        Vec3 at = pos != null ? pos : source.getPosition();
        float yRot = source.getRotation().y;
        PlayerMobEntity mob = PlayerMobSummon.summon(level, at.x, at.y, at.z, yRot, fightFlight, friendliness);
        if (mob == null) {
            source.sendFailure(Component.literal("Could not create a PlayerMob."));
            return null;
        }
        if (customName != null) {
            mob.setCustomName(Component.literal(customName));
            mob.setCustomNameVisible(true);
        }
        // Apply spawn NBT / tether now — after the nameplate, before the async skin apply below — so an
        // NBT-supplied skin key still wins the race with the name-resolved skin.
        if (beforeSkin != null) {
            beforeSkin.accept(mob);
        }
        // Auto-name (when the mode covers command spawns and no explicit `named` was given): label the mob
        // with its source <name|file> once the real skin has loaded — never off the temporary rolled skin
        // (PlayerMobSummon deferred finalizeSpawn's auto-name for exactly this). `named` always wins.
        boolean autoName = customName == null
            && PlayerMobConfig.autoNameMode().covers(AutoNameMode.Category.EGG);
        // A local-folder skin (a PNG in config/playermob/skins) takes precedence: if <displayName>
        // names one, the mob wears it immediately. Otherwise <displayName> is treated as a player
        // name and that player's skin is resolved off-thread, applied when it lands.
        boolean localSkin = LocalSkinFolder.resolve(name) != null;
        SkinNameApplier.apply(source.getServer(), name, mob, () -> {
            if (autoName) {
                mob.applyNameplate(name);
            }
        });
        String who = customName != null
            ? "Summoned a PlayerMob named \"" + customName + "\""
            : "Summoned a PlayerMob";
        String label = localSkin
            ? who + " wearing local skin '" + name + "'."
            : who + " for " + name + " — resolving skin…";
        source.sendSuccess(() -> Component.literal(label), true);
        return mob;
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
