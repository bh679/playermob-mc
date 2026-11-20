package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Game-side glue for the Writing signal: book saves and signatures (from
 * {@code ServerGameBookWritingMixin}) and the per-second blank-quill count (from
 * {@link ReadingHooks#onSecond}) into {@link WritingTracker}, whose updates land in the player's
 * live {@link PlayerLifeRecord} via {@link PlayerLifeStore#creditWriting}.
 *
 * <p>Server-side and survival-only, like the reading hooks. Failure-isolated: a bug here must never
 * stop a player saving or signing a book.</p>
 */
public final class WritingHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, WritingTracker> TRACKERS = new HashMap<>();
    private static MinecraftServer owner;

    private WritingHooks() {}

    private static boolean counts(Entity e) {
        return e instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator() && sp.isAlive();
    }

    private static synchronized WritingTracker tracker(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != owner) {
            TRACKERS.clear();
            owner = server;
        }
        return TRACKERS.computeIfAbsent(player.getUUID(),
            id -> new WritingTracker(update -> {
                if (server != null) {
                    PlayerLifeStore.get(server.overworld()).creditWriting(id, update);
                }
            }));
    }

    private static ItemStack bookInSlot(ServerPlayer player, int slot) {
        return slot == 40 ? player.getOffhandItem() : player.getInventory().getItem(slot);
    }

    /** The player is saving {@code pages} into the book &amp; quill in {@code slot} (before it's applied). */
    public static void onBookSaved(ServerPlayer player, List<String> pages, int slot) {
        if (!counts(player)) return;
        try {
            tracker(player).onBookSaved(DraftBooks.pagesOf(bookInSlot(player, slot)), pages);
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] writing save hook failed", e);
        }
    }

    /** The player is signing the book &amp; quill in {@code slot} (before it's applied). */
    public static void onSigned(ServerPlayer player, int slot) {
        if (!counts(player)) return;
        try {
            tracker(player).onSigned(!DraftBooks.isDraft(bookInSlot(player, slot)));
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] writing sign hook failed", e);
        }
    }

    /** Once a second (from {@link ReadingHooks#onSecond}): count blank quills the player carries. */
    public static void onSecond(ServerPlayer player) {
        if (!counts(player)) return;
        try {
            int blank = 0;
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (isBlankQuill(inv.getItem(i))) blank++;
            }
            if (isBlankQuill(player.containerMenu.getCarried())) blank++;
            tracker(player).sampleBlank(blank, player.containerMenu != player.inventoryMenu);
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] writing tick hook failed", e);
        }
    }

    private static boolean isBlankQuill(ItemStack stack) {
        return stack.is(Items.WRITABLE_BOOK) && !DraftBooks.isDraft(stack);
    }

    /** The player died: forget their live writing state. */
    public static void onPlayerDeath(ServerPlayer player) {
        synchronized (WritingHooks.class) {
            TRACKERS.remove(player.getUUID());
        }
    }
}
