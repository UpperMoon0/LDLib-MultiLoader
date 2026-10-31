package com.lowdragmc.lowdraglib.gui.modular;

import com.lowdragmc.lowdraglib.gui.widget.SlotWidget;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.inventory.Slot;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ModularUIContainerSlotTest {
    @Test
    void removedSlotIsNotDispatchedEvenIfMappingIsStale() {
        var placeholder = new ModularUIContainer.EmptySlotPlaceholder();
        var widget = new SlotWidget();
        assertNull(ModularUIContainer.activeSlotWidget(placeholder, Map.of(placeholder, widget)));
        assertNull(ModularUIContainer.activeSlotWidget(placeholder, Map.of()));
    }

    @Test
    void orphanedSlotIsIgnoredButMappedSlotStillWorks() {
        var slot = new Slot(new SimpleContainer(1), 0, 0, 0);
        var widget = new SlotWidget();
        assertNull(ModularUIContainer.activeSlotWidget(slot, Map.of()));
        assertSame(widget, ModularUIContainer.activeSlotWidget(slot, Map.of(slot, widget)));
    }
}
