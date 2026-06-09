package games.brennan.playermob.entity.goal;

/**
 * A {@link net.minecraft.world.entity.ai.goal.Goal} that can describe, in plain
 * words, what it is currently making the mob do — used by the Creative-only
 * objective readout drawn under a PlayerMob's name and in its right-click menu.
 *
 * <p>The readout is built server-side ({@link games.brennan.playermob.entity.ObjectiveReadout})
 * from the goals currently running in the mob's {@code goalSelector}, then synced
 * to clients. A goal implements this interface to own its own label — typically
 * mapping its existing internal {@code phase} field to a word — rather than
 * having one central class reach into every goal's private state.</p>
 *
 * <p>Goals that don't implement this still appear in the readout via a fallback
 * label in {@code ObjectiveReadout}, so the visualisation is never blank for a
 * goal that was added later and not yet annotated.</p>
 */
public interface DescribableGoal {

    /**
     * The top-level objective shown under the mob's name, e.g. {@code "Raiding chest"}.
     * Should be a short, human-readable noun phrase. Never {@code null}/empty.
     */
    String objective();

    /**
     * Optional sub-objective / phase shown after the objective, e.g. {@code "looting"}.
     * Return {@code null} (the default) when there's no meaningful sub-state to show.
     */
    default String subObjective() {
        return null;
    }
}
