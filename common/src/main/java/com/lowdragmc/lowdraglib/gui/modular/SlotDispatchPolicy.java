package com.lowdragmc.lowdraglib.gui.modular;

/** Removed or unmapped slots must never receive client interactions. */
final class SlotDispatchPolicy {
    private SlotDispatchPolicy() {}

    static boolean shouldDispatch(boolean isRemovedPlaceholder, boolean hasWidgetMapping) {
        return !isRemovedPlaceholder && hasWidgetMapping;
    }
}
