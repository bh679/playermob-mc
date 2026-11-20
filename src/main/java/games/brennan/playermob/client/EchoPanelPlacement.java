package games.brennan.playermob.client;

/**
 * Where a floating activity panel sits in front of a PlayerMob, matching how What Are They Up To
 * (WATUT) places its panels over a player: {@link #FORWARD} blocks out along the body's view
 * direction and {@link #UP} blocks above the feet, turned to the body's yaw.
 *
 * <p>Pure maths over primitives so the placement unit-tests without a client — see
 * {@code EchoPanelPlacementTest}. Used by the NeoForge WATUT compat to put WATUT's own book panel
 * over a reading or writing echo.</p>
 */
public final class EchoPanelPlacement {

    /** Distance in front of the body, in blocks (WATUT's own value). */
    public static final double FORWARD = 0.85;
    /** Height above the feet, in blocks (WATUT's own value). */
    public static final double UP = 1.2;

    private EchoPanelPlacement() {}

    /**
     * The panel's position for a body at {@code (x, y, z)} with pitch {@code xRot} and body yaw
     * {@code yBodyRot} (degrees, Minecraft convention): {@code {px, py, pz}}.
     */
    public static double[] position(double x, double y, double z, float xRot, float yBodyRot) {
        double[] dir = viewVector(xRot, yBodyRot);
        return new double[] {x + dir[0] * FORWARD, y + UP + dir[1] * FORWARD, z + dir[2] * FORWARD};
    }

    /** Minecraft's {@code Entity.calculateViewVector}: the unit direction for a pitch and yaw. */
    static double[] viewVector(float xRot, float yRot) {
        double pitch = xRot * (Math.PI / 180.0);
        double yaw = -yRot * (Math.PI / 180.0);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        return new double[] {sinYaw * cosPitch, -sinPitch, cosYaw * cosPitch};
    }
}
