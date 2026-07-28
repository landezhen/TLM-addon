package com.github.maidmining.client;

import com.github.maidmining.network.AnchorSyncPacket;

/**
 * 客户端专属的网络包处理入口。单独成类，供 {@link AnchorSyncPacket#handle} 通过
 * DistExecutor 调用，确保专用服务器环境不会加载到客户端逻辑。
 */
public final class ClientPacketHandler {

    private ClientPacketHandler() {
    }

    public static void handleAnchorSync(AnchorSyncPacket msg) {
        if (msg.valid()) {
            AnchorClientCache.put(msg.maidId(), msg.center(), msg.radius(), msg.oreMask(), msg.version());
        } else {
            AnchorClientCache.remove(msg.maidId());
        }
    }
}