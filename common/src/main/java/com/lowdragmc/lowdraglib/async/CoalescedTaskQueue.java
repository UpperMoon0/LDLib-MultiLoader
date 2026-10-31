package com.lowdragmc.lowdraglib.async;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
 * Allows at most one queued or running task per key. Work is always executed
 * by the supplied executor, never by the caller.
 *
 * <p>Entries are released after execution, including exceptional completion,
 * executor rejection and explicit cancellation. Keys need stable equality
 * and hash codes.
 */
public final class CoalescedTaskQueue<K> {
    private final ConcurrentHashMap<K, Object> pending = new ConcurrentHashMap<>();

    public boolean isPending(K key) {
        return pending.containsKey(key);
    }

    /**
     * Discards queued work for an unloaded object. A scheduled but not yet
     * dispatched runnable is invalidated without touching newer submissions.
     */
    public void cancel(K key) {
        pending.remove(key);
    }

    public boolean submit(K key, Executor executor, Runnable task) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(executor, "executor");
        Objects.requireNonNull(task, "task");

        Object token = new Object();
        if (pending.putIfAbsent(key, token) != null) {
            return false;
        }
        try {
            executor.execute(() -> {
                try {
                    // A chunk may have unloaded and reloaded while this was queued.
                    if (pending.get(key) == token) {
                        task.run();
                    }
                } finally {
                    pending.remove(key, token);
                }
            });
            return true;
        } catch (RuntimeException | Error ex) {
            pending.remove(key, token);
            throw ex;
        }
    }
}
