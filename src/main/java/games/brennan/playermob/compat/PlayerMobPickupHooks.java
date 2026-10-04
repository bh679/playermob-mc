package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.util.function.Predicate;

/**
 * Optional-mod integration seam letting an integrating mod add items a PlayerMob should
 * <em>always</em> want off the floor and out of chests — the code-side twin of the
 * {@code extraPickupItems} config line. Dungeon Train uses it so its disposable camera (an
 * Exposure: Polaroid item PlayerMob cannot name) is picked up like any valuable.
 *
 * <p>Mirrors {@link PlayerMobSocialHooks}: the predicate defaults to "wants nothing" and is replaced
 * exactly once, at boot, by a consuming mod (from inside its {@code ModList.isLoaded("playermob")}
 * guard). A wanted item goes to the backpack through the same hoard path as config extras — it is
 * never equipped or placed. When no consumer is installed the check is a zero-cost no-op.</p>
 *
 * <p>Called from the pickup want-filter on the server thread. The dispatcher swallows predicate
 * exceptions (treating them as "not wanted") so a consumer fault can never break a pickup scan.</p>
 */
public final class PlayerMobPickupHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Predicate<ItemStack> wanted = stack -> false;

    private PlayerMobPickupHooks() {}

    /**
     * Install the active predicate. Called once during loader boot by a consuming mod; never called
     * on runs without such a mod, so the holder stays the wants-nothing default.
     */
    public static void install(Predicate<ItemStack> newWanted) {
        wanted = newWanted == null ? stack -> false : newWanted;
    }

    /** True if an installed consumer wants {@code stack}. Empty stacks are never wanted. */
    public static boolean wants(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return wanted.test(stack);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] pickup predicate threw; treating as not wanted", t);
            return false;
        }
    }
}
