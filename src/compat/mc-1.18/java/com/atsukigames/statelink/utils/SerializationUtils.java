package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.StateLink;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.StringNbtReader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.registry.Registry;

import java.util.ArrayList;
import java.util.Collection;

public class SerializationUtils {
    private static final Gson GSON = new Gson();

    // ★ PlayerDataSyncer互換メソッド ★
    public static String serializeInventory(DefaultedList<ItemStack> inventory, MinecraftServer server) {
        return serializeStacks(inventory, server);
    }

    public static DefaultedList<ItemStack> deserializeInventory(String data, MinecraftServer server) {
        return deserializeStacks(data, 41, server); // メインインベントリ41スロット
    }

    // ★ 汎用ItemStackリスト同期（完全版）★
    public static String serializeStacks(DefaultedList<ItemStack> stacks, MinecraftServer server) {
        try {
            NbtCompound root = new NbtCompound();
            net.minecraft.inventory.Inventories.writeNbt(root, stacks);
            return root.toString();
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to serialize stacks: {}", e.getMessage());
            return "{}";
        }
    }

    public static DefaultedList<ItemStack> deserializeStacks(String snbt, int size, MinecraftServer server) {
        DefaultedList<ItemStack> stacks = DefaultedList.ofSize(size, ItemStack.EMPTY);
        try {
            if (snbt == null || snbt.isEmpty() || "{}".equals(snbt)) return stacks;
            // 修正: .asCompound()を削除
            NbtCompound root = StringNbtReader.parse(snbt);
            net.minecraft.inventory.Inventories.readNbt(root, stacks);
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to deserialize stacks: {}", e.getMessage());
        }
        return stacks;
    }

    // ★ エンダーチェスト専用同期 ★
    public static String serializeEnderChest(EnderChestInventory inv, MinecraftServer server) {
        try {
            NbtCompound root = new NbtCompound();
            root.put("Items", inv.toNbtList());
            return root.toString();
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to serialize ender chest: {}", e.getMessage());
            return "{}";
        }
    }

    public static void deserializeEnderChest(String snbt, EnderChestInventory inv, MinecraftServer server) {
        try {
            if (snbt == null || snbt.isEmpty() || "{}".equals(snbt)) return;
            // 修正: .asCompound()を削除
            NbtCompound root = StringNbtReader.parse(snbt);
            var list = root.getList("Items", NbtElement.COMPOUND_TYPE);
            inv.readNbtList(list);
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to deserialize ender chest: {}", e.getMessage());
        }
    }

    // ★ 防具専用（4スロット）★
    public static String serializeArmor(DefaultedList<ItemStack> armor, MinecraftServer server) {
        return serializeStacks(armor, server);
    }

    public static DefaultedList<ItemStack> deserializeArmor(String data, MinecraftServer server) {
        return deserializeStacks(data, 4, server); // 防具4スロット
    }

    // ★ オフハンド専用（1スロット）★
    public static String serializeOffhand(DefaultedList<ItemStack> offhand, MinecraftServer server) {
        return serializeStacks(offhand, server);
    }

    public static DefaultedList<ItemStack> deserializeOffhand(String data, MinecraftServer server) {
        return deserializeStacks(data, 1, server); // オフハンド1スロット
    }

    // ★ 単一ItemStack同期（完全版）★
    public static String serializeItemStack(ItemStack stack, MinecraftServer server) {
        try {
            if (stack.isEmpty()) return null;
            NbtCompound compound = new NbtCompound();
            stack.writeNbt(compound);
            return compound.toString();
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to serialize single item: {}", e.getMessage());
        }
        return null;
    }

    public static ItemStack deserializeItemStack(String data, MinecraftServer server) {
        try {
            if (data == null || data.isEmpty()) return ItemStack.EMPTY;
            // 修正: .asCompound()を削除
            NbtCompound compound = StringNbtReader.parse(data);
            return ItemStack.fromNbt(compound);
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to deserialize single item: {}", e.getMessage());
            return ItemStack.EMPTY;
        }
    }

    // ★ ステータスエフェクト同期（完全版）★
    public static String serializeEffects(Collection<StatusEffectInstance> effects) {
        try {
            JsonArray array = new JsonArray();
            for (StatusEffectInstance effect : effects) {
                JsonObject obj = new JsonObject();
                StatusEffectInstance effectInstance = effect;
                Identifier effectId = Registry.STATUS_EFFECT.getId(effectInstance.getEffectType());
                String id = effectId == null ? "minecraft:unknown" : effectId.toString();
                obj.addProperty("id", id);
                obj.addProperty("duration", effect.getDuration());
                obj.addProperty("amplifier", effect.getAmplifier());
                obj.addProperty("ambient", effect.isAmbient());
                obj.addProperty("showParticles", effect.shouldShowParticles());
                obj.addProperty("showIcon", effect.shouldShowIcon());
                array.add(obj);
            }
            return GSON.toJson(array);
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to serialize effects: {}", e.getMessage());
            return "[]";
        }
    }

    public static Collection<StatusEffectInstance> deserializeEffects(String data) {
        Collection<StatusEffectInstance> effects = new ArrayList<>();
        try {
            if (data == null || data.isEmpty() || "[]".equals(data)) return effects;
            JsonArray array = GSON.fromJson(data, JsonArray.class);
            for (JsonElement el : array) {
                JsonObject obj = el.getAsJsonObject();
                Identifier id = Identifier.tryParse(obj.get("id").getAsString());
                if (id != null && Registry.STATUS_EFFECT.containsId(id)) {
                    StatusEffectInstance inst = new StatusEffectInstance(
                        Registry.STATUS_EFFECT.get(id),
                        obj.get("duration").getAsInt(),
                        obj.get("amplifier").getAsInt(),
                        obj.get("ambient").getAsBoolean(),
                        obj.get("showParticles").getAsBoolean(),
                        obj.get("showIcon").getAsBoolean()
                    );
                    effects.add(inst);
                }
            }
        } catch (Exception e) {
            StateLink.LOGGER.error("Failed to deserialize effects: {}", e.getMessage());
        }
        return effects;
    }
}
