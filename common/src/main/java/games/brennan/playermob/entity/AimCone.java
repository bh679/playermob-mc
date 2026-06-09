package games.brennan.playermob.entity;

/**
 * Pure "is the shooter pointing at me?" cone test for the raise-a-shield reflex
 * ({@code BlockArrowsGoal}). A shooter with a unit view vector {@code look} is
 * judged to be aiming at a target offset {@code (dx, dy, dz)} away if the angle
 * between the two is within the cone whose half-angle is {@code acos(cosThreshold)}.
 *
 * <p>Used for the cases where there's no explicit AI target to read — chiefly a
 * <b>player</b> drawing a bow (players have no {@code getTarget()}). For mobs the
 * goal reads {@code Mob.getTarget()} directly instead, which is definitive and
 * needs no geometry.</p>
 *
 * <p>Pure logic, no Minecraft types — unit-tested like {@link ArrowThreat} and
 * {@link DoorStuckMonitor}. Assumes {@code look} is already unit length (vanilla's
 * {@code Entity.getViewVector} returns a normalized vector).</p>
 */
public final class AimCone {

    private AimCone() {}

    /**
     * Whether {@code look} points within the cone toward the target offset.
     *
     * @param lookX        shooter view vector X (unit length)
     * @param lookY        shooter view vector Y
     * @param lookZ        shooter view vector Z
     * @param dx           target X minus shooter X
     * @param dy           target Y minus shooter eye Y
     * @param dz           target Z minus shooter Z
     * @param cosThreshold cosine of the cone's half-angle (higher = tighter cone)
     * @return {@code true} if within the cone; {@code true} for a ~zero offset
     *         (point-blank, where "direction to target" is undefined)
     */
    public static boolean facesWithin(double lookX, double lookY, double lookZ,
                                      double dx, double dy, double dz,
                                      double cosThreshold) {
        double lenSqr = dx * dx + dy * dy + dz * dz;
        if (lenSqr < 1.0e-6) {
            return true; // point-blank — treat as aimed
        }
        double dot = (lookX * dx + lookY * dy + lookZ * dz) / Math.sqrt(lenSqr);
        return dot >= cosThreshold;
    }
}
