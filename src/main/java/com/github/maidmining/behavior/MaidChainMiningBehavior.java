package com.github.maidmining.behavior;

import com.github.maidmining.util.ChainMiningManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import org.jetbrains.annotations.NotNull;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;

import java.util.Map;

/**
 * 连锁挖矿驱动行为。
 *
 * 只负责"推进引线"——每 tick 调用 ChainMiningManager.tick，让待破碎的
 * 同种矿一个接一个破碎。种子注入在 MaidMiningTask.tryDestroyBlock（起点矿
 * 被正常挖掉后）完成，本行为不负责选目标，也不影响女仆寻路。
 *
 * 只要该女仆有待处理连锁就持续运行，与挖矿/移动核心状态并行不冲突
 * （连锁只操作已确定坐标的方块，不抢 CurrentWork）。
 */
public class MaidChainMiningBehavior extends Behavior<EntityMaid> {

    public MaidChainMiningBehavior() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel level, @NotNull EntityMaid maid) {
        return maid.getTask() instanceof IMaidBlockDestroyTask
                && ChainMiningManager.hasPending(maid);
    }

    @Override
    protected boolean canStillUse(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        return ChainMiningManager.hasPending(maid);
    }

    @Override
    protected void tick(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        ChainMiningManager.tick(maid);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }
}