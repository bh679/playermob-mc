package games.brennan.playermob.mixin;

import net.minecraft.world.LockCode;
import net.minecraft.world.level.block.entity.BaseContainerBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Exposes a container's vanilla lock ({@code /data merge block … {lock:…}}), so a PlayerMob
 * fetching food never opens a chest a player locked. The field is named {@code lockKey} on every
 * supported version; 26.x also has a public {@code isLocked()}, read directly there.
 */
@Mixin(BaseContainerBlockEntity.class)
public interface ContainerLockAccessor {

    @Accessor("lockKey")
    LockCode playermob$lockKey();
}
