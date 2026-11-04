package com.atsukigames.statelink.utils;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CheckpointItemOverlayTest {
    @Test
    void repeatedCheckpointsReplaceLiveOverlayWithoutMutatingDurableBase() {
        List<String> durable = List.of("pending-A");
        List<String> live = List.of("cursor-B", "anvil-C");

        assertEquals(List.of("pending-A", "cursor-B", "anvil-C"),
            CheckpointItemOverlay.compose(durable, live, UnaryOperator.identity()));
        assertEquals(List.of("pending-A", "cursor-B", "anvil-C"),
            CheckpointItemOverlay.compose(durable, live, UnaryOperator.identity()));
        assertEquals(List.of("pending-A"), durable);
    }

    @Test
    void nextCheckpointRemovesStaleTransientWhenItMovesIntoPlayerInventory() {
        List<String> durable = List.of("pending-A");
        assertEquals(List.of("pending-A", "cursor-B"),
            CheckpointItemOverlay.compose(durable, List.of("cursor-B"), UnaryOperator.identity()));
        assertEquals(List.of("pending-A"),
            CheckpointItemOverlay.compose(durable, List.of(), UnaryOperator.identity()));
    }

    @Test
    void equalStackValuesFromSeparateSourcesAreNotDeduplicated() {
        assertEquals(List.of("diamond", "diamond"), CheckpointItemOverlay.compose(
            List.of("diamond"), List.of("diamond"), UnaryOperator.identity()));
    }
}
