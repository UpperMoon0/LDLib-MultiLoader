package com.lowdragmc.lowdraglib.syncdata.blockentity;

import com.lowdragmc.lowdraglib.Platform;
import com.lowdragmc.lowdraglib.async.AsyncThreadData;
import com.lowdragmc.lowdraglib.async.AsyncPollingSchedule;
import com.lowdragmc.lowdraglib.async.CoalescedTaskQueue;
import com.lowdragmc.lowdraglib.async.IAsyncLogic;
import com.lowdragmc.lowdraglib.networking.LDLNetworking;
import com.lowdragmc.lowdraglib.networking.s2c.SPacketManagedPayload;
import com.lowdragmc.lowdraglib.syncdata.managed.IRef;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

import java.util.Objects;

/**
 * @author KilaBash
 * @date 2022/9/7
 * @implNote IAsyncAutoSyncBlockEntity
 */
public interface IAsyncAutoSyncBlockEntity extends IAutoSyncBlockEntity, IAsyncLogic {

    // A pending sync is scoped to a tile instance and released in a finally block.
    // Unlike the legacy no-op setAsyncSyncing method, this actually coalesces work.
    CoalescedTaskQueue<IAsyncAutoSyncBlockEntity> SYNC_PACKETS = new CoalescedTaskQueue<>();
    // Only default field change-detection is rate-limited; overridden asyncTick
    // implementations retain the 50 ms scheduler cadence.
    int SYNC_POLL_INTERVAL = AsyncPollingSchedule.clampInterval(
            Integer.getInteger("ldlib.asyncSyncPollInterval", 2));

    default boolean useAsyncThread() {
        return true;
    }

    default void onValid() {
        if (useAsyncThread() && getSelf().getLevel() instanceof ServerLevel serverLevel) {
            AsyncThreadData.getOrCreate(serverLevel).addAsyncLogic(this);
        }
    }

    default void onInValid() {
        SYNC_PACKETS.cancel(this);
        if (getSelf().getLevel() instanceof ServerLevel serverLevel) {
            AsyncThreadData.getOrCreate(serverLevel).removeAsyncLogic(this);
        }
    }

    /**
     * whether it's syncing in an async thread
     */
    @ApiStatus.AvailableSince("1.21")
    default boolean isAsyncSyncing() {
        return SYNC_PACKETS.isPending(this);
    }

    /**
     * set whether it's syncing in an async thread
     */
    @ApiStatus.AvailableSince("1.21")
    default void setAsyncSyncing(boolean syncing) {

    }

    @Override
    default void asyncTick(long periodID) {
        if (Platform.isServerNotSafe()) return;

        if (useAsyncThread() && !getSelf().isRemoved()) {
            if (!AsyncPollingSchedule.shouldPoll(periodID, getCurrentPos().asLong(), SYNC_POLL_INTERVAL)) return;
            for (IRef field : getNonLazyFields()) {
                field.update();
            }
            if (getRootStorage().hasDirtySyncFields()) {
                SYNC_PACKETS.submit(this, Platform.getMinecraftServer(), () -> {
                    if (Platform.isServerNotSafe() || getSelf().isRemoved()) return;
                    var packet = SPacketManagedPayload.of(this, false);
                    LDLNetworking.NETWORK.sendToTrackingChunk(packet, Objects.requireNonNull(getSelf().getLevel()).getChunkAt(getCurrentPos()));
                });
            }
        }
    }
}
