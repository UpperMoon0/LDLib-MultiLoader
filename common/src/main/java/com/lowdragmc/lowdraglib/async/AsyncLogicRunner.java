package com.lowdragmc.lowdraglib.async;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Runs independent asynchronous logic objects without letting one broken
 * block entity suppress all later objects in the same scheduler pass.
 */
public final class AsyncLogicRunner {
    private AsyncLogicRunner() {}

    public static <T> void forEachIsolated(Iterable<T> work, Consumer<? super T> action,
                                            BiConsumer<? super T, ? super Throwable> onFailure) {
        for (T entry : work) {
            try {
                action.accept(entry);
            } catch (Throwable failure) {
                try {
                    onFailure.accept(entry, failure);
                } catch (Throwable ignored) {
                    // A broken error reporter must not stop unrelated logic.
                }
            }
        }
    }
}
