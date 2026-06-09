package games.brennan.playermob.entity;

import games.brennan.playermob.entity.goal.DescribableGoal;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.GoalSelector;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.RandomStrollGoal;
import net.minecraft.world.entity.ai.goal.WrappedGoal;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Builds the human-readable "what is this mob doing" readout from the goals
 * currently running in a PlayerMob's {@code goalSelector}. One line per running
 * objective, highest priority first, each formatted {@code "Objective — phase"}.
 *
 * <p>Runs server-side (goals only tick on the server); the result is synced to
 * clients via {@code PlayerMobEntity}'s {@code DATA_OBJECTIVES} and rendered both
 * as floating text under the mob (Creative only) and in the right-click menu.</p>
 *
 * <p>Each goal labels itself through {@link DescribableGoal}. Goals that don't —
 * including the vanilla goals PlayerMob registers — fall back to {@link #fallback},
 * so a newly added goal still shows <em>something</em> without touching this class.
 * The idle "look around" goals are filtered out: the mob is essentially always
 * looking at something, so they're noise rather than objectives.</p>
 */
public final class ObjectiveReadout {

    /** Shown when nothing meaningful is running (e.g. standing between strolls). */
    private static final String IDLE = "Idle";

    private ObjectiveReadout() {
    }

    /**
     * @param selector the mob's {@code goalSelector} (passed in because the field
     *                 is {@code protected} on {@code Mob} — only the entity can reach it)
     * @return newline-joined readout, never {@code null}; {@link #IDLE} when nothing runs
     */
    public static String of(GoalSelector selector) {
        List<String> lines = selector.getAvailableGoals().stream()
            .filter(WrappedGoal::isRunning)
            .sorted(Comparator.comparingInt(WrappedGoal::getPriority))
            .map(WrappedGoal::getGoal)
            .filter(g -> !isNoise(g))
            .map(ObjectiveReadout::describe)
            .filter(s -> s != null && !s.isEmpty())
            .collect(Collectors.toList());
        return lines.isEmpty() ? IDLE : String.join("\n", lines);
    }

    /** The idle look goals run almost constantly; they're not "objectives". */
    private static boolean isNoise(Goal goal) {
        return goal instanceof LookAtPlayerGoal || goal instanceof RandomLookAroundGoal;
    }

    private static String describe(Goal goal) {
        if (goal instanceof DescribableGoal describable) {
            String objective = describable.objective();
            String sub = describable.subObjective();
            return (sub == null || sub.isEmpty()) ? objective : objective + " — " + sub;
        }
        return fallback(goal);
    }

    /** Labels for the vanilla goals PlayerMob registers; humanised class name otherwise. */
    private static String fallback(Goal goal) {
        if (goal instanceof FloatGoal) {
            return "Swimming";
        }
        if (goal instanceof RandomStrollGoal) {
            // Covers WaterAvoidingRandomStrollGoal (its subclass) too.
            return "Wandering";
        }
        return humanise(goal.getClass().getSimpleName());
    }

    /** {@code "SomeCustomGoal"} → {@code "Some custom"} — a last-resort readable label. */
    private static String humanise(String className) {
        String name = className.endsWith("Goal")
            ? className.substring(0, className.length() - "Goal".length())
            : className;
        StringBuilder out = new StringBuilder(name.length() + 4);
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (i == 0) {
                out.append(Character.toUpperCase(c));
            } else {
                if (Character.isUpperCase(c) && !Character.isUpperCase(name.charAt(i - 1))) {
                    out.append(' ');
                }
                out.append(Character.toLowerCase(c));
            }
        }
        return out.toString();
    }
}
