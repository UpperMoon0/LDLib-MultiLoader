package com.lowdragmc.lowdraglib.async;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CoalescedTaskQueueTest {
    @Test
    void coalescesUntilDispatchedTaskCompletes() {
        var queue = new CoalescedTaskQueue<Object>();
        Object key = new Object();
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger sends = new AtomicInteger();

        assertTrue(queue.submit(key, work::add, sends::incrementAndGet));
        assertTrue(queue.isPending(key));
        assertFalse(queue.submit(key, work::add, sends::incrementAndGet));
        assertEquals(1, work.size());

        work.remove().run();
        assertEquals(1, sends.get());
        assertFalse(queue.isPending(key));
        assertTrue(queue.submit(key, work::add, sends::incrementAndGet));
    }

    @Test
    void distinctKeysCanBeQueuedIndependently() {
        var queue = new CoalescedTaskQueue<Object>();
        List<Runnable> work = new ArrayList<>();
        assertTrue(queue.submit(new Object(), work::add, () -> {}));
        assertTrue(queue.submit(new Object(), work::add, () -> {}));
        assertEquals(2, work.size());
        work.forEach(Runnable::run);
    }

    @Test
    void releasesAfterTaskFailureAndExecutorRejection() {
        var queue = new CoalescedTaskQueue<Object>();
        Object key = new Object();
        Queue<Runnable> work = new ArrayDeque<>();

        assertTrue(queue.submit(key, work::add, () -> {
            throw new IllegalStateException("failed packet");
        }));
        assertThrows(IllegalStateException.class, () -> work.remove().run());
        assertFalse(queue.isPending(key));
        assertThrows(RejectedExecutionException.class,
                () -> queue.submit(key, runnable -> {
                    throw new RejectedExecutionException();
                }, () -> {}));
        assertFalse(queue.isPending(key));
        assertTrue(queue.submit(key, work::add, () -> {}));
    }

    @Test
    void cancellationDropsUnloadedTaskWithoutCancellingReloadedTask() {
        var queue = new CoalescedTaskQueue<Object>();
        Object key = new Object();
        Queue<Runnable> work = new ArrayDeque<>();
        AtomicInteger sends = new AtomicInteger();

        assertTrue(queue.submit(key, work::add, sends::incrementAndGet));
        queue.cancel(key);
        assertFalse(queue.isPending(key));
        assertTrue(queue.submit(key, work::add, sends::incrementAndGet));
        assertEquals(2, work.size());
        // Simulate an old delayed network task executing after reload.
        work.remove().run();
        assertTrue(queue.isPending(key), "old task must not remove the new task token");
        assertEquals(0, sends.get(), "cancelled packet must not be sent");
        work.remove().run();
        assertEquals(1, sends.get());
        assertFalse(queue.isPending(key));
    }

    @Test
    void cancellationIsIdempotentAndDoesNotBlockLaterSubmissions() {
        var queue = new CoalescedTaskQueue<Object>();
        Object key = new Object();
        queue.cancel(key);
        Queue<Runnable> work = new ArrayDeque<>();
        assertTrue(queue.submit(key, work::add, () -> {}));
        queue.cancel(key);
        queue.cancel(key);
        assertTrue(queue.submit(key, work::add, () -> {}));
    }

    @Test
    void concurrentProducersCannotScheduleDuplicates() throws Exception {
        var queue = new CoalescedTaskQueue<Object>();
        Object key = new Object();
        AtomicInteger accepted = new AtomicInteger();
        List<Runnable> dispatched = new ArrayList<>();
        ExecutorService executor = Executors.newFixedThreadPool(8);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> submissions = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                submissions.add(executor.submit(() -> {
                    start.await();
                    if (queue.submit(key, runnable -> {
                        synchronized (dispatched) {
                            dispatched.add(runnable);
                        }
                    }, () -> {})) {
                        accepted.incrementAndGet();
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : submissions) {
                f.get(5, TimeUnit.SECONDS);
            }
            assertEquals(1, accepted.get());
            assertEquals(1, dispatched.size());
            dispatched.get(0).run();
            assertFalse(queue.isPending(key));
        } finally {
            executor.shutdownNow();
        }
    }
}
