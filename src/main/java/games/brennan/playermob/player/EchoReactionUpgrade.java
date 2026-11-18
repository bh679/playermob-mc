package games.brennan.playermob.player;

import games.brennan.playermob.entity.DispositionTraits;
import games.brennan.playermob.entity.ReactionChannel;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;

/**
 * Gives an <em>outdated</em> player-life snapshot random reaction speeds as it becomes an echo.
 *
 * <p>A snapshot is outdated when it has no {@link DispositionTraits#TAG_REACTION_SPEEDS} compound:
 * every death captured since per-skill reaction speeds existed writes one, so its absence means the
 * life predates that data. Before scoring existed at all its {@code ReactionSpeed} was a pinned 5
 * for everybody — a value that says nothing about the player — so rather than carry that forward,
 * the overall trait and every skill get an independent uniform roll in {@code [0, 10]}, the same
 * roll a wild PlayerMob gets.</p>
 *
 * <p>Only ever applied to a <em>copy</em> at the moment a stored life becomes an echo (spawn or
 * reincarnation egg): the stored record keeps what it had, and each echo of an old life rolls fresh.
 * Once spawned, the echo saves its rolled values with the entity, so it is the same echo after a
 * reload. Pure (no world) — see {@code EchoReactionUpgradeTest}.</p>
 */
public final class EchoReactionUpgrade {

    private EchoReactionUpgrade() {}

    /** True when {@code snapshot} predates per-skill reaction speeds. */
    public static boolean isOutdated(CompoundTag snapshot) {
        return !snapshot.contains(DispositionTraits.TAG_REACTION_SPEEDS);
    }

    /**
     * Return {@code snapshot} with random reaction speeds if it is outdated — a modified copy —
     * or {@code snapshot} itself, untouched, if it already carries per-skill values.
     */
    public static CompoundTag upgrade(CompoundTag snapshot, RandomSource random) {
        if (snapshot == null || !isOutdated(snapshot)) {
            return snapshot;
        }
        CompoundTag out = snapshot.copy();
        out.putInt(DispositionTraits.TAG_REACTION_SPEED, roll(random));
        CompoundTag channels = new CompoundTag();
        for (ReactionChannel channel : ReactionChannel.SPECIFIC) {
            channels.putInt(channel.key(), roll(random));
        }
        out.put(DispositionTraits.TAG_REACTION_SPEEDS, channels);
        return out;
    }

    private static int roll(RandomSource random) {
        return random.nextInt(DispositionTraits.MAX + 1);
    }
}
