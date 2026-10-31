package com.lowdragmc.lowdraglib.networking.s2c;

import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SPacketRPCMethodPayloadTest {
    @Test
    void roundTripsManagedIndicesForMultipleEntries() {
        for (int index : new int[] {0, 1, 2, 127, 128, Integer.MAX_VALUE}) {
            var buf = Unpooled.buffer();
            try {
                RpcManagedIndexCodec.writeHeader(buf);
                RpcManagedIndexCodec.writeIndex(buf, index);
                RpcManagedIndexCodec.readHeader(buf);
                assertEquals(index, RpcManagedIndexCodec.readIndex(buf));
                assertFalse(buf.isReadable());
            } finally {
                buf.release();
            }
        }
    }

    @Test
    void rejectsInvalidManagedTargets() {
        assertFalse(RpcManagedIndexCodec.isValidIndex(-1, 2));
        assertFalse(RpcManagedIndexCodec.isValidIndex(2, 2));
        assertFalse(RpcManagedIndexCodec.isValidIndex(0, 0));
        assertTrue(RpcManagedIndexCodec.isValidIndex(0, 2));
        assertTrue(RpcManagedIndexCodec.isValidIndex(1, 2));
        var buf = Unpooled.buffer();
        try {
            assertThrows(IllegalArgumentException.class, () -> RpcManagedIndexCodec.writeIndex(buf, -1));
            RpcManagedIndexCodec.writeHeader(buf);
            RpcManagedIndexCodec.writeHeader(buf);
            RpcManagedIndexCodec.readHeader(buf);
            assertThrows(IllegalArgumentException.class, () -> RpcManagedIndexCodec.readIndex(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsLegacyFrameInsteadOfInvokingWrongManagedObject() {
        var buf = Unpooled.buffer();
        try {
            buf.writeByte(9); // legacy ResourceLocation length, not v2 sentinel
            assertThrows(IllegalArgumentException.class, () -> RpcManagedIndexCodec.readHeader(buf));
        } finally {
            buf.release();
        }
    }

    @Test
    void rejectsTruncatedHeader() {
        var buf = Unpooled.buffer();
        try {
            buf.writeByte(0xff);
            assertThrows(IllegalArgumentException.class, () -> RpcManagedIndexCodec.readHeader(buf));
        } finally {
            buf.release();
        }
    }
}
