package games.brennan.playermob.player;

import com.mojang.logging.LogUtils;
import games.brennan.playermob.compat.TrainConfinement;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.LecternMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
//?}

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Game-side glue for the Reading signal: turns item uses, inputs and the per-player tick into
 * {@link ReadingTracker} calls, and routes the tracker's updates into the player's live
 * {@link PlayerLifeRecord} via {@link PlayerLifeStore#creditReading}. Called from
 * {@link ReflexHooks} (inputs, the per-player tick, death) and from the reflex packet mixin's
 * use-in-air hook ({@link #onUseItem}), so reading adds no mixin of its own.
 *
 * <p>Server-side and survival-only, like the reflex hooks. Failure-isolated: a bug here must never
 * break item use or the player tick, so each entry point catches and logs.</p>
 */
public final class ReadingHooks {

    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Map<UUID, ReadingTracker> TRACKERS = new HashMap<>();
    /** The server the trackers belong to — a new (singleplayer) world starts them fresh. */
    private static MinecraftServer owner;

    private ReadingHooks() {}

    private static boolean counts(Entity e) {
        return e instanceof ServerPlayer sp && !sp.isCreative() && !sp.isSpectator() && sp.isAlive();
    }

    private static int tick(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        return server == null ? 0 : server.getTickCount();
    }

    private static synchronized ReadingTracker tracker(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server != owner) {
            TRACKERS.clear();
            owner = server;
        }
        // Resolve the store from the server on every update, never hold this ServerPlayer.
        return TRACKERS.computeIfAbsent(player.getUUID(),
            id -> new ReadingTracker(update -> {
                if (server != null) {
                    PlayerLifeStore.get(server.overworld()).creditReading(id, update);
                }
            }));
    }

    // ---- book identity ----------------------------------------------------

    /** True for a stack whose screen is a book to read: a written book or a book &amp; quill. */
    public static boolean isReadable(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(Items.WRITTEN_BOOK) || stack.is(Items.WRITABLE_BOOK));
    }

    /**
     * A hash of the book's text — title, author and pages — so two copies of one book are the same
     * book and an edited draft is a new one. Built from plain strings, so it is stable across
     * restarts.
     */
    public static int bookHash(ItemStack stack) {
        StringBuilder text = new StringBuilder(stack.is(Items.WRITTEN_BOOK) ? "W|" : "Q|");
        //? if >=1.21.1 {
        WrittenBookContent written = stack.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            text.append(written.title().raw()).append('|').append(written.author()).append('|');
            for (Filterable<Component> page : written.pages()) {
                text.append(page.raw().getString()).append('\u0001');
            }
        }
        WritableBookContent writable = stack.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable != null) {
            for (Filterable<String> page : writable.pages()) {
                text.append(page.raw()).append('\u0001');
            }
        }
        //?} else {
        /*net.minecraft.nbt.CompoundTag tag = stack.getTag();
        if (tag != null) {
            text.append(tag.getString("title")).append('|').append(tag.getString("author")).append('|');
            net.minecraft.nbt.ListTag pages = tag.getList("pages", net.minecraft.nbt.Tag.TAG_STRING);
            for (int i = 0; i < pages.size(); i++) {
                text.append(pages.getString(i)).append('\u0001');
            }
        }*/
        //?}
        return text.toString().hashCode();
    }

    // ---- hooks ------------------------------------------------------------

    /** One deliberate input from the player: a held-book screen can't be open, so that session ends. */
    public static void onAction(Player actor, ReflexTracker.ActionKind kind) {
        if (!counts(actor)) {
            return;
        }
        try {
            ServerPlayer player = (ServerPlayer) actor;
            tracker(player).onAction(kind == ReflexTracker.ActionKind.SWING, tick(player));
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reading action hook failed", e);
        }
    }

    /**
     * The player used the item in hand in the air — the packet that opens a written book or book
     * &amp; quill. With a readable book in the used hand, that opens a held-book session. (A use on a
     * block never comes here: right-clicking a chest with a book in hand isn't reading it.)
     */
    public static void onUseItem(Player actor) {
        if (!counts(actor)) {
            return;
        }
        try {
            ServerPlayer player = (ServerPlayer) actor;
            // The main hand is used first; the offhand book only opens when the main hand is empty.
            ItemStack main = player.getMainHandItem();
            ReadingTracker.Hand hand = isReadable(main) ? ReadingTracker.Hand.MAIN
                : main.isEmpty() && isReadable(player.getOffhandItem()) ? ReadingTracker.Hand.OFF
                : null;
            if (hand != null) {
                ItemStack book = hand == ReadingTracker.Hand.MAIN ? main : player.getOffhandItem();
                tracker(player).onOpenHeldBook(bookHash(book), hand, player.getYRot(), player.getXRot(),
                    player.getX(), player.getY(), player.getZ(), tick(player));
            }
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reading use hook failed", e);
        }
    }

    /** Once a second (called from {@link ReflexHooks#onPlayerTick}): sample lectern + held book. */
    public static void onSecond(ServerPlayer player) {
        if (!counts(player)) {
            return;
        }
        try {
            ReadingTracker t = tracker(player);
            Integer lecternBook = null;
            boolean otherMenu = false;
            if (player.containerMenu instanceof LecternMenu lectern) {
                ItemStack book = lectern.getBook();
                lecternBook = book.isEmpty() ? null : bookHash(book);
            } else if (player.containerMenu != player.inventoryMenu) {
                otherMenu = true;
            }
            t.sampleLectern(lecternBook);

            ReadingTracker.Hand hand = isReadable(player.getMainHandItem()) ? ReadingTracker.Hand.MAIN
                : isReadable(player.getOffhandItem()) ? ReadingTracker.Hand.OFF : null;
            Integer heldBook = hand == null ? null
                : bookHash(hand == ReadingTracker.Hand.MAIN ? player.getMainHandItem() : player.getOffhandItem());
            // On a train the carriage carries the player along; only the look direction is reliable.
            boolean checkPosition = TrainConfinement.carriageIndex(player) == ReflexTracker.NO_CARRIAGE;
            t.sampleHeld(heldBook, hand, otherMenu || lecternBook != null, player.getYRot(), player.getXRot(),
                player.getX(), player.getY(), player.getZ(), checkPosition);
            // Books that left: put away into any open menu (a chest, a lectern) doesn't count.
            t.sampleInventory(carriedBooks(player), player.containerMenu != player.inventoryMenu);
        } catch (RuntimeException e) {
            LOGGER.error("[playermob] reading tick hook failed", e);
        }
    }

    /**
     * Hashes of every book worth reading the player carries — inventory, offhand and the stack on
     * their cursor (a book mid-move in the inventory screen hasn't left them). A blank book &amp;
     * quill has nothing to read, so leaving it behind says nothing.
     */
    static Set<Integer> carriedBooks(ServerPlayer player) {
        Set<Integer> out = new HashSet<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            addIfBook(out, inv.getItem(i));
        }
        addIfBook(out, player.containerMenu.getCarried());
        return out;
    }

    private static void addIfBook(Set<Integer> out, ItemStack stack) {
        if (stack.is(Items.WRITTEN_BOOK) || DraftBooks.isDraft(stack)) {
            out.add(bookHash(stack));
        }
    }

    /** The player died: close any session they were in the middle of, then forget them. */
    public static void onPlayerDeath(ServerPlayer player) {
        ReadingTracker t;
        synchronized (ReadingHooks.class) {
            t = TRACKERS.remove(player.getUUID());
        }
        if (t != null) {
            try {
                t.endAll();
            } catch (RuntimeException e) {
                LOGGER.error("[playermob] reading death flush failed", e);
            }
        }
    }
}
