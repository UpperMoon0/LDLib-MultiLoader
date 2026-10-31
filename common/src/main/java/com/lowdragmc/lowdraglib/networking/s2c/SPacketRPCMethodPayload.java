package com.lowdragmc.lowdraglib.networking.s2c;

import com.lowdragmc.lowdraglib.LDLib;
import com.lowdragmc.lowdraglib.networking.IHandlerContext;
import com.lowdragmc.lowdraglib.networking.IPacket;
import com.lowdragmc.lowdraglib.networking.PacketIntLocation;
import com.lowdragmc.lowdraglib.syncdata.IManaged;
import com.lowdragmc.lowdraglib.syncdata.TypedPayloadRegistries;
import com.lowdragmc.lowdraglib.syncdata.blockentity.IRPCBlockEntity;
import com.lowdragmc.lowdraglib.syncdata.payload.ITypedPayload;
import com.lowdragmc.lowdraglib.syncdata.rpc.RPCSender;
import lombok.Getter;
import lombok.NoArgsConstructor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.Objects;

/**
 * a packet that contains payload for managed fields
 */
@NoArgsConstructor
public class SPacketRPCMethodPayload extends PacketIntLocation implements IPacket {
    private BlockEntityType<?> blockEntityType;
    private ITypedPayload<?>[] payloads;

    private String methodName;
    // v2 RPC wire format: mandatory managed-object index.
    @Getter
    private int managedId;
    private static final int RPC_WIRE_MARKER = -1;

    public SPacketRPCMethodPayload(FriendlyByteBuf buffer) {
        decode(buffer);
    }

    public SPacketRPCMethodPayload(int managedId, BlockEntityType<?> type, BlockPos pos, String methodName, ITypedPayload<?>[] payloads) {
        super(pos);
        this.managedId = managedId;
        blockEntityType = type;
        this.methodName = methodName;
        this.payloads = payloads;
    }

    public static SPacketRPCMethodPayload of(IManaged managed, IRPCBlockEntity tile, String methodName, Object... args) {
        var index = Arrays.stream(tile.getRootStorage().getManaged()).toList().indexOf(managed);
        if (index < 0) {
            throw new IllegalArgumentException("No such rpc managed: " + methodName);
        }
        var rpcMethod = tile.getRPCMethod(managed, methodName);
        if (rpcMethod == null) {
            throw new IllegalArgumentException("No such RPC method: " + methodName);
        }
        var payloads = rpcMethod.serializeArgs(args);
        return new SPacketRPCMethodPayload(index, tile.getBlockEntityType(), tile.getCurrentPos(), methodName, payloads);
    }

    public void processPacket(@NotNull BlockEntity blockEntity, RPCSender sender) {
        if (blockEntity.getType() != blockEntityType) {
            LDLib.LOGGER.warn("Block entity type mismatch in rpc payload packet!");
            return;
        }
        if (!(blockEntity instanceof IRPCBlockEntity tile)) {
            LDLib.LOGGER.error("Received managed payload packet for block entity that does not implement IRPCBlockEntity: " + blockEntity);
            return;
        }
        if (!isValidManagedIndex(managedId, tile.getRootStorage().getManaged().length)) {
            LDLib.LOGGER.error("Received managed couldn't be found in IRPCBlockEntity: " + blockEntity);
            return;
        }
        var managed = tile.getRootStorage().getManaged()[managedId];
        var rpcMethod = tile.getRPCMethod(managed, methodName);
        if (rpcMethod == null) {
            LDLib.LOGGER.error("Cannot find RPC method: " + methodName);
            return;
        }

        rpcMethod.invoke(managed, sender, payloads);
    }

    static boolean isValidManagedIndex(int index, int managedCount) {
        return index >= 0 && index < managedCount;
    }

    @Override
    public void encode(FriendlyByteBuf buf) {
        if (managedId < 0) {
            throw new IllegalArgumentException("Invalid RPC managed-object index: " + managedId);
        }
        super.encode(buf);
        // A negative sentinel makes old-format packets fail closed on both loaders.
        buf.writeVarInt(RPC_WIRE_MARKER);
        buf.writeResourceLocation(Objects.requireNonNull(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(blockEntityType)));
        buf.writeUtf(methodName);
        buf.writeVarInt(managedId);
        buf.writeVarInt(payloads.length);
        for (ITypedPayload<?> payload : payloads) {
            buf.writeByte(payload.getType());
            payload.writePayload(buf);
        }
    }

    @Override
    public void decode(FriendlyByteBuf buffer) {
        super.decode(buffer);
        if (buffer.readVarInt() != RPC_WIRE_MARKER) {
            throw new IllegalArgumentException("Incompatible LDLib RPC packet format (requires v2)");
        }
        blockEntityType = BuiltInRegistries.BLOCK_ENTITY_TYPE.get(buffer.readResourceLocation());
        methodName = buffer.readUtf();
        managedId = buffer.readVarInt();
        if (managedId < 0) {
            throw new IllegalArgumentException("Invalid RPC managed-object index: " + managedId);
        }
        payloads = new ITypedPayload<?>[buffer.readVarInt()];
        for (int i = 0; i < payloads.length; i++) {
            byte id = buffer.readByte();
            var payload = TypedPayloadRegistries.create(id);
            payload.readPayload(buffer);
            payloads[i] = payload;
        }
    }

    @Override
    public void execute(IHandlerContext handler) {
        if (!handler.isClient()) {
            var player = handler.getPlayer();
            if (player == null) {
                LDLib.LOGGER.error("Received rpc payload packet from client with no player!");
                return;
            }
            var level = player.level();
            if (!level.isLoaded(pos)) return;
            BlockEntity tile = level.getBlockEntity(pos);
            if (tile == null) {
                return;
            }
            processPacket(tile, RPCSender.ofClient(player));

        } else {
            if (handler.getLevel() == null) {
                return;
            }
            BlockEntity tile = handler.getLevel().getBlockEntity(pos);
            if (tile == null) {
                return;
            }
            processPacket(tile, RPCSender.ofServer());
        }

    }
}
