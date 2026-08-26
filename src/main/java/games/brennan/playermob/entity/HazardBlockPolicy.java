package games.brennan.playermob.entity;

import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

/**
 * Which blocks hurt a {@link PlayerMobEntity} standing on or in them, and how long it takes one to
 * notice. Pure logic — the live scan for a safe spot to step onto lives in
 * {@code games.brennan.playermob.entity.goal.StepOffHazardGoal}.
 *
 * <p>Same pure-core / live-scan split as {@link PathFirePolicy}: the classification is unit-tested
 * here, the world scan is covered by the in-game Gate 2 test.</p>
 *
 * <p>The hazard set is an explicit list rather than a general "does this state hurt me" query,
 * because vanilla has no such query — damage is applied by each block's own tick/entity-inside
 * code. The list therefore covers vanilla's standing-on and standing-in hazards and will miss
 * modded ones; that is the deliberate trade for something testable.</p>
 */
public final class HazardBlockPolicy {

    private HazardBlockPolicy() {}

    /**
     * The delayed reaction — how long the mob stands on the hazard before acting. The neutral
     * (reaction 5) window; {@code StepOffHazardGoal} rolls it through
     * {@link PlayerMobEntity#reactRoll}, so a quick-reacting mob lands low in it more often and a
     * sluggish one high. Shorter than {@link PathFirePolicy#REACTION_MIN_TICKS}–
     * {@link PathFirePolicy#REACTION_MAX_TICKS}: this hazard is already burning it, not merely in
     * the way, so it reads as a flinch rather than a double-take.
     */
    public static final int REACTION_MIN_TICKS = 5;
    public static final int REACTION_MAX_TICKS = 20;

    /** How far out, horizontally and vertically, to look for somewhere safe to stand. */
    public static final int SEARCH_RADIUS = 3;
    public static final int SEARCH_VERTICAL = 1;

    /**
     * How long to wait before scanning again after finding nowhere safe — a mob boxed in on a
     * magma platform must not re-scan its whole neighbourhood every tick. Not reaction-scaled:
     * it's a rescan budget, not a reaction.
     */
    public static final int RECHECK_TICKS = 40;

    /** Give up on a chosen safe spot after this long; the mob re-picks or the goal ends. */
    public static final int MOVE_TIMEOUT_TICKS = 100;

    /** What kind of harm a block does, which decides whether a given mob can shrug it off. */
    public enum Hazard {
        /** Harmless to stand on. */
        NONE,
        /** Burns — fire, soul fire, lava, magma, a lit campfire. A fire-immune mob ignores these. */
        FIRE,
        /** Hurts on contact regardless of fire immunity — cactus, berries, wither rose, powder snow. */
        CONTACT
    }

    /**
     * Classify a block state the mob is standing on, in, or with its head in.
     *
     * <p>Campfires are only a hazard while lit, and powder snow counts because a mob left in it
     * freezes; both are checked by state, not by block identity alone.</p>
     */
    public static Hazard hazardKind(BlockState state) {
        if (state == null || state.isAir()) {
            return Hazard.NONE;
        }
        // Lava is matched by block identity as well as by fluid tag: the tag catches flowing lava and
        // waterlogged-style states, the identity check works even where the tag isn't bound (unit tests).
        if (PathFirePolicy.isFire(state)
            || state.is(Blocks.MAGMA_BLOCK)
            || state.is(Blocks.LAVA)
            || state.getFluidState().is(FluidTags.LAVA)) {
            return Hazard.FIRE;
        }
        if (state.getBlock() instanceof CampfireBlock
            && state.hasProperty(BlockStateProperties.LIT)
            && state.getValue(BlockStateProperties.LIT)) {
            return Hazard.FIRE;
        }
        if (state.is(Blocks.CACTUS)
            || state.is(Blocks.SWEET_BERRY_BUSH)
            || state.is(Blocks.WITHER_ROSE)
            || state.is(Blocks.POWDER_SNOW)) {
            return Hazard.CONTACT;
        }
        return Hazard.NONE;
    }

    /**
     * Whether {@code kind} actually threatens a mob with the given fire immunity. A fire-immune or
     * fire-resistant mob has no reason to step off magma, so it doesn't — anything else is a
     * pointless twitch away from whatever it was doing.
     */
    public static boolean isHarmful(Hazard kind, boolean fireImmune) {
        return switch (kind) {
            case NONE -> false;
            case FIRE -> !fireImmune;
            case CONTACT -> true;
        };
    }

    /** Convenience for the common "is this state something I should get off" question. */
    public static boolean isHarmful(BlockState state, boolean fireImmune) {
        return isHarmful(hazardKind(state), fireImmune);
    }
}
