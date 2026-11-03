package games.brennan.playermob.entity;

import games.brennan.playermob.testutil.WeaponClassification;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Pure-logic tests for {@link CarriedGear#pick} — which backpack items show where on the body.
 * Sword and digging-tool cases skip on MC 26.x, where they classify by datapack tag (see
 * {@link WeaponClassification}); shields, banners, arrows, bows and tridents classify by class
 * everywhere.
 */
class CarriedGearTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    private static List<ItemStack> bag(Item... items) {
        return Arrays.stream(items).map(ItemStack::new).toList();
    }

    // MC 26.x folds the 16 dyed variants into a ColorCollection (Items.BANNER.white()).
    private static Item whiteBanner() {
        //? if >=26 {
        /*return Items.BANNER.white();
        *///?} else {
        return Items.WHITE_BANNER;
        //?}
    }

    private static Item itemAt(CarriedGear.Loadout loadout, CarriedGear.Spot spot) {
        return loadout.at(spot).getItem();
    }

    @Test
    void emptyBagShowsNothing() {
        CarriedGear.Loadout loadout = CarriedGear.pick(List.of(ItemStack.EMPTY, ItemStack.EMPTY));
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            assertTrue(loadout.at(spot).isEmpty(), spot + " should be empty");
        }
        assertEquals(CarriedGear.Quiver.NONE, loadout.quiver());
    }

    @Test
    void shieldAndBannerTakeTheirOwnSpots() {
        CarriedGear.Loadout loadout = CarriedGear.pick(bag(whiteBanner(), Items.SHIELD, Items.SHIELD));
        assertSame(Items.SHIELD, itemAt(loadout, CarriedGear.Spot.SHIELD));
        assertSame(whiteBanner(), itemAt(loadout, CarriedGear.Spot.BANNER));
        assertTrue(loadout.at(CarriedGear.Spot.BACK_RIGHT).isEmpty(), "a second shield goes nowhere");
    }

    @Test
    void rangedWeaponsAndTridentsFillTheBackInSlotOrder() {
        CarriedGear.Loadout loadout = CarriedGear.pick(bag(Items.BOW, Items.TRIDENT, Items.CROSSBOW));
        assertSame(Items.BOW, itemAt(loadout, CarriedGear.Spot.BACK_RIGHT));
        assertSame(Items.TRIDENT, itemAt(loadout, CarriedGear.Spot.BACK_LEFT));
        // Third back weapon has nowhere to go.
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            assertTrue(loadout.at(spot).getItem() != Items.CROSSBOW, "crossbow shown at " + spot);
        }
    }

    @Test
    void swordsGoOnTheBackAndToolsAtTheHips() {
        assumeTrue(WeaponClassification.available(), WeaponClassification.SKIP_REASON);
        CarriedGear.Loadout loadout = CarriedGear.pick(
            bag(Items.IRON_PICKAXE, Items.DIAMOND_SWORD, Items.STONE_AXE, Items.IRON_SHOVEL));
        assertSame(Items.DIAMOND_SWORD, itemAt(loadout, CarriedGear.Spot.BACK_RIGHT));
        assertTrue(loadout.at(CarriedGear.Spot.BACK_LEFT).isEmpty());
        assertSame(Items.IRON_PICKAXE, itemAt(loadout, CarriedGear.Spot.HIP_RIGHT));
        assertSame(Items.STONE_AXE, itemAt(loadout, CarriedGear.Spot.HIP_LEFT));
    }

    @Test
    void foodAndBlocksAreNotShown() {
        CarriedGear.Loadout loadout = CarriedGear.pick(bag(Items.BREAD, Items.COBBLESTONE, Items.TORCH));
        assertEquals(CarriedGear.NONE, loadout);
    }

    @Test
    void anyArrowsFillTheQuiverAndSpectralWins() {
        assertEquals(CarriedGear.Quiver.ARROWS, CarriedGear.pick(bag(Items.TIPPED_ARROW)).quiver());
        assertEquals(CarriedGear.Quiver.SPECTRAL,
            CarriedGear.pick(bag(Items.ARROW, Items.SPECTRAL_ARROW)).quiver());
        assertEquals(CarriedGear.Quiver.SPECTRAL,
            CarriedGear.pick(bag(Items.SPECTRAL_ARROW, Items.ARROW)).quiver());
    }

    @Test
    void shownCopiesAreSingleItemsAndLeaveTheBagAlone() {
        ItemStack banners = new ItemStack(whiteBanner(), 16);
        CarriedGear.Loadout loadout = CarriedGear.pick(List.of(banners));
        assertEquals(1, loadout.at(CarriedGear.Spot.BANNER).getCount());
        assertEquals(16, banners.getCount(), "the backpack stack is not touched");
        assertThrows(UnsupportedOperationException.class,
            () -> loadout.spots().set(0, ItemStack.EMPTY));
    }

    private static int shownCount(CarriedGear.Loadout loadout) {
        int count = loadout.quiver() == CarriedGear.Quiver.NONE ? 0 : 1;
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            if (!loadout.at(spot).isEmpty()) {
                count++;
            }
        }
        return count;
    }

    // Six gear items that classify by class on every version, each with its own spot/quiver room:
    // two back weapons, shield, banner, arrows — plus a third back weapon that can never show.
    private static List<ItemStack> mixedBag() {
        return bag(Items.BREAD, Items.BOW, Items.SHIELD, Items.COBBLESTONE, Items.TRIDENT,
            whiteBanner(), Items.ARROW);
    }

    @Test
    void limitZeroShowsNothing() {
        assertEquals(CarriedGear.NONE, CarriedGear.pick(mixedBag(), 0, 42L));
    }

    @Test
    void noLimitMatchesThePlainPick() {
        List<ItemStack> bag = mixedBag();
        CarriedGear.Loadout all = CarriedGear.pick(bag, CarriedGear.NO_LIMIT, 42L);
        assertEquals(5, shownCount(all));
        assertEquals(shownCount(CarriedGear.pick(bag)), shownCount(all));
    }

    @Test
    void limitShowsExactlyThatManyGearItemsAndIgnoresJunk() {
        List<ItemStack> bag = mixedBag();
        for (long seed = 0; seed < 50; seed++) {
            for (int limit = 1; limit <= 5; limit++) {
                assertEquals(limit, shownCount(CarriedGear.pick(bag, limit, seed)),
                    "limit " + limit + " seed " + seed);
            }
            assertEquals(5, shownCount(CarriedGear.pick(bag, 8, seed)), "limit above the gear count");
        }
    }

    @Test
    void sameSeedPicksTheSameItemsAndSeedsVary() {
        List<ItemStack> bag = mixedBag();
        CarriedGear.Loadout first = CarriedGear.pick(bag, 2, 7L);
        CarriedGear.Loadout again = CarriedGear.pick(bag, 2, 7L);
        for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
            assertSame(first.at(spot).getItem(), again.at(spot).getItem(), spot.name());
        }
        assertEquals(first.quiver(), again.quiver());

        java.util.Set<String> picks = new java.util.HashSet<>();
        for (long seed = 0; seed < 64; seed++) {
            CarriedGear.Loadout loadout = CarriedGear.pick(bag, 1, seed);
            StringBuilder key = new StringBuilder(loadout.quiver().name());
            for (CarriedGear.Spot spot : CarriedGear.Spot.values()) {
                key.append(',').append(loadout.at(spot).getItem());
            }
            picks.add(key.toString());
        }
        assertTrue(picks.size() >= 4, "one-item picks should vary with the seed, got " + picks);
    }

    @Test
    void quiverOrdinalRoundTripsAndRejectsJunk() {
        for (CarriedGear.Quiver quiver : CarriedGear.Quiver.values()) {
            assertEquals(quiver, CarriedGear.Quiver.fromOrdinal(quiver.ordinal()));
        }
        assertEquals(CarriedGear.Quiver.NONE, CarriedGear.Quiver.fromOrdinal(-1));
        assertEquals(CarriedGear.Quiver.NONE, CarriedGear.Quiver.fromOrdinal(99));
    }
}
