package com.lowdragmc.lowdraglib.gui.modular;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ModularUIContainerSlotTest {
    @Test
    void removedSlotIsNeverDispatchedEvenIfMappingIsStale() {
        assertFalse(SlotDispatchPolicy.shouldDispatch(true, true));
        assertFalse(SlotDispatchPolicy.shouldDispatch(true, false));
    }

    @Test
    void orphanedSlotIsIgnoredButMappedActiveSlotStillWorks() {
        assertFalse(SlotDispatchPolicy.shouldDispatch(false, false));
        assertTrue(SlotDispatchPolicy.shouldDispatch(false, true));
    }
}
