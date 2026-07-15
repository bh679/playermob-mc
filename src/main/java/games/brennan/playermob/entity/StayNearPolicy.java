package games.brennan.playermob.entity;

/**
 * Pure decision core for {@link games.brennan.playermob.entity.goal.StayNearGoal} — the distances
 * that decide when a tethered mob should walk back toward its {@link StayAnchor} and when it has
 * returned close enough to release. Primitives only (no Minecraft types), so it unit-tests without
 * a game bootstrap, exactly like {@link FollowLovedOnePolicy} / {@link GiftPolicy}.
 *
 * <p><b>Leash, not glue.</b> A stay-near mob does its normal thing (raiding, harvesting, strolling,
 * fighting) until it drifts — or is dragged by a fight — <em>past</em> its radius, then walks back
 * and resumes once well inside. "Past" and "back inside" use hysteresis so the mob doesn't flicker
 * on the boundary:
 * <ul>
 *   <li>Engage once farther than {@link #DEFAULT_RADIUS the radius} from the anchor
 *       ({@link #beyond}).</li>
 *   <li>Keep returning until within {@code radius * }{@value #KEEP_FRACTION} ({@link #keep}).</li>
 * </ul>
 * The return walk runs at {@link #RETURN_SPEED} — a normal stroll home, not a sprint.</p>
 */
public final class StayNearPolicy {

    private StayNearPolicy() {}

    /** Tether radius (blocks) applied when a {@code /playermob stay} command omits one. */
    public static final int DEFAULT_RADIUS = 32;
    /** Smallest settable radius (blocks) — a mob still needs room to path back. */
    public static final int MIN_RADIUS = 1;
    /** Largest settable radius (blocks) — beyond this the anchor entity is often unloaded anyway. */
    public static final int MAX_RADIUS = 256;

    /**
     * Release the return walk once back within this fraction of the radius. Below 1.0 so a mob that
     * was pulled out to the edge comes comfortably inside before resuming normal behaviour, rather
     * than yo-yoing across the boundary.
     */
    public static final double KEEP_FRACTION = 0.75;

    /** Return-walk pathfinder speed multiplier — a calm walk home, not an emergency sprint. */
    public static final double RETURN_SPEED = 1.0;

    /** Begin walking back once the mob is farther than {@code radius} blocks from the anchor. */
    public static boolean beyond(double distance, int radius) {
        return distance > radius;
    }

    /**
     * Keep walking back until within {@code radius * }{@value #KEEP_FRACTION} of the anchor
     * (hysteresis below {@link #beyond} so the release point sits inside the engage point).
     */
    public static boolean keep(double distance, int radius) {
        return distance > radius * KEEP_FRACTION;
    }

    /** Clamp a requested radius into {@code [}{@value #MIN_RADIUS}{@code , }{@value #MAX_RADIUS}{@code ]}. */
    public static int clampRadius(int radius) {
        return Math.max(MIN_RADIUS, Math.min(MAX_RADIUS, radius));
    }
}
