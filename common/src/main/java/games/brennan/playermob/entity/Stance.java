package games.brennan.playermob.entity;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Behavioural stance for a {@link PlayerMobEntity}.
 *
 * <p>Stances control which entities the mob treats as valid targets. v1 ships
 * a single stance — {@link #HOSTILE_TO_HOSTILE_MOBS} — but the enum and its
 * {@link #permitsTargeting(LivingEntity, LivingEntity)} predicate are deliberately
 * structured so future stances (neutral, hostile-to-players, hostile-to-all)
 * drop in without touching goal-selector wiring.</p>
 *
 * <p>Stored on the entity via a TrackedData<Integer> ordinal so it survives
 * save/load and replicates client-side.</p>
 */
public enum Stance {

    /**
     * v1 default. The mob targets entities that implement {@link Enemy}
     * (vanilla hostile mobs — zombies, skeletons, creepers, etc.) except itself.
     * Always retaliates against any attacker via the entity's vanilla
     * HurtByTargetGoal, regardless of stance.
     */
    HOSTILE_TO_HOSTILE_MOBS {
        @Override
        public boolean permitsTargeting(LivingEntity self, LivingEntity candidate) {
            if (candidate == self) return false;
            return candidate instanceof Enemy;
        }
    };

    /**
     * Returns true if {@code self} (a PlayerMobEntity) is allowed to target
     * {@code candidate} given its current stance. Called from the target
     * goal predicate every tick the selector evaluates — keep cheap.
     *
     * <p>Subclasses (one per enum constant) override to define the rule.</p>
     */
    public abstract boolean permitsTargeting(LivingEntity self, LivingEntity candidate);

    /**
     * Map a stored ordinal back to a Stance. Out-of-range or negative input
     * falls back to {@link #HOSTILE_TO_HOSTILE_MOBS} so loading a future-stamped
     * save on an older mod version degrades gracefully rather than crashing.
     */
    public static Stance byOrdinal(int ordinal) {
        Stance[] values = values();
        if (ordinal < 0 || ordinal >= values.length) {
            return HOSTILE_TO_HOSTILE_MOBS;
        }
        return values[ordinal];
    }
}
