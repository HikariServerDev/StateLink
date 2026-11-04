package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.config.Configuration;
import com.atsukigames.statelink.sync.MutationRevision;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.item.ItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-thread lightweight equality observation. No JSON/NBT serialization, no
 * progress/stat map scan. Item copies are retained only when their value changes;
 * equality includes in-place NBT edits (inventory dirty callbacks alone miss these).
 * Disabled domains are neither read nor observed. This is not clean-release proof:
 * saveOnDisconnect=false still compares the actual durable row under its DB lock.
 */
public final class PlayerChangeObserver {
    private List<ItemStack> items;
    private List<Object> fields;
    private String pending;

    public boolean observe(ServerPlayerEntity player, Configuration.SyncConfig sync, String durablePending) {
        if (player.getServer() == null || !player.getServer().isOnThread())
            throw new IllegalStateException("Dirty observation requires server thread");
        List<ItemStack> currentItems = new ArrayList<>();
        if (sync.inventory) {
            currentItems.addAll(player.getInventory().getMainStacks());
            currentItems.addAll(CompletePlayerDataSerializer.ownedTransientViewsForDirtyDetection(player));
        }
        if (sync.armor) currentItems.addAll(com.atsukigames.statelink.utils.PlayerEquipment.armor(player));
        if (sync.offhand) currentItems.addAll(com.atsukigames.statelink.utils.PlayerEquipment.offhand(player));
        if (sync.enderchest) for (int i=0;i<player.getEnderChestInventory().size();i++)
            currentItems.add(player.getEnderChestInventory().getStack(i));
        boolean itemChange = items == null || items.size() != currentItems.size();
        if (!itemChange) for (int i=0;i<items.size();i++) {
            if (!ItemStack.areEqual(items.get(i), currentItems.get(i))) { itemChange=true; break; }
        }
        if (itemChange) items=currentItems.stream().map(ItemStack::copy).toList();
        List<Object> current = new ArrayList<>();
        if (sync.inventory) current.add(player.getInventory().getSelectedSlot());
        if (sync.health) { current.add(player.getHealth()); current.add(player.getAir()); }
        if (sync.food) {
            current.add(player.getHungerManager().getFoodLevel());
            current.add(player.getHungerManager().getSaturationLevel());
            current.add(((com.atsukigames.statelink.mixin.HungerManagerAccessor) player.getHungerManager()).statelink$getExhaustion());
        }
        if (sync.experience) {
            current.add(player.experienceLevel); current.add(player.totalExperience);
            current.add(ExperienceProgress.pointsIntoLevel(player.experienceLevel, player.experienceProgress));
        }
        if (sync.effects) player.getStatusEffects().stream()
            .sorted(java.util.Comparator.comparing(e -> net.minecraft.registry.Registries.STATUS_EFFECT.getId(e.getEffectType().value()).toString()))
            .forEach(e -> {
                current.add(e.getEffectType()); current.add(e.getAmplifier()); current.add(e.getDuration());
                current.add(e.isAmbient()); current.add(e.shouldShowParticles()); current.add(e.shouldShowIcon());
            });
        if (sync.position) {
            current.add(player.getX()); current.add(player.getY()); current.add(player.getZ());
        }
        if (sync.dimensionEnabled()) current.add(player.getWorld().getRegistryKey());
        if (sync.rotationEnabled()) { current.add(player.getYaw()); current.add(player.getPitch()); }
        if (sync.gamemode) {
            current.add(player.interactionManager.getGameMode()); current.add(player.getAbilities().flying);
            current.add(player.getAbilities().allowFlying); current.add(player.getAbilities().invulnerable);
        }
        if (sync.advancements) current.add(revision(player.getAdvancementTracker()));
        if (sync.statistics) current.add(revision(player.getStatHandler()));
        if (sync.recipeBook) current.add(revision(player.getRecipeBook()));
        if (sync.playerProfile) {
            current.add(player.getGameProfile().getName()); current.add(player.getDisplayName().getString());
            player.getGameProfile().getProperties().entries().stream().map(e ->
                new PropertyValue(e.getKey(), e.getValue().name(), e.getValue().value(), e.getValue().signature()))
                .sorted(java.util.Comparator.comparing(PropertyValue::key).thenComparing(PropertyValue::name)
                    .thenComparing(PropertyValue::value).thenComparing(p -> Objects.toString(p.signature(), "")))
                .forEach(current::add);
        }
        String enabledPending=sync.inventory ? durablePending : null;
        boolean changed=itemChange || !Objects.equals(fields,current) || !Objects.equals(pending,enabledPending);
        fields=List.copyOf(current); pending=enabledPending;
        return changed;
    }

    private static long revision(Object value) {
        if (!(value instanceof MutationRevision counter)) throw new IllegalStateException("Dirty observer mixin missing");
        return counter.statelink$mutationRevision();
    }
    private record PropertyValue(String key,String name,String value,String signature) {}
}
