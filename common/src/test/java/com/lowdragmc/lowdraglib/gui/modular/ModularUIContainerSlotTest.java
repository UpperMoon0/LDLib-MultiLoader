package com.lowdragmc.lowdraglib.gui.modular;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModularUIContainerSlotTest {
    @Test
    void removedSlotIsNeverDispatchedEvenIfMappingIsStale() {
        assertFalse(SlotDispatchPolicy.shouldDispatch(true, true));
        assertFalse(SlotDispatchPolicy.shouldDispatch(true, false));
        var removed = new Object();
        var slots = new ArrayList<>(List.of(removed));
        var mapping = Map.of(removed, "stale widget");
        // A placeholder must be inert even if a map entry lingers.
        assertNull(SlotDispatchPolicy.activeWidget(removed, 0, slots, mapping, true));
    }

    @Test
    void orphanedSlotIsIgnoredButMappedActiveSlotStillWorks() {
        var slot = new Object();
        var slots = List.of(slot);
        assertNull(SlotDispatchPolicy.activeWidget(slot, 0, slots, Map.of(), false));
        assertEquals("active", SlotDispatchPolicy.activeWidget(slot, 0, slots, Map.of(slot, "active"), false));
    }

    @Test
    void removalAndIndexReuseCannotReachStaleWidgetFromAnyMenuDispatch() {
        var original = new Object();
        var retired = new Object();
        var replacement = new Object();
        var live = new ArrayList<>(List.of(original, retired));
        Map<Object, String> widgets = new IdentityHashMap<>();
        widgets.put(original, "original");
        widgets.put(retired, "retired");
        assertEquals("retired", SlotDispatchPolicy.activeWidget(retired, 1, live, widgets, false));

        // A removed index becomes a placeholder; a stale slot-map entry may remain.
        var placeholder = new Object();
        live.set(1, placeholder);
        widgets.put(placeholder, "should never dispatch");
        assertNull(SlotDispatchPolicy.activeWidget(retired, 1, live, widgets, false));
        assertNull(SlotDispatchPolicy.activeWidget(placeholder, 1, live, widgets, true));

        // Reuse the numeric index with a different slot; old references stay invalid.
        live.set(1, replacement);
        widgets.put(replacement, "replacement");
        assertFalse(SlotDispatchPolicy.isCurrentSlot(retired, 1, live));
        assertNull(SlotDispatchPolicy.activeWidget(retired, 1, live, widgets, false));
        assertNull(SlotDispatchPolicy.activeWidget(placeholder, 1, live, widgets, true));
        assertEquals("replacement", SlotDispatchPolicy.activeWidget(replacement, 1, live, widgets, false));
        assertEquals("original", SlotDispatchPolicy.activeWidget(original, 0, live, widgets, false));

        // The same production selection policy gates click, shift-click sources
        // and destinations, and pick-all; no path may select the stale mapping.
        for (String operation : List.of("clicked", "quickMoveStack", "merge destination", "pick-all")) {
            assertNull(SlotDispatchPolicy.activeWidget(retired, 1, live, widgets, false), operation);
            assertEquals("replacement", SlotDispatchPolicy.activeWidget(replacement, 1, live, widgets, false), operation);
        }
    }

    @Test
    void slotMustBelongToTheActiveMenuAtTheExpectedIndex() {
        var first = new Object();
        var second = new Object();
        var current = List.of(first, second);
        var widgets = Map.of(first, "first", second, "second");
        assertNull(SlotDispatchPolicy.activeWidget(first, -1, current, widgets, false));
        assertNull(SlotDispatchPolicy.activeWidget(first, 2, current, widgets, false));
        assertNull(SlotDispatchPolicy.activeWidget(first, 1, current, widgets, false));
    }
}
