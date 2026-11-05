package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.StateLink;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerRecipeBook;

/** Complete enabled-domain checkpoint; direct replacement without recipe-unlocked criteria. */
public final class PlayerRecipeCodec {
    private PlayerRecipeCodec() {}
    private static void onServerThread(ServerPlayer player) {
        if (player.level().getServer() == null || !player.level().getServer().isSameThread()) throw new IllegalStateException("Recipe capture/apply requires server thread");
    }
    public static String capture(ServerPlayer player) {
        onServerThread(player);
        CompoundTag nbt = toNbt(player.getRecipeBook());
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("recipes", identifiers(nbt.getListOrEmpty("recipes")));
        root.add("display", identifiers(nbt.getListOrEmpty("toBeDisplayed")));
        JsonObject options = new JsonObject();
        nbt.keySet().stream().filter(key -> !key.equals("recipes") && !key.equals("toBeDisplayed"))
            .sorted().forEach(key -> options.addProperty(key, nbt.getBooleanOr(key, false)));
        root.add("options", options);
        return root.toString();
    }
    /** The serialized recipe book layout: "recipes", "toBeDisplayed" and the flattened option flags. */
    private static CompoundTag toNbt(ServerRecipeBook book) {
        return (CompoundTag) ServerRecipeBook.Packed.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, book.pack()).getOrThrow();
    }
    private static JsonArray identifiers(ListTag list) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i=0;i<list.size();i++) ids.add(list.getStringOr(i, ""));
        JsonArray result = new JsonArray();
        ids.stream().sorted().forEach(result::add);
        return result;
    }
    public static ServerRecipeBook prepare(ServerPlayer player, String json) {
        onServerThread(player);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.size()==1 && root.has("known_recipes") && root.get("known_recipes").isJsonArray()
                && root.getAsJsonArray("known_recipes").isEmpty()) {
            StateLink.LOGGER.warn("Legacy recipe placeholder: preserving local recipes until real checkpoint");
            return null;
        }
        if (root.get("format").getAsBigDecimal().intValueExact()!=1) throw new IllegalArgumentException("Invalid recipe format");
        ServerRecipeBook result = new ServerRecipeBook((key, consumer) -> {});
        CompoundTag nbt = toNbt(result);
        var keys = new java.util.HashSet<>(nbt.keySet());
        keys.remove("recipes");keys.remove("toBeDisplayed");
        var options = root.getAsJsonObject("options");
        if (!keys.equals(options.keySet())) throw new IllegalArgumentException("Invalid recipe options");
        for (var option : options.entrySet()) {
            if (!option.getValue().isJsonPrimitive() || !option.getValue().getAsJsonPrimitive().isBoolean()) throw new IllegalArgumentException("Invalid recipe option type");
            nbt.putBoolean(option.getKey(),option.getValue().getAsBoolean());
        }
        var owned = new java.util.HashSet<String>();
        nbt.put("recipes", validate(player,root.getAsJsonArray("recipes"),owned));
        var display = new java.util.HashSet<String>();
        nbt.put("toBeDisplayed",validate(player,root.getAsJsonArray("display"),display));
        if (!owned.containsAll(display)) throw new IllegalArgumentException("Recipe display entry is not owned");
        result.loadUntrusted(ServerRecipeBook.Packed.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow(),
            key -> player.level().getServer().getRecipeManager().byKey(key).isPresent());
        return result;
    }
    private static ListTag validate(ServerPlayer player, JsonArray ids, java.util.Set<String> unique) {
        ListTag result = new ListTag();
        for (var value : ids) {
            String raw = value.getAsString();
            var recipe = player.level().getServer().getRecipeManager().byKey(net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.RECIPE, Identifier.parse(raw)))
                .orElseThrow(() -> new IllegalArgumentException("Unknown recipe identifier"));
            if (recipe.value().isSpecial() || !unique.add(raw)) throw new IllegalArgumentException("Invalid or duplicate recipe entry");
            result.add(StringTag.valueOf(raw));
        }
        return result;
    }
    public static void apply(ServerPlayer player, ServerRecipeBook prepared) {
        if (prepared==null) return;
        onServerThread(player);
        player.getRecipeBook().copyOverData(prepared);
        player.getRecipeBook().sendInitialRecipeBook(player); // immutable ID lists / copied options; no grant trigger.
    }
}
