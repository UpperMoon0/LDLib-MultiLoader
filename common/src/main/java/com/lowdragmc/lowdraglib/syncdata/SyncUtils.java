package com.lowdragmc.lowdraglib.syncdata;

import com.lowdragmc.lowdraglib.side.fluid.FluidStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Array;
import java.util.Collection;

public class SyncUtils {
    public static boolean isChanged(@NotNull Object oldValue, @NotNull Object newValue) {
        if (oldValue instanceof ItemStack itemStack) {
            if (!(newValue instanceof ItemStack)) {
                return true;
            }
            return !ItemStack.matches(itemStack, (ItemStack) newValue);
        }
        if (oldValue instanceof FluidStack fluidStack) {
            if (!(newValue instanceof FluidStack)) {
                return true;
            }
            return !fluidStack.isFluidStackEqual((FluidStack) newValue);
        }

        return !oldValue.equals(newValue);
    }

    @Deprecated
    public static Object copyWhenNecessary(Object value) {
        if (value instanceof ItemStack itemStack) {
            return itemStack.copy();
        }
        if (value instanceof FluidStack fluidStack) {
            return fluidStack.copy();
        }
        if (value instanceof BlockPos blockPos) {
            return blockPos.immutable();
        }
        return value;
    }

    public static Object copyArrayLike(Object value, boolean isArray) {
        if (isArray) {
            var componentType = value.getClass().getComponentType();
            if (componentType.isPrimitive()) {
                Object result = Array.newInstance(componentType, Array.getLength(value));
                System.arraycopy(value, 0, result, 0, Array.getLength(value));
                return result;
            }

            Object[] array = (Object[]) value;
            Object[] result = new Object[array.length];
            for (int i = 0; i < array.length; i++) {
                result[i] = copyWhenNecessary(array[i]);
            }
            return result;

        }

        if (value instanceof Collection<?> collection) {
            // Never iterate a mutable collection concurrently with game logic.
            Object[] snapshot = collection.toArray();
            for (int i = 0; i < snapshot.length; i++) {
                snapshot[i] = copyWhenNecessary(snapshot[i]);
            }
            return snapshot;
        }

        throw new IllegalArgumentException("Value %s is not an array or collection".formatted(value));
    }
}
