package games.brennan.playermob.mixin;

import games.brennan.playermob.player.WritingHooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.FilteredText;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Writing signal: a player saving text into a book &amp; quill, or signing one. Hooks vanilla's two
 * private appliers, which run on the server thread once the edit packet's text has been filtered,
 * at {@code HEAD} — before the item changes, so the old text is still there to compare.
 *
 * <p>Priority 900 (ahead of the default 1000) so the signature is seen even when another mod's
 * sign hook (Dungeon Train's community-book signing) cancels vanilla's own.</p>
 */
@Mixin(value = ServerGamePacketListenerImpl.class, priority = 900)
public abstract class ServerGameBookWritingMixin {

    @Shadow public ServerPlayer player;

    @Inject(method = "updateBookContents", at = @At("HEAD"))
    private void playermob$bookSaved(List<FilteredText> pages, int slot, CallbackInfo ci) {
        List<String> raw = new ArrayList<>(pages.size());
        for (FilteredText page : pages) {
            raw.add(page.raw());
        }
        WritingHooks.onBookSaved(player, raw, slot);
    }

    @Inject(method = "signBook", at = @At("HEAD"))
    private void playermob$bookSigned(FilteredText title, List<FilteredText> pages, int slot, CallbackInfo ci) {
        WritingHooks.onSigned(player, slot);
    }
}
