package games.brennan.playermob.mixin;

import net.minecraft.world.entity.animal.Animal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Lets a PlayerMob clear an animal's love cause when it feeds the animal itself.
 *
 * <p>Vanilla's {@code setInLove(null)} sets the love timer but leaves {@code loveCause} as it was,
 * and {@code resetLove} never clears it either. So a cow a player once fed would keep that player as
 * its cause, and a calf the echo bred would credit that player with the bred-animals stat, the
 * advancement and a Taming breed. Clearing it makes the echo's breeding credit nobody.</p>
 *
 * <p>The field is {@code loveCause} on every supported version: a {@code UUID} before 26.x, an
 * {@code EntityReference} from 26.x. Only a {@code null} is ever written.</p>
 */
@Mixin(Animal.class)
public interface AnimalLoveCauseAccessor {

    //? if >=26 {
    /*@Accessor("loveCause")
    void playermob$setLoveCause(net.minecraft.world.entity.EntityReference<net.minecraft.server.level.ServerPlayer> cause);
    *///?} else {
    @Accessor("loveCause")
    void playermob$setLoveCause(java.util.UUID cause);
    //?}
}
