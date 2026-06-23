package games.brennan.playermob.compat;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
//?}

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

/**
 * Version-bridging helpers for registration / item-construction APIs that changed
 * between MC 1.20.1 and 1.21.1: the {@link ResourceLocation} factory
 * ({@code fromNamespaceAndPath} vs the public constructor) and the spawn-egg entity
 * payload (the {@code ENTITY_DATA} data component vs the {@code EntityTag} stack NBT).
 */
public final class RegistryCompat {

    private RegistryCompat() {}

    /** Build a namespaced id. 1.21.1's {@code fromNamespaceAndPath}; 1.20.1's constructor. */
    public static ResourceLocation id(String namespace, String path) {
        //? if >=1.21.1 {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new ResourceLocation(namespace, path);*/
        //?}
    }

    /**
     * A spawn egg that stamps {@code entityData} onto the mob it spawns. On 1.21.1
     * the data rides as an {@code ENTITY_DATA} component baked into the item
     * properties; on 1.20.1 — where {@code Item.Properties} has no component API —
     * the egg's default item-stack carries the data under the vanilla
     * {@code "EntityTag"} NBT key, which {@code SpawnEggItem} reads and merges onto
     * the spawned entity exactly the same way. {@code entityData} must contain an
     * {@code "id"} naming the entity type (vanilla requires it; the caller already
     * sets it).
     */
    public static SpawnEggItem archetypeSpawnEgg(EntityType<? extends Mob> type, int background, int highlight,
                                                 CompoundTag entityData) {
        //? if >=1.21.1 {
        return new SpawnEggItem(type, background, highlight,
            new Item.Properties().component(DataComponents.ENTITY_DATA, CustomData.of(entityData)));
        //?} else {
        /*return new SpawnEggItem(type, background, highlight, new Item.Properties()) {
            @Override
            public ItemStack getDefaultInstance() {
                ItemStack stack = super.getDefaultInstance();
                stack.getOrCreateTag().put("EntityTag", entityData.copy());
                return stack;
            }
        };*/
        //?}
    }

    /**
     * Stamp {@code entityData} onto an existing spawn-egg {@code stack} so the egg
     * spawns a mob carrying it (the reincarnation egg path). 1.21.1 sets the
     * {@code ENTITY_DATA} component; 1.20.1 writes the {@code "EntityTag"} NBT key.
     * {@code entityData} must contain an {@code "id"} naming the entity type.
     */
    public static void applyEntityData(ItemStack stack, CompoundTag entityData) {
        //? if >=1.21.1 {
        stack.set(DataComponents.ENTITY_DATA, CustomData.of(entityData));
        //?} else {
        /*stack.getOrCreateTag().put("EntityTag", entityData.copy());*///?}
    }
}
