package games.brennan.playermob.entity.goal;

import games.brennan.playermob.entity.PlayerMobEntity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.target.TargetGoal;

import java.util.EnumSet;

/**
 * Makes a friendly {@link PlayerMobEntity} join a fight to defend an individual it
 * has come to love. When someone the mob loves ({@code feeling >= 7}) is attacked,
 * the mob targets their attacker — provided the mob is friendly enough to care
 * ({@code friendliness >= }{@value #DEFEND_FRIENDLINESS}) and brave enough to
 * help (the courage / "can I win?" gate, {@link PlayerMobEntity#wouldEngageFoe}).
 *
 * <p>A {@link TargetGoal} (not extendable {@code OwnerHurtByTargetGoal}, whose
 * constructor is {@code TamableAnimal}-only). It overrides only acquisition
 * ({@link #canUse} + {@link #start}); retention and cleanup are the inherited
 * {@code TargetGoal} machinery — once a foe is locked, the defence is held and
 * dropped exactly like any other acquired target (foe killed, out of range, or
 * the mob's own {@code customServerAiStep} sweep clears it).</p>
 *
 * <p>Registered at the same priority as {@code HurtByTargetGoal} but after it, so
 * self-defence wins the TARGET-flag tie while defending a friend still outranks
 * proactively hunting a random hostile.</p>
 */
public final class DefendLovedOneGoal extends TargetGoal implements DescribableGoal {

    /** Minimum friendliness for the mob to bother defending anyone. */
    static final int DEFEND_FRIENDLINESS = 6;
    /** A loved one counts as "under attack" only if hit within this many ticks (vanilla's revenge window). */
    static final int DEFEND_RECENCY_TICKS = 100;

    private final PlayerMobEntity self;
    private final double range;
    private LivingEntity foe;

    public DefendLovedOneGoal(PlayerMobEntity mob, double range) {
        super(mob, /* mustSee */ false);
        this.self = mob;
        this.range = range;
        setFlags(EnumSet.of(Flag.TARGET));
    }

    @Override
    public String objective() {
        return "Defending";
    }

    @Override
    public boolean canUse() {
        if (self.friendliness() < DEFEND_FRIENDLINESS) {
            return false;
        }
        LivingEntity[] pair = self.findLovedOneAndFoe(range, DEFEND_RECENCY_TICKS);
        if (pair == null) {
            return false;
        }
        LivingEntity candidateFoe = pair[1];
        if (!self.wouldEngageFoe(candidateFoe)) {
            return false; // not brave enough / can't win — don't suicidally join
        }
        this.foe = candidateFoe;
        return true;
    }

    @Override
    public void start() {
        self.setTarget(foe);
        super.start();
    }
}
