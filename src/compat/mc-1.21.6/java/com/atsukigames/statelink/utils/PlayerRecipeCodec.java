package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.StateLink;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.network.ServerRecipeBook;
import net.minecraft.util.Identifier;

/** Complete enabled-domain checkpoint; direct replacement without recipe-unlocked criteria. */
public final class PlayerRecipeCodec {
    private PlayerRecipeCodec() {}
    private static void onServerThread(ServerPlayerEntity player) {
        if (player.getServer() == null || !player.getServer().isOnThread()) throw new IllegalStateException("Recipe capture/apply requires server thread");
    }
    public static String capture(ServerPlayerEntity player) {
        onServerThread(player);
        NbtCompound nbt = toNbt(player.getRecipeBook());
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.add("recipes", identifiers(nbt.getListOrEmpty("recipes")));
        root.add("display", identifiers(nbt.getListOrEmpty("toBeDisplayed")));
        JsonObject options = new JsonObject();
        nbt.getKeys().stream().filter(key -> !key.equals("recipes") && !key.equals("toBeDisplayed"))
            .sorted().forEach(key -> options.addProperty(key, nbt.getBoolean(key, false)));
        root.add("options", options);
        return root.toString();
    }
    /** The serialized recipe book layout: "recipes", "toBeDisplayed" and the flattened option flags. */
    private static NbtCompound toNbt(ServerRecipeBook book) {
        return (NbtCompound) ServerRecipeBook.Packed.CODEC.encodeStart(net.minecraft.nbt.NbtOps.INSTANCE, book.pack()).getOrThrow();
    }
    private static JsonArray identifiers(NbtList list) {
        java.util.List<String> ids = new java.util.ArrayList<>();
        for (int i=0;i<list.size();i++) ids.add(list.getString(i, ""));
        JsonArray result = new JsonArray();
        ids.stream().sorted().forEach(result::add);
        return result;
    }
    public static ServerRecipeBook prepare(ServerPlayerEntity player, String json) {
        onServerThread(player);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        if (root.size()==1 && root.has("known_recipes") && root.get("known_recipes").isJsonArray()
                && root.getAsJsonArray("known_recipes").isEmpty()) {
            StateLink.LOGGER.warn("Legacy recipe placeholder: preserving local recipes until real checkpoint");
            return null;
        }
        if (root.get("format").getAsBigDecimal().intValueExact()!=1) throw new IllegalArgumentException("Invalid recipe format");
        ServerRecipeBook result = new ServerRecipeBook((key, consumer) -> {});
        NbtCompound nbt = toNbt(result);
        var keys = new java.util.HashSet<>(nbt.getKeys());
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
        result.unpack(ServerRecipeBook.Packed.CODEC.parse(net.minecraft.nbt.NbtOps.INSTANCE, nbt).getOrThrow(),
            key -> player.getServer().getRecipeManager().get(key).isPresent());
        return result;
    }
    private static NbtList validate(ServerPlayerEntity player, JsonArray ids, java.util.Set<String> unique) {
        NbtList result = new NbtList();
        for (var value : ids) {
            String raw = value.getAsString();
            var recipe = player.getServer().getRecipeManager().get(net.minecraft.registry.RegistryKey.of(net.minecraft.registry.RegistryKeys.RECIPE, Identifier.of(raw)))
                .orElseThrow(() -> new IllegalArgumentException("Unknown recipe identifier"));
            if (recipe.value().isIgnoredInRecipeBook() || !unique.add(raw)) throw new IllegalArgumentException("Invalid or duplicate recipe entry");
            result.add(NbtString.of(raw));
        }
        return result;
    }
    public static void apply(ServerPlayerEntity player, ServerRecipeBook prepared) {
        if (prepared==null) return;
        onServerThread(player);
        player.getRecipeBook().copyFrom(prepared);
        player.getRecipeBook().sendInitRecipesPacket(player); // immutable ID lists / copied options; no grant trigger.
    }
}
