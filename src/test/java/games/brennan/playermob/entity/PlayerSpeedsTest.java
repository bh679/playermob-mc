package games.brennan.playermob.entity;

import games.brennan.playermob.entity.PlayerSpeeds.Gait;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic oracle for {@link PlayerSpeeds} — gait selection, the player-stick input, the legacy
 * base-speed migration, and a physics check that the constants really do produce a player's speeds
 * under vanilla's movement maths. Primitives only, so no Minecraft bootstrap is needed (mirrors
 * {@link StayNearPolicyTest}).
 */
class PlayerSpeedsTest {

    private static final float EPS = 1.0e-6F;

    // Vanilla movement constants (LivingEntity.travel / jumpFromGround), for the physics oracle.
    private static final double INPUT_DAMPING = 0.98;
    private static final double GROUND_DRAG = 0.6 * 0.91;      // default block friction x air drag
    private static final double AIR_DRAG = 0.91;
    private static final double SPRINT_MODIFIER = 1.3;         // +30% multiply-total
    private static final double SPRINT_JUMP_BOOST = 0.2;
    private static final int JUMP_PERIOD_TICKS = 12;           // flat-ground jump, takeoff to landing

    @Test
    void everyLegacyMultiplierMapsToOneOfTwoGaits() {
        // The seven multipliers the goals used before the player-speed model.
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(0.5));
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(0.6));
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(0.9));
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(1.0));
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(1.3));
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(1.4));
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(4.0));
    }

    @Test
    void gaitBoundarySitsBetweenWalkAndSprint() {
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(PlayerSpeeds.WALK));
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(PlayerSpeeds.SPRINT));
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(1.19));
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(1.2));
        // Vanilla's strafe modifier — strafing is always a walk.
        assertEquals(Gait.WALK, PlayerSpeeds.gaitFor(0.25));
    }

    @Test
    void urgentSprintIsStillTheSprintGait() {
        assertEquals(Gait.SPRINT, PlayerSpeeds.gaitFor(PlayerSpeeds.URGENT_SPRINT));
        assertTrue(PlayerSpeeds.isUrgent(PlayerSpeeds.URGENT_SPRINT));
        assertFalse(PlayerSpeeds.isUrgent(PlayerSpeeds.SPRINT));
        assertFalse(PlayerSpeeds.isUrgent(PlayerSpeeds.WALK));
    }

    @Test
    void slowestReactorsNeverSprint() {
        for (int reaction = 0; reaction <= 1; reaction++) {
            assertFalse(PlayerSpeeds.allowsSprint(reaction, false), "reaction " + reaction);
            assertFalse(PlayerSpeeds.allowsSprint(reaction, true), "reaction " + reaction + ", urgent");
        }
    }

    @Test
    void slowReactorSprintsOnlyWhenUrgent() {
        assertFalse(PlayerSpeeds.allowsSprint(2, false));
        assertTrue(PlayerSpeeds.allowsSprint(2, true));
    }

    @Test
    void everyoneElseSprintsWhenAsked() {
        for (int reaction = 3; reaction <= 10; reaction++) {
            assertTrue(PlayerSpeeds.allowsSprint(reaction, false), "reaction " + reaction);
            assertTrue(PlayerSpeeds.allowsSprint(reaction, true), "reaction " + reaction + ", urgent");
        }
    }

    @Test
    void movingForwardIsFullStick() {
        float[] input = PlayerSpeeds.stickInput(1.0F, 0.0F, 1.0F, false);
        assertEquals(1.0F, input[0], EPS);
        assertEquals(0.0F, input[1], EPS);
    }

    @Test
    void vanillaSpeedScaledInputIsReplacedNotKept() {
        // Mob.setSpeed leaves forward = modifier x attribute (here 1.3 x 0.10); the magnitude must not survive.
        float[] input = PlayerSpeeds.stickInput(0.13F, 0.0F, 1.0F, false);
        assertEquals(1.0F, input[0], EPS);
    }

    @Test
    void strafeIsRescaledToUnitLength() {
        // Bow goals strafe with (0.5, 0.5) — a player holding W+D moves at full walk speed.
        float[] input = PlayerSpeeds.stickInput(0.5F, 0.5F, 1.0F, false);
        assertEquals(1.0, Math.hypot(input[0], input[1]), 1.0e-6);
        assertEquals(input[0], input[1], EPS);

        float[] back = PlayerSpeeds.stickInput(-0.5F, 0.5F, 1.0F, false);
        assertEquals(-input[0], back[0], EPS, "direction is preserved");
    }

    @Test
    void usingAnItemCutsInputToAFifth() {
        float[] forward = PlayerSpeeds.stickInput(1.0F, 0.0F, 1.0F, true);
        assertEquals(0.2F, forward[0], EPS);

        float[] strafe = PlayerSpeeds.stickInput(0.5F, -0.5F, 1.0F, true);
        assertEquals(0.2, Math.hypot(strafe[0], strafe[1]), 1.0e-6);
    }

    @Test
    void fullStickMagnitudeIsVersionSupplied() {
        // 26+ applies the 0.98 input damping before the AI step, so the control writes it itself.
        float[] input = PlayerSpeeds.stickInput(1.0F, 0.0F, 0.98F, false);
        assertEquals(0.98F, input[0], EPS);
    }

    @Test
    void noInputStaysNoInput() {
        float[] input = PlayerSpeeds.stickInput(0.0F, 0.0F, 1.0F, false);
        assertEquals(0.0F, input[0], EPS);
        assertEquals(0.0F, input[1], EPS);
    }

    @Test
    void legacyBaseSpeedMigratesOnce() {
        assertEquals(PlayerSpeeds.PLAYER_BASE_SPEED,
            PlayerSpeeds.migratedBaseSpeed(PlayerSpeeds.LEGACY_BASE_SPEED, false), 1.0e-9);
    }

    @Test
    void migratedOrCustomBaseSpeedsAreLeftAlone() {
        // Already saved under the new model: someone set 0.30 on purpose with /attribute.
        assertEquals(0.30, PlayerSpeeds.migratedBaseSpeed(0.30, true), 1.0e-9);
        // An unmarked save with a deliberate custom base.
        assertEquals(0.25, PlayerSpeeds.migratedBaseSpeed(0.25, false), 1.0e-9);
        // A fresh spawn (unmarked, already at the player base).
        assertEquals(0.10, PlayerSpeeds.migratedBaseSpeed(0.10, false), 1.0e-9);
    }

    // ---- Physics oracle: these constants under vanilla's maths give a player's speeds ----

    @Test
    void walkIsPlayerWalkSpeed() {
        assertEquals(4.317, groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED), 0.001);
    }

    @Test
    void sprintIsPlayerSprintSpeed() {
        assertEquals(5.612, groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED * SPRINT_MODIFIER), 0.001);
    }

    @Test
    void usingAnItemIsAFifthOfWalk() {
        double slowed = groundSpeedMetresPerSecond(PlayerSpeeds.PLAYER_BASE_SPEED) * PlayerSpeeds.USE_ITEM_INPUT;
        assertEquals(0.863, slowed, 0.001);
    }

    @Test
    void sprintJumpAveragesPlayerSprintJumpSpeed() {
        assertEquals(7.127, sprintJumpMetresPerSecond(PlayerSpeeds.SPRINT_FLYING_SPEED), 0.01);
    }

    @Test
    void withoutThePlayerFlyingSpeedASprintJumpFallsShort() {
        // The mob default (0.02 airborne, sprinting or not) is why getFlyingSpeed is overridden.
        assertEquals(6.25, sprintJumpMetresPerSecond(PlayerSpeeds.FLYING_SPEED), 0.05);
    }

    @Test
    void legacyBaseUnderFullStickWouldBeFarTooFast() {
        // Why the save migration matters: an unmigrated 0.30 base is three times a player's walk.
        assertEquals(12.95, groundSpeedMetresPerSecond(PlayerSpeeds.LEGACY_BASE_SPEED), 0.01);
    }

    /** Terminal ground speed for a full-stick input at the given speed attribute, in m/s. */
    private static double groundSpeedMetresPerSecond(double speedAttribute) {
        double accel = speedAttribute * INPUT_DAMPING;   // friction factor is 1.0 on default blocks
        return accel / (1.0 - GROUND_DRAG) * 20.0;
    }

    /** Steady-state average speed of held-jump sprint-jumping on flat ground, in m/s. */
    private static double sprintJumpMetresPerSecond(float flyingSpeed) {
        double groundAccel = PlayerSpeeds.PLAYER_BASE_SPEED * SPRINT_MODIFIER * INPUT_DAMPING;
        double airAccel = flyingSpeed * INPUT_DAMPING;
        double velocity = 0.0;
        double cycleDistance = 0.0;
        for (int cycle = 0; cycle < 50; cycle++) {              // long enough to converge
            cycleDistance = 0.0;
            for (int tick = 0; tick < JUMP_PERIOD_TICKS; tick++) {
                boolean takeoff = tick == 0;
                // The takeoff tick starts on the ground: sprint boost, ground accel, ground drag.
                velocity += takeoff ? SPRINT_JUMP_BOOST + groundAccel : airAccel;
                cycleDistance += velocity;
                velocity *= takeoff ? GROUND_DRAG : AIR_DRAG;
            }
        }
        return cycleDistance / JUMP_PERIOD_TICKS * 20.0;
    }
}
