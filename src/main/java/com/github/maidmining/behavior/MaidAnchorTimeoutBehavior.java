package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.Behavior;
import org.jetbrains.annotations.NotNull;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;

import java.util.Map;

/**
 * 锚定挖矿「任务超时」巡检驱动行为。
 *
 * <p>只做一件事：每 tick 调用 {@link MaidAnchorMoveBehavior#tickTimeout}，
 * 检查女仆当前锁定的目标是否已经卡了 10 秒没挖动，超时则解锁并在脚下重埋锚点。</p>
 *
 * <p><b>为什么需要独立行为</b>：{@link MaidAnchorMoveBehavior} 的记忆前置条件要求
 * WALK_TARGET 与 TARGET_POS 都为空，一旦锁定目标进入 DESTROY 状态它就不再运行，
 * 正是死锁发生的时段。本行为声明空记忆条件（{@code Map.of()}），任何状态下都能跑，
 * 因此能观测到「锁死不动」的全过程。行为本身不写走路/目标记忆，不与移动、破坏抢状态。</p>
 */
public class MaidAnchorTimeoutBehavior extends Behavior<EntityMaid> {

    public MaidAnchorTimeoutBehavior() {
        super(Map.of());
    }

    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel level, @NotNull EntityMaid maid) {
        if (!(maid.getTask() instanceof IMaidBlockDestroyTask)) {
            return false;
        }
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        // 仅锚定模式（一键连锁开 + 持续检测关）维护锚点与目标锁，其他模式无需巡检
        return d.vein && !d.continuousScan;
    }

    @Override
    protected boolean canStillUse(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        return checkExtraStartConditions(level, maid);
    }

    @Override
    protected void tick(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        MaidAnchorMoveBehavior.tickTimeout(maid, gameTime);
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }
}
