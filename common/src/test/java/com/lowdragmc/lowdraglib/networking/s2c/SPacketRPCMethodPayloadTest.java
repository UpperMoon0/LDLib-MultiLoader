package com.lowdragmc.lowdraglib.networking.s2c;

import com.lowdragmc.lowdraglib.syncdata.payload.ITypedPayload;
import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SPacketRPCMethodPayloadTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.bootStrap();
    }

    @Test
    void roundTripsManagedIndicesForMultipleEntries() {
        for (int index : new int[] {0, 1, 2, 127, 128}) {
            var encoded = new FriendlyByteBuf(Unpooled.buffer());
            try {
                new SPacketRPCMethodPayload(index, BlockEntityType.FURNACE, BlockPos.ZERO,
                        "example", new ITypedPayload<?>[0]).encode(encoded);
                var decoded = new SPacketRPCMethodPayload(encoded);
                assertEquals(index, decoded.getManagedId());
            } finally {
                encoded.release();
            }
        }
    }

    @Test
    void rejectsOutOfRangeAndNegativeIndices() {
        assertFalse(SPacketRPCMethodPayload.isValidManagedIndex(-1, 2));
        assertFalse(SPacketRPCMethodPayload.isValidManagedIndex(2, 2));
        assertFalse(SPacketRPCMethodPayload.isValidManagedIndex(0, 0));
        assertTrue(SPacketRPCMethodPayload.isValidManagedIndex(0, 2));
        assertTrue(SPacketRPCMethodPayload.isValidManagedIndex(1, 2));
    }

    @Test
    void legacyWireFormatFailsClosedInsteadOfCallingWrongManagedObject() {
        var oldFormat = new FriendlyByteBuf(Unpooled.buffer());
        try {
            oldFormat.writeBlockPos(BlockPos.ZERO);
            oldFormat.writeResourceLocation(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(BlockEntityType.FURNACE));
            oldFormat.writeUtf("example");
            oldFormat.writeVarInt(0);
            assertThrows(IllegalArgumentException.class, () -> new SPacketRPCMethodPayload(oldFormat));
        } finally {
            oldFormat.release();
        }
    }
}
