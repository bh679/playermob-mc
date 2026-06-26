package games.brennan.playermob.skin;

import java.util.function.DoubleSupplier;
import java.util.function.IntUnaryOperator;

/**
 * Pure decision of which skin source a freshly-spawned PlayerMob draws from, honouring the three
 * per-source toggles ({@code bundled} / {@code online} / {@code local}). Extracted from
 * {@code PlayerMobEntity.rollSpawnDefaults} so the toggle behaviour is unit-tested without the entity
 * or the filesystem, mirroring the {@code DispositionResolver} pattern.
 *
 * <p>Bundled remains the base look (and the downgrade-safe payload for old clients). With probability
 * {@code customChance} the roll overrides it with a "custom" skin picked uniformly across the combined
 * enabled pool — online registry entries first, then local-folder names. Two rules keep "all sources
 * on by default" intuitive while still letting a player narrow it down:</p>
 * <ul>
 *   <li>If {@code bundled} is <em>off</em> but a custom pool exists, the override is forced (a mob never
 *       shows a bundled skin while alternatives exist).</li>
 *   <li>If the combined custom pool is empty (both sources off, or simply nothing loaded), the mob keeps
 *       its bundled skin regardless of the toggles — a mob always has <em>some</em> skin.</li>
 * </ul>
 */
public final class SkinSourceSelector {

    /** Which source the spawn roll chose. */
    public enum Kind { BUNDLED, ONLINE, LOCAL }

    /** The chosen source plus the index into that source's list ({@code index} is unused for BUNDLED). */
    public record Choice(Kind kind, int index) {}

    private SkinSourceSelector() {}

    /**
     * @param bundledEnabled whether the bundled default skins may be chosen
     * @param onlineEnabled  whether the online registry pool may be chosen
     * @param localEnabled   whether the local-folder pool may be chosen
     * @param onlineCount    number of online registry entries currently available
     * @param localCount     number of local-folder skins currently available
     * @param customChance   probability (0–1) of overriding the bundled base when a custom pool exists
     * @param rngBound       {@code bound -> [0, bound)} uniform int source (e.g. {@code random::nextInt})
     * @param rngFloat       {@code () -> [0, 1)} uniform source (e.g. {@code random::nextFloat})
     * @return the chosen source and (for ONLINE/LOCAL) the index within that source's list
     */
    public static Choice choose(boolean bundledEnabled, boolean onlineEnabled, boolean localEnabled,
                                int onlineCount, int localCount, float customChance,
                                IntUnaryOperator rngBound, DoubleSupplier rngFloat) {
        int onlineN = onlineEnabled ? Math.max(0, onlineCount) : 0;
        int localN = localEnabled ? Math.max(0, localCount) : 0;
        int total = onlineN + localN;
        if (total == 0) {
            return new Choice(Kind.BUNDLED, 0);
        }
        boolean override = !bundledEnabled || rngFloat.getAsDouble() < customChance;
        if (!override) {
            return new Choice(Kind.BUNDLED, 0);
        }
        int pick = rngBound.applyAsInt(total);
        if (pick < onlineN) {
            return new Choice(Kind.ONLINE, pick);
        }
        return new Choice(Kind.LOCAL, pick - onlineN);
    }
}
