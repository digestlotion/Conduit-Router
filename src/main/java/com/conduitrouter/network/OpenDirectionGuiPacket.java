package com.conduitrouter.network;

import com.conduitrouter.gui.DirectionScreen;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

public class OpenDirectionGuiPacket {

    // INITIAL
    public enum Phase { INITIAL, SEGMENT }

    public final Phase phase;

    public OpenDirectionGuiPacket(Phase phase) {
        this.phase = phase;
    }

    public static void encode(OpenDirectionGuiPacket packet, FriendlyByteBuf buf) {
        buf.writeEnum(packet.phase);
    }

    public static OpenDirectionGuiPacket decode(FriendlyByteBuf buf) {
        return new OpenDirectionGuiPacket(buf.readEnum(Phase.class));
    }

    public static void handle(OpenDirectionGuiPacket packet, Supplier<NetworkEvent.Context> ctx) {
        ctx.get().enqueueWork(() -> {
            net.minecraft.client.Minecraft.getInstance()
                    .setScreen(new DirectionScreen(packet.phase));
        });
        ctx.get().setPacketHandled(true);
    }
}