package games.brennan.playermob.entity;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure-logic tests for {@link ModdedRangedWeapons} — the config-driven registry that lets a PlayerMob treat
 * modded firearms as ranged weapons. Uses vanilla item ids as stand-ins for modded ids (the parse + id-matching
 * rules are item-agnostic); tag matching needs a loaded datapack and is verified in-game, not here.
 *
 * <p>Same {@link Bootstrap} dance as {@link RangedAmmoTest}: registries must be bootstrapped before any
 * {@link Items} reference, so stacks are built inside the tests.</p>
 */
class ModdedRangedWeaponsTest {

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void blankOrNullYieldsEmpty() {
        assertSame(ModdedRangedWeapons.EMPTY, ModdedRangedWeapons.parse(null));
        assertSame(ModdedRangedWeapons.EMPTY, ModdedRangedWeapons.parse(""));
        assertSame(ModdedRangedWeapons.EMPTY, ModdedRangedWeapons.parse("   "));
        assertTrue(ModdedRangedWeapons.EMPTY.isEmpty());
    }

    @Test
    void parsesWeaponAmmoBinding() {
        ModdedRangedWeapons reg = ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow");
        assertEquals(1, reg.size());
        assertTrue(reg.isRangedWeapon(new ItemStack(Items.DIAMOND_SWORD)), "configured weapon is ranged");
        assertFalse(reg.isRangedWeapon(new ItemStack(Items.STICK)), "unconfigured item is not ranged");
        assertTrue(reg.ammoMatches(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.ARROW)), "bound ammo matches");
        assertFalse(reg.ammoMatches(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.STICK)), "other item is not its ammo");
    }

    @Test
    void ammoIsScopedToItsWeapon() {
        // Two firearms, each with distinct ammo — ammo must not cross-match.
        ModdedRangedWeapons reg = ModdedRangedWeapons.parse(
            "minecraft:diamond_sword>minecraft:arrow, minecraft:iron_sword>minecraft:snowball");
        assertEquals(2, reg.size());
        assertTrue(reg.ammoMatches(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.ARROW)));
        assertTrue(reg.ammoMatches(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.SNOWBALL)));
        assertFalse(reg.ammoMatches(new ItemStack(Items.DIAMOND_SWORD), new ItemStack(Items.SNOWBALL)),
            "sword-1 does not fire sword-2's ammo");
    }

    @Test
    void oneEntryCoversManyWeaponVariantsSharingAmmo() {
        // The weapon side may list several ids (space-separated) that all share one ammo — e.g. every MusketMod
        // firearm variant firing cartridges. Stand-in vanilla ids: three "guns", one "cartridge".
        ModdedRangedWeapons reg = ModdedRangedWeapons.parse(
            "minecraft:diamond_sword minecraft:iron_sword minecraft:golden_sword>minecraft:arrow");
        assertEquals(1, reg.size(), "one binding");
        assertTrue(reg.isRangedWeapon(new ItemStack(Items.DIAMOND_SWORD)), "variant 1 is ranged");
        assertTrue(reg.isRangedWeapon(new ItemStack(Items.IRON_SWORD)), "variant 2 is ranged");
        assertTrue(reg.isRangedWeapon(new ItemStack(Items.GOLDEN_SWORD)), "variant 3 is ranged");
        assertFalse(reg.isRangedWeapon(new ItemStack(Items.STONE_SWORD)), "unlisted variant is not ranged");
        assertTrue(reg.ammoMatches(new ItemStack(Items.IRON_SWORD), new ItemStack(Items.ARROW)),
            "every listed variant accepts the shared ammo");
    }

    @Test
    void defaultAndExplicitHoldTicks() {
        ModdedRangedWeapons def = ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow");
        assertEquals(ModdedRangedWeapons.DEFAULT_HOLD_TICKS, def.holdTicks(new ItemStack(Items.DIAMOND_SWORD)));

        ModdedRangedWeapons explicit = ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow>25");
        assertEquals(25, explicit.holdTicks(new ItemStack(Items.DIAMOND_SWORD)));

        // Unconfigured item falls back to the default hold.
        assertEquals(ModdedRangedWeapons.DEFAULT_HOLD_TICKS, explicit.holdTicks(new ItemStack(Items.STICK)));
    }

    @Test
    void holdTicksAreClamped() {
        assertEquals(5, ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow>0")
            .holdTicks(new ItemStack(Items.DIAMOND_SWORD)), "below min clamps up to 5");
        assertEquals(200, ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow>99999")
            .holdTicks(new ItemStack(Items.DIAMOND_SWORD)), "above max clamps down to 200");
        // Malformed hold falls back to the default, entry still parses.
        assertEquals(ModdedRangedWeapons.DEFAULT_HOLD_TICKS,
            ModdedRangedWeapons.parse("minecraft:diamond_sword>minecraft:arrow>abc")
                .holdTicks(new ItemStack(Items.DIAMOND_SWORD)));
    }

    @Test
    void malformedEntriesAreSkippedButOthersSurvive() {
        // "garbage" has no ammo half; "iron_sword>" has an empty ammo side — both dropped, the valid one stays.
        ModdedRangedWeapons reg = ModdedRangedWeapons.parse(
            "garbage, minecraft:iron_sword>, minecraft:diamond_sword>minecraft:arrow");
        assertEquals(1, reg.size());
        assertTrue(reg.isRangedWeapon(new ItemStack(Items.DIAMOND_SWORD)));
        assertFalse(reg.isRangedWeapon(new ItemStack(Items.IRON_SWORD)));
    }

    @Test
    void allJunkYieldsEmpty() {
        assertTrue(ModdedRangedWeapons.parse("garbage, alsojunk, >>>").isEmpty());
    }
}
