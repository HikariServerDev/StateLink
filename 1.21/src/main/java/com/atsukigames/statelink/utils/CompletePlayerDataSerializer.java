package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.CraftingInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.screen.AbstractRecipeScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.util.Identifier;
import net.minecraft.registry.Registries;
import net.minecraft.world.GameMode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * PlayerDataのMinecraft側シリアライザ。
 *
 * <p>復元は、先に全対象データをparse・registry lookup・range validationしてから
 * Minecraft stateへ触れる。apply中に例外が出た場合は、変更前のローカルstateを
 * rollbackし、呼び出し側がそのsessionを切断できるよう例外を返す。</p>
 */
public final class CompletePlayerDataSerializer {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String serializeInventory(ServerPlayerEntity player) {
        // PlayerInventory全体を保存するとarmor/offhandが別columnと重複するため、mainのみ保存する。
        return itemListToJson(player, player.getInventory().main);
    }

    public static String serializeEnderChest(ServerPlayerEntity player) {
        return inventoryToJson(player, player.getEnderChestInventory());
    }

    public static String serializeArmor(ServerPlayerEntity player) {
        return itemListToJson(player, player.getInventory().armor);
    }

    public static String serializeOffhand(ServerPlayerEntity player) {
        return itemListToJson(player, player.getInventory().offHand);
    }

    /**
     * Read-only copy of player-owned transient stacks at this instant. This is
     * also used by live checkpoints: callers must not clear or mutate the
     * player's cursor, screen, or inventories here. Cursors from both
     * Player-referenced handlers, CraftingInventory slots, and explicitly
     * classified vanilla inputs are included.
     */
    public static List<ItemStack> captureActiveTransientItems(ServerPlayerEntity player) {
        return captureTransientState(player).items();
    }

    /** Server-thread-only raw views for change detection. Never escape to a worker or clear here. */
    public static List<ItemStack> ownedTransientViewsForDirtyDetection(ServerPlayerEntity player) {
        var handlers = collectRelevantScreenHandlers(player.currentScreenHandler, player.playerScreenHandler);
        List<ItemStack> values = new ArrayList<>();
        for (ScreenHandler handler : handlers) values.add(handler.getCursorStack());
        for (Slot slot : disconnectTransientSlots(player, handlers)) values.add(slot.getStack());
        return values;
    }

