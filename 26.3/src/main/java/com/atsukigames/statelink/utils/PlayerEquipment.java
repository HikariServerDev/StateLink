package com.atsukigames.statelink.utils;

import com.atsukigames.statelink.mixin.LivingEntityAccessor;
import java.util.AbstractList;
import java.util.List;
import net.minecraft.world.entity.EntityEquipment;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * Live list views of a player's armor and off-hand slots. Since 1.21.5 these stacks are stored in
 * the entity's equipment instead of PlayerInventory lists; the views keep the list-based
 * capture/replace code unchanged and write without equip sounds or other side effects.
 */
public final class PlayerEquipment {
    private static final EquipmentSlot[] ARMOR = {
        EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD};

    private PlayerEquipment() {}

    public static List<ItemStack> armor(Player player) { return new View(player, ARMOR); }

    public static List<ItemStack> offhand(Player player) { return new View(player, new EquipmentSlot[] {EquipmentSlot.OFFHAND}); }

    private static final class View extends AbstractList<ItemStack> {
        private final EntityEquipment equipment;
        private final EquipmentSlot[] slots;

        View(Player player, EquipmentSlot[] slots) {
            this.equipment = ((LivingEntityAccessor) player).statelink$equipment();
            this.slots = slots;
        }

        @Override public ItemStack get(int index) { return equipment.get(slots[index]); }

        @Override public ItemStack set(int index, ItemStack stack) { return equipment.set(slots[index], stack); }

        @Override public int size() { return slots.length; }
    }
}
