package games.brennan.playermob.compat;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.server.level.ServerPlayer;
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
 *
 * <p>The <b>floor-gift</b> want ({@link #installFloorGift}) is the narrower, context-aware twin: it is
 * asked only about an item on the floor that a player threw, and sees the mob and the thrower, so a
 * consumer can want an item <em>only from this player, right now</em>. Dungeon Train uses it so a mob
 * takes a camera only when it will photograph whoever threw it. Never consulted for chests or armour
 * stands (no thrower there).</p>
 */
public final class PlayerMobPickupHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static volatile Predicate<ItemStack> wanted = stack -> false;

    /** Wants an item on the floor because of who threw it, at this moment, to this mob. */
    @FunctionalInterface
    public interface FloorGiftWant {
        boolean wants(PlayerMobEntity mob, ServerPlayer thrower, ItemStack stack);
    }

    private static final FloorGiftWant NO_FLOOR_GIFT = (mob, thrower, stack) -> false;

    private static volatile FloorGiftWant floorGift = NO_FLOOR_GIFT;

    private PlayerMobPickupHooks() {}

    /** Install the floor-gift want. Called once during loader boot by a consuming mod. */
    public static void installFloorGift(FloorGiftWant newWant) {
        floorGift = newWant == null ? NO_FLOOR_GIFT : newWant;
    }

    /**
     * True if an installed consumer wants {@code stack}, thrown by {@code thrower}, picked up by
     * {@code mob}. No thrower (an ownerless drop, a mob's own drop) or an empty stack is never wanted.
     */
    public static boolean wantsFloorGift(PlayerMobEntity mob, ServerPlayer thrower, ItemStack stack) {
        if (mob == null || thrower == null || stack == null || stack.isEmpty()) {
            return false;
        }
        try {
            return floorGift.wants(mob, thrower, stack);
        } catch (Throwable t) {
            LOGGER.warn("[playermob] floor-gift predicate threw; treating as not wanted", t);
            return false;
        }
    }

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
