package com.lowdragmc.lowdraglib.async;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AsyncPollingScheduleTest {
    @Test
    void clampsPollIntervalsToSafeRange() {
        assertEquals(1, AsyncPollingSchedule.clampInterval(-8));
        assertEquals(1, AsyncPollingSchedule.clampInterval(0));
        assertEquals(2, AsyncPollingSchedule.clampInterval(2));
        assertEquals(20, AsyncPollingSchedule.clampInterval(999));
    }

    @Test
    void everyEntityIsCheckedExactlyOncePerInterval() {
        for (int interval : new int[] {1, 2, 4, 7, 20}) {
            for (long key : new long[] {0, 1, -1, 0x123456789ABCDEFL, Long.MIN_VALUE}) {
                int count = 0;
                for (long tick = 0; tick < interval * 3L; tick++) {
                    if (AsyncPollingSchedule.shouldPoll(tick, key, interval)) {
                        count++;
                    }
                }
                assertEquals(3, count, "interval=" + interval + ", key=" + key);
            }
        }
    }

    @Test
    void worksAcrossNegativePeriodIdsAndStaggersDifferentKeys() {
        for (int interval : new int[] {2, 4, 5}) {
            int count = 0;
            for (long tick = Long.MIN_VALUE; tick < Long.MIN_VALUE + interval * 3L; tick++) {
                if (AsyncPollingSchedule.shouldPoll(tick, 123456789, interval)) {
                    count++;
                }
            }
            assertEquals(3, count);
        }
        assertNotEquals(
                AsyncPollingSchedule.shouldPoll(17, 1L << 32, 2),
                AsyncPollingSchedule.shouldPoll(17, 2L << 32, 2));
    }
}
