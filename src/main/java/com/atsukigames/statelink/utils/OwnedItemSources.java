package com.atsukigames.statelink.utils;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Identity-based snapshots and guarded clearing for independently owned item sources. */
final class OwnedItemSources {
    private OwnedItemSources() {
    }

    static <T> List<T> identityDistinct(T first, T second) {
        List<T> result = new ArrayList<>(2);
        if (first != null) result.add(first);
        if (second != null && second != first) result.add(second);
        return List.copyOf(result);
    }

    static <O, S> List<Snapshot<O, S>> capture(
        List<O> owners,
        Function<O, S> read,
        UnaryOperator<S> copy
    ) {
        Map<O, Boolean> seen = new IdentityHashMap<>();
        List<Snapshot<O, S>> result = new ArrayList<>(owners.size());
        for (O owner : owners) {
            if (seen.put(owner, Boolean.TRUE) != null) continue;
            result.add(new Snapshot<>(owner, copy.apply(read.apply(owner))));
        }
        return List.copyOf(result);
    }

    /** Validate the complete set before clearing any nonempty source. */
    static <O, S> void clearIfUnchanged(
        List<Snapshot<O, S>> captured,
        Function<O, S> read,
        BiPredicate<S, S> same,
        Predicate<S> empty,
        BiConsumer<O, S> clear
    ) {
        Objects.requireNonNull(captured, "captured");
        for (Snapshot<O, S> source : captured) {
            if (!same.test(read.apply(source.owner()), source.value())) {
                throw new IllegalStateException("item source changed after its snapshot was captured");
            }
        }
        for (Snapshot<O, S> source : captured) {
            if (!empty.test(source.value())) clear.accept(source.owner(), source.value());
        }
    }

    record Snapshot<O, S>(O owner, S value) {
        Snapshot {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(value, "value");
        }
    }
}
