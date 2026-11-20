package games.brennan.playermob.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EchoPanelPlacementTest {

    private static final double EPS = 1e-9;

    @Test
    void facingSouthThePanelIsInFrontAndAboveTheFeet() {
        // Yaw 0 faces +Z (south) in Minecraft.
        double[] p = EchoPanelPlacement.position(10, 64, -3, 0F, 0F);
        assertEquals(10.0, p[0], EPS);
        assertEquals(64 + EchoPanelPlacement.UP, p[1], EPS);
        assertEquals(-3 + EchoPanelPlacement.FORWARD, p[2], EPS);
    }

    @Test
    void itTurnsWithTheBody() {
        // Yaw 90 faces -X (west); yaw 180 faces -Z (north).
        double[] west = EchoPanelPlacement.position(0, 0, 0, 0F, 90F);
        assertEquals(-EchoPanelPlacement.FORWARD, west[0], EPS);
        assertEquals(0.0, west[2], EPS);
        double[] north = EchoPanelPlacement.position(0, 0, 0, 0F, 180F);
        assertEquals(-EchoPanelPlacement.FORWARD, north[2], EPS);
    }

    @Test
    void lookingDownLowersAndPullsItIn() {
        double[] level = EchoPanelPlacement.position(0, 0, 0, 0F, 0F);
        double[] down = EchoPanelPlacement.position(0, 0, 0, 30F, 0F);
        assertEquals(EchoPanelPlacement.UP - EchoPanelPlacement.FORWARD * Math.sin(Math.toRadians(30)), down[1], EPS);
        assertEquals(EchoPanelPlacement.FORWARD * Math.cos(Math.toRadians(30)), down[2], EPS);
        assertEquals(true, down[1] < level[1]);
    }
}
