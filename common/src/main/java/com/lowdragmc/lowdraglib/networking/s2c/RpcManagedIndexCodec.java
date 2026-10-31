package com.lowdragmc.lowdraglib.networking.s2c;

import io.netty.buffer.ByteBuf;

/** Shared RPC wire preamble and managed index, testable without Minecraft bootstrap. */
final class RpcManagedIndexCodec {
    private static final int RPC_V2_MARKER = -1;

    private RpcManagedIndexCodec() {}

    static void writeHeader(ByteBuf buf) {
        writeVarInt(buf, RPC_V2_MARKER);
    }

    static void readHeader(ByteBuf buf) {
        if (readVarInt(buf) != RPC_V2_MARKER) {
            throw new IllegalArgumentException("Incompatible LDLib RPC packet format (requires v2)");
        }
    }

    static void writeIndex(ByteBuf buf, int managedId) {
        if (managedId < 0) {
            throw new IllegalArgumentException("Invalid RPC managed-object index: " + managedId);
        }
        writeVarInt(buf, managedId);
    }

    static int readIndex(ByteBuf buf) {
        int managedId = readVarInt(buf);
        if (managedId < 0) {
            throw new IllegalArgumentException("Invalid RPC managed-object index: " + managedId);
        }
        return managedId;
    }

    static boolean isValidIndex(int index, int managedCount) {
        return index >= 0 && index < managedCount;
    }

    // Match FriendlyByteBuf's VarInt wire format for both supported loaders.
    private static void writeVarInt(ByteBuf buf, int value) {
        while ((value & ~0x7f) != 0) {
            buf.writeByte((value & 0x7f) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
    }

    private static int readVarInt(ByteBuf buf) {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            if (!buf.isReadable()) {
                throw new IllegalArgumentException("Truncated LDLib RPC VarInt");
            }
            int part = buf.readUnsignedByte();
            if (i == 4 && (part & 0x70) != 0) {
                throw new IllegalArgumentException("Overflow in LDLib RPC VarInt");
            }
            value |= (part & 0x7f) << (i * 7);
            if ((part & 0x80) == 0) return value;
        }
        throw new IllegalArgumentException("LDLib RPC VarInt exceeds five bytes");
    }
}
