package games.brennan.playermob.player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * One player's live, tick-timed reflex bookkeeping — the part of the reaction-speed signal that
 * needs a clock and so can't live in the saved {@link ReflexTally}. Each finished sample is handed
 * to {@code sink} as a tally update; nothing here is persisted (a restart loses at most the open
 * windows of the last {@value #FLUSH_TICKS} ticks).
 *
 * <p>Pure: every input is a primitive or a UUID and the game tick is passed in, so the timing
 * rules unit-test without a world — see {@code ReflexTrackerTest}. {@link ReflexHooks} feeds it
 * from the game.</p>
 *
 * <p>Block placements are judged only by the placed blocks' positions and timing, never by the
 * player's position or speed: on a moving Sable carriage blocks land in the carriage's own
 * coordinates while the player is in world space, so only block-to-block geometry is reliable.</p>
 */
public final class ReflexTracker {

    /** A hit not answered within this many ticks yields no strike-back sample (that was flight). */
    static final int STRIKE_WINDOW_TICKS = 100;
    /** A mob that hurt the player this recently can't be "first hit" — the player isn't first. */
    static final int FIRST_HIT_QUIET_TICKS = 200;
    /** An open first hit unresolved after this long settles as a plain (not clean) first hit. */
    static final int FIRST_HIT_EXPIRE_TICKS = 600;

    /** Max ticks between two blocks of a speed-bridge run, and the run length that counts. */
    static final int BRIDGE_GAP_TICKS = 8;
    static final int BRIDGE_MIN_RUN = 4;
    /** Max ticks between two jump-placed blocks, and the pillar height that counts. */
    static final int JUMP_GAP_TICKS = 12;
    static final int JUMP_MIN_RUN = 3;

    /** A gap between actions longer than this many seconds is idle, not active time. */
    static final int ACTIVE_GAP_SECONDS = 3;
    /**
     * A swing this soon after the last is a held button, not a press: the client swings every
     * tick while mining. A deliberate click can't come one tick apart.
     */
    static final int SWING_REPEAT_TICKS = 1;
    /** Likewise for use: a held right-click re-sends its use every 4 ticks. */
    static final int USE_REPEAT_TICKS = 4;

    /** The kind of input, for filtering a held button's auto-repeat out of the count. */
    public enum ActionKind { SWING, USE, OTHER }

    /** Consecutive off-train pace samples after which re-boarding doesn't count as progress. */
    static final int OFF_TRAIN_ABSORB_SAMPLES = 5;
    /** Pace sentinel for "not on a train" (matches {@code TrainEnvironment.NO_CARRIAGE}). */
    public static final int NO_CARRIAGE = Integer.MIN_VALUE;

    /** How often {@link ReflexHooks} flushes the action and pace windows. */
    public static final int FLUSH_TICKS = 400;

    /** Closest pass, in blocks, at which a mob projectile that missed counts as dodged. */
    static final double DODGE_RADIUS = 1.5;

    private final Consumer<UnaryOperator<ReflexTally>> sink;

    // Strike back / first hit.
    private final Map<UUID, Integer> struckAt = new HashMap<>();
    private final Map<UUID, Integer> lastHurtByAt = new HashMap<>();
    private final Map<UUID, Integer> openFirstHits = new HashMap<>();

    // Block placement.
    private boolean hasLastBlock;
    private long lastX, lastY, lastZ;
    private int lastBlockTick;
    private int bridgeRun;
    private int jumpRun;

    // Actions.
    private int windowActions;
    private int windowActiveSeconds;
    private int lastActionSecond = Integer.MIN_VALUE;
    private int lastSwingTick = Integer.MIN_VALUE;
    private int lastUseTick = Integer.MIN_VALUE;

    // Pace.
    private boolean paceAnchored;
    private int paceMin, paceMax;
    private int offTrainSamples;
    private int windowCarriages;
    private int windowPaceSeconds;

    public ReflexTracker(Consumer<UnaryOperator<ReflexTally>> sink) {
        this.sink = sink;
    }

    // ---- Combat -----------------------------------------------------------

    /** A hostile mob {@code attacker} just damaged the player. */
    public void onStruckBy(UUID attacker, int tick) {
        struckAt.putIfAbsent(attacker, tick);   // latency runs from the first unanswered hit
        lastHurtByAt.put(attacker, tick);
        // It hit back before the player finished it: the first hit was not clean.
        if (openFirstHits.remove(attacker) != null) {
            sink.accept(t -> t.withFirstHit(false));
        }
    }

    /**
     * The player just landed a hit on hostile mob {@code mob}. {@code mobTargetsPlayer} says the
     * mob was already coming for them — hitting an idle mob first is not a reflex.
     */
    public void onHitMob(UUID mob, boolean mobTargetsPlayer, int tick) {
        Integer struck = struckAt.remove(mob);
        if (struck != null) {
            int latency = tick - struck;
            if (latency >= 0 && latency <= STRIKE_WINDOW_TICKS) {
                sink.accept(t -> t.withStrikeBack(latency));
            }
            return;
        }
        if (!mobTargetsPlayer || openFirstHits.containsKey(mob)) {
            return;
        }
        Integer hurtBy = lastHurtByAt.get(mob);
        if (hurtBy == null || tick - hurtBy > FIRST_HIT_QUIET_TICKS) {
            openFirstHits.put(mob, tick);
        }
    }

    /** {@code mob} died; {@code killedByPlayer} when this tracker's player got the kill. */
    public void onMobDied(UUID mob, boolean killedByPlayer) {
        struckAt.remove(mob);
        lastHurtByAt.remove(mob);
        if (openFirstHits.remove(mob) != null) {
            sink.accept(t -> t.withFirstHit(killedByPlayer));
        }
    }

    /** One mob projectile aimed at the player has resolved. */
    public void onProjectile(boolean dodged) {
        sink.accept(t -> t.withProjectile(dodged));
    }

    /**
     * Whether a resolved mob projectile counts as dodged: it never hit its target and passed
     * within {@link #DODGE_RADIUS} of them. A wild miss is no dodge — there was nothing to avoid.
     */
    public static boolean isDodge(double closestPass, boolean hitTarget) {
        return !hitTarget && closestPass <= DODGE_RADIUS;
    }

    /** True when the projectile came close enough to be judged at all (hit or dodge). */
    public static boolean isThreat(double closestPass, boolean hitTarget) {
        return hitTarget || closestPass <= DODGE_RADIUS;
    }

    // ---- Block placement --------------------------------------------------

    /**
     * The player placed a block at {@code (x, y, z)} (in whatever space the block lives in).
     * Crouching is not checked: shift-tapping at the edge is how most players speed-bridge, and
     * the {@value #BRIDGE_GAP_TICKS}-tick gap already rules out a slow, held-shift bridge.
     */
    public void onBlockPlaced(long x, long y, long z, boolean airborne, int tick) {
        if (hasLastBlock) {
            int gap = tick - lastBlockTick;
            long dx = x - lastX, dy = y - lastY, dz = z - lastZ;
            boolean beside = dy == 0 && Math.abs(dx) + Math.abs(dz) == 1;
            boolean above = dx == 0 && dz == 0 && dy == 1;

            bridgeRun = (beside && gap >= 0 && gap <= BRIDGE_GAP_TICKS) ? bridgeRun + 1 : 1;
            jumpRun = (above && airborne && gap >= 0 && gap <= JUMP_GAP_TICKS) ? jumpRun + 1 : 1;
        } else {
            bridgeRun = 1;
            jumpRun = 1;
        }
        hasLastBlock = true;
        lastX = x;
        lastY = y;
        lastZ = z;
        lastBlockTick = tick;

        creditRun(bridgeRun, BRIDGE_MIN_RUN, true);
        creditRun(jumpRun, JUMP_MIN_RUN, false);
    }

    /** At the threshold credit the whole run so far; past it, one block at a time. */
    private void creditRun(int run, int minRun, boolean bridge) {
        int count = run == minRun ? minRun : (run > minRun ? 1 : 0);
        if (count > 0) {
            sink.accept(bridge ? t -> t.withBridgeBlocks(count) : t -> t.withJumpBlocks(count));
        }
    }

    // ---- Actions ----------------------------------------------------------

    /**
     * One input (swing, hotbar swap, sneak/sprint toggle, jump, use, break). A swing or use that
     * repeats as fast as a held button auto-repeats is dropped, so holding a button down reads as
     * one press, not twenty a second.
     */
    public void onAction(ActionKind kind, int tick) {
        if (kind == ActionKind.SWING) {
            boolean repeat = isRepeat(lastSwingTick, tick, SWING_REPEAT_TICKS);
            lastSwingTick = tick;
            if (repeat) return;
        } else if (kind == ActionKind.USE) {
            boolean repeat = isRepeat(lastUseTick, tick, USE_REPEAT_TICKS);
            lastUseTick = tick;
            if (repeat) return;
        }
        int second = Math.floorDiv(tick, 20);
        windowActions++;
        if (lastActionSecond == Integer.MIN_VALUE) {
            windowActiveSeconds += 1;
        } else if (second > lastActionSecond) {
            int gap = second - lastActionSecond;
            // A short pause between inputs is still active play; a long one is idle time.
            windowActiveSeconds += gap <= ACTIVE_GAP_SECONDS ? gap : 1;
        }
        lastActionSecond = Math.max(lastActionSecond, second);
    }

    private static boolean isRepeat(int last, int tick, int within) {
        return last != Integer.MIN_VALUE && tick >= last && tick - last <= within;
    }

    // ---- Pace -------------------------------------------------------------

    /**
     * One-second pace sample. {@code carriage} is the carriage index the player stands in, or
     * {@link #NO_CARRIAGE} off the train; {@code excluded} is elytra, creative flight or riding —
     * any progress then is absorbed without credit.
     */
    public void onPaceSample(int carriage, boolean excluded) {
        if (carriage == NO_CARRIAGE) {
            offTrainSamples++;
            return;
        }
        boolean absorb = excluded || !paceAnchored || offTrainSamples > OFF_TRAIN_ABSORB_SAMPLES;
        offTrainSamples = 0;
        if (!paceAnchored) {
            paceMin = carriage;
            paceMax = carriage;
            paceAnchored = true;
        }
        int gained = 0;
        if (carriage > paceMax) {
            gained += carriage - paceMax;
            paceMax = carriage;
        }
        if (carriage < paceMin) {
            gained += paceMin - carriage;
            paceMin = carriage;
        }
        if (!absorb) {
            windowCarriages += gained;
            windowPaceSeconds++;
        }
    }

    // ---- Housekeeping -----------------------------------------------------

    /** Drop stale strike/hurt timers and settle first hits that never resolved. */
    public void expire(int tick) {
        struckAt.values().removeIf(t -> tick - t > STRIKE_WINDOW_TICKS || tick < t);
        lastHurtByAt.values().removeIf(t -> tick - t > FIRST_HIT_QUIET_TICKS || tick < t);
        Iterator<Integer> open = openFirstHits.values().iterator();
        while (open.hasNext()) {
            int opened = open.next();
            if (tick - opened > FIRST_HIT_EXPIRE_TICKS || tick < opened) {
                open.remove();
                sink.accept(t -> t.withFirstHit(false));
            }
        }
    }

    /** Hand the open action and pace windows to the sink and start fresh ones. */
    public void flush() {
        if (windowActions > 0 || windowActiveSeconds > 0) {
            int actions = windowActions;
            int seconds = windowActiveSeconds;
            sink.accept(t -> t.withActions(actions, seconds));
        }
        if (windowPaceSeconds > 0) {
            int carriages = windowCarriages;
            int seconds = windowPaceSeconds;
            sink.accept(t -> t.withPace(carriages, seconds));
        }
        windowActions = 0;
        windowActiveSeconds = 0;
        windowCarriages = 0;
        windowPaceSeconds = 0;
    }

    /** True if this player still has a combat engagement open with {@code mob}. */
    public boolean tracks(UUID mob) {
        return struckAt.containsKey(mob) || openFirstHits.containsKey(mob)
            || lastHurtByAt.containsKey(mob);
    }
}
