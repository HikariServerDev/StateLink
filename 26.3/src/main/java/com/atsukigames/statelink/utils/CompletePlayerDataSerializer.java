package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.effect.MobEffect;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;

/**
 * PlayerDataのMinecraft側シリアライザ。
 *
 * <p>復元は、先に全対象データをparse・registry lookup・range validationしてから
 * Minecraft stateへ触れる。apply中に例外が出た場合は、変更前のローカルstateを
 * rollbackし、呼び出し側がそのsessionを切断できるよう例外を返す。</p>
 */
public final class CompletePlayerDataSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String serializeInventory(ServerPlayer player) {
        // PlayerInventory全体を保存するとarmor/offhandが別columnと重複するため、mainのみ保存する。
        return itemListToJson(player, player.getInventory().getNonEquipmentItems());
    }

    public static String serializeEnderChest(ServerPlayer player) {
        return inventoryToJson(player, player.getEnderChestInventory());
    }

    public static String serializeArmor(ServerPlayer player) {
        return itemListToJson(player, com.atsukigames.statelink.utils.PlayerEquipment.armor(player));
    }

    public static String serializeOffhand(ServerPlayer player) {
        return itemListToJson(player, com.atsukigames.statelink.utils.PlayerEquipment.offhand(player));
    }

    /**
     * Read-only copy of player-owned transient stacks at this instant. This is
     * also used by live checkpoints: callers must not clear or mutate the
     * player's cursor, screen, or inventories here. Cursors from both
     * Player-referenced handlers, CraftingInventory slots, and explicitly
     * classified vanilla inputs are included.
     */
    public static List<ItemStack> captureActiveTransientItems(ServerPlayer player) {
        return captureTransientState(player).items();
    }

    /** Server-thread-only raw views for change detection. Never escape to a worker or clear here. */
    public static List<ItemStack> ownedTransientViewsForDirtyDetection(ServerPlayer player) {
        var handlers = collectRelevantScreenHandlers(player.containerMenu, player.inventoryMenu);
        List<ItemStack> values = new ArrayList<>();
        for (AbstractContainerMenu handler : handlers) values.add(handler.getCarried());
        for (Slot slot : disconnectTransientSlots(player, handlers)) values.add(slot.getItem());
        return values;
    }

    /**
     * Captures both transient item values and the exact source objects from one
     * identity-distinct handler set. Disconnect cleanup later clears these same
     * sources only after the complete immutable PlayerData payload exists.
     */
    public static TransientCapture captureTransientState(ServerPlayer player) {
        List<AbstractContainerMenu> handlers = collectRelevantScreenHandlers(
            player.containerMenu, player.inventoryMenu);
        List<OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack>> cursors = collectCursorSources(handlers);
        List<ItemStack> items = new ArrayList<>();

        for (OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack> cursor : cursors) {
            ItemStack capturedCursor = cursor.value();
            if (!capturedCursor.isEmpty()) items.add(capturedCursor.copy());
        }

        List<TransientInventorySlot> inputSlots = captureTransientSlots(
            player, disconnectTransientSlots(player, handlers));
        for (TransientInventorySlot input : inputSlots) {
            ItemStack capturedStack = input.capturedStack();
            if (!capturedStack.isEmpty()) items.add(capturedStack.copy());
        }

        return new TransientCapture(player, handlers, cursors, inputSlots, items);
    }

    /**
     * Builds a snapshot-only pending overlay from durable leftovers and the
     * current live transient stacks. The caller's durable JSON/context is never
     * changed, and each invocation replaces the prior checkpoint overlay by
     * rebuilding it from the same durable base plus current live sources.
     */
    public static String composeCheckpointPendingItems(
        ServerPlayer player,
        String durablePendingJson,
        List<ItemStack> activeTransient
    ) {
        List<ItemStack> durable = decodePendingItems(player, durablePendingJson);
        List<ItemStack> effective = CheckpointItemOverlay.compose(
            durable, activeTransient, ItemStack::copy);
        return pendingItemsToJson(effective);
    }

    /**
     * Clears already-captured transient references before vanilla close hooks run.
     * Vanilla ScreenHandler.close() therefore has no cursor or allowlisted input
     * stack that it can externalize through offerOrDrop or World.spawnEntity.
     */
    public static void clearDisconnectTransientStateAndCloseScreen(
        ServerPlayer player,
        TransientCapture capture
    ) {
        Objects.requireNonNull(capture, "capture");
        if (capture.player != player) {
            throw new IllegalStateException("disconnect transient capture belongs to another player");
        }
        List<AbstractContainerMenu> currentHandlers = collectRelevantScreenHandlers(
            player.containerMenu, player.inventoryMenu);
        if (!sameHandlerIdentities(capture.handlers, currentHandlers)) {
            throw new IllegalStateException("player screen handlers changed during disconnect capture");
        }

        // Validate every source before mutating any source. This prevents a
        // re-entrant/modded change during serialization from causing a partial
        // transfer into the durable snapshot.
        validateCapturedCursorSources(capture.cursors);
        for (TransientInventorySlot input : capture.inputSlots) {
            if (!ItemStack.matches(input.slot().getItem(), input.capturedStack())) {
                throw new IllegalStateException("temporary screen input changed during disconnect capture");
            }
        }

        OwnedItemSources.clearIfUnchanged(
            capture.cursors,
            AbstractContainerMenu::getCarried,
            ItemStack::matches,
            ItemStack::isEmpty,
            (handler, ignored) -> handler.setCarried(ItemStack.EMPTY));
        for (TransientInventorySlot input : capture.inputSlots) {
            if (!input.capturedStack().isEmpty()) input.slot().setByPlayer(ItemStack.EMPTY);
        }
        for (AbstractContainerMenu handler : capture.handlers) {
            if (DisconnectTransientInventoryPolicy.shouldClearCraftingResult(handler.getClass())) {
                var crafting = (net.minecraft.world.inventory.AbstractCraftingMenu) handler;
                for (var slot : crafting.getInputGridSlots()) slot.set(ItemStack.EMPTY);
                crafting.getResultSlot().set(ItemStack.EMPTY);
            }
        }
        player.doCloseContainer();
    }

    /** Adds captured stacks after any already-pending stacks, preserving their order and full NBT. */
    public static String appendPendingDisconnectItems(
        ServerPlayer player,
        String existingJson,
        List<ItemStack> additionalStacks
    ) {
        List<ItemStack> combined = CheckpointItemOverlay.compose(
            decodePendingItems(player, existingJson), additionalStacks, ItemStack::copy);
        return pendingItemsToJson(combined);
    }

    /**
     * Vanilla 1.18.2 retains cursor state on these two Player references when a
     * handled screen replaces the player screen. No third persistent
     * cursor-owning handler reference exists on ServerPlayerEntity/PlayerEntity.
     * Compare by identity: two distinct handlers can own two distinct stacks.
     */
    static List<AbstractContainerMenu> collectRelevantScreenHandlers(
        AbstractContainerMenu currentScreenHandler,
        AbstractContainerMenu playerScreenHandler
    ) {
        return OwnedItemSources.identityDistinct(currentScreenHandler, playerScreenHandler);
    }

    static List<OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack>> collectCursorSources(
        List<AbstractContainerMenu> handlers
    ) {
        return OwnedItemSources.capture(handlers, AbstractContainerMenu::getCarried, ItemStack::copy);
    }

    private static void validateCapturedCursorSources(
        List<OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack>> cursors
    ) {
        for (OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack> cursor : cursors) {
            if (!ItemStack.matches(cursor.owner().getCarried(), cursor.value())) {
                throw new IllegalStateException("screen cursor changed during disconnect capture");
            }
        }
    }

    private static boolean sameHandlerIdentities(List<AbstractContainerMenu> left, List<AbstractContainerMenu> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) return false;
        }
        return true;
    }

    private static List<Slot> disconnectTransientSlots(
        ServerPlayer player,
        List<AbstractContainerMenu> handlers
    ) {
        Map<Container, Set<Integer>> seen = new IdentityHashMap<>();
        List<Slot> inputs = new ArrayList<>();
        for (AbstractContainerMenu handler : handlers) {
            for (Slot slot : handler.slots) {
                if (slot.container instanceof TransientCraftingContainer) {
                    addTransientSlot(inputs, seen, slot);
                }
            }
            for (int screenSlotId : DisconnectTransientInventoryPolicy.inputScreenSlotIds(handler.getClass())) {
                if (screenSlotId < 0 || screenSlotId >= handler.slots.size()) {
                    throw new IllegalStateException("vanilla disconnect input slot is missing from screen handler");
                }
                Slot slot = handler.slots.get(screenSlotId);
                if (slot.container == player.getInventory()) {
                    throw new IllegalStateException("disconnect input policy resolved to persistent player inventory");
                }
                addTransientSlot(inputs, seen, slot);
            }
        }
        return List.copyOf(inputs);
    }

    private static List<TransientInventorySlot> captureTransientSlots(ServerPlayer player, List<Slot> slots) {
        List<TransientInventorySlot> captured = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.container == player.getInventory()) {
                throw new IllegalStateException("disconnect input policy resolved to persistent player inventory");
            }
            captured.add(new TransientInventorySlot(slot, slot.getItem().copy()));
        }
        return List.copyOf(captured);
    }

    private static void addTransientSlot(
        List<Slot> inputs,
        Map<Container, Set<Integer>> seen,
        Slot slot
    ) {
        Set<Integer> indexes = seen.computeIfAbsent(slot.container, ignored -> new HashSet<>());
        int inventorySlot = slot.getContainerSlot();
        if (inventorySlot < 0 || inventorySlot >= slot.container.getContainerSize()) {
            throw new IllegalStateException("disconnect input slot resolves outside its backing inventory");
        }
        if (indexes.add(inventorySlot)) inputs.add(slot);
    }

    private record TransientInventorySlot(Slot slot, ItemStack capturedStack) {
    }

    public static final class TransientCapture {
        private final ServerPlayer player;
        private final List<AbstractContainerMenu> handlers;
        private final List<OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack>> cursors;
        private final List<TransientInventorySlot> inputSlots;
        private final List<ItemStack> items;

        private TransientCapture(
            ServerPlayer player,
            List<AbstractContainerMenu> handlers,
            List<OwnedItemSources.Snapshot<AbstractContainerMenu, ItemStack>> cursors,
            List<TransientInventorySlot> inputSlots,
            List<ItemStack> items
        ) {
            this.player = player;
            this.handlers = List.copyOf(handlers);
            this.cursors = List.copyOf(cursors);
            this.inputSlots = List.copyOf(inputSlots);
            this.items = items.stream().map(ItemStack::copy).toList();
        }

        public List<ItemStack> items() {
            return items.stream().map(ItemStack::copy).toList();
        }
    }

    private static List<ItemStack> decodePendingItems(ServerPlayer player, String json) {
        List<ItemStack> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        JsonArray array;
        try {
            array = GSON.fromJson(json, JsonArray.class);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid pending disconnect item JSON", error);
        }
        if (array == null) throw invalid("pending disconnect items JSON is not an array");
        int expectedSlot = 0;
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw invalid("pending disconnect item entry is not an object");
            JsonObject object = element.getAsJsonObject();
            if (requiredInt(object, "slot") != expectedSlot) {
                throw invalid("pending disconnect item order is invalid");
            }
            result.add(decodeItem(player, object));
            expectedSlot++;
        }
        return result;
    }

    private static String pendingItemsToJson(List<ItemStack> stacks) {
        if (stacks == null || stacks.isEmpty()) return null;
        JsonArray array = new JsonArray();
        int slot = 0;
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            array.add(itemStackToJson(null, slot++, stack));
        }
        return array.size() == 0 ? null : GSON.toJson(array);
    }

    private static MergeResult mergeIntoMainInventory(List<ItemStack> main, List<ItemStack> pending) {
        List<ItemStack> remaining = new ArrayList<>();
        long insertedCount = 0L;
        for (ItemStack original : pending) {
            ItemStack stack = original.copy();
            int originalCount = stack.getCount();

            for (ItemStack existing : main) {
                if (stack.isEmpty()) break;
                if (existing.isEmpty() || !ItemStack.isSameItemSameComponents(existing, stack)) continue;
                int max = Math.min(existing.getMaxStackSize(), stack.getMaxStackSize());
                int moved = Math.min(stack.getCount(), Math.max(0, max - existing.getCount()));
                if (moved > 0) {
                    existing.grow(moved);
                    stack.shrink(moved);
                }
            }
            for (int slot = 0; slot < main.size() && !stack.isEmpty(); slot++) {
                if (!main.get(slot).isEmpty()) continue;
                int moved = Math.min(stack.getCount(), stack.getMaxStackSize());
                ItemStack placed = stack.copy();
                placed.setCount(moved);
                main.set(slot, placed);
                stack.shrink(moved);
            }

            insertedCount += originalCount - stack.getCount();
            if (!stack.isEmpty()) remaining.add(stack.copy());
        }
        return new MergeResult(main, remaining, insertedCount);
    }

    private record MergeResult(List<ItemStack> inventory, List<ItemStack> remaining, long insertedCount) {}

    public static String serializeEffects(ServerPlayer player) {
        JsonArray array = new JsonArray();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            JsonObject object = new JsonObject();
            Identifier id = BuiltInRegistries.MOB_EFFECT.getKey(effect.getEffect().value());
            object.addProperty("effect", id == null ? "" : id.toString());
            object.addProperty("amplifier", effect.getAmplifier());
            object.addProperty("duration", effect.getDuration());
            object.addProperty("ambient", effect.isAmbient());
            object.addProperty("showParticles", effect.isVisible());
            object.addProperty("showIcon", effect.showIcon());
            array.add(object);
        }
        return GSON.toJson(array);
    }

    public static String serializeAdvancements(ServerPlayer player) {
        return PlayerProgressCodec.advancements(player);
    }

    public static String serializeStatistics(ServerPlayer player) {
        return PlayerProgressCodec.statistics(player);
    }

    public static String serializeRecipeBook(ServerPlayer player) {
        return PlayerRecipeCodec.capture(player);
    }

    public static String serializeSkinData(ServerPlayer player) {
        return PlayerProfileCodec.capture(player);
    }

    /** DB snapshotをMC thread上で事前検証する。ここではplayer stateを変更しない。 */
    public static PreparedPlayerData prepare(
        ServerPlayer player,
        CompletePlayerData data,
        Configuration.SyncConfig sync
    ) {
        if (data == null || data.uuid == null || !data.uuid.equals(player.getUUID())) {
            throw new IllegalArgumentException("PlayerData UUID does not match current player");
        }
        if (sync == null) sync = new Configuration.SyncConfig();
        if (sync.position != sync.dimensionEnabled()) throw invalid("position/dimension must be paired");

        // sync=trueの列がNULL/空の場合に、local/default stateをそのままREADYにして
        // 次回SAVEするのは、DBの欠損値による上書きにつながる。authoritative payload
        // がない場合は適用せず継続するのではなく、fail closedする。
        List<ItemStack> inventory = sync.inventory
            ? decodeInventory(player, requireAuthoritativeJson(data.inventoryJson, "inventory"),
                player.getInventory().getNonEquipmentItems().size(), true) : null;
        List<ItemStack> pendingItems = sync.inventory
            ? decodePendingItems(player, data.pendingDisconnectItemsJson) : List.of();
        boolean pendingNormalizationRequired = false;
        String normalizedPendingJson = sync.inventory ? data.pendingDisconnectItemsJson : null;
        if (sync.inventory && inventory != null && !pendingItems.isEmpty()) {
            MergeResult merge = mergeIntoMainInventory(inventory, pendingItems);
            inventory = merge.inventory;
            pendingItems = merge.remaining;
            pendingNormalizationRequired = merge.insertedCount > 0;
            if (pendingNormalizationRequired) normalizedPendingJson = pendingItemsToJson(pendingItems);
        }
        List<ItemStack> enderChest = sync.enderchest
            ? decodeInventory(player, requireAuthoritativeJson(data.enderchestJson, "enderchest"),
                player.getEnderChestInventory().getContainerSize(), false) : null;
        List<ItemStack> armor = sync.armor
            ? decodeInventory(player, requireAuthoritativeJson(data.armorJson, "armor"),
                com.atsukigames.statelink.utils.PlayerEquipment.armor(player).size(), false) : null;
        List<ItemStack> offhand = sync.offhand
            ? decodeInventory(player, requireAuthoritativeJson(data.offhandJson, "offhand"),
                com.atsukigames.statelink.utils.PlayerEquipment.offhand(player).size(), false) : null;
        List<MobEffectInstance> effects = sync.effects
            ? decodeEffects(requireAuthoritativeJson(data.effectsJson, "effects")) : null;

        if (sync.health) {
            requireFinite(data.health, "health");
            if (data.health < 0.0 || data.health > 1024.0) throw invalid("health range");
            if (data.air < 0 || data.air > 1_000_000) throw invalid("air range");
        }
        if (sync.food) {
            if (data.foodLevel < 0 || data.foodLevel > 20) throw invalid("food level range");
            requireFinite(data.saturation, "saturation");
            if (data.saturation < 0.0F || data.saturation > data.foodLevel) throw invalid("saturation range");
            requireFinite(data.exhaustion, "exhaustion");
            if (data.exhaustion < 0.0F || data.exhaustion > 40.0F) throw invalid("exhaustion range");
        }
        if (sync.experience) {
            if (data.experienceLevel < 0 || data.experiencePoints < 0) throw invalid("experience range");
            requireFinite(data.experienceTotal, "experience total");
            if (data.experienceTotal < 0.0F) throw invalid("experience total range");
            if (data.experiencePointsIntoLevel != null
                    && (data.experiencePointsIntoLevel < 0
                        || data.experiencePointsIntoLevel >= ExperienceProgress.experienceToNextLevel(
                            data.experienceLevel))) {
                throw invalid("experience points into level range");
            }
            if (data.experienceProgress != null
                    && (!Float.isFinite(data.experienceProgress)
                        || data.experienceProgress < 0.0F || data.experienceProgress >= 1.0F)) {
                throw invalid("experience progress range");
            }
        }
        if (sync.dimensionEnabled()) {
            if (data.dimension == null || Identifier.tryParse(data.dimension) == null) throw invalid("dimension");
            if (player.level().getServer().getLevel(net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(data.dimension))) == null) throw invalid("unknown dimension");
        }
        if (sync.position) {
            validateCoordinate(data.posX, "posX");
            validateCoordinate(data.posY, "posY");
            validateCoordinate(data.posZ, "posZ");
        }
        if (sync.rotationEnabled()) {
            requireFinite(data.yaw, "yaw");
            requireFinite(data.pitch, "pitch");
            if (data.pitch < -90.0F || data.pitch > 90.0F) throw invalid("pitch range");
        }
        GameType gameMode = null;
        if (sync.gamemode) {
            if (data.gamemode == null) throw invalid("gamemode");
            gameMode = GameType.byName(data.gamemode, null);
            if (gameMode == null) throw invalid("unknown gamemode: " + data.gamemode);
        }
        if (sync.inventory && (data.selectedItemSlot < 0 || data.selectedItemSlot >= player.getInventory().getNonEquipmentItems().size() / 4)) {
            throw invalid("selected item slot range");
        }

        PreparedPlayerData prepared = new PreparedPlayerData(
            data,
            inventory,
            enderChest,
            armor,
            offhand,
            effects,
            gameMode,
            sync.health,
            sync.food,
            sync.experience,
            sync.position,
            sync.dimensionEnabled(),
            sync.rotationEnabled(),
            sync.gamemode,
            sync.inventory,
            normalizedPendingJson,
            pendingNormalizationRequired
        );
        if (sync.advancements) prepared.advancements = PlayerProgressCodec.prepareAdvancements(player, data.advancementsJson);
        if (sync.statistics) prepared.statistics = PlayerProgressCodec.prepareStatistics(player, data.statisticsJson);
        if (sync.playerProfile) prepared.profile = PlayerProfileCodec.prepare(player, data.skinTexture, data.displayName);
        if (sync.recipeBook) prepared.recipes = PlayerRecipeCodec.prepare(player, data.recipeBookJson);
        return prepared;
    }

    /** 事前検証済みデータを適用し、途中失敗時は変更前のstateへ戻す。 */
    public static void applyPrepared(ServerPlayer player, PreparedPlayerData prepared) {
        PlayerStateBackup backup = new PlayerStateBackup(player, prepared);
        var advancementBackup = prepared.advancements == null ? null
            : PlayerProgressCodec.prepareAdvancements(player, PlayerProgressCodec.advancements(player));
        var statisticBackup = prepared.statistics == null ? null
            : PlayerProgressCodec.prepareStatistics(player, PlayerProgressCodec.statistics(player));
        var profileBackup = prepared.profile == null ? null
            : PlayerProfileCodec.prepare(player, PlayerProfileCodec.capture(player), player.getDisplayName().getString());
        var recipeBackup = prepared.recipes == null ? null
            : PlayerRecipeCodec.prepare(player, PlayerRecipeCodec.capture(player));
        try {
            if (prepared.inventory != null) replaceInventory(player.getInventory().getNonEquipmentItems(), prepared.inventory);
            if (prepared.enderChest != null) replaceInventory(player.getEnderChestInventory(), prepared.enderChest);
            if (prepared.armor != null) replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.armor(player), prepared.armor);
            if (prepared.offhand != null) replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.offhand(player), prepared.offhand);

            if (prepared.applyHealth) {
                player.setHealth((float) prepared.data.health);
                player.setAirSupply(prepared.data.air);
            }
            if (prepared.applyFood) {
                player.getFoodData().setFoodLevel(prepared.data.foodLevel);
                player.getFoodData().setSaturation(prepared.data.saturation);
                ((com.atsukigames.statelink.mixin.HungerManagerAccessor) player.getFoodData()).statelink$setExhaustion(prepared.data.exhaustion);
            }
            if (prepared.applyExperience) {
                player.setExperienceLevels(prepared.data.experienceLevel);
                player.experienceProgress = ExperienceProgress.resolve(
                    prepared.data.experienceLevel,
                    prepared.data.experiencePoints,
                    prepared.data.experiencePointsIntoLevel,
                    prepared.data.experienceProgress);
                // experience_points has always stored cumulative XP in 2.1.3.
                player.totalExperience = prepared.data.experiencePoints;
            }
            if (prepared.effects != null) {
                player.removeAllEffects();
                for (MobEffectInstance effect : prepared.effects) {
                    player.addEffect(new MobEffectInstance(effect));
                }
            }
            if (prepared.applyPosition) {
                var world = prepared.applyDimension ? player.level().getServer().getLevel(
                    net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, Identifier.parse(prepared.data.dimension)))
                    : player.level();
                float yaw = prepared.applyRotation ? prepared.data.yaw : player.getYRot();
                float pitch = prepared.applyRotation ? prepared.data.pitch : player.getXRot();
                try (var ignored = SpatialMutationScope.authoritativeApply(player)) {
                    player.teleportTo(world, prepared.data.posX, prepared.data.posY, prepared.data.posZ, java.util.Set.of(), yaw, pitch, false);
                }
            } else if (prepared.applyRotation) {
                // Rotation-only never invokes teleport/setPos/setWorld or closes a screen.
                player.setYRot(prepared.data.yaw);
                player.setXRot(prepared.data.pitch);
                player.setYHeadRot(prepared.data.yaw);
                player.connection.send(new net.minecraft.network.protocol.game.ClientboundMoveEntityPacket.Rot(
                    player.getId(), (byte) (prepared.data.yaw * 256 / 360),
                    (byte) (prepared.data.pitch * 256 / 360), player.onGround()));
            }
            if (prepared.applyGameMode && prepared.gameMode != null) {
                player.setGameMode(prepared.gameMode);
                player.getAbilities().mayfly = prepared.data.allowFlying;
                player.getAbilities().flying = prepared.data.isFlying && prepared.data.allowFlying;
                player.onUpdateAbilities();
            }
            if (prepared.applySelectedSlot) player.getInventory().setSelectedSlot(prepared.data.selectedItemSlot);
            PlayerProgressCodec.applyAdvancements(player, prepared.advancements);
            PlayerProgressCodec.applyStatistics(player, prepared.statistics);
            PlayerProfileCodec.apply(player, prepared.profile);
            PlayerRecipeCodec.apply(player, prepared.recipes);

            if (prepared.inventory != null || prepared.armor != null || prepared.offhand != null) {
                player.getInventory().setChanged();
                player.inventoryMenu.sendAllDataToRemote();
            }
            if (prepared.enderChest != null) player.getEnderChestInventory().setChanged();
        } catch (Throwable error) {
            try {
                backup.restore(player);
                PlayerProgressCodec.applyAdvancements(player, advancementBackup);
                PlayerProgressCodec.applyStatistics(player, statisticBackup);
                PlayerProfileCodec.apply(player, profileBackup);
                PlayerRecipeCodec.apply(player, recipeBackup);
            } catch (Throwable rollbackError) {
                error.addSuppressed(rollbackError);
            }
            if (error instanceof RuntimeException runtimeException) throw runtimeException;
            throw new IllegalStateException("Failed to apply authoritative player data", error);
        }
    }

    /** 既存APIはstrict parserを使う。失敗時は部分適用せず例外を返す。 */
    public static void applyInventoryJson(ServerPlayer player, String json) {
        if (json == null || json.isBlank()) return;
        applyPrepared(player, prepareSingleInventory(player, json, true));
    }

    public static void applyEnderChestJson(ServerPlayer player, String json) {
        if (json == null || json.isBlank()) return;
        applyPrepared(player, prepareSingleInventory(player, json, false));
    }

    public static void applyArmorJson(ServerPlayer player, String json) {
        if (json == null || json.isBlank()) return;
        List<ItemStack> armor = decodeInventory(player, json, com.atsukigames.statelink.utils.PlayerEquipment.armor(player).size(), false);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.armor(player), armor);
            player.inventoryMenu.sendAllDataToRemote();
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyOffhandJson(ServerPlayer player, String json) {
        if (json == null || json.isBlank()) return;
        List<ItemStack> offhand = decodeInventory(player, json, com.atsukigames.statelink.utils.PlayerEquipment.offhand(player).size(), false);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.offhand(player), offhand);
            player.inventoryMenu.sendAllDataToRemote();
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyEffectsJson(ServerPlayer player, String json) {
        if (json == null || json.isBlank()) return;
        List<MobEffectInstance> effects = decodeEffects(json);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            player.removeAllEffects();
            for (MobEffectInstance effect : effects) player.addEffect(new MobEffectInstance(effect));
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyPositionAndRotation(
        ServerPlayer player,
        String dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
        validateCoordinate(x, "posX");
        validateCoordinate(y, "posY");
        validateCoordinate(z, "posZ");
        requireFinite(yaw, "yaw");
        requireFinite(pitch, "pitch");
        player.setPosRaw(x, y, z);
        player.setYRot(yaw);
        player.setXRot(pitch);
    }

    public static void applyGameMode(ServerPlayer player, String gamemode, boolean isFlying, boolean allowFlying) {
        GameType mode = GameType.byName(gamemode, null);
        if (mode == null) throw invalid("unknown gamemode: " + gamemode);
        player.setGameMode(mode);
        player.getAbilities().mayfly = allowFlying;
        player.getAbilities().flying = isFlying && allowFlying;
        player.onUpdateAbilities();
    }

    public static final class PreparedPlayerData {
        private Map<net.minecraft.advancements.AdvancementHolder, net.minecraft.advancements.AdvancementProgress> advancements;
        private Map<net.minecraft.stats.Stat<?>, Integer> statistics;
        private com.mojang.authlib.properties.PropertyMap profile;
        private net.minecraft.stats.ServerRecipeBook recipes;
        private final CompletePlayerData data;
        private final List<ItemStack> inventory;
        private final List<ItemStack> enderChest;
        private final List<ItemStack> armor;
        private final List<ItemStack> offhand;
        private final List<MobEffectInstance> effects;
        private final GameType gameMode;
        private final boolean applyHealth;
        private final boolean applyFood;
        private final boolean applyExperience;
        private final boolean applyPosition;
        private final boolean applyDimension;
        private final boolean applyRotation;
        private final boolean applyGameMode;
        private final boolean applySelectedSlot;
        private final String pendingDisconnectItemsJson;
        private final boolean pendingNormalizationRequired;

        private PreparedPlayerData(
            CompletePlayerData data,
            List<ItemStack> inventory,
            List<ItemStack> enderChest,
            List<ItemStack> armor,
            List<ItemStack> offhand,
            List<MobEffectInstance> effects,
            GameType gameMode,
            boolean applyHealth,
            boolean applyFood,
            boolean applyExperience,
            boolean applyPosition,
            boolean applyDimension,
            boolean applyRotation,
            boolean applyGameMode,
            boolean applySelectedSlot,
            String pendingDisconnectItemsJson,
            boolean pendingNormalizationRequired
        ) {
            this.data = data;
            this.inventory = inventory;
            this.enderChest = enderChest;
            this.armor = armor;
            this.offhand = offhand;
            this.effects = effects;
            this.gameMode = gameMode;
            this.applyHealth = applyHealth;
            this.applyFood = applyFood;
            this.applyExperience = applyExperience;
            this.applyPosition = applyPosition;
            this.applyDimension = applyDimension;
            this.applyRotation = applyRotation;
            this.applyGameMode = applyGameMode;
            this.applySelectedSlot = applySelectedSlot;
            this.pendingDisconnectItemsJson = pendingDisconnectItemsJson;
            this.pendingNormalizationRequired = pendingNormalizationRequired;
        }

        public boolean pendingNormalizationRequired() {
            return pendingNormalizationRequired;
        }

        public String pendingDisconnectItemsJson() {
            return pendingDisconnectItemsJson;
        }

        /**
         * Creates the database payload that replaces consumed pending items with
         * their planned main-inventory slots. This is persisted before READY/apply.
         */
        public CompletePlayerData normalizedCompleteData() {
            if (!pendingNormalizationRequired || inventory == null) {
                throw new IllegalStateException("No pending inventory normalization was prepared");
            }
            return data.withInventoryAndPendingItems(
                itemListToJson(inventory), pendingDisconnectItemsJson);
        }
    }

    private static PreparedPlayerData prepareSingleInventory(ServerPlayer player, String json, boolean main) {
        List<ItemStack> items = decodeInventory(
            player,
            json,
            main ? player.getInventory().getNonEquipmentItems().size() : player.getEnderChestInventory().getContainerSize(),
            main
        );
        return new PreparedPlayerData(
            new CompletePlayerData(
                player.getUUID(), player.getName().getString(), null, null, null, null,
                player.getHealth(), player.getFoodData().getFoodLevel(), player.getFoodData().getSaturationLevel(),
                ((com.atsukigames.statelink.mixin.HungerManagerAccessor) player.getFoodData()).statelink$getExhaustion(), player.getAirSupply(), player.experienceLevel,
                player.totalExperience, (float) player.totalExperience, player.experienceProgress,
                null, player.level().dimension().identifier().toString(), player.getX(), player.getY(), player.getZ(),
                player.getYRot(), player.getXRot(), player.gameMode.getGameModeForPlayer().getName(),
                player.getAbilities().flying, player.getAbilities().mayfly, false,
                player.getName().getString(), null, null, null, null, null, player.getInventory().getSelectedSlot(), 0, 1
            ),
            main ? items : null,
            main ? null : items,
            null,
            null,
            null,
            null,
            false, false, false, false, false, false, false, false, null, false
        );
    }

    private static List<ItemStack> decodeInventory(
        ServerPlayer player,
        String json,
        int size,
        boolean mainInventory
    ) {
        List<ItemStack> result = emptyStacks(size);
        if (json == null || json.isBlank()) return result;
        JsonArray array;
        try {
            array = GSON.fromJson(json, JsonArray.class);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid inventory JSON", error);
        }
        if (array == null) throw invalid("inventory JSON is not an array");
        Set<Integer> slots = new HashSet<>();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw invalid("inventory entry is not an object");
            JsonObject object = element.getAsJsonObject();
            int slot = requiredInt(object, "slot");
            // 旧版はPlayerInventory全体をinventory columnへ入れていたため、36-40は重複分として無視する。
            if (mainInventory && slot >= size && slot < size + 5) continue;
            if (slot < 0 || slot >= size) throw invalid("inventory slot range: " + slot);
            if (!slots.add(slot)) throw invalid("duplicate inventory slot: " + slot);
            result.set(slot, decodeItem(player, object));
        }
        return result;
    }

    private static String requireAuthoritativeJson(String json, String field) {
        if (json == null || json.isBlank()) throw invalid("missing authoritative " + field);
        return json;
    }

    private static List<MobEffectInstance> decodeEffects(String json) {
        List<MobEffectInstance> result = new ArrayList<>();
        if (json == null || json.isBlank()) return result;
        JsonArray array;
        try {
            array = GSON.fromJson(json, JsonArray.class);
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid effects JSON", error);
        }
        if (array == null) throw invalid("effects JSON is not an array");
        for (JsonElement element : array) {
            if (!element.isJsonObject()) throw invalid("effect entry is not an object");
            JsonObject object = element.getAsJsonObject();
            String rawId = object.has("effect") ? requiredString(object, "effect") : requiredString(object, "id");
            Identifier id = Identifier.tryParse(rawId);
            if (id == null) throw invalid("invalid effect identifier: " + rawId);
            if (!BuiltInRegistries.MOB_EFFECT.containsKey(id)) throw invalid("unknown effect: " + rawId);
            MobEffect effect = BuiltInRegistries.MOB_EFFECT.getValue(id);
            int amplifier = requiredInt(object, "amplifier");
            int duration = requiredInt(object, "duration");
            if (amplifier < 0 || amplifier > 255) throw invalid("effect amplifier range");
            if (duration < 0) throw invalid("effect duration range");
            result.add(new MobEffectInstance(
                BuiltInRegistries.MOB_EFFECT.wrapAsHolder(effect),
                duration,
                amplifier,
                requiredBoolean(object, "ambient"),
                requiredBoolean(object, "showParticles"),
                requiredBoolean(object, "showIcon")
            ));
        }
        return result;
    }

    private static ItemStack decodeItem(ServerPlayer player, JsonObject object) {
        if (!object.has("item")) throw invalid("item identifier is missing");
        ItemStack stack;
        if (object.has("nbt")) {
            String snbt = requiredString(object, "nbt");
            try {
                Tag element = TagParser.parseCompoundFully(snbt);
                if (!(element instanceof CompoundTag compound)) throw invalid("item NBT is not a compound");
                stack = ItemStack.CODEC.parse(RegistryContext.lookup().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), compound)
                    .result().orElseThrow(() -> invalid("item NBT does not describe an item"));
            } catch (Exception error) {
                throw new IllegalArgumentException("invalid item NBT", error);
            }
        } else {
            String rawId = requiredString(object, "item");
            Identifier id = Identifier.tryParse(rawId);
            if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) throw invalid("unknown item: " + rawId);
            int count = requiredInt(object, "count");
            Item item = BuiltInRegistries.ITEM.getValue(id);
            if (count <= 0 || count > item.getDefaultMaxStackSize()) throw invalid("item count range");
            stack = new ItemStack(item, count);
        }

        if (stack.isEmpty()) throw invalid("decoded item is empty");
        if (stack.getCount() <= 0 || stack.getCount() > stack.getMaxStackSize()) throw invalid("decoded item count range");
        if (object.has("damage")) {
            int damage = requiredInt(object, "damage");
            if (!stack.isDamageableItem() || damage < 0 || damage > stack.getMaxDamage()) {
                throw invalid("item damage range");
            }
            stack.setDamageValue(damage);
        }
        return stack;
    }

    private static void replaceInventory(List<ItemStack> target, List<ItemStack> source) {
        if (target.size() != source.size()) throw invalid("inventory size mismatch");
        for (int i = 0; i < target.size(); i++) target.set(i, source.get(i).copy());
    }

    private static void replaceInventory(Container target, List<ItemStack> source) {
        if (target.getContainerSize() != source.size()) throw invalid("inventory size mismatch");
        for (int i = 0; i < target.getContainerSize(); i++) target.setItem(i, source.get(i).copy());
    }

    private static List<ItemStack> emptyStacks(int size) {
        List<ItemStack> result = new ArrayList<>(size);
        for (int i = 0; i < size; i++) result.add(ItemStack.EMPTY);
        return result;
    }

    private static int requiredInt(JsonObject object, String key) {
        if (!object.has(key)) throw invalid("missing " + key);
        try {
            return object.get(key).getAsInt();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid integer: " + key, error);
        }
    }

    private static String requiredString(JsonObject object, String key) {
        if (!object.has(key) || object.get(key).isJsonNull()) throw invalid("missing " + key);
        try {
            return object.get(key).getAsString();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid string: " + key, error);
        }
    }

    private static boolean requiredBoolean(JsonObject object, String key) {
        if (!object.has(key)) throw invalid("missing " + key);
        try {
            return object.get(key).getAsBoolean();
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("invalid boolean: " + key, error);
        }
    }

    private static void requireFinite(double value, String name) {
        if (!Double.isFinite(value)) throw invalid(name + " must be finite");
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) throw invalid(name + " must be finite");
    }

    private static void validateCoordinate(double value, String name) {
        requireFinite(value, name);
        if (value < -30_000_000.0 || value > 30_000_000.0) throw invalid(name + " range");
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }

    private static JsonObject itemStackToJson(ServerPlayer player, int slot, ItemStack stack) {
        JsonObject object = new JsonObject();
        object.addProperty("slot", slot);
        object.addProperty("item", BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
        object.addProperty("count", stack.getCount());
        if (stack.isDamageableItem()) object.addProperty("damage", stack.getDamageValue());
        CompoundTag compound = new CompoundTag();
        writeNbtOrThrow(compound, () -> {
            if (ItemStack.CODEC.encodeStart(RegistryContext.lookup().createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE), stack)
                .getOrThrow() instanceof CompoundTag encoded) compound.merge(encoded);
        }, object.get("item").getAsString());
        if (!compound.isEmpty()) object.addProperty("nbt", compound.toString());
        return object;
    }

    /** Package-visible fault seam: a failed full-stack encoding must abort the snapshot. */
    static void writeNbtOrThrow(CompoundTag target, Runnable writer, String itemId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        try {
            writer.run();
        } catch (RuntimeException error) {
            throw new SnapshotSerializationException(
                "Could not serialize complete NBT for item " + itemId, error);
        }
    }

    private static String inventoryToJson(ServerPlayer player, Container inventory) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < inventory.getContainerSize(); i++) {
            ItemStack stack = inventory.getItem(i);
            if (!stack.isEmpty()) array.add(itemStackToJson(null, i, stack));
        }
        return GSON.toJson(array);
    }

    private static String itemListToJson(ServerPlayer player, List<ItemStack> stacks) {
        return itemListToJson(stacks);
    }

    private static String itemListToJson(List<ItemStack> stacks) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < stacks.size(); i++) {
            ItemStack stack = stacks.get(i);
            if (!stack.isEmpty()) array.add(itemStackToJson(null, i, stack));
        }
        return GSON.toJson(array);
    }

    private static final class PlayerStateBackup {
        private final PreparedPlayerData selection;
        private final List<ItemStack> main;
        private final List<ItemStack> armor;
        private final List<ItemStack> offhand;
        private final List<ItemStack> enderChest;
        private final List<MobEffectInstance> effects;
        private final float health;
        private final int air;
        private final int food;
        private final float saturation;
        private final float exhaustion;
        private final int experienceLevel;
        private final float experienceProgress;
        private final int totalExperience;
        private final double x;
        private final double y;
        private final double z;
        private final net.minecraft.server.level.ServerLevel world;
        private final float yaw;
        private final float pitch;
        private final GameType gameMode;
        private final boolean flying;
        private final boolean allowFlying;
        private final int selectedSlot;

        private PlayerStateBackup(ServerPlayer player, PreparedPlayerData selection) {
            this.selection = selection;
            this.main = selection == null || selection.inventory != null ? copyStacks(player.getInventory().getNonEquipmentItems()) : null;
            this.armor = selection == null || selection.armor != null ? copyStacks(com.atsukigames.statelink.utils.PlayerEquipment.armor(player)) : null;
            this.offhand = selection == null || selection.offhand != null ? copyStacks(com.atsukigames.statelink.utils.PlayerEquipment.offhand(player)) : null;
            this.enderChest = selection == null || selection.enderChest != null ? copyStacks(player.getEnderChestInventory()) : null;
            this.effects = selection == null || selection.effects != null
                ? player.getActiveEffects().stream().map(MobEffectInstance::new).toList() : null;
            this.health = player.getHealth();
            this.air = player.getAirSupply();
            this.food = player.getFoodData().getFoodLevel();
            this.saturation = player.getFoodData().getSaturationLevel();
            this.exhaustion = ((com.atsukigames.statelink.mixin.HungerManagerAccessor) player.getFoodData()).statelink$getExhaustion();
            this.experienceLevel = player.experienceLevel;
            this.experienceProgress = player.experienceProgress;
            this.totalExperience = player.totalExperience;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.world = player.level();
            this.yaw = player.getYRot();
            this.pitch = player.getXRot();
            this.gameMode = player.gameMode.getGameModeForPlayer();
            this.flying = player.getAbilities().flying;
            this.allowFlying = player.getAbilities().mayfly;
            this.selectedSlot = selection == null || selection.applySelectedSlot ? player.getInventory().getSelectedSlot() : 0;
        }

        static PlayerStateBackup capture(ServerPlayer player) {
            return new PlayerStateBackup(player, null);
        }

        void restore(ServerPlayer player) {
            if (main != null) replaceInventory(player.getInventory().getNonEquipmentItems(), main);
            if (armor != null) replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.armor(player), armor);
            if (offhand != null) replaceInventory(com.atsukigames.statelink.utils.PlayerEquipment.offhand(player), offhand);
            if (enderChest != null) replaceInventory(player.getEnderChestInventory(), enderChest);
            if (effects != null) {
                player.removeAllEffects();
                for (MobEffectInstance effect : effects) player.addEffect(new MobEffectInstance(effect));
            }
            if (selection == null || selection.applyHealth) {
            player.setHealth(health);
            player.setAirSupply(air);
            }
            if (selection == null || selection.applyFood) {
            player.getFoodData().setFoodLevel(food);
            player.getFoodData().setSaturation(saturation);
            ((com.atsukigames.statelink.mixin.HungerManagerAccessor) player.getFoodData()).statelink$setExhaustion(exhaustion);
            }
            if (selection == null || selection.applyExperience) {
            player.setExperienceLevels(experienceLevel);
            player.experienceProgress = experienceProgress;
            player.totalExperience = totalExperience;
            }
            if (selection == null || selection.applyPosition) {
            try (var ignored = SpatialMutationScope.authoritativeApply(player)) {
                player.teleportTo(world, x, y, z, java.util.Set.of(), yaw, pitch, false);
            }
            }
            if (selection == null || selection.applyRotation) {
            player.setYRot(yaw);
            player.setXRot(pitch);
            }
            if (selection == null || selection.applyGameMode) {
            player.setGameMode(gameMode);
            player.getAbilities().mayfly = allowFlying;
            player.getAbilities().flying = flying;
            }
            if (selection == null || selection.applySelectedSlot) player.getInventory().setSelectedSlot(selectedSlot);
            if (main != null || armor != null || offhand != null) {
                player.getInventory().setChanged();
                player.inventoryMenu.sendAllDataToRemote();
            }
            if (enderChest != null) player.getEnderChestInventory().setChanged();
        }

        private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
            return stacks.stream().map(ItemStack::copy).toList();
        }

        private static List<ItemStack> copyStacks(Container inventory) {
            List<ItemStack> result = new ArrayList<>(inventory.getContainerSize());
            for (int i = 0; i < inventory.getContainerSize(); i++) result.add(inventory.getItem(i).copy());
            return result;
        }
    }

    private CompletePlayerDataSerializer() {
        throw new UnsupportedOperationException("Utility class");
    }
}
