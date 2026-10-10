package com.atsukigames.statelink.utils;

import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * Pure snapshot composition for durable overflow plus the live transient items
 * visible at one checkpoint. No content-based deduplication is performed:
 * equal stacks from distinct ownership sources are distinct items.
 */
final class CheckpointItemOverlay {
    private CheckpointItemOverlay() {
    }

    static <T> List<T> compose(
        List<T> durablePending,
        List<T> activeTransient,
        UnaryOperator<T> copy
    ) {
        List<T> effective = new ArrayList<>(durablePending.size() + activeTransient.size());
        for (T item : durablePending) effective.add(copy.apply(item));
        for (T item : activeTransient) effective.add(copy.apply(item));
        return List.copyOf(effective);
    }
}
