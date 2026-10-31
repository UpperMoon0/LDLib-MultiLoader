package com.lowdragmc.lowdraglib.async;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AsyncLogicRunnerTest {
    @Test
    void brokenMachineDoesNotSuppressLaterMachines() {
        List<Integer> ran = new ArrayList<>();
        List<Integer> failed = new ArrayList<>();
        AsyncLogicRunner.forEachIsolated(List.of(1, 2, 3, 4), i -> {
            ran.add(i);
            if (i == 2) throw new IllegalStateException("unstable field");
        }, (i, exception) -> {
            assertInstanceOf(IllegalStateException.class, exception);
            failed.add(i);
        });
        assertEquals(List.of(1, 2, 3, 4), ran);
        assertEquals(List.of(2), failed);
    }

    @Test
    void brokenFailureReporterCannotSuppressOtherMachineTicks() {
        AtomicInteger ran = new AtomicInteger();
        assertDoesNotThrow(() -> AsyncLogicRunner.forEachIsolated(
                List.of(1, 2, 3), i -> {
                    ran.incrementAndGet();
                    if (i == 1) throw new IllegalStateException("scan error");
                }, (i, error) -> {
                    throw new IllegalArgumentException("log error");
                }));
        assertEquals(3, ran.get());
    }
}
