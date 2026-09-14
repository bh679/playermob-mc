package games.brennan.playermob.entity;

/**
 * Which horizontal axis a mob is travelling along, for the path-aware door reflex on a
 * Dungeon Train — with a fallback assumption for a mob that is standing still.
 *
 * <p><b>Why not the one-tick latch.</b> The previous heading ({@link DoorObstruction#travelAxis})
 * re-latched on any single tick of confident movement. A mob shoving a closed door has ~zero
 * displacement along its facing axis but slides a little <em>along</em> the panel, and one such
 * tick re-latched the heading to the panel's axis — which the closed door does not "obstruct",
 * so the reflex went idle with the mob wedged for good. Here each axis's displacement feeds a
 * decayed accumulator instead, so a heading takes a few ticks of real walking to establish
 * ({@code ~3} at walking speed) and a one-tick slide against a panel cannot flip it.</p>
 *
 * <p><b>Marching.</b> While the mob is marching between carriages the answer is simply the
 * train's own axis — {@link Axis#X}, the direction carriages are laid along — whatever its
 * momentary displacement says. A marching mob's Z movement is always a detour (routing round
 * furniture, sliding along a panel, a scuffle in the corridor), and reading it as a heading
 * closes the corridor's X-facing doors in the mob's own face. The doors it needs on a march are
 * the ones across the train axis, so that is the axis it is assessed against.</p>
 *
 * <p><b>Not marching.</b> The displacement heading applies; when none is confident (standing
 * still, or the accumulators have decayed — {@code ~8} idle ticks) the answer is
 * {@link Axis#NONE}, so no door is touched on an assumption. A mob raiding a chest through a
 * side door is exactly the case this protects; the stuck probe covers it if it wedges.</p>
 *
 * <p>Pure logic, no Minecraft types; one instance per mob, server thread only. Unit-tested
 * like {@link DoorStuckMonitor}.</p>
 */
public final class DoorHeading {

    /** A horizontal travel axis, or none confidently known. */
    public enum Axis { X, Z, NONE }

    /** Per-tick decay of each axis's displacement accumulator (steady state ≈ 6.7× per-tick speed). */
    static final double DECAY = 0.85;

    /**
     * Accumulated displacement at or above which a heading is confident. Walking (~0.15/tick)
     * reaches it in three ticks; a stalled mob decays below it in about eight.
     */
    static final double CONFIDENT_MIN = 0.3;

    /** How much the dominant axis must exceed the other — near-diagonal movement is not a heading. */
    static final double DOMINANCE = 1.5;

    private boolean primed;
    private double lastX;
    private double lastZ;
    private double accX;
    private double accZ;

    /**
     * Advance one tick with the mob's position (in the frame the doors live in) and answer the
     * current heading.
     *
     * @param x        current X in the caller's chosen frame
     * @param z        current Z in the same frame
     * @param marching whether the mob is currently marching between carriages (⇒ the train axis)
     * @return {@link Axis#X} while marching; else the confident displacement heading, else
     *         {@link Axis#NONE}
     */
    public Axis tick(double x, double z, boolean marching) {
        if (!primed) {
            primed = true;
            lastX = x;
            lastZ = z;
            return marching ? Axis.X : Axis.NONE;
        }
        // Accumulate every tick, marching or not, so the displacement heading is continuous and
        // already settled the moment a march ends.
        accX = accX * DECAY + Math.abs(x - lastX);
        accZ = accZ * DECAY + Math.abs(z - lastZ);
        lastX = x;
        lastZ = z;
        if (marching) {
            return Axis.X;
        }
        double max = Math.max(accX, accZ);
        double min = Math.min(accX, accZ);
        if (max >= CONFIDENT_MIN && max >= min * DOMINANCE) {
            return accX >= accZ ? Axis.X : Axis.Z;
        }
        return Axis.NONE;
    }
}
