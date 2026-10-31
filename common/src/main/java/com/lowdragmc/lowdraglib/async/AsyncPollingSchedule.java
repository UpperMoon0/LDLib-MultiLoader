package com.lowdragmc.lowdraglib.async;

/**
 * Staggers periodic, asynchronous block-entity change detection. Does not
 * change the scheduler frequency or the frequency of unrelated IAsyncLogic.
 */
public final class AsyncPollingSchedule {
    private AsyncPollingSchedule() {}

    public static int clampInterval(int interval) {
        return Math.max(1, Math.min(20, interval));
    }

    public static boolean shouldPoll(long periodId, long blockPositionKey, int interval) {
        if (interval <= 1) {
            return true;
        }
        int slot = (int) (blockPositionKey ^ (blockPositionKey >>> 32));
        return Math.floorMod(periodId - slot, interval) == 0;
    }
}
