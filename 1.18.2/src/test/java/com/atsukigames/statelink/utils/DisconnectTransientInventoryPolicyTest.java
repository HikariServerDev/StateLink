package com.atsukigames.statelink.utils;

import net.minecraft.screen.AbstractFurnaceScreenHandler;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.BlastFurnaceScreenHandler;
import net.minecraft.screen.BeaconScreenHandler;
import net.minecraft.screen.BrewingStandScreenHandler;
import net.minecraft.screen.CartographyTableScreenHandler;
import net.minecraft.screen.CraftingScreenHandler;
import net.minecraft.screen.EnchantmentScreenHandler;
import net.minecraft.screen.GrindstoneScreenHandler;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.HopperScreenHandler;
import net.minecraft.screen.HorseScreenHandler;
import net.minecraft.screen.LecternScreenHandler;
import net.minecraft.screen.LoomScreenHandler;
import net.minecraft.screen.MerchantScreenHandler;
import net.minecraft.screen.PlayerScreenHandler;
import net.minecraft.screen.ShulkerBoxScreenHandler;
import net.minecraft.screen.SmithingScreenHandler;
import net.minecraft.screen.SmokerScreenHandler;
import net.minecraft.screen.StonecutterScreenHandler;
import net.minecraft.screen.Generic3x3ContainerScreenHandler;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DisconnectTransientInventoryPolicyTest {
    @Test
    void capturesOnlyVanillaPlayerOwnedInputSlotsAndNeverComputedOutputs() {
        assertInputs(AnvilScreenHandler.class, 0, 1);
        assertInputs(SmithingScreenHandler.class, 0, 1);
        assertInputs(EnchantmentScreenHandler.class, 0, 1);
        assertInputs(StonecutterScreenHandler.class, 0);
        assertInputs(CartographyTableScreenHandler.class, 0, 1);
        assertInputs(LoomScreenHandler.class, 0, 1, 2);
        assertInputs(GrindstoneScreenHandler.class, 0, 1);
        assertInputs(MerchantScreenHandler.class, 0, 1);
        assertInputs(BeaconScreenHandler.class, 0);
    }

    @Test
    void persistentBlockEntityScreenHandlersAreNotTransientItemSources() {
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            AbstractFurnaceScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            BlastFurnaceScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            SmokerScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            BrewingStandScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            GenericContainerScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            HopperScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            ShulkerBoxScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            Generic3x3ContainerScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            LecternScreenHandler.class));
        assertEquals(List.of(), DisconnectTransientInventoryPolicy.inputScreenSlotIds(
            HorseScreenHandler.class));
    }

    @Test
    void computedCraftingResultClearIsRestrictedToPlayerAndCraftingTables() {
        assertTrue(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(CraftingScreenHandler.class));
        assertTrue(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(PlayerScreenHandler.class));

        assertFalse(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(AbstractFurnaceScreenHandler.class));
        assertFalse(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(AnvilScreenHandler.class));
        assertFalse(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(StonecutterScreenHandler.class));
        assertFalse(DisconnectTransientInventoryPolicy.shouldClearCraftingResult(MerchantScreenHandler.class));
    }

    private static void assertInputs(Class<? extends net.minecraft.screen.ScreenHandler> type, Integer... expected) {
        assertEquals(List.of(expected), DisconnectTransientInventoryPolicy.inputScreenSlotIds(type), type.getSimpleName());
    }
}
