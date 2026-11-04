package com.atsukigames.statelink.utils;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class OwnedItemSourcesTest {
    @Test
    void currentAndPlayerScreenReferencesToTheSameHandlerProduceOneCursorOwner() {
        CursorOwner playerScreen = new CursorOwner(new StackValue("diamond", 1));

        List<CursorOwner> handlers = OwnedItemSources.identityDistinct(playerScreen, playerScreen);
        List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> captured = capture(handlers);

        assertEquals(1, handlers.size());
        assertEquals(1, captured.size());
        assertEquals(1, captured.get(0).value().count);
    }

    @Test
    void distinctEqualHandlersAndEqualStackValuesRemainDistinctOwners() {
        CursorOwner activeChest = new CursorOwner(new StackValue("diamond", 1));
        CursorOwner retainedPlayerScreen = new CursorOwner(new StackValue("diamond", 1));

        List<CursorOwner> handlers = OwnedItemSources.identityDistinct(activeChest, retainedPlayerScreen);
        List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> captured = capture(handlers);

        assertEquals(2, handlers.size(), "handler equals() must not merge distinct cursor owners");
        assertEquals(2, captured.size(), "equal ItemStack values from separate cursors are separate items");
        assertEquals(2, captured.stream().mapToInt(source -> source.value().count).sum());
    }

    @Test
    void captureCopiesLiveStackSoLaterMutationCannotChangeTheSnapshot() {
        CursorOwner current = new CursorOwner(new StackValue("emerald", 2));
        List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> captured = capture(List.of(current));
        StackValue original = current.cursor;

        original.count = 0;

        assertEquals(2, captured.get(0).value().count);
        assertNotSame(original, captured.get(0).value());
        assertEquals(0, current.cursor.count);
    }

    @Test
    void disconnectClearsBothRecordedCursorSources() {
        CursorOwner current = new CursorOwner(new StackValue("emerald", 2));
        CursorOwner playerScreen = new CursorOwner(new StackValue("diamond", 1));
        List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> captured = capture(
            OwnedItemSources.identityDistinct(current, playerScreen));

        OwnedItemSources.clearIfUnchanged(captured, owner -> owner.cursor, StackValue::same,
            StackValue::empty, (owner, ignored) -> owner.cursor = StackValue.emptyStack());

        assertEquals(0, current.cursor.count);
        assertEquals(0, playerScreen.cursor.count);
    }

    @Test
    void changedSourceFailsBeforeAnyCapturedCursorIsCleared() {
        CursorOwner first = new CursorOwner(new StackValue("diamond", 1));
        CursorOwner second = new CursorOwner(new StackValue("emerald", 1));
        List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> captured = capture(List.of(first, second));
        second.cursor = new StackValue("emerald", 2);

        assertThrows(IllegalStateException.class, () -> OwnedItemSources.clearIfUnchanged(
            captured, owner -> owner.cursor, StackValue::same, StackValue::empty,
            (owner, ignored) -> owner.cursor = StackValue.emptyStack()));

        assertEquals(1, first.cursor.count, "validation failure must not partially clear earlier sources");
        assertEquals(2, second.cursor.count);
    }

    private static List<OwnedItemSources.Snapshot<CursorOwner, StackValue>> capture(List<CursorOwner> owners) {
        return OwnedItemSources.capture(owners, owner -> owner.cursor, StackValue::copy);
    }

    private static final class CursorOwner {
        private StackValue cursor;

        private CursorOwner(StackValue cursor) {
            this.cursor = cursor;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof CursorOwner;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }

    private static final class StackValue {
        private final String item;
        private int count;

        private StackValue(String item, int count) {
            this.item = item;
            this.count = count;
        }

        private StackValue copy() {
            return new StackValue(item, count);
        }

        private boolean same(StackValue other) {
            return other != null && item.equals(other.item) && count == other.count;
        }

        private boolean empty() {
            return count == 0;
        }

        private static StackValue emptyStack() {
            return new StackValue("", 0);
        }
    }
}
