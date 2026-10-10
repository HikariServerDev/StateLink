package com.atsukigames.statelink.utils;

import java.util.List;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.inventory.BeaconMenu;
import net.minecraft.world.inventory.CartographyTableMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.LoomMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.inventory.SmithingMenu;
import net.minecraft.world.inventory.StonecutterMenu;

/**
 * Explicit allowlist for vanilla screen-owned inputs that are returned/dropped
 * when a disconnected player's screen handler closes. Persistent block-entity
 * inventories (including furnace inputs) are intentionally not included.
 */
final class DisconnectTransientInventoryPolicy {
    private DisconnectTransientInventoryPolicy() {
    }

    /** ScreenHandler slot IDs, not backing-inventory indices. Result slots are excluded. */
    static List<Integer> inputScreenSlotIds(Class<? extends AbstractContainerMenu> handlerType) {
        if (handlerType == AnvilMenu.class || handlerType == SmithingMenu.class) {
            return List.of(0, 1);
        }
        if (handlerType == EnchantmentMenu.class) return List.of(0, 1);
        if (handlerType == StonecutterMenu.class) return List.of(0);
        if (handlerType == CartographyTableMenu.class) return List.of(0, 1);
        if (handlerType == LoomMenu.class) return List.of(0, 1, 2);
        if (handlerType == GrindstoneMenu.class) return List.of(0, 1);
        if (handlerType == MerchantMenu.class) return List.of(0, 1);
        if (handlerType == BeaconMenu.class) return List.of(0);
        return List.of();
    }

    /**
     * Only these vanilla handlers have player-owned crafting inputs plus a
     * computed result that should be cleared. Do not invoke this on every
     * AbstractRecipeScreenHandler: furnace handlers inherit it but contain
     * persistent block-entity inventory.
     */
    static boolean shouldClearCraftingResult(Class<? extends AbstractContainerMenu> handlerType) {
        return handlerType == CraftingMenu.class || handlerType == InventoryMenu.class;
    }
}
