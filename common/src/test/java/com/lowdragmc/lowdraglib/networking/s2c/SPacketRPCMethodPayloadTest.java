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
    void dispatchesWireIndexToSecondManagedObjectWithExactArguments() {
        class Target {
            String method;
            String sender;
            int argument;
            int calls;

            void invoke(String method, String sender, int argument) {
                this.method = method;
                this.sender = sender;
                this.argument = argument;
                calls++;
            }
        }
        var first = new Target();
        var second = new Target();
        var managed = new Target[] {first, second};
        var wire = Unpooled.buffer();
        try {
            RpcManagedIndexCodec.writeHeader(wire);
            RpcManagedIndexCodec.writeIndex(wire, 1);
            RpcManagedIndexCodec.readHeader(wire);
            var decodedIndex = RpcManagedIndexCodec.readIndex(wire);
            assertTrue(RpcManagedIndexCodec.dispatchManaged(decodedIndex, managed,
                    target -> target.invoke("changeMode", "server", 37)));
            assertEquals(0, first.calls, "managed object #0 must never receive target #1 RPC");
            assertEquals(1, second.calls);
            assertEquals("changeMode", second.method);
            assertEquals("server", second.sender);
            assertEquals(37, second.argument);
            assertFalse(wire.isReadable());
        } finally {
            wire.release();
        }
    }

    @Test
    void invalidWireIndexNeverInvokesAnyManagedObject() {
        var targets = new String[] {"first", "second"};
        var called = new int[] {0};
        assertFalse(RpcManagedIndexCodec.dispatchManaged(-1, targets, ignored -> called[0]++));
        assertFalse(RpcManagedIndexCodec.dispatchManaged(2, targets, ignored -> called[0]++));
        assertEquals(0, called[0]);
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
