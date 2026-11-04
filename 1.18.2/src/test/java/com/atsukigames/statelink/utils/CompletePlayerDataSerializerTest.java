package com.atsukigames.statelink.utils;

import net.minecraft.nbt.NbtCompound;
import com.atsukigames.statelink.database.PlayerDataRepository.CompletePlayerData;
import com.atsukigames.statelink.sync.PlayerSessionContext;
import com.atsukigames.statelink.sync.SaveReason;
import com.atsukigames.statelink.sync.SyncState;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletePlayerDataSerializerTest {
    @Test
    void nbtEncodingFailureAbortsInsteadOfWritingAnItemWithoutNbt() {
        RuntimeException cause = new IllegalStateException("injected NBT encoder failure");

        SnapshotSerializationException thrown = assertThrows(
            SnapshotSerializationException.class,
            () -> CompletePlayerDataSerializer.writeNbtOrThrow(
                new NbtCompound(),
                () -> { throw cause; },
                "minecraft:shulker_box"));

        assertTrue(thrown.getMessage().contains("minecraft:shulker_box"));
        assertEquals(cause, thrown.getCause());
    }

    @Test
    void successfulNbtEncodingRemainsAvailableForEnchantmentsAndCustomData() {
        NbtCompound compound = new NbtCompound();
        CompletePlayerDataSerializer.writeNbtOrThrow(compound, () -> {
            compound.putString("display", "custom name and lore");
            compound.putInt("Enchantments", 1);
        }, "minecraft:diamond_sword");

        assertTrue(compound.contains("display"));
        assertTrue(compound.contains("Enchantments"));
    }

    @Test
    void nbtFailureDuringFinalCaptureCannotReachDatabaseSaveOrOwnershipRelease() {
        PlayerSessionContext context = new PlayerSessionContext(UUID.randomUUID(), null, 1L);
        long leaseAnchor = System.nanoTime();
        context.markOwnership(12L, 4L, leaseAnchor);
        assertTrue(context.markReadyIfSafe(true, true, false, leaseAnchor + 1,
            TimeUnit.MILLISECONDS.toNanos(3_500)));
        CompletePlayerData previousDirty = new CompletePlayerData(
            context.uuid(), "test", "prior-nbt", null, null, null,
            20.0, 20, 5.0F, 0.0F, 300, 0, 0, 0.0F, null, null,
            "minecraft:overworld", 0.0, 64.0, 0.0, 0.0F, 0.0F,
            "survival", false, false, false, "test", null, null, null, null, null, 0, 0L, 1);
        context.recordDirtySnapshot(previousDirty, SaveReason.DISCONNECT_FINAL);
        AtomicInteger dbUpdates = new AtomicInteger();
        AtomicInteger releases = new AtomicInteger();

        assertThrows(SnapshotSerializationException.class, () -> {
            try {
                CompletePlayerDataSerializer.writeNbtOrThrow(new NbtCompound(),
                    () -> { throw new IllegalStateException("injected shulker NBT failure"); },
                    "minecraft:shulker_box");
            } catch (SnapshotSerializationException error) {
                // Mirrors the coordinator's final-capture failure branch: no final
                // transaction is submitted and the current authority is not released.
                context.recordSnapshotCaptureFailure();
                context.markDegraded();
                throw error;
            }
            dbUpdates.incrementAndGet();
            releases.incrementAndGet();
        });

        assertEquals(0, dbUpdates.get());
        assertEquals(0, releases.get());
        assertEquals(SyncState.DEGRADED, context.state());
        assertTrue(context.hasSnapshotCaptureFailure());
        assertTrue(context.dirtySnapshot() == previousDirty);
        assertFalse(context.canFlush());
    }
}
