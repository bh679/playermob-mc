package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.RandomSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link FlintAndSteelPolicy}. The rate window, the last-hit prediction and the
 * randomized wind-ups are fully pure; the item lookups need vanilla registries, so we run the same
 * {@link Bootstrap} dance as {@code ItemPickupPolicyTest}. The live-entity paths
 * ({@code wantsCookFinisher} / {@code wantsCombatIgnite} against a real animal) need spawned mobs and
 * are covered by the in-game Gate 2 smoke test instead.
 */
class FlintAndSteelPolicyTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void identifiesFlintAndSteel() {
        assertTrue(FlintAndSteelPolicy.isFlintAndSteel(new ItemStack(Items.FLINT_AND_STEEL)), "flint and steel");
        assertFalse(FlintAndSteelPolicy.isFlintAndSteel(new ItemStack(Items.FIRE_CHARGE)),
            "fire charge is a throwable, not a ground-lighter");
        assertFalse(FlintAndSteelPolicy.isFlintAndSteel(ItemStack.EMPTY), "empty hand");
    }

    @Test
    void findsFlintAndSteelInPackOrHand() {
        SimpleContainer pack = new SimpleContainer(9);
        assertEquals(-1, FlintAndSteelPolicy.firstSlot(pack), "empty pack");
        assertFalse(FlintAndSteelPolicy.hasFlintAndSteel(pack, ItemStack.EMPTY), "nothing anywhere");

        // In hand only — an operator-placed tool still counts.
        assertTrue(FlintAndSteelPolicy.hasFlintAndSteel(pack, new ItemStack(Items.FLINT_AND_STEEL)), "in hand");

        pack.setItem(3, new ItemStack(Items.FLINT_AND_STEEL));
        assertEquals(3, FlintAndSteelPolicy.firstSlot(pack), "found in pack");
        assertTrue(FlintAndSteelPolicy.hasFlintAndSteel(pack, ItemStack.EMPTY), "in pack, empty hand");
    }

    @Test
    void lightsOnlyWhenTheNextSwingWouldFinishIt() {
        // 3.0 damage is the PlayerMob's base ATTACK_DAMAGE.
        assertTrue(FlintAndSteelPolicy.finisherHealthReached(2.0f, 3.0), "one swing kills it");
        assertTrue(FlintAndSteelPolicy.finisherHealthReached(3.0f, 3.0), "exactly lethal");
        assertFalse(FlintAndSteelPolicy.finisherHealthReached(4.0f, 3.0), "would survive the swing");
        // A better weapon widens the window: a cow (10 HP) is finishable with a diamond sword.
        assertTrue(FlintAndSteelPolicy.finisherHealthReached(10.0f, 11.0), "heavy weapon");
    }

    @Test
    void allowsFiveLightsPerWindowThenRefuses() {
        long[] window = FlintAndSteelPolicy.newRateWindow();
        long now = 1_000L;
        for (int i = 0; i < FlintAndSteelPolicy.RATE_MAX_IGNITES; i++) {
            assertTrue(FlintAndSteelPolicy.withinRate(window, now), "light " + (i + 1) + " of 5 allowed");
            window = FlintAndSteelPolicy.recordIgnite(window, now);
        }
        assertFalse(FlintAndSteelPolicy.withinRate(window, now), "6th light in the same window refused");
        // Still refused just inside the window …
        assertFalse(FlintAndSteelPolicy.withinRate(window, now + FlintAndSteelPolicy.RATE_WINDOW_TICKS - 1),
            "one tick short of the window sliding");
        // … and allowed again the moment the oldest stamp ages out.
        assertTrue(FlintAndSteelPolicy.withinRate(window, now + FlintAndSteelPolicy.RATE_WINDOW_TICKS),
            "window slid past the oldest light");
    }

    @Test
    void ratesLightsIndependentlyAsTheWindowSlides() {
        long[] window = FlintAndSteelPolicy.newRateWindow();
        // Four lights spread over the window, then a fifth much later: budget is never exhausted.
        for (int i = 0; i < 4; i++) {
            window = FlintAndSteelPolicy.recordIgnite(window, i * 50L);
        }
        assertTrue(FlintAndSteelPolicy.withinRate(window, 150L), "4 recent lights still leaves one");
        window = FlintAndSteelPolicy.recordIgnite(window, 150L);
        assertFalse(FlintAndSteelPolicy.withinRate(window, 150L), "5 within 200 ticks is the cap");
        assertTrue(FlintAndSteelPolicy.withinRate(window, 260L), "the tick-0 light has aged out");
    }

    @Test
    void recordIgniteDoesNotMutateTheWindowItWasGiven() {
        long[] original = FlintAndSteelPolicy.newRateWindow();
        long[] snapshot = original.clone();
        long[] updated = FlintAndSteelPolicy.recordIgnite(original, 42L);
        org.junit.jupiter.api.Assertions.assertArrayEquals(snapshot, original, "input array untouched");
        assertEquals(42L, updated[updated.length - 1], "new array carries the stamp");
    }

    @Test
    void randomWindUpsStayInsideTheirStatedRanges() {
        RandomSource random = RandomSource.create(1234L);
        for (int i = 0; i < 500; i++) {
            int swap = FlintAndSteelPolicy.swapDelayTicks(random);
            assertTrue(swap >= FlintAndSteelPolicy.SWAP_MIN_TICKS && swap <= FlintAndSteelPolicy.SWAP_MAX_TICKS,
                "swap delay in 4..20, got " + swap);

            int burn = FlintAndSteelPolicy.burnTicks(random);
            assertTrue(burn >= FlintAndSteelPolicy.BURN_MIN_TICKS && burn <= FlintAndSteelPolicy.BURN_MAX_TICKS,
                "burn ticks in 3..40, got " + burn);

            int gap = FlintAndSteelPolicy.combatGapTicks(random);
            assertTrue(gap >= FlintAndSteelPolicy.COMBAT_GAP_MIN_TICKS
                    && gap <= FlintAndSteelPolicy.COMBAT_GAP_MAX_TICKS,
                "combat gap in 40..120, got " + gap);
        }
    }

    @Test
    void windUpRangesMatchTheSpecifiedBounds() {
        assertEquals(4, FlintAndSteelPolicy.SWAP_MIN_TICKS);
        assertEquals(20, FlintAndSteelPolicy.SWAP_MAX_TICKS);
        assertEquals(3, FlintAndSteelPolicy.BURN_MIN_TICKS);
        assertEquals(40, FlintAndSteelPolicy.BURN_MAX_TICKS);
        assertEquals(5, FlintAndSteelPolicy.RATE_MAX_IGNITES);
        assertEquals(200, FlintAndSteelPolicy.RATE_WINDOW_TICKS);
    }
}
