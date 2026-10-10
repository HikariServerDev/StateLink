package com.atsukigames.statelink.utils;

import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.inventory.EnderChestInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.collection.DefaultedList;

import java.util.Collection;

public class NBTUtils {
    
    public static DefaultedList<ItemStack> getPlayerInventory(ServerPlayerEntity player) {
        DefaultedList<ItemStack> inventory = DefaultedList.ofSize(41, ItemStack.EMPTY);
        
        // Main inventory (slots 0-35)
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            inventory.set(i, player.getInventory().main.get(i).copy());
        }
        
        // Hotbar is part of main inventory (slots 0-8)
        
        return inventory;
    }

    public static void setPlayerInventory(ServerPlayerEntity player, DefaultedList<ItemStack> inventory) {
        // Clear existing inventory
        player.getInventory().clear();
        
        // Set main inventory
        for (int i = 0; i < Math.min(inventory.size(), player.getInventory().main.size()); i++) {
            player.getInventory().main.set(i, inventory.get(i).copy());
        }
        
        // Update inventory on client
        player.playerScreenHandler.syncState();
    }

    public static DefaultedList<ItemStack> getPlayerEnderChest(ServerPlayerEntity player) {
        EnderChestInventory enderChest = player.getEnderChestInventory();
        DefaultedList<ItemStack> items = DefaultedList.ofSize(enderChest.size(), ItemStack.EMPTY);
        
        for (int i = 0; i < enderChest.size(); i++) {
            items.set(i, enderChest.getStack(i).copy());
        }
        
        return items;
    }

    public static void setPlayerEnderChest(ServerPlayerEntity player, DefaultedList<ItemStack> items) {
        EnderChestInventory enderChest = player.getEnderChestInventory();
        enderChest.clear();
        
        for (int i = 0; i < Math.min(items.size(), enderChest.size()); i++) {
            enderChest.setStack(i, items.get(i).copy());
        }
    }

    public static DefaultedList<ItemStack> getPlayerArmor(ServerPlayerEntity player) {
        DefaultedList<ItemStack> armor = DefaultedList.ofSize(4, ItemStack.EMPTY);
        
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            armor.set(i, player.getInventory().armor.get(i).copy());
        }
        
        return armor;
    }

    public static void setPlayerArmor(ServerPlayerEntity player, DefaultedList<ItemStack> armor) {
        for (int i = 0; i < Math.min(armor.size(), player.getInventory().armor.size()); i++) {
            player.getInventory().armor.set(i, armor.get(i).copy());
        }
        
        // Update equipment on client
        player.playerScreenHandler.syncState();
    }

    public static ItemStack getPlayerOffhand(ServerPlayerEntity player) {
        return player.getOffHandStack().copy();
    }

    public static void setPlayerOffhand(ServerPlayerEntity player, ItemStack offhand) {
        player.getInventory().offHand.set(0, offhand.copy());
        player.playerScreenHandler.syncState();
    }

    public static void setPlayerEffects(ServerPlayerEntity player, Collection<StatusEffectInstance> effects) {
        // Clear existing effects
        player.clearStatusEffects();
        
        // Apply new effects
        for (StatusEffectInstance effect : effects) {
            player.addStatusEffect(new StatusEffectInstance(effect));
        }
    }
}
