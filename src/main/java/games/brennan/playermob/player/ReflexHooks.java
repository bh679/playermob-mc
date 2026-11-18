package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.TrainConfinement;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ThrownTrident;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Game-side glue for the reaction-speed signal: turns hurts, deaths, block placements, inputs,
 * projectiles and the per-player tick into {@link ReflexTracker} calls, and routes the tracker's
 * finished samples into the player's live {@link PlayerLifeRecord} via
 * {@link PlayerLifeStore#recordReflex}.
 *
 * <p>Server-side only, and every entry point bails on its first check unless a survival
 * {@link ServerPlayer} is involved, so the common (mob-vs-mob, client) case costs one test.
 * Fights count against <em>any</em> hostile mob — an {@link Enemy}, a PlayerMob, or anything
 * currently targeting the player — not only PlayerMobs, so a typical life has enough samples.</p>
 *
 * <p>Failure-isolated: a bug here must never break combat or block placement, so each hook
 * catches and logs.</p>
 */
public final class ReflexHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, ReflexTracker> TRACKERS = new HashMap<>();
    /** The server the trackers belong to — a new (singleplayer) world starts them fresh. */
    private static MinecraftServer owner;

    private ReflexHooks() {}

    // ---- tracker lookup ---------------------------------------------------

    private static boolean counts(Entity e) {
        return e instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator() && sp.isAlive();
    }

    private static int tick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0 : server.getTickCount();
    }

    private static synchronized ReflexTracker tracker(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != owner) {
            TRACKERS.clear();
            owner = server;
        }
        // The sink resolves the store from the server, not from this ServerPlayer instance: a
        // relog makes a new one, and the tracker must not hold the old one alive.
        return TRACKERS.computeIfAbsent(player.getUUID(),
            id -> new ReflexTracker(update -> {
                if (server != null) {
                    PlayerLifeStore.get(server.overworld()).creditReflex(id, update);
                }
            }));
    }

    private static boolean isHostile(LivingEntity mob, ServerPlayer player) {
        return mob instanceof Enemy || mob instanceof PlayerMobEntity
            || (mob instanceof Mob m && m.getTarget() == player);
    }

    // ---- hooks ------------------------------------------------------------

    /**
     * A living entity just took damage (server side, hit landed — {@code RETURN} of {@code hurt}).
     *
     * <p>Owns the killing blow's death too. {@code die} runs <em>inside</em> {@code hurt}, so the
     * death hook fires before this one for the same blow; settling it there would lose the
     * strike back the kill answered, and open a phantom first hit on the corpse. So
     * {@link #onDie} leaves the killer's tracker alone and this hook settles it, hit first.</p>
     */
    public static void onHurt(LivingEntity victim, DamageSource source) {
        try {
            Entity cause = source.getEntity();
            if (counts(victim) && cause instanceof LivingEntity attacker && attacker != victim) {
                ServerPlayer player = (ServerPlayer) victim;
                if (isHostile(attacker, player)) {
                    tracker(player).onStruckBy(attacker.getUUID(), tick(player));
                }
            } else if (counts(cause) && victim != cause) {
                ServerPlayer player = (ServerPlayer) cause;
                if (!isHostile(victim, player)) {
                    return;
                }
                ReflexTracker t = tracker(player);
                boolean ownBlow = isOwnBlow(source, player);
                if (ownBlow) {
                    boolean targeting = victim instanceof Mob m && m.getTarget() == player;
                    boolean melee = source.getDirectEntity() == player;
                    t.onHitMob(victim.getUUID(), targeting, melee, tick(player));
                }
                if (victim.isDeadOrDying()) {
                    t.onMobDied(victim.getUUID(), ownBlow);
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex hurt hook failed", e);
        }
    }

    /**
     * The player's own attack — melee, or a projectile they fired. Thorns names the player as its
     * source too, but it lands in the same tick as the mob's hit with no input at all, so it
     * would read as a perfect strike back.
     */
    private static boolean isOwnBlow(DamageSource source, ServerPlayer player) {
        if (source.is(DamageTypes.THORNS)) {
            return false;
        }
        Entity direct = source.getDirectEntity();
        return direct == player || direct instanceof Projectile;
    }

    /**
     * A living entity is dying. Settles any first hit a player other than the killer had open on
     * it; the killer's is settled by {@link #onHurt} once the killing blow itself is counted.
     */
    public static void onDie(LivingEntity dying, DamageSource source) {
        if (dying instanceof Player) {
            return;   // the player's own death goes through onPlayerDeath
        }
        try {
            Entity killer = source.getEntity();
            UUID killerId = counts(killer) ? killer.getUUID() : null;
            synchronized (ReflexHooks.class) {
                if (TRACKERS.isEmpty()) {
                    return;
                }
                UUID mob = dying.getUUID();
                for (Map.Entry<UUID, ReflexTracker> e : TRACKERS.entrySet()) {
                    if (!e.getKey().equals(killerId) && e.getValue().tracks(mob)) {
                        e.getValue().onMobDied(mob, false);
                    }
                }
            }
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex death hook failed", e);
        }
    }

    /** The player died: settle open windows into the record before it is snapshotted. */
    public static void onPlayerDeath(ServerPlayer player) {
        try {
            ReflexTracker t;
            synchronized (ReflexHooks.class) {
                t = TRACKERS.remove(player.getUUID());
            }
            if (t != null) {
                t.expire(Integer.MAX_VALUE);   // every open first hit settles as plain
                t.flush();
            }
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex death flush failed", e);
        }
    }

    /** The player placed a block at {@code (x, y, z)}. */
    public static void onBlockPlaced(Player placer, long x, long y, long z) {
        if (!counts(placer)) {
            return;
        }
        try {
            ServerPlayer player = (ServerPlayer) placer;
            // Not also an action: the use-item-on packet that placed it already counted one.
            tracker(player).onBlockPlaced(x, y, z, !player.onGround(), tick(player));
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex place hook failed", e);
        }
    }

    /** One deliberate input from the player (swing, swap, toggle, jump, use, break). */
    public static void onAction(Player actor, ReflexTracker.ActionKind kind) {
        if (!counts(actor)) {
            return;
        }
        try {
            ServerPlayer player = (ServerPlayer) actor;
            tracker(player).onAction(kind, tick(player));
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex action hook failed", e);
        }
    }

    /** Per-player server tick: pace sample, expiry and window flush. */
    public static void onPlayerTick(ServerPlayer player) {
        if (!counts(player)) {
            return;
        }
        int tick = tick(player);
        if (tick % 20 != 0) {
            return;
        }
        try {
            ReflexTracker t = tracker(player);
            boolean excluded = player.isFallFlying() || player.getAbilities().flying
                || player.isPassenger();
            t.onPaceSample(TrainConfinement.carriageIndex(player), excluded);
            t.expire(tick);
            if (tick % ReflexTracker.FLUSH_TICKS == 0) {
                t.flush();
            }
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex tick hook failed", e);
        }
    }

    // ---- projectiles ------------------------------------------------------

    /**
     * The player a mob-owned projectile is aimed at, or {@code null} when it isn't a projectile
     * worth tracking (not owned by a mob, or the mob has no player target).
     */
    public static ServerPlayer aimedAt(Projectile projectile) {
        Entity owner = projectile.getOwner();
        if (owner instanceof Mob mob && mob.getTarget() instanceof ServerPlayer target && counts(target)) {
            return target;
        }
        return null;
    }

    /** Distance, in blocks, from {@code point} to the player's hitbox (0 inside it). */
    public static double distanceTo(Player player, Vec3 point) {
        AABB box = player.getBoundingBox();
        double dx = Math.max(Math.max(box.minX - point.x, 0.0), point.x - box.maxX);
        double dy = Math.max(Math.max(box.minY - point.y, 0.0), point.y - box.maxY);
        double dz = Math.max(Math.max(box.minZ - point.z, 0.0), point.z - box.maxZ);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * Whether {@code arrow}, just fired by {@code shooter}, can say anything about a full bow draw:
     * {@code Boolean.TRUE/FALSE} for a bow shot (crit or not), {@code null} when it can't — a
     * crossbow bolt is always critical and a trident never is, whatever the player's timing.
     */
    public static Boolean bowCrit(AbstractArrow arrow, ServerPlayer shooter) {
        if (arrow instanceof ThrownTrident) {
            return null;
        }
        boolean bow = shooter.getMainHandItem().getItem() instanceof BowItem
            || shooter.getOffhandItem().getItem() instanceof BowItem;
        boolean crossbow = shooter.getMainHandItem().getItem() instanceof CrossbowItem
            || shooter.getOffhandItem().getItem() instanceof CrossbowItem;
        if (!bow || crossbow) {
            return null;
        }
        return arrow.isCritArrow();
    }

    /** The player who shot {@code projectile}, if it's an arrow or trident a counted player fired. */
    public static ServerPlayer arrowShooter(Projectile projectile) {
        if (projectile instanceof AbstractArrow && projectile.getOwner() instanceof ServerPlayer sp
                && counts(sp)) {
            return sp;
        }
        return null;
    }

    /** One of {@code shooter}'s own arrows landed; {@code hit} when it struck a living thing not them. */
    public static void onOwnArrowResolved(ServerPlayer shooter, boolean hit, Boolean crit) {
        if (!counts(shooter)) {
            return;
        }
        try {
            tracker(shooter).onOwnArrow(hit, crit);
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex arrow hook failed", e);
        }
    }

    /** A tracked mob projectile resolved: it hit {@code target} or passed {@code closest} blocks away. */
    public static void onProjectileResolved(ServerPlayer target, double closest, boolean hitTarget) {
        if (!counts(target) || !ReflexTracker.isThreat(closest, hitTarget)) {
            return;
        }
        try {
            tracker(target).onProjectile(ReflexTracker.isDodge(closest, hitTarget));
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reflex projectile hook failed", e);
        }
    }
}
