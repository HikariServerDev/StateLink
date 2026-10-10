package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.StateLink;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import net.minecraft.server.network.ServerPlayerEntity;
import java.util.Comparator;
import java.util.UUID;

/** Profile properties are replace-authoritative; login identity/team names are not renamed. */
public final class PlayerProfileCodec {
    private PlayerProfileCodec() {}
    private static void onServerThread(ServerPlayerEntity player) {
        if (player.getServer() == null || !player.getServer().isOnThread()) {
            throw new IllegalStateException("Profile capture/apply requires server thread");
        }
    }
    public static String capture(ServerPlayerEntity player) {
        onServerThread(player);
        return encode(player.getGameProfile());
    }
    public static String encode(GameProfile profile) {
        JsonObject root = new JsonObject();
        root.addProperty("format", 1);
        root.addProperty("name", profile.getName());
        root.addProperty("uuid", profile.getId().toString());
        JsonArray properties = new JsonArray();
        profile.getProperties().entries().stream().sorted(Comparator
            .comparing((java.util.Map.Entry<String, Property> e) -> e.getKey())
            .thenComparing(e -> e.getValue().name())
            .thenComparing(e -> e.getValue().value())
            .thenComparing(e -> e.getValue().signature() != null ? e.getValue().signature() : ""))
            .forEach(entry -> {
                JsonObject value = new JsonObject();
                value.addProperty("key", entry.getKey());
                value.addProperty("name", entry.getValue().name());
                value.addProperty("value", entry.getValue().value());
                if (entry.getValue().signature() != null) value.addProperty("signature", entry.getValue().signature());
                properties.add(value);
            });
        root.add("properties", properties);
        return root.toString();
    }
    public static PropertyMap decode(String json, UUID uuid, String name) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        // <=2.1.11 wrote only {"name","uuid"} and never applied it. The name in that placeholder is
        // whatever the player was called at the time; identity is the UUID, so a later rename must
        // not block the login. Recognize the placeholder before any name comparison.
        if (legacyIdentityShape(root)) {
            if (!uuid.toString().equals(root.get("uuid").getAsString())) {
                throw new IllegalArgumentException("Legacy profile placeholder belongs to another UUID");
            }
            return null;
        }
        if (!uuid.toString().equals(root.get("uuid").getAsString()) || !name.equals(root.get("name").getAsString())) {
            throw new IllegalArgumentException("Profile login identity mismatch; automatic rename is unsupported");
        }
        if (root.get("format").getAsBigDecimal().intValueExact() != 1 || !root.get("properties").isJsonArray()) {
            throw new IllegalArgumentException("Invalid profile format");
        }
        PropertyMap result = new PropertyMap();
        for (var element : root.getAsJsonArray("properties")) {
            JsonObject property = element.getAsJsonObject();
            String key = property.get("key").getAsString(), propertyName = property.get("name").getAsString();
            if (key.isBlank() || propertyName.isBlank()) throw new IllegalArgumentException("Invalid profile property name");
            String value = property.get("value").getAsString();
            result.put(key, property.has("signature")
                ? new Property(propertyName, value, property.get("signature").getAsString())
                : new Property(propertyName, value));
        }
        return result;
    }
    private static boolean legacyIdentityShape(JsonObject root) {
        return !root.has("format") && root.size() == 2
            && root.has("uuid") && root.get("uuid").isJsonPrimitive() && root.getAsJsonPrimitive("uuid").isString()
            && root.has("name") && root.get("name").isJsonPrimitive() && root.getAsJsonPrimitive("name").isString();
    }
    /** True for the 2.1.11 identity-only value, whose stored name and display label were never authoritative. */
    public static boolean isLegacyIdentityPlaceholder(String json) {
        if (json == null || json.isBlank()) return false;
        try {
            var parsed = JsonParser.parseString(json);
            return parsed.isJsonObject() && legacyIdentityShape(parsed.getAsJsonObject());
        } catch (RuntimeException malformed) {
            return false;
        }
    }
    public static PropertyMap prepare(ServerPlayerEntity player, String json, String displayName) {
        onServerThread(player);
        if (isLegacyIdentityPlaceholder(json)) {
            // The legacy display_name column is the old player name as well; only the UUID is checked.
            PropertyMap legacy = decode(json, player.getUuid(), player.getGameProfile().getName());
            StateLink.LOGGER.warn("Legacy identity-only profile: preserving local properties until real checkpoint");
            return legacy;
        }
        if (!player.getDisplayName().getString().equals(displayName)) {
            throw new IllegalArgumentException("Profile display/team name mismatch; global scoreboard rename is unsupported");
        }
        PropertyMap result = decode(json, player.getUuid(), player.getGameProfile().getName());
        if (result == null) StateLink.LOGGER.warn("Legacy identity-only profile: preserving local properties until real checkpoint");
        return result;
    }
    public static void apply(ServerPlayerEntity player, PropertyMap prepared) {
        if (prepared == null) return;
        onServerThread(player);
        var properties = player.getGameProfile().getProperties();
        properties.clear();
        properties.putAll(prepared);
        // Refresh player-list properties, not rewards, inventory, or global team state.
        var manager = player.getServer().getPlayerManager();
        manager.sendToAll(new net.minecraft.network.packet.s2c.play.PlayerRemoveS2CPacket(
            java.util.List.of(player.getUuid())));
        manager.sendToAll(detachedAddPacket(player));
    }
    /**
     * The add packet is written to a private buffer and read back, so the packet that reaches the
     * Netty encoder owns copies of the profile properties and never the live mutable PropertyMap.
     */
    private static net.minecraft.network.packet.s2c.play.PlayerListS2CPacket detachedAddPacket(ServerPlayerEntity player) {
        var actions = java.util.EnumSet.of(
            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.ADD_PLAYER,
            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_LISTED,
            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_LATENCY,
            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_GAME_MODE,
            net.minecraft.network.packet.s2c.play.PlayerListS2CPacket.Action.UPDATE_DISPLAY_NAME);
        var live = new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(actions, java.util.List.of(player));
        var buffer = new net.minecraft.network.PacketByteBuf(io.netty.buffer.Unpooled.buffer());
        live.write(buffer);
        return new net.minecraft.network.packet.s2c.play.PlayerListS2CPacket(buffer);
    }
}
