package com.github.grule.gravestones.system;

import com.github.grule.gravestones.Gravestones;
import com.github.grule.gravestones.data.GravestoneState;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.math.util.ChunkUtil;
import com.hypixel.hytale.math.util.MathUtil;
import com.hypixel.hytale.math.vector.Vector3d;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.gameplay.DeathConfig;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.filter.FilterType;
import com.hypixel.hytale.server.core.modules.entity.component.HeadRotation;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockStateModule;
import com.hypixel.hytale.server.core.universe.world.storage.ChunkStore;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * System that creates gravestones when players die.
 * The gravestone stores the player's items and can only be accessed by the
 * owner.
 */
public class GravestoneDeathSystem extends DeathSystems.OnDeathSystem {

    private final Gravestones plugin;

    private static final Query<EntityStore> QUERY = Query.and(
            DeathComponent.getComponentType(),
            Player.getComponentType());

    // Run BEFORE DropPlayerDeathItems to prevent item duplication
    private static final Set<Dependency<EntityStore>> DEPENDENCIES = Set.of(
            new SystemDependency<>(Order.BEFORE, DeathSystems.DropPlayerDeathItems.class));

    public GravestoneDeathSystem() {
        this.plugin = Gravestones.get();
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return QUERY;
    }

    @Nonnull
    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return DEPENDENCIES;
    }

    @NonNullDecl
    @Override
    public ComponentType<EntityStore, DeathComponent> componentType() {
        return DeathComponent.getComponentType();
    }

    @Override
    public void onComponentAdded(
            @Nonnull Ref<EntityStore> ref,
            @Nonnull DeathComponent deathComponent,
            @Nonnull Store<EntityStore> store,
            @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        // Get necessary components
        PlayerRef playerRef = commandBuffer.getComponent(ref, PlayerRef.getComponentType());
        Player player = commandBuffer.getComponent(ref, Player.getComponentType());
        TransformComponent transform = commandBuffer.getComponent(ref, TransformComponent.getComponentType());
        UUIDComponent uuidComponent = commandBuffer.getComponent(ref, UUIDComponent.getComponentType());

        if (playerRef == null || player == null || transform == null || uuidComponent == null) {
            return;
        }

        // Don't create gravestone for creative mode players
        if (player.getGameMode() == GameMode.Creative) {
            plugin.getLogger().at(Level.INFO).log("Gamemode is creative, returning early");
            return;
        }

        var itemsLost = getLostItems(ref, deathComponent, store);
        deathComponent.setItemsLostOnDeath(itemsLost);

        // Don't create gravestone if no items will be dropped
        if (itemsLost.isEmpty()) {
            plugin.getLogger().at(Level.INFO).log("No items present, returning early");
            return;
        }

        // Spawn gravestone block
        Vector3d pos = transform.getPosition();
        World world = store.getExternalData().getWorld();
        int x = (int) Math.floor(pos.x);
        int y = (int) Math.floor(pos.y);
        int z = (int) Math.floor(pos.z);

        // if everything ok, disable item dropping
        deathComponent.setItemsLossMode(DeathConfig.ItemsLossMode.NONE);

        TransformComponent transformComponent = store.getComponent(ref, TransformComponent.getComponentType());
        assert transformComponent != null;
        Vector3d position = transformComponent.getPosition();
        HeadRotation headRotationComponent = store.getComponent(ref, HeadRotation.getComponentType());
        assert headRotationComponent != null;
        Vector3f headRotation = headRotationComponent.getRotation();

        // Store reference data for async operation
        String playerName = playerRef.getUsername();
        java.util.UUID playerUUID = uuidComponent.getUuid();
        long deathTime = System.currentTimeMillis();

        // Execute the item storage asynchronously on the world thread
        world.execute(() -> {
            world.setBlock(x, y, z, "Gravestone");

            var itemsToDrop = setupGravestone(
                    world, x, y, z,
                    itemsLost,
                    playerRef,
                    playerUUID,
                    playerName,
                    deathTime
            );

            // Re-obtain an entity store inside async world context
            var entityStore = world.getEntityStore().getStore();
            Holder<EntityStore>[] drops = ItemComponent.generateItemDrops(entityStore, itemsToDrop,
                    position.clone().add(0.0F, 1.0F, 0.0F), headRotation);
            entityStore.addEntities(drops, AddReason.SPAWN);
        });
    }

    // some code taken from DeathSystems.DropPlayerDeathItems.onComponentAdded
    @Nonnull
    public List<ItemStack> getLostItems(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent component,
                                        @Nonnull Store<EntityStore> store) {
        Player playerComponent = store.getComponent(ref, Player.getComponentType());

        assert playerComponent != null;

        if (playerComponent.getGameMode() == GameMode.Creative) {
            return List.of();
        }

        List<ItemStack> itemsToDrop = null;
        switch (component.getItemsLossMode()) {
            case ALL:
                itemsToDrop = playerComponent.getInventory().dropAllItemStacks();
                break;
            case CONFIGURED:
                double itemsAmountLossPercentage = component.getItemsAmountLossPercentage();
                if (itemsAmountLossPercentage > 0.0) {
                    double itemAmountLossRatio = itemsAmountLossPercentage / 100.0;
                    itemsToDrop = new ObjectArrayList<>();

                    var combinedItemContainer = playerComponent.getInventory().getCombinedEverything();
                    for (short i = 0; i < combinedItemContainer.getCapacity(); ++i) {
                        ItemStack itemStack = combinedItemContainer.getItemStack(i);
                        if (!ItemStack.isEmpty(itemStack) && itemStack.getItem().dropsOnDeath()) {
                            int quantityToLose = Math.max(1,
                                    MathUtil.floor(((double) itemStack.getQuantity()) * itemAmountLossRatio));
                            itemsToDrop.add(itemStack.withQuantity(quantityToLose));
                            int newQuantity = itemStack.getQuantity() - quantityToLose;
                            if (newQuantity > 0) {
                                ItemStack updatedItemStack = itemStack.withQuantity(newQuantity);
                                combinedItemContainer.replaceItemStackInSlot(i, itemStack, updatedItemStack);
                            } else {
                                combinedItemContainer.removeItemStackFromSlot(i);
                            }
                        }
                    }
                }
            case NONE:
                break;
        }

        if (itemsToDrop == null || itemsToDrop.isEmpty()) {
            return List.of();
        }

        return itemsToDrop;
    }

    /**
     * Sets up the gravestone with items and owner information.
     * Must be called on the world thread.
     * Returns items that failed to get stored.
     */
    @SuppressWarnings("removal") // Acknowledging BlockStateModule deprecation
    private List<ItemStack> setupGravestone(
            World world,
            int x, int y, int z,
            List<ItemStack> items,
            PlayerRef playerRef,
            java.util.UUID playerUUID,
            String playerName,
            long deathTime) {
        var errorMsg =
                Message.translation("gravestones.message.create_gravestone.failed")
                        .color(Color.RED);

        // Get chunk and block reference
        WorldChunk chunk = world.getChunk(ChunkUtil.indexChunkFromBlock(x, z));
        if (chunk == null) {
            playerRef.sendMessage(errorMsg.param("error", "chunk not found"));
            return items;
        }

        Ref<ChunkStore> blockRef = chunk.getBlockComponentEntity(x, y, z);
        if (blockRef == null) {
            playerRef.sendMessage(errorMsg.param("error", "block not found"));
            return items;
        }

        // Get chunk store
        Store<ChunkStore> chunkStore = world.getChunkStore().getStore();

        // Get component type for GravestoneState
        ComponentType<ChunkStore, GravestoneState> gravestoneComponentType = BlockStateModule.get()
                .getComponentType(GravestoneState.class);

        if (gravestoneComponentType == null) {
            playerRef.sendMessage(errorMsg.param("error", "component not found"));
            return items;
        }

        // Get the gravestone component
        GravestoneState gravestoneState = chunkStore.getComponent(blockRef, gravestoneComponentType);

        if (gravestoneState == null) {
            playerRef.sendMessage(errorMsg.param("error", "not a gravestone block"));
            return items;
        }

        // nameplate spawning logic, needs more testing
        // var holder = EntityStore.REGISTRY.newHolder();
        // var projectileComponent = new ProjectileComponent("Projectile");
        // holder.putComponent(ProjectileComponent.getComponentType(),
        // projectileComponent);
        // holder.putComponent(TransformComponent.getComponentType(), new
        // TransformComponent(new Vector3d(x + 0.5, y + 1, z + 0.5), Vector3f.ZERO));
        // holder.ensureComponent(UUIDComponent.getComponentType());
        // if (projectileComponent.getProjectile() == null) {
        // projectileComponent.initialize();
        // }
        // holder.addComponent(NetworkId.getComponentType(), new
        // NetworkId(world.getEntityStore().getStore().getExternalData().takeNextNetworkId()));
        // holder.addComponent(Nameplate.getComponentType(), new Nameplate(playerName +
        // "'s Gravestone"));
        // world.getEntityStore().getStore().addEntity(holder, AddReason.SPAWN);
        // var uuidComponent = holder.getComponent(UUIDComponent.getComponentType());
        // assert uuidComponent != null;

        // Set owner information
        gravestoneState.setOwnerUUID(playerUUID);
        gravestoneState.setOwnerName(playerName);
        gravestoneState.setDeathTime(deathTime);
        // gravestoneState.setNameplateUUID(uuidComponent.getUuid());

        // Calculate required capacity (number of non-empty items)
        int itemCount = 0;
        for (ItemStack item : items) {
            if (!ItemStack.isEmpty(item)) {
                itemCount++;
            }
        }

        // Max 63 slots, otherwise the UI will overflow the screen
        gravestoneState.setDynamicCapacity((short) Math.min(63, itemCount));

        // Store items in the gravestone
        ItemContainer container = gravestoneState.getItemContainer();
        if (container == null) {
            playerRef.sendMessage(errorMsg.param("error", "container not initialized"));
            return items;
        }

        short slot = 0;
        List<ItemStack> failed = new ArrayList<>();

        for (ItemStack item : items) {
            if (ItemStack.isEmpty(item)) {
                continue;
            }

            if (slot >= container.getCapacity()) {
                failed.add(item);
                continue;
            }

            container.addItemStackToSlot(slot++, item);
        }

        // CRITICAL: Set container to extraction-only mode
        // This prevents players from using gravestones as storage chests
        // This has to be done after the items are already introduced as otherwise
        // storage of items will fail
        container.setGlobalFilter(FilterType.ALLOW_OUTPUT_ONLY);

        playerRef.sendMessage(
                Message.translation("gravestones.messages.create_gravestone.success.position")
                        .param("x", x)
                        .param("y", y)
                        .param("z", z)
                        .color(new Color(0x0384fc))
        );

        if (!failed.isEmpty()) {
            playerRef.sendMessage(
                    Message.translation("gravestones.messages.create_gravestone.success.dropped")
                            .param("amount", failed.size())
                            .color(Color.RED)
            );
        }

        return failed;
    }
}
