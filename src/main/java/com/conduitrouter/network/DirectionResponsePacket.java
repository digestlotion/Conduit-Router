package com.conduitrouter.network;

import com.conduitrouter.conduitrouteritem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntArrayTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.function.Supplier;

public class DirectionResponsePacket {

    public final OpenDirectionGuiPacket.Phase phase;
    public final int dirIndex;

    public DirectionResponsePacket(OpenDirectionGuiPacket.Phase phase, int dirIndex) {
        this.phase = phase;
        this.dirIndex = dirIndex;
    }

    public static void encode(DirectionResponsePacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.phase);
        buf.writeInt(packet.dirIndex);
    }

    public static DirectionResponsePacket decode(FriendlyByteBuf buf) {
        return new DirectionResponsePacket(buf.readEnum(OpenDirectionGuiPacket.Phase.class), buf.readInt());
    }

    public static void handle(DirectionResponsePacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            ServerPlayer player = ctx.get().getSender();
            if (player == null) return;

            ItemStack stack = player.getMainHandItem();
            if (!(stack.getItem() instanceof conduitrouteritem)) return;

            CompoundTag nbt = stack.getOrCreateTag();
            Direction dir = Direction.from3DDataValue(packet.dirIndex);
            Direction.Axis axis = dir.getAxis();

            if (packet.phase == OpenDirectionGuiPacket.Phase.INITIAL) {
                // guardar Start con eje inicial
                BlockPos targetPos = conduitrouteritem.getTargetPos(player, player.level(), false);
                nbt.put("Start", NbtUtils.writeBlockPos(targetPos));
                nbt.putInt("StartDir", dir.get3DDataValue());
                nbt.putInt("CurrentAxis", axis.ordinal());
                nbt.putString("Selected", ForgeRegistries.BLOCKS.getKey(
                        ((BlockItem) player.getOffhandItem().getItem()).getBlock()).toString());
                nbt.put("Corners", new ListTag());

            }

            stack.setTag(nbt);
        });
        ctx.get().setPacketHandled(true);
    }
}