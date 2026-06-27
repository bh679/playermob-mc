package games.brennan.playermob.testutil;

import games.brennan.playermob.compat.ItemKindCompat;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * Test-harness probe for whether weapon/tool classification by item <em>type</em> is usable.
 *
 * <p>On MC 1.20.1 / 1.21.1 the classifiers in {@link ItemKindCompat} are {@code instanceof}-based
 * ({@code SwordItem}, {@code PickaxeItem}) and always work under a bare bootstrap. On MC 26.x those
 * tiered item classes were removed and classification is datapack-tag-driven
 * ({@code ItemTags.SWORDS}, {@code ItemTags.PICKAXES}). A unit-test harness only runs
 * {@code Bootstrap.bootStrap()}, which binds all tags to <em>empty</em> (no datapack reload), so a
 * known sword does not classify on 26.x.</p>
 *
 * <p>Tests that assert weapon-class behaviour call
 * {@code assumeTrue(WeaponClassification.available(), WeaponClassification.SKIP_REASON)} so they run
 * (and are meaningful) on the shipping versions and <em>skip</em> — rather than fail — on 26.x. The
 * same logic stays covered on 1.20.1 / 1.21.1. See the project's {@code unit-tests-no-tag-binding}
 * note. The probe is version-agnostic: if a future MC binds tags in the harness, the guarded tests
 * resume automatically.</p>
 */
public final class WeaponClassification {

    private WeaponClassification() {
    }

    public static final String SKIP_REASON =
        "Weapon/tool classification is datapack-tag-driven on MC 26.x and the bootstrap-only test "
        + "harness binds tags to EMPTY (no datapack reload), so a known sword does not classify. The "
        + "same logic is covered on 1.20.1/1.21.1 where classification is instanceof-based. See the "
        + "unit-tests-no-tag-binding note.";

    /** True when a known sword classifies as a sword in this test JVM. */
    public static boolean available() {
        return ItemKindCompat.isSword(new ItemStack(Items.IRON_SWORD));
    }
}
