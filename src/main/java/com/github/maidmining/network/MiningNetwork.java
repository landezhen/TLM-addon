package com.github.maidmining.network;

import com.github.maidmining.MaidMining;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

/**
 * 网络通道：只承载锚点可视化的 S2C 同步（{@link AnchorSyncPacket}）。
 */
public class MiningNetwork {

    private static final String PROTOCOL = "1";
    private static SimpleChannel CHANNEL;
    private static int nextId = 0;

    public static void register() {
        CHANNEL = NetworkRegistry.newSimpleChannel(
                new ResourceLocation(MaidMining.MODID, "main"),
                () -> PROTOCOL,
                PROTOCOL::equals,
                PROTOCOL::equals
        );
        CHANNEL.registerMessage(nextId++, AnchorSyncPacket.class,
                AnchorSyncPacket::encode, AnchorSyncPacket::decode, AnchorSyncPacket::handle);
    }

    /** 把锚点数据发给指定玩家（追踪该女仆的玩家）。 */
    public static void sendToPlayer(ServerPlayer player, AnchorSyncPacket msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }
}