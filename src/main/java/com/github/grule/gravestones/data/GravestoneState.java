package com.github.grule.gravestones.data;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.*;
import com.hypixel.hytale.event.EventPriority;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Vector3f;
import com.hypixel.hytale.math.vector.Vector3i;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.windows.ContainerBlockWindow;
import com.hypixel.hytale.server.core.entity.entities.player.windows.WindowManager;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.inventory.container.SimpleItemContainer;
import com.hypixel.hytale.server.core.modules.entity.item.ItemComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.chunk.WorldChunk;
import com.hypixel.hytale.server.core.universe.world.meta.BlockState;
import com.hypixel.hytale.server.core.universe.world.meta.state.*;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.checkerframework.checker.nullness.compatqual.NonNullDecl;
import org.checkerframework.checker.nullness.compatqual.NullableDecl;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * A custom gravestone block that stores items and is owned by a specific player.
 * Only the owner can access the gravestone's contents.
 */
@SuppressWarnings({"removal", "deprecation"}) // Acknowledging BlockState deprecation
public class GravestoneState extends ItemContainerState
        implements ItemContainerBlockState, PlacedByBlockState, DestroyableBlockState, BreakValidatedBlockState {

    /**
     * Codec for serializing/deserializing GravestoneState
     */
    public static final Codec<GravestoneState> CODEC = BuilderCodec
            .builder(
                    GravestoneState.class,
                    GravestoneState::new,
                    BlockState.BASE_CODEC
            )
            .append(
                    new KeyedCodec<>("OwnerUUID", Codec.UUID_BINARY),
                    (state, uuid) -> state.ownerUUID = uuid,
                    state -> state.ownerUUID
            )
            .add()
            .append(
                    new KeyedCodec<>("GravestoneUUID", Codec.UUID_BINARY),
                    (state, uuid) -> state.gravestoneUUID = uuid,
                    state -> state.gravestoneUUID
            )
            .add()
            .append(
                    new KeyedCodec<>("OwnerName", Codec.STRING),
                    (state, name) -> state.ownerName = name,
                    state -> state.ownerName
            )
            .add()
            .append(
                    new KeyedCodec<>("DeathTime", Codec.LONG),
                    (state, time) -> state.deathTime = time,
                    state -> state.deathTime
            )
            .add()
            .append(
                    new KeyedCodec<>("AllowOthersAccess", Codec.BOOLEAN),
                    (state, allow) -> state.allowOthersAccess = allow,
                    state -> state.allowOthersAccess
            )
            .add()
            .append(
                    new KeyedCodec<>("ItemContainer", SimpleItemContainer.CODEC),
                    (state, container) -> state.itemContainer = container,
                    state -> state.itemContainer
            )
            .add()
            .append(
                    new KeyedCodec<>("DynamicCapacity", Codec.SHORT),
                    (state, capacity) -> state.dynamicCapacity = capacity,
                    state -> state.dynamicCapacity
            )
            .add()
            .append(
                    new KeyedCodec<>("NameplateUUID", Codec.UUID_BINARY),
                    (state, uuid) -> state.nameplateUUID = uuid,
                    state -> state.nameplateUUID
            )
            .add()
            .build();

    private final Map<UUID, ContainerBlockWindow> windows = new ConcurrentHashMap<>();
    // Owner's UUID - only this player can access the gravestone
    @Nullable
    protected UUID ownerUUID;
    @Nullable
    protected UUID gravestoneUUID;
    @Nullable
    protected String ownerName;
    protected long deathTime;
    protected boolean allowOthersAccess = false;
    // Dynamic capacity - set based on number of items stored
    protected short dynamicCapacity = 0;
    @Nullable
    protected SimpleItemContainer itemContainer;
    @Nullable
    protected UUID nameplateUUID;

    /**
     * Default constructor required for codec
     */
    public GravestoneState() {
        this.gravestoneUUID = UUID.randomUUID();
        this.deathTime = System.currentTimeMillis();
    }

    @Override
    public boolean initialize(@Nonnull BlockType blockType) {
        if (!super.initialize(blockType)) {
            return false;
        }

        // Determine capacity: use dynamic if set, otherwise use StateData
        short capacity;
        if (this.dynamicCapacity > 0) {
            // Use the stored dynamic capacity
            capacity = this.dynamicCapacity;
        } else {
            // Fall back to StateData capacity (for manually placed blocks)
            if (blockType.getState() instanceof ItemContainerStateData gravestoneStateData) {
                capacity = gravestoneStateData.getCapacity();
            } else {
                capacity = 0; // Default fallback
            }
        }

        var previousContainer = this.itemContainer;
        // Ensure container has correct capacity
        List<ItemStack> remainder = new ObjectArrayList<>();
        this.itemContainer = ItemContainer.ensureContainerCapacity(
                this.itemContainer,
                capacity,
                SimpleItemContainer::new,
                remainder
        );

        if (this.itemContainer != previousContainer) {
            // Only register if we got a new container
            this.itemContainer.registerChangeEvent(EventPriority.LAST, this::onItemChange);
        }

        // Handle excess items (drop them if capacity is exceeded)
        if (!remainder.isEmpty()) {
            WorldChunk chunk = this.getChunk();
            assert chunk != null;
            World world = chunk.getWorld();
            Store<EntityStore> store = world.getEntityStore().getStore();

            HytaleLogger.getLogger()
                    .at(Level.WARNING)
                    .withCause(new Throwable())
                    .log(
                            "Dropping %d excess items from gravestone: %s at world: %s, chunk: %s, block: %s",
                            remainder.size(),
                            blockType.getId(),
                            chunk.getWorld().getName(),
                            chunk,
                            this.getPosition()
                    );

            Vector3i blockPosition = this.getBlockPosition();
            Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(
                    store,
                    remainder,
                    blockPosition.toVector3d(),
                    Vector3f.ZERO
            );
            store.addEntities(itemEntityHolders, AddReason.SPAWN);
        }

        return true;
    }

    /**
     * Check if a player can open this gravestone.
     * Only the owner can access their gravestone by default.
     */
    @Override
    public boolean canOpen(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor
    ) {
        return hasPermission(playerRef, componentAccessor, false);
    }

    @Override
    public boolean canDestroy(@NonNullDecl Ref<EntityStore> playerRef, @NonNullDecl ComponentAccessor<EntityStore> componentAccessor) {
        return hasPermission(playerRef, componentAccessor, true);
    }

    /**
     * Called when the gravestone block is destroyed.
     * Drops all items and closes all windows.
     */
    @Override
    public void onDestroy() {
        // Close all open windows
        WindowManager.closeAndRemoveAll(this.windows);

        var chunk = this.getChunk();
        assert chunk != null;
        var world = this.getChunk().getWorld();
        var store = world.getEntityStore().getStore();

        // Drop all items
        if (this.itemContainer != null && !this.itemContainer.isEmpty()) {
            var allItemStacks = this.itemContainer.dropAllItemStacks();
            var dropPosition = this.getBlockPosition().toVector3d().add(0.5, 0.5, 0.5);

            Holder<EntityStore>[] itemEntityHolders = ItemComponent.generateItemDrops(
                    store,
                    allItemStacks,
                    dropPosition,
                    Vector3f.ZERO
            );

            if (itemEntityHolders.length > 0) {
                world.execute(() -> store.addEntities(itemEntityHolders, AddReason.SPAWN));
            }
        }

        world.execute(() -> {
            if (this.nameplateUUID == null) {
                return;
            }

            var ref = world.getEntityRef(this.nameplateUUID);
            if (ref == null) {
                return;
            }

            store.removeEntity(ref, RemoveReason.REMOVE);
        });
    }

    /**
     * Called when a player places this gravestone block.
     * Captures the placer's UUID as the owner.
     */
    @Override
    public void placedBy(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull String blockTypeKey,
            @Nonnull BlockState blockState,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor
    ) {
        // Get player UUID
        UUIDComponent uuidComponent = componentAccessor.getComponent(
                playerRef,
                UUIDComponent.getComponentType()
        );
        if (uuidComponent != null) {
            this.ownerUUID = uuidComponent.getUuid();
        }

        // Get player name
        PlayerRef playerRefComponent = componentAccessor.getComponent(
                playerRef,
                PlayerRef.getComponentType()
        );
        if (playerRefComponent != null) {
            this.ownerName = playerRefComponent.getUsername();
        }

        this.markNeedsSave();
    }

    private boolean hasPermission(
            @Nonnull Ref<EntityStore> playerRef,
            @Nonnull ComponentAccessor<EntityStore> componentAccessor,
            boolean canDestroy
    ) {
        var player = playerRef.getStore().getComponent(playerRef, Player.getComponentType());
        assert player != null;

        if (this.itemContainer != null && !this.itemContainer.isEmpty() && canDestroy && this.itemContainer.countItemStacks(item -> !item.isEmpty()) != 0) {
            player.sendMessage(Message.raw("This gravestone is not empty!"));
            return false;
        }

        if (itemContainer != null && itemContainer.isEmpty() && !canDestroy) {
            player.sendMessage(Message.raw("This gravestone is empty!"));
            return false;
        }

        // If no owner is set, anyone can open (shouldn't happen normally)
        if (this.ownerUUID == null) {
            return true;
        }

        // If others are allowed to access, let them in
        if (this.allowOthersAccess) {
            return true;
        }

        // Check if the player opening is the owner
        UUIDComponent uuidComponent = componentAccessor.getComponent(
                playerRef,
                UUIDComponent.getComponentType()
        );

        assert uuidComponent != null;

        boolean isOwner = this.ownerUUID.equals(uuidComponent.getUuid());

        // Send message if not owner
        if (!isOwner) {
            PlayerRef playerRefComponent = componentAccessor.getComponent(
                    playerRef,
                    PlayerRef.getComponentType()
            );
            if (playerRefComponent != null) {
                playerRefComponent.sendMessage(
                        Message.raw("This gravestone belongs to " + (this.ownerName != null ? this.ownerName : "someone else") + "!")
                );
            }
        }

        return isOwner;
    }

    // ===== GETTERS AND SETTERS =====

    @Nullable
    public UUID getOwnerUUID() {
        return this.ownerUUID;
    }

    public void setOwnerUUID(@Nullable UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
        this.markNeedsSave();
    }

    @Nullable
    public UUID getGravestoneUUID() {
        return this.gravestoneUUID;
    }

    @Nullable
    public String getOwnerName() {
        return this.ownerName;
    }

    public void setOwnerName(@Nullable String ownerName) {
        this.ownerName = ownerName;
        this.markNeedsSave();
    }

    public long getDeathTime() {
        return this.deathTime;
    }

    public void setDeathTime(long deathTime) {
        this.deathTime = deathTime;
        this.markNeedsSave();
    }

    public boolean isAllowOthersAccess() {
        return this.allowOthersAccess;
    }

    public void setAllowOthersAccess(boolean allowOthersAccess) {
        this.allowOthersAccess = allowOthersAccess;
        this.markNeedsSave();
    }

    public short getDynamicCapacity() {
        return this.dynamicCapacity;
    }

    public void setNameplateUUID(@NullableDecl UUID nameplateUUID) {
        this.nameplateUUID = nameplateUUID;
    }

    @Nullable
    public UUID getNameplateUUID() {
        return this.nameplateUUID;
    }

    /**
     * Sets the dynamic capacity and recreates the container with the new size.
     * This should be called AFTER the block is placed but BEFORE items are added.
     */
    public void setDynamicCapacity(short capacity) {
        if (this.itemContainer != null && !this.itemContainer.isEmpty() && capacity < this.itemContainer.countItemStacks(item -> item.isValid() && !item.isEmpty())) {
            throw new IllegalStateException("Cannot reduce capacity below current item count");
        }

        this.dynamicCapacity = capacity;

        // Recreate the container with the new capacity
        List<ItemStack> existingItems = new ObjectArrayList<>();
        if (this.itemContainer != null && !this.itemContainer.isEmpty()) {
            // Save existing items
            for (short i = 0; i < this.itemContainer.getCapacity(); i++) {
                ItemStack item = this.itemContainer.getItemStack(i);
                if (!ItemStack.isEmpty(item)) {
                    existingItems.add(item);
                }
            }
        }

        // Create new container with exact capacity
        this.itemContainer = new SimpleItemContainer(capacity);
        this.itemContainer.registerChangeEvent(EventPriority.LAST, _ -> this.markNeedsSave());

        // Restore items if any
        for (int i = 0; i < existingItems.size() && i < capacity; i++) {
            this.itemContainer.addItemStackToSlot((short) i, existingItems.get(i));
        }

        this.markNeedsSave();
    }

    @Override
    @Nullable
    public ItemContainer getItemContainer() {
        return this.itemContainer;
    }

    public void setItemContainer(@Nonnull SimpleItemContainer itemContainer) {
        this.itemContainer = itemContainer;
        this.markNeedsSave();
    }

    @Nonnull
    public Map<UUID, ContainerBlockWindow> getWindows() {
        return this.windows;
    }
}
