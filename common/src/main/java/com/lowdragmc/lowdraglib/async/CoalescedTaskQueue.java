package com.lowdragmc.lowdraglib.async;

import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Allows at most one queued or running task per key. Work is always executed
 * by the supplied executor, never by the caller.
 *
 * <p>Entries are released after execution, including exceptional completion,
 * and after executor rejection. Keys must have stable equality and hash codes.
 */
public final class CoalescedTaskQueue<K> {
    private final Set<K> pending = ConcurrentHashMap.newKeySet();

    public boolean isPending(K key) {
        return pending.contains(key);
    }

    public boolean submit(K key, Executor executor, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");

        if (!pending.add(key)) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } finally {
                    pending.remove(key);
                }
            });
            return true;
        } catch (RuntimeException | Error ex) {
            pending.remove(key);
            throw ex;
        }
    }
}
