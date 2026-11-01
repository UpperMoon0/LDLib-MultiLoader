package com.lowdragmc.lowdraglib.gui.modular;

import java.util.List;
import java.util.Map;

/** Removed or unmapped slots must never receive client interactions. */
final class SlotDispatchPolicy {
    private SlotDispatchPolicy() {}

    static boolean shouldDispatch(boolean isRemovedPlaceholder, boolean hasWidgetMapping) {
        return !isRemovedPlaceholder && hasWidgetMapping;
    }

    static <S> boolean isCurrentSlot(S slot, int index, List<S> liveSlots) {
        return index >= 0 && index < liveSlots.size() && liveSlots.get(index) == slot;
    }

    /** Check both the widget mapping and the live menu slot identity after index reuse. */
    static <S, W> W activeWidget(S slot, int index, List<S> liveSlots,
                                  Map<S, W> mappings, boolean removedPlaceholder) {
        if (!isCurrentSlot(slot, index, liveSlots) ||
                !shouldDispatch(removedPlaceholder, mappings.containsKey(slot))) {
            return null;
        }
        return mappings.get(slot);
    }
}
