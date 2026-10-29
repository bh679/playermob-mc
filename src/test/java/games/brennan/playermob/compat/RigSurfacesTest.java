package games.brennan.playermob.compat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Dispatch tests for the {@link RigSurfaces} seam. {@code null} mobs stand in for real entities (no
 * Minecraft bootstrap needed); the world surface and the live rig are covered by the in-game Gate 2 test.
 */
class RigSurfacesTest {

    /** Reset the global holder so one test's install never leaks into the next. */
    @AfterEach
    void resetProvider() {
        RigSurfaces.install(new RigSurfaces.Provider() {});
    }

    @Test
    void defaultProviderBuildsInTheWorld() {
        assertNull(RigSurfaces.surfaceFor(null));
    }

    @Test
    void installedProviderIsAskedOncePerRig() {
        AtomicInteger calls = new AtomicInteger();
        RigSurfaces.RigSurface fixed = new RigSurfaces.WorldSurface(null);
        RigSurfaces.install(new RigSurfaces.Provider() {
            @Override
            public RigSurfaces.RigSurface surfaceFor(net.minecraft.world.entity.Mob mob) {
                calls.incrementAndGet();
                return fixed;
            }
        });
        assertSame(fixed, RigSurfaces.surfaceFor(null));
        assertEquals(1, calls.get());
    }

    @Test
    void throwingProviderFallsBackToTheWorld() {
        RigSurfaces.install(new RigSurfaces.Provider() {
            @Override
            public RigSurfaces.RigSurface surfaceFor(net.minecraft.world.entity.Mob mob) {
                throw new IllegalStateException("boom");
            }
        });
        assertNull(RigSurfaces.surfaceFor(null));
    }
}
