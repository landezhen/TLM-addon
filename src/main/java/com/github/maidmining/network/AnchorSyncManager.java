package com.github.maidmining.network;

import com.github.maidmining.behavior.MaidAnchorMoveBehavior;
import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.task.MaidMiningTask;
import com.github.maidmining.util.OreMatcher.OreType;
import com.github.maidmining.MaidMining;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.List;

/**
 * 服务端锚点同步推送器：每隔固定 tick 把正在挖矿的女仆的锚点数据推给能看见它的玩家。
 *
 * <p>数据来源：{@link MaidAnchorMoveBehavior#getAnchor} 拿锚点中心（无锚点/漂移模式回退女仆位置），
 * {@link MaidMiningConfigData} 拿半径与矿物勾选。只推给该维度内、距离女仆 &le; PUSH_RANGE 的玩家，
 * 控制流量。客户端缓存有 TTL，女仆停挖后停止推送，缓存自动过期清除。</p>
 */
@Mod.EventBusSubscriber(modid = MaidMining.MODID)
public class AnchorSyncManager {

    /** 推送间隔（tick）。10 tick = 0.5 秒，足够可视化跟手，开销可忽略。 */
    private static final int PUSH_INTERVAL = 10;
    /** 推送距离（格）：玩家距女仆超过此值不推。略大于客户端默认渲染距离。 */
    private static final double PUSH_RANGE = 80.0;

    private static int counter = 0;

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (++counter < PUSH_INTERVAL) {
            return;
        }
        counter = 0;

        var server = event.getServer();
        if (server == null) {
            return;
        }
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            // 以每个玩家为中心的小范围收集女仆，避免全世界扫实体
            for (ServerPlayer p : players) {
                AABB box = p.getBoundingBox().inflate(PUSH_RANGE);
                for (EntityMaid maid : level.getEntitiesOfClass(EntityMaid.class, box,
                        m -> m.isAlive() && m.getTask() instanceof MaidMiningTask)) {
                    pushMaid(maid, p);
                }
            }
        }
    }

    private static void pushMaid(EntityMaid maid, ServerPlayer player) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);

        // 触发门：仅「一键连锁开 且 持续检测关」（锚定模式）才渲染。
        // 持续检测开=漂移模式无锚点、连锁关=走声波原机制，两者都不触发可视化。
        if (!d.vein || d.continuousScan) {
            return;
        }
        BlockPos center = MaidAnchorMoveBehavior.getAnchor(maid.getId());
        if (center == null) {
            // 锚定模式但尚未埋下锚点（女仆还没开始本轮探测）：暂不推送，下轮再说
            return;
        }
        int radius = d.passRadius;
        int oreMask = buildOreMask(d);
        AnchorSyncPacket packet = new AnchorSyncPacket(maid.getId(), true, center, radius, oreMask,
                VisualVersionTracker.get(maid.getId()));
        MiningNetwork.sendToPlayer(player, packet);
    }

    /** 把女仆的矿物勾选编成位掩码（位序 = OreType.ordinal()）。 */
    private static int buildOreMask(MaidMiningConfigData.Data d) {
        int mask = 0;
        if (d.coal) mask |= 1 << OreType.COAL.ordinal();
        if (d.iron) mask |= 1 << OreType.IRON.ordinal();
        if (d.copper) mask |= 1 << OreType.COPPER.ordinal();
        if (d.gold) mask |= 1 << OreType.GOLD.ordinal();
        if (d.redstone) mask |= 1 << OreType.REDSTONE.ordinal();
        if (d.lapis) mask |= 1 << OreType.LAPIS.ordinal();
        if (d.diamond) mask |= 1 << OreType.DIAMOND.ordinal();
        if (d.emerald) mask |= 1 << OreType.EMERALD.ordinal();
        if (d.nether) mask |= 1 << OreType.NETHER.ordinal();
        if (d.debris) mask |= 1 << OreType.DEBRIS.ordinal();
        return mask;
    }
}