    /**
     * Captures both transient item values and the exact source objects from one
     * identity-distinct handler set. Disconnect cleanup later clears these same
     * sources only after the complete immutable PlayerData payload exists.
     */
    public static TransientCapture captureTransientState(ServerPlayerEntity player) {
        List<ScreenHandler> handlers = collectRelevantScreenHandlers(
            player.currentScreenHandler, player.playerScreenHandler);
        List<OwnedItemSources.Snapshot<ScreenHandler, ItemStack>> cursors = collectCursorSources(handlers);
        List<ItemStack> items = new ArrayList<>();

        for (OwnedItemSources.Snapshot<ScreenHandler, ItemStack> cursor : cursors) {
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
        ServerPlayerEntity player,
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
        ServerPlayerEntity player,
        TransientCapture capture
    ) {
        Objects.requireNonNull(capture, "capture");
        if (capture.player != player) {
            throw new IllegalStateException("disconnect transient capture belongs to another player");
        }
        List<ScreenHandler> currentHandlers = collectRelevantScreenHandlers(
            player.currentScreenHandler, player.playerScreenHandler);
        if (!sameHandlerIdentities(capture.handlers, currentHandlers)) {
            throw new IllegalStateException("player screen handlers changed during disconnect capture");
        }

        // Validate every source before mutating any source. This prevents a
        // re-entrant/modded change during serialization from causing a partial
        // transfer into the durable snapshot.
        validateCapturedCursorSources(capture.cursors);
        for (TransientInventorySlot input : capture.inputSlots) {
            if (!ItemStack.areEqual(input.slot().getStack(), input.capturedStack())) {
                throw new IllegalStateException("temporary screen input changed during disconnect capture");
            }
        }

        OwnedItemSources.clearIfUnchanged(
            capture.cursors,
            ScreenHandler::getCursorStack,
            ItemStack::areEqual,
            ItemStack::isEmpty,
            (handler, ignored) -> handler.setCursorStack(ItemStack.EMPTY));
        for (TransientInventorySlot input : capture.inputSlots) {
            if (!input.capturedStack().isEmpty()) input.slot().setStack(ItemStack.EMPTY);
        }
        for (ScreenHandler handler : capture.handlers) {
            if (DisconnectTransientInventoryPolicy.shouldClearCraftingResult(handler.getClass())) {
                ((AbstractRecipeScreenHandler<?, ?>) handler).clearCraftingSlots();
            }
        }
        player.onHandledScreenClosed();
    }

    /** Adds captured stacks after any already-pending stacks, preserving their order and full NBT. */
    public static String appendPendingDisconnectItems(
        ServerPlayerEntity player,
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
    static List<ScreenHandler> collectRelevantScreenHandlers(
        ScreenHandler currentScreenHandler,
        ScreenHandler playerScreenHandler
    ) {
        return OwnedItemSources.identityDistinct(currentScreenHandler, playerScreenHandler);
    }

    static List<OwnedItemSources.Snapshot<ScreenHandler, ItemStack>> collectCursorSources(
        List<ScreenHandler> handlers
    ) {
        return OwnedItemSources.capture(handlers, ScreenHandler::getCursorStack, ItemStack::copy);
    }

    private static void validateCapturedCursorSources(
        List<OwnedItemSources.Snapshot<ScreenHandler, ItemStack>> cursors
    ) {
        for (OwnedItemSources.Snapshot<ScreenHandler, ItemStack> cursor : cursors) {
            if (!ItemStack.areEqual(cursor.owner().getCursorStack(), cursor.value())) {
                throw new IllegalStateException("screen cursor changed during disconnect capture");
            }
        }
    }

    private static boolean sameHandlerIdentities(List<ScreenHandler> left, List<ScreenHandler> right) {
        if (left.size() != right.size()) return false;
        for (int index = 0; index < left.size(); index++) {
            if (left.get(index) != right.get(index)) return false;
        }
        return true;
    }

    private static List<Slot> disconnectTransientSlots(
        ServerPlayerEntity player,
        List<ScreenHandler> handlers
    ) {
        Map<Inventory, Set<Integer>> seen = new IdentityHashMap<>();
        List<Slot> inputs = new ArrayList<>();
        for (ScreenHandler handler : handlers) {
            for (Slot slot : handler.slots) {
                if (slot.inventory instanceof CraftingInventory) {
                    addTransientSlot(inputs, seen, slot);
                }
            }
            for (int screenSlotId : DisconnectTransientInventoryPolicy.inputScreenSlotIds(handler.getClass())) {
                if (screenSlotId < 0 || screenSlotId >= handler.slots.size()) {
                    throw new IllegalStateException("vanilla disconnect input slot is missing from screen handler");
                }
                Slot slot = handler.slots.get(screenSlotId);
                if (slot.inventory == player.getInventory()) {
                    throw new IllegalStateException("disconnect input policy resolved to persistent player inventory");
                }
                addTransientSlot(inputs, seen, slot);
            }
        }
        return List.copyOf(inputs);
    }

    private static List<TransientInventorySlot> captureTransientSlots(ServerPlayerEntity player, List<Slot> slots) {
        List<TransientInventorySlot> captured = new ArrayList<>(slots.size());
        for (Slot slot : slots) {
            if (slot.inventory == player.getInventory()) {
                throw new IllegalStateException("disconnect input policy resolved to persistent player inventory");
            }
            captured.add(new TransientInventorySlot(slot, slot.getStack().copy()));
        }
        return List.copyOf(captured);
    }

    private static void addTransientSlot(
        List<Slot> inputs,
        Map<Inventory, Set<Integer>> seen,
        Slot slot
    ) {
        Set<Integer> indexes = seen.computeIfAbsent(slot.inventory, ignored -> new HashSet<>());
        int inventorySlot = slot.getIndex();
        if (inventorySlot < 0 || inventorySlot >= slot.inventory.size()) {
            throw new IllegalStateException("disconnect input slot resolves outside its backing inventory");
        }
        if (indexes.add(inventorySlot)) inputs.add(slot);
    }

    private record TransientInventorySlot(Slot slot, ItemStack capturedStack) {
    }

    public static final class TransientCapture {
        private final ServerPlayerEntity player;
        private final List<ScreenHandler> handlers;
        private final List<OwnedItemSources.Snapshot<ScreenHandler, ItemStack>> cursors;
        private final List<TransientInventorySlot> inputSlots;
        private final List<ItemStack> items;

        private TransientCapture(
            ServerPlayerEntity player,
            List<ScreenHandler> handlers,
            List<OwnedItemSources.Snapshot<ScreenHandler, ItemStack>> cursors,
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

    private static List<ItemStack> decodePendingItems(ServerPlayerEntity player, String json) {
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
                if (existing.isEmpty() || !ItemStack.areItemsAndComponentsEqual(existing, stack)) continue;
                int max = Math.min(existing.getMaxCount(), stack.getMaxCount());
                int moved = Math.min(stack.getCount(), Math.max(0, max - existing.getCount()));
                if (moved > 0) {
                    existing.increment(moved);
                    stack.decrement(moved);
                }
            }
            for (int slot = 0; slot < main.size() && !stack.isEmpty(); slot++) {
                if (!main.get(slot).isEmpty()) continue;
                int moved = Math.min(stack.getCount(), stack.getMaxCount());
                ItemStack placed = stack.copy();
                placed.setCount(moved);
                main.set(slot, placed);
                stack.decrement(moved);
            }

            insertedCount += originalCount - stack.getCount();
            if (!stack.isEmpty()) remaining.add(stack.copy());
        }
        return new MergeResult(main, remaining, insertedCount);
    }

    private record MergeResult(List<ItemStack> inventory, List<ItemStack> remaining, long insertedCount) {}

    public static String serializeEffects(ServerPlayerEntity player) {
        JsonArray array = new JsonArray();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            JsonObject object = new JsonObject();
            Identifier id = Registries.STATUS_EFFECT.getId(effect.getEffectType().value());
            object.addProperty("effect", id == null ? "" : id.toString());
            object.addProperty("amplifier", effect.getAmplifier());
            object.addProperty("duration", effect.getDuration());
            object.addProperty("ambient", effect.isAmbient());
            object.addProperty("showParticles", effect.shouldShowParticles());
            object.addProperty("showIcon", effect.shouldShowIcon());
            array.add(object);
        }
        return GSON.toJson(array);
    }

    public static String serializeAdvancements(ServerPlayerEntity player) {
        return PlayerProgressCodec.advancements(player);
    }

    public static String serializeStatistics(ServerPlayerEntity player) {
        return PlayerProgressCodec.statistics(player);
    }

    public static String serializeRecipeBook(ServerPlayerEntity player) {
        return PlayerRecipeCodec.capture(player);
    }

    public static String serializeSkinData(ServerPlayerEntity player) {
        return PlayerProfileCodec.capture(player);
    }

    /** DB snapshotをMC thread上で事前検証する。ここではplayer stateを変更しない。 */
    public static PreparedPlayerData prepare(
        ServerPlayerEntity player,
        CompletePlayerData data,
        Configuration.SyncConfig sync
    ) {
        if (data == null || data.uuid == null || !data.uuid.equals(player.getUuid())) {
            throw new IllegalArgumentException("PlayerData UUID does not match current player");
        }
        if (sync == null) sync = new Configuration.SyncConfig();
        if (sync.position != sync.dimensionEnabled()) throw invalid("position/dimension must be paired");

        // sync=trueの列がNULL/空の場合に、local/default stateをそのままREADYにして
        // 次回SAVEするのは、DBの欠損値による上書きにつながる。authoritative payload
        // がない場合は適用せず継続するのではなく、fail closedする。
        List<ItemStack> inventory = sync.inventory
            ? decodeInventory(player, requireAuthoritativeJson(data.inventoryJson, "inventory"),
                player.getInventory().main.size(), true) : null;
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
                player.getEnderChestInventory().size(), false) : null;
        List<ItemStack> armor = sync.armor
            ? decodeInventory(player, requireAuthoritativeJson(data.armorJson, "armor"),
                player.getInventory().armor.size(), false) : null;
        List<ItemStack> offhand = sync.offhand
            ? decodeInventory(player, requireAuthoritativeJson(data.offhandJson, "offhand"),
                player.getInventory().offHand.size(), false) : null;
        List<StatusEffectInstance> effects = sync.effects
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
            if (player.getServer().getWorld(net.minecraft.registry.RegistryKey.of(
                    net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(data.dimension))) == null) throw invalid("unknown dimension");
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
        GameMode gameMode = null;
        if (sync.gamemode) {
            if (data.gamemode == null) throw invalid("gamemode");
            gameMode = GameMode.byName(data.gamemode, null);
            if (gameMode == null) throw invalid("unknown gamemode: " + data.gamemode);
        }
        if (sync.inventory && (data.selectedItemSlot < 0 || data.selectedItemSlot >= player.getInventory().main.size() / 4)) {
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
    public static void applyPrepared(ServerPlayerEntity player, PreparedPlayerData prepared) {
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
            if (prepared.inventory != null) replaceInventory(player.getInventory().main, prepared.inventory);
            if (prepared.enderChest != null) replaceInventory(player.getEnderChestInventory(), prepared.enderChest);
            if (prepared.armor != null) replaceInventory(player.getInventory().armor, prepared.armor);
            if (prepared.offhand != null) replaceInventory(player.getInventory().offHand, prepared.offhand);

            if (prepared.applyHealth) {
                player.setHealth((float) prepared.data.health);
                player.setAir(prepared.data.air);
            }
            if (prepared.applyFood) {
                player.getHungerManager().setFoodLevel(prepared.data.foodLevel);
                player.getHungerManager().setSaturationLevel(prepared.data.saturation);
                player.getHungerManager().setExhaustion(prepared.data.exhaustion);
            }
            if (prepared.applyExperience) {
                player.setExperienceLevel(prepared.data.experienceLevel);
                player.experienceProgress = ExperienceProgress.resolve(
                    prepared.data.experienceLevel,
                    prepared.data.experiencePoints,
                    prepared.data.experiencePointsIntoLevel,
                    prepared.data.experienceProgress);
                // experience_points has always stored cumulative XP in 2.1.3.
                player.totalExperience = prepared.data.experiencePoints;
            }
            if (prepared.effects != null) {
                player.clearStatusEffects();
                for (StatusEffectInstance effect : prepared.effects) {
                    player.addStatusEffect(new StatusEffectInstance(effect));
                }
            }
            if (prepared.applyPosition) {
                var world = prepared.applyDimension ? player.getServer().getWorld(
                    net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.WORLD, Identifier.of(prepared.data.dimension)))
                    : player.getServerWorld();
                float yaw = prepared.applyRotation ? prepared.data.yaw : player.getYaw();
                float pitch = prepared.applyRotation ? prepared.data.pitch : player.getPitch();
                try (var ignored = SpatialMutationScope.authoritativeApply(player)) {
                    player.teleport(world, prepared.data.posX, prepared.data.posY, prepared.data.posZ, yaw, pitch);
                }
            } else if (prepared.applyRotation) {
                // Rotation-only never invokes teleport/setPos/setWorld or closes a screen.
                player.setYaw(prepared.data.yaw);
                player.setPitch(prepared.data.pitch);
                player.setHeadYaw(prepared.data.yaw);
                player.networkHandler.sendPacket(new net.minecraft.network.packet.s2c.play.EntityS2CPacket.Rotate(
                    player.getId(), (byte) (prepared.data.yaw * 256 / 360),
                    (byte) (prepared.data.pitch * 256 / 360), player.isOnGround()));
            }
            if (prepared.applyGameMode && prepared.gameMode != null) {
                player.changeGameMode(prepared.gameMode);
                player.getAbilities().allowFlying = prepared.data.allowFlying;
                player.getAbilities().flying = prepared.data.isFlying && prepared.data.allowFlying;
                player.sendAbilitiesUpdate();
            }
            if (prepared.applySelectedSlot) player.getInventory().selectedSlot = prepared.data.selectedItemSlot;
            PlayerProgressCodec.applyAdvancements(player, prepared.advancements);
            PlayerProgressCodec.applyStatistics(player, prepared.statistics);
            PlayerProfileCodec.apply(player, prepared.profile);
            PlayerRecipeCodec.apply(player, prepared.recipes);

            if (prepared.inventory != null || prepared.armor != null || prepared.offhand != null) {
                player.getInventory().markDirty();
                player.playerScreenHandler.syncState();
            }
            if (prepared.enderChest != null) player.getEnderChestInventory().markDirty();
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
    public static void applyInventoryJson(ServerPlayerEntity player, String json) {
        if (json == null || json.isBlank()) return;
        applyPrepared(player, prepareSingleInventory(player, json, true));
    }

    public static void applyEnderChestJson(ServerPlayerEntity player, String json) {
        if (json == null || json.isBlank()) return;
        applyPrepared(player, prepareSingleInventory(player, json, false));
    }

    public static void applyArmorJson(ServerPlayerEntity player, String json) {
        if (json == null || json.isBlank()) return;
        List<ItemStack> armor = decodeInventory(player, json, player.getInventory().armor.size(), false);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            replaceInventory(player.getInventory().armor, armor);
            player.playerScreenHandler.syncState();
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyOffhandJson(ServerPlayerEntity player, String json) {
        if (json == null || json.isBlank()) return;
        List<ItemStack> offhand = decodeInventory(player, json, player.getInventory().offHand.size(), false);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            replaceInventory(player.getInventory().offHand, offhand);
            player.playerScreenHandler.syncState();
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyEffectsJson(ServerPlayerEntity player, String json) {
        if (json == null || json.isBlank()) return;
        List<StatusEffectInstance> effects = decodeEffects(json);
        PlayerStateBackup backup = PlayerStateBackup.capture(player);
        try {
            player.clearStatusEffects();
            for (StatusEffectInstance effect : effects) player.addStatusEffect(new StatusEffectInstance(effect));
        } catch (Throwable error) {
            backup.restore(player);
            throw error instanceof RuntimeException runtimeException
                ? runtimeException : new IllegalStateException(error);
        }
    }

    public static void applyPositionAndRotation(
        ServerPlayerEntity player,
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
        player.setPos(x, y, z);
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    public static void applyGameMode(ServerPlayerEntity player, String gamemode, boolean isFlying, boolean allowFlying) {
        GameMode mode = GameMode.byName(gamemode, null);
        if (mode == null) throw invalid("unknown gamemode: " + gamemode);
        player.changeGameMode(mode);
        player.getAbilities().allowFlying = allowFlying;
        player.getAbilities().flying = isFlying && allowFlying;
        player.sendAbilitiesUpdate();
    }

    public static final class PreparedPlayerData {
        private Map<net.minecraft.advancement.AdvancementEntry, net.minecraft.advancement.AdvancementProgress> advancements;
        private Map<net.minecraft.stat.Stat<?>, Integer> statistics;
        private com.mojang.authlib.properties.PropertyMap profile;
        private net.minecraft.server.network.ServerRecipeBook recipes;
        private final CompletePlayerData data;
        private final List<ItemStack> inventory;
        private final List<ItemStack> enderChest;
        private final List<ItemStack> armor;
        private final List<ItemStack> offhand;
        private final List<StatusEffectInstance> effects;
        private final GameMode gameMode;
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
            List<StatusEffectInstance> effects,
            GameMode gameMode,
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

    private static PreparedPlayerData prepareSingleInventory(ServerPlayerEntity player, String json, boolean main) {
        List<ItemStack> items = decodeInventory(
            player,
            json,
            main ? player.getInventory().main.size() : player.getEnderChestInventory().size(),
            main
        );
        return new PreparedPlayerData(
            new CompletePlayerData(
                player.getUuid(), player.getName().getString(), null, null, null, null,
                player.getHealth(), player.getHungerManager().getFoodLevel(), player.getHungerManager().getSaturationLevel(),
                player.getHungerManager().getExhaustion(), player.getAir(), player.experienceLevel,
                player.totalExperience, (float) player.totalExperience, player.experienceProgress,
                null, player.getWorld().getRegistryKey().getValue().toString(), player.getX(), player.getY(), player.getZ(),
                player.getYaw(), player.getPitch(), player.interactionManager.getGameMode().getName(),
                player.getAbilities().flying, player.getAbilities().allowFlying, false,
                player.getName().getString(), null, null, null, null, null, player.getInventory().selectedSlot, 0, 1
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
        ServerPlayerEntity player,
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

    private static List<StatusEffectInstance> decodeEffects(String json) {
        List<StatusEffectInstance> result = new ArrayList<>();
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
            if (!Registries.STATUS_EFFECT.containsId(id)) throw invalid("unknown effect: " + rawId);
            StatusEffect effect = Registries.STATUS_EFFECT.get(id);
            int amplifier = requiredInt(object, "amplifier");
            int duration = requiredInt(object, "duration");
            if (amplifier < 0 || amplifier > 255) throw invalid("effect amplifier range");
            if (duration < 0) throw invalid("effect duration range");
            result.add(new StatusEffectInstance(
                Registries.STATUS_EFFECT.getEntry(effect),
                duration,
                amplifier,
                requiredBoolean(object, "ambient"),
                requiredBoolean(object, "showParticles"),
                requiredBoolean(object, "showIcon")
            ));
        }
        return result;
    }

    private static ItemStack decodeItem(ServerPlayerEntity player, JsonObject object) {
        if (!object.has("item")) throw invalid("item identifier is missing");
        ItemStack stack;
        if (object.has("nbt")) {
            String snbt = requiredString(object, "nbt");
            try {
                NbtElement element = StringNbtReader.parse(snbt);
                if (!(element instanceof NbtCompound compound)) throw invalid("item NBT is not a compound");
                stack = ItemStack.fromNbt(RegistryContext.lookup(), compound)
                    .orElseThrow(() -> invalid("item NBT does not describe an item"));
            } catch (Exception error) {
                throw new IllegalArgumentException("invalid item NBT", error);
            }
        } else {
            String rawId = requiredString(object, "item");
            Identifier id = Identifier.tryParse(rawId);
            if (id == null || !Registries.ITEM.containsId(id)) throw invalid("unknown item: " + rawId);
            int count = requiredInt(object, "count");
            Item item = Registries.ITEM.get(id);
            if (count <= 0 || count > item.getMaxCount()) throw invalid("item count range");
            stack = new ItemStack(item, count);
        }

        if (stack.isEmpty()) throw invalid("decoded item is empty");
        if (stack.getCount() <= 0 || stack.getCount() > stack.getMaxCount()) throw invalid("decoded item count range");
        if (object.has("damage")) {
            int damage = requiredInt(object, "damage");
            if (!stack.isDamageable() || damage < 0 || damage > stack.getMaxDamage()) {
                throw invalid("item damage range");
            }
            stack.setDamage(damage);
        }
        return stack;
    }

    private static void replaceInventory(List<ItemStack> target, List<ItemStack> source) {
        if (target.size() != source.size()) throw invalid("inventory size mismatch");
        for (int i = 0; i < target.size(); i++) target.set(i, source.get(i).copy());
    }

    private static void replaceInventory(Inventory target, List<ItemStack> source) {
        if (target.size() != source.size()) throw invalid("inventory size mismatch");
        for (int i = 0; i < target.size(); i++) target.setStack(i, source.get(i).copy());
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

    private static JsonObject itemStackToJson(ServerPlayerEntity player, int slot, ItemStack stack) {
        JsonObject object = new JsonObject();
        object.addProperty("slot", slot);
        object.addProperty("item", Registries.ITEM.getId(stack.getItem()).toString());
        object.addProperty("count", stack.getCount());
        if (stack.isDamageable()) object.addProperty("damage", stack.getDamage());
        NbtCompound compound = new NbtCompound();
        writeNbtOrThrow(compound, () -> {
            if (stack.encode(RegistryContext.lookup()) instanceof NbtCompound encoded) compound.copyFrom(encoded);
        }, object.get("item").getAsString());
        if (!compound.isEmpty()) object.addProperty("nbt", compound.toString());
        return object;
    }

    /** Package-visible fault seam: a failed full-stack encoding must abort the snapshot. */
    static void writeNbtOrThrow(NbtCompound target, Runnable writer, String itemId) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(writer, "writer");
        try {
            writer.run();
        } catch (RuntimeException error) {
            throw new SnapshotSerializationException(
                "Could not serialize complete NBT for item " + itemId, error);
        }
    }

    private static String inventoryToJson(ServerPlayerEntity player, Inventory inventory) {
        JsonArray array = new JsonArray();
        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (!stack.isEmpty()) array.add(itemStackToJson(null, i, stack));
        }
        return GSON.toJson(array);
    }

    private static String itemListToJson(ServerPlayerEntity player, List<ItemStack> stacks) {
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
        private final List<StatusEffectInstance> effects;
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
        private final net.minecraft.server.world.ServerWorld world;
        private final float yaw;
        private final float pitch;
        private final GameMode gameMode;
        private final boolean flying;
        private final boolean allowFlying;
        private final int selectedSlot;

        private PlayerStateBackup(ServerPlayerEntity player, PreparedPlayerData selection) {
            this.selection = selection;
            this.main = selection == null || selection.inventory != null ? copyStacks(player.getInventory().main) : null;
            this.armor = selection == null || selection.armor != null ? copyStacks(player.getInventory().armor) : null;
            this.offhand = selection == null || selection.offhand != null ? copyStacks(player.getInventory().offHand) : null;
            this.enderChest = selection == null || selection.enderChest != null ? copyStacks(player.getEnderChestInventory()) : null;
            this.effects = selection == null || selection.effects != null
                ? player.getStatusEffects().stream().map(StatusEffectInstance::new).toList() : null;
            this.health = player.getHealth();
            this.air = player.getAir();
            this.food = player.getHungerManager().getFoodLevel();
            this.saturation = player.getHungerManager().getSaturationLevel();
            this.exhaustion = player.getHungerManager().getExhaustion();
            this.experienceLevel = player.experienceLevel;
            this.experienceProgress = player.experienceProgress;
            this.totalExperience = player.totalExperience;
            this.x = player.getX();
            this.y = player.getY();
            this.z = player.getZ();
            this.world = player.getServerWorld();
            this.yaw = player.getYaw();
            this.pitch = player.getPitch();
            this.gameMode = player.interactionManager.getGameMode();
            this.flying = player.getAbilities().flying;
            this.allowFlying = player.getAbilities().allowFlying;
            this.selectedSlot = selection == null || selection.applySelectedSlot ? player.getInventory().selectedSlot : 0;
        }

        static PlayerStateBackup capture(ServerPlayerEntity player) {
            return new PlayerStateBackup(player, null);
        }

        void restore(ServerPlayerEntity player) {
            if (main != null) replaceInventory(player.getInventory().main, main);
            if (armor != null) replaceInventory(player.getInventory().armor, armor);
            if (offhand != null) replaceInventory(player.getInventory().offHand, offhand);
            if (enderChest != null) replaceInventory(player.getEnderChestInventory(), enderChest);
            if (effects != null) {
                player.clearStatusEffects();
                for (StatusEffectInstance effect : effects) player.addStatusEffect(new StatusEffectInstance(effect));
            }
            if (selection == null || selection.applyHealth) {
            player.setHealth(health);
            player.setAir(air);
            }
            if (selection == null || selection.applyFood) {
            player.getHungerManager().setFoodLevel(food);
            player.getHungerManager().setSaturationLevel(saturation);
            player.getHungerManager().setExhaustion(exhaustion);
            }
            if (selection == null || selection.applyExperience) {
            player.setExperienceLevel(experienceLevel);
            player.experienceProgress = experienceProgress;
            player.totalExperience = totalExperience;
            }
            if (selection == null || selection.applyPosition) {
            try (var ignored = SpatialMutationScope.authoritativeApply(player)) {
                player.teleport(world, x, y, z, yaw, pitch);
            }
            }
            if (selection == null || selection.applyRotation) {
            player.setYaw(yaw);
            player.setPitch(pitch);
            }
            if (selection == null || selection.applyGameMode) {
            player.changeGameMode(gameMode);
            player.getAbilities().allowFlying = allowFlying;
            player.getAbilities().flying = flying;
            }
            if (selection == null || selection.applySelectedSlot) player.getInventory().selectedSlot = selectedSlot;
            if (main != null || armor != null || offhand != null) {
                player.getInventory().markDirty();
                player.playerScreenHandler.syncState();
            }
            if (enderChest != null) player.getEnderChestInventory().markDirty();
        }

        private static List<ItemStack> copyStacks(List<ItemStack> stacks) {
            return stacks.stream().map(ItemStack::copy).toList();
        }

        private static List<ItemStack> copyStacks(Inventory inventory) {
            List<ItemStack> result = new ArrayList<>(inventory.size());
            for (int i = 0; i < inventory.size(); i++) result.add(inventory.getStack(i).copy());
            return result;
        }
    }

    private CompletePlayerDataSerializer() {
        throw new UnsupportedOperationException("Utility class");
    }
}
