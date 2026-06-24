package games.brennan.playermob.compat;

//? if >=1.21.1 {
import net.minecraft.core.component.DataComponents;
//?}
//? if >=26 {
/*import net.minecraft.world.item.component.TypedEntityData;
*///?} else if >=1.21.1 {
import net.minecraft.world.item.component.CustomData;
//?}

import net.minecraft.nbt.CompoundTag;
//? if >=26 {
/*import net.minecraft.resources.Identifier;
*///?} else {
import net.minecraft.resources.ResourceLocation;
//?}
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

    /** Build a namespaced id. 26.x's {@code Identifier}; 1.21.1's {@code fromNamespaceAndPath}; 1.20.1's constructor. */
    //? if >=26 {
    /*public static net.minecraft.resources.Identifier id(String namespace, String path) {
        return net.minecraft.resources.Identifier.fromNamespaceAndPath(namespace, path);
    }
    *///?} else {
    public static ResourceLocation id(String namespace, String path) {
        //? if >=1.21.1 {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
        //?} else {
        /*return new ResourceLocation(namespace, path);*/
        //?}
    }
    //?}

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
    //? if >=26 {
    /*public static SpawnEggItem archetypeSpawnEgg(EntityType<? extends Mob> type, int background, int highlight,
                                                 CompoundTag entityData, net.minecraft.resources.Identifier eggId) {*/
    //?} else {
    public static SpawnEggItem archetypeSpawnEgg(EntityType<? extends Mob> type, int background, int highlight,
                                                 CompoundTag entityData, ResourceLocation eggId) {
    //?}
        //? if >=26 {
        /*// 26.x dropped the (type, bgColor, hlColor, Properties) ctor — the egg's entity type now
        // rides the ENTITY_DATA component (TypedEntityData), and the two egg colours are
        // data-driven from the item model/texture rather than constructor args. Item.Properties
        // must also carry its registry id before the Item ctor runs (ctor calls itemIdOrThrow()).
        return new SpawnEggItem(new Item.Properties()
            .setId(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.ITEM, eggId))
            .component(DataComponents.ENTITY_DATA, TypedEntityData.of(type, entityData)));
        *///?} else if >=1.21.1 {
        return new SpawnEggItem(type, background, highlight,
            new Item.Properties().component(DataComponents.ENTITY_DATA, CustomData.of(entityData)));
        //?} else {
        /*return new SpawnEggItem(type, background, highlight, new Item.Properties()) {
            @Override
            public ItemStack getDefaultInstance() {
                ItemStack stack = super.getDefaultInstance();
                ensureArchetypeEntityTag(stack, entityData);
                return stack;
            }
            // A stack built with new ItemStack(item) — the creative tab, a bare /give, or
            // pick-block — skips getDefaultInstance, so it carries no EntityTag. Inject it
            // (only when absent, never clobbering an explicit one) before vanilla reads the
            // stack tag at spawn, so the archetype pins its traits however the egg was made.
            @Override
            public net.minecraft.world.InteractionResult useOn(net.minecraft.world.item.context.UseOnContext context) {
                ensureArchetypeEntityTag(context.getItemInHand(), entityData);
                return super.useOn(context);
            }
            @Override
            public net.minecraft.world.InteractionResultHolder<ItemStack> use(
                    net.minecraft.world.level.Level level,
                    net.minecraft.world.entity.player.Player player,
                    net.minecraft.world.InteractionHand hand) {
                ensureArchetypeEntityTag(player.getItemInHand(hand), entityData);
                return super.use(level, player, hand);
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
        //? if >=26 {
        /*// The ENTITY_DATA component is now TypedEntityData<EntityType<?>>, so resolve the
        // entity type from the snapshot's "id" key (vanilla requires it; the caller sets it).
        net.minecraft.world.entity.EntityType<?> type = entityData.getString("id")
            .map(net.minecraft.resources.Identifier::tryParse)
            .map(net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE::getValue)
            .orElse(null);
        if (type != null) {
            stack.set(DataComponents.ENTITY_DATA, TypedEntityData.of(type, entityData));
        }
        *///?} else if >=1.21.1 {
        stack.set(DataComponents.ENTITY_DATA, CustomData.of(entityData));
        //?} else {
        /*stack.getOrCreateTag().put("EntityTag", entityData.copy());*///?}
    }

    //? if <1.21.1 {
    /*// 1.20.1 has no default-NBT mechanism: a stack built with new ItemStack(item)
    // (creative tab, bare /give, pick-block) skips getDefaultInstance and so carries no
    // EntityTag. Inject the archetype's tag when the stack has none — never overwriting an
    // explicit map-maker EntityTag — so the egg pins its traits however the stack was built.
    // The tag includes "id", so vanilla's EntityType.updateCustomEntityTag resolves the
    // type and merges the traits via entity.load at spawn time.
    public static void ensureArchetypeEntityTag(ItemStack stack, CompoundTag entityData) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains("EntityTag")) {
            stack.getOrCreateTag().put("EntityTag", entityData.copy());
        }
    }
    *///?}
}
