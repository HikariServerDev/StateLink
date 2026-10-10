package com.atsukigames.statelink.utils;

import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;

import java.util.List;

/**
 * Explicit allowlist for vanilla screen-owned inputs that are returned/dropped
 * when a disconnected player's screen handler closes. Persistent block-entity
 * inventories (including furnace inputs) are intentionally not included.
 */
final class DisconnectTransientInventoryPolicy {
    private DisconnectTransientInventoryPolicy() {
    }

    /** ScreenHandler slot IDs, not backing-inventory indices. Result slots are excluded. */
    static List<Integer> inputScreenSlotIds(Class<? extends ScreenHandler> handlerType) {
        if (handlerType == AnvilScreenHandler.class || handlerType == SmithingScreenHandler.class) {
            return List.of(0, 1);
        }
        if (handlerType == EnchantmentScreenHandler.class) return List.of(0, 1);
        if (handlerType == StonecutterScreenHandler.class) return List.of(0);
        if (handlerType == CartographyTableScreenHandler.class) return List.of(0, 1);
        if (handlerType == LoomScreenHandler.class) return List.of(0, 1, 2);
        if (handlerType == GrindstoneScreenHandler.class) return List.of(0, 1);
        if (handlerType == MerchantScreenHandler.class) return List.of(0, 1);
        if (handlerType == BeaconScreenHandler.class) return List.of(0);
        return List.of();
    }

    /**
     * Only these vanilla handlers have player-owned crafting inputs plus a
     * computed result that should be cleared. Do not invoke this on every
     * AbstractRecipeScreenHandler: furnace handlers inherit it but contain
     * persistent block-entity inventory.
     */
    static boolean shouldClearCraftingResult(Class<? extends ScreenHandler> handlerType) {
        return handlerType == CraftingScreenHandler.class || handlerType == PlayerScreenHandler.class;
    }
}
