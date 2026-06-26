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
import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.PlayerMobEntity;
import games.brennan.playermob.entity.PlayerMobSummon;
import games.brennan.playermob.entity.goal.AttackOrder;
import games.brennan.playermob.entity.goal.Order;
import games.brennan.playermob.entity.goal.OrderType;
import games.brennan.playermob.skin.LocalSkinFolder;
import games.brennan.playermob.skin.LocalSkinRef;
import games.brennan.playermob.skin.PlayerMobSkinRegistry;
import games.brennan.playermob.skin.PlayerSkinResolver;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.GameProfileArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.commands.arguments.item.ItemArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
 *       — spawn a PlayerMob wearing that player's resolved skin, at an optional position (default: the
 *       command source), with optional locked traits (default: random). Append {@code named} to also give
 *       the mob a nameplate — {@code customName} if supplied, otherwise {@code displayName}.</li>
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
 *   <li>{@code /playermob skin sources} — report which skin sources (bundled / online / local) a random
 *       PlayerMob may draw from, with counts.</li>
 *   <li>{@code /playermob skin source <bundled|online|local> on|off} — toggle one skin source for this
 *       session.</li>
 *   <li>{@code /playermob skin spawn <file> [pos]} — summon a PlayerMob wearing the named local-folder
 *       skin (a PNG dropped in {@code config/playermob/skins}).</li>
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
                .then(Commands.literal("reincarnate")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::reincarnate)))
                .then(Commands.literal("life")
                    .then(Commands.argument("player", GameProfileArgument.gameProfile())
                        .executes(ReincarnateCommand::life)))
                .then(Commands.literal("summon")
                    .then(Commands.argument("displayName", StringArgumentType.word())
                        .executes(ctx -> summon(ctx, false))
                        // `named [<customName>]` gives the mob a nameplate (defaults to displayName);
                        // attached at every level so it can follow any arg combo. Literals bind before
                        // the vec3/int args, so it never clashes with `pos`/the trait numbers.
                        .then(namedFlag())
                        // Position chains before the traits (and a number alone would be ambiguous with
                        // a vec3 coord), so use `~ ~ ~` to set traits "here" — mirrors /summon's [pos].
                        .then(Commands.argument("pos", Vec3Argument.vec3())
                            .executes(ctx -> summon(ctx, false))
                            .then(namedFlag())
                            .then(Commands.argument("friendliness", IntegerArgumentType.integer(0, 10))
                                .executes(ctx -> summon(ctx, false))
                                .then(namedFlag())
                                .then(Commands.argument("fightFlight", IntegerArgumentType.integer(0, 10))
                                    .executes(ctx -> summon(ctx, false))
                                    .then(namedFlag()))))))
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
                                .executes(ctx -> setGroup(ctx, FloatArgumentType.getFloat(ctx, "chance")))))))
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
     *   <li>{@code skin spawn <file> [pos]} — summon a PlayerMob wearing the named local-folder skin.</li>
     * </ul>
     */
    private static LiteralArgumentBuilder<CommandSourceStack> skinTree() {
        return Commands.literal("skin")
            .then(Commands.literal("sources").executes(ReincarnateCommand::reportSkinSources))
            .then(Commands.literal("source")
                .then(skinSourceToggle("bundled"))
                .then(skinSourceToggle("online"))
                .then(skinSourceToggle("local")))
            .then(Commands.literal("spawn")
                .then(Commands.argument("file", StringArgumentType.string())
                    .suggests(LOCAL_SKIN_SUGGESTIONS)
                    .executes(ReincarnateCommand::skinSpawn)
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ReincarnateCommand::skinSpawn))));
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
        ctx.getSource().sendSuccess(() -> Component.literal(
            "PlayerMob skin sources — bundled " + onOff(bundled)
                + ", online " + onOff(online) + " (" + onlineCount + ")"
                + ", local " + onOff(local) + " (" + localCount + " in config/playermob/skins)."), false);
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

    /**
     * {@code /playermob skin spawn <file> [pos]} — summon a PlayerMob wearing the local-folder skin
     * {@code <file>} (a PNG base name in config/playermob/skins), at {@code pos} or the command source.
     * Fails with the available names if the file isn't found.
     */
    private static int skinSpawn(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        String file = StringArgumentType.getString(ctx, "file");
        if (LocalSkinFolder.resolve(file) == null) {
            source.sendFailure(Component.literal("No local skin '" + file
                + "' in config/playermob/skins. Available: "
                + (LocalSkinFolder.list().isEmpty() ? "(none)" : String.join(", ", LocalSkinFolder.list())) + "."));
            return 0;
        }
        ServerLevel level = source.getLevel();
        Vec3 at = has(ctx, "pos") ? Vec3Argument.getVec3(ctx, "pos") : source.getPosition();
        float yRot = source.getRotation().y;
        PlayerMobEntity mob = PlayerMobSummon.summon(level, at.x, at.y, at.z, yRot, null, null);
        if (mob == null) {
            source.sendFailure(Component.literal("Could not create a PlayerMob."));
            return 0;
        }
        mob.setSkinTextureUrl(LocalSkinRef.encode(file));
        source.sendSuccess(() -> Component.literal(
            "Summoned a PlayerMob wearing local skin '" + file + "'."), true);
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
     * {@code <name>} selects a PlayerMob by custom name (nearest one as a fallback); a
     * {@code <target>} resolves to an online player or a named PlayerMob; positions accept
     * {@code ~ ~ ~}. {@code place} conjures the given block from air.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> orderTree(
            CommandBuildContext buildContext) {
        return Commands.literal("order")
            .then(Commands.argument("name", StringArgumentType.word())
                .suggests(MOB_NAME_SUGGESTIONS)
                .then(Commands.literal("walk")
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ReincarnateCommand::orderWalkPos))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(TARGET_SUGGESTIONS)
                        .executes(ReincarnateCommand::orderWalkEntity)))
                .then(entityAction("punch", OrderType.PUNCH))
                .then(entityAction("punchat", OrderType.PUNCH_AT))
                .then(attackSubtree(buildContext))
                .then(stealSubtree())
                .then(giftSubtree(buildContext))
                .then(entityAction("greet", OrderType.GREET))
                .then(useSubtree(buildContext))
                .then(Commands.literal("place")
                    .then(Commands.argument("pos", Vec3Argument.vec3())
                        .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(ReincarnateCommand::orderPlacePos)))
                    .then(Commands.argument("target", StringArgumentType.word())
                        .suggests(TARGET_SUGGESTIONS)
                        .then(Commands.argument("block", BlockStateArgument.block(buildContext))
                            .executes(ReincarnateCommand::orderPlaceEntity)))));
    }

    /** An entity-directed action literal (punch / gift / greet) taking a {@code <target>}. */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> entityAction(
            String name, OrderType type) {
        return Commands.literal(name)
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .executes(ctx -> orderEntity(ctx, type)));
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
     * returns {@code null} only when the dimension holds no PlayerMob at all.
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
    private static int orderEntity(CommandContext<CommandSourceStack> ctx, OrderType type) {
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
        mob.setOrder(Order.toward(type, target));
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
        amount.then(Commands.literal("s").executes(ctx -> orderSteal(ctx, Order.FleeUnit.SECONDS)));
        amount.then(Commands.literal("seconds").executes(ctx -> orderSteal(ctx, Order.FleeUnit.SECONDS)));
        amount.then(Commands.literal("b").executes(ctx -> orderSteal(ctx, Order.FleeUnit.BLOCKS)));
        amount.then(Commands.literal("blocks").executes(ctx -> orderSteal(ctx, Order.FleeUnit.BLOCKS)));
        return Commands.literal("steal")
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .executes(ctx -> orderSteal(ctx, Order.FleeUnit.NONE))
                .then(amount));
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
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                    .executes(ctx -> orderUsePos(ctx, false))
                    .then(Commands.literal("spawn").executes(ctx -> orderUsePos(ctx, true)))))
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                    .executes(ctx -> orderUseEntity(ctx, false))
                    .then(Commands.literal("spawn").executes(ctx -> orderUseEntity(ctx, true)))));
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
    private static int orderUsePos(CommandContext<CommandSourceStack> ctx, boolean spawn) {
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
        mob.setOrder(Order.use(pos, item));
        String who = label(mob);
        String itemName = item.getHoverName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to use " + itemName + " at "
            + pos.getX() + " " + pos.getY() + " " + pos.getZ() + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> use <target> <item> [spawn]}. */
    private static int orderUseEntity(CommandContext<CommandSourceStack> ctx, boolean spawn) {
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
        mob.setOrder(Order.use(target, item));
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
            .then(Commands.argument("target", StringArgumentType.word())
                .suggests(TARGET_SUGGESTIONS)
                .executes(ctx -> orderGift(ctx, false))
                .then(Commands.argument("item", ItemArgument.item(buildContext))
                    .executes(ctx -> orderGift(ctx, true))));
    }

    /** {@code /playermob order <name> gift <target> [<item>]}. */
    private static int orderGift(CommandContext<CommandSourceStack> ctx, boolean withItem) {
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
        mob.setOrder(item.isEmpty() ? Order.toward(OrderType.GIFT, target) : Order.gift(target, item));
        String who = label(mob);
        String targetName = target.getName().getString();
        String what = item.isEmpty() ? "a gift" : item.getHoverName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to gift " + what + " to " + targetName + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> steal <target> [<n> s|blocks]}. */
    private static int orderSteal(CommandContext<CommandSourceStack> ctx, Order.FleeUnit unit) {
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
        mob.setOrder(Order.steal(target, amount, unit));
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
    private static int orderWalkPos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        Vec3 pos = Vec3Argument.getVec3(ctx, "pos");
        mob.setOrder(Order.walkTo(BlockPos.containing(pos)));
        String who = label(mob);
        source.sendSuccess(() -> Component.literal(who + " ordered to walk to "
            + (int) pos.x + " " + (int) pos.y + " " + (int) pos.z + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> walk <target>}. */
    private static int orderWalkEntity(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        LivingEntity target = resolveTarget(source, mob, StringArgumentType.getString(ctx, "target"));
        if (target == null) {
            return 0;
        }
        mob.setOrder(Order.walkTo(target));
        String who = label(mob);
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to walk to " + targetName + "."), true);
        return 1;
    }

    /** {@code /playermob order <name> place <pos> <block>}. */
    private static int orderPlacePos(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack source = ctx.getSource();
        PlayerMobEntity mob = resolveMob(source, StringArgumentType.getString(ctx, "name"));
        if (mob == null) {
            return 0;
        }
        BlockPos pos = BlockPos.containing(Vec3Argument.getVec3(ctx, "pos"));
        BlockState state = BlockStateArgument.getBlock(ctx, "block").getState();
        return issuePlace(source, mob, pos, state);
    }

    /** {@code /playermob order <name> place <target> <block>} — chase the live target, place at its feet. */
    private static int orderPlaceEntity(CommandContext<CommandSourceStack> ctx) {
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
        mob.setOrder(Order.placeAt(target, state));
        String who = label(mob);
        String block = state.getBlock().getName().getString();
        String targetName = target.getName().getString();
        source.sendSuccess(() -> Component.literal(who + " ordered to place " + block + " by " + targetName + "."), true);
        return 1;
    }

    private static int issuePlace(CommandSourceStack source, PlayerMobEntity mob, BlockPos pos, BlockState state) {
        mob.setOrder(Order.place(pos, state));
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
            return 0;
        }
        if (customName != null) {
            mob.setCustomName(Component.literal(customName));
            mob.setCustomNameVisible(true);
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
        String label = customName != null
            ? "Summoned a PlayerMob named \"" + customName + "\" for " + name
            : "Summoned a PlayerMob for " + name;
        source.sendSuccess(() -> Component.literal(label + " — resolving skin…"), true);
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
