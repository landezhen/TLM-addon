package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.util.OreMatcher;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;
import studio.fantasyit.maid_useful_task.memory.CurrentWork;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;
import studio.fantasyit.maid_useful_task.util.MaidUtils;
import studio.fantasyit.maid_useful_task.util.MemoryUtil;

import java.util.Map;

/**
 * 保守的自动搭路行为（实验性）。
 *
 * 触发条件（全部满足）：
 * - 当前任务为挖矿任务且女仆开启了"自动搭路"。
 * - 女仆处于 IDLE（不与挖矿/移动核心状态争抢）。
 * - 女仆正下方临空（站在坑边或悬空），即将无法通行。
 * - 女仆在原地停滞了一小段时间（避免正常行走时误放）。
 * - 背包内有可用的搭路材料（废石/泥土类）。
 *
 * 动作：把材料换到主手，在脚下放一格填坑。放置失败时安全跳过，不会卡死。
 *
 * 能力边界：仅填女仆正下方的坑，不做前方探路或跨多格搭桥。这是为了
 * 不与女仆寻路系统冲突而刻意保守的实现。
 */
public class MaidBridgeBehavior extends Behavior<EntityMaid> {

    private static final int STALL_TICKS = 20;

    private double lastX, lastY, lastZ;
    private int stallCounter = 0;

    public MaidBridgeBehavior() {
        super(Map.of(), 100);
    }

    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel level, @NotNull EntityMaid maid) {
        if (!(maid.getTask() instanceof IMaidBlockDestroyTask)) {
            return false;
        }
        MaidMiningConfigData.Data data = MaidMiningConfigData.get(maid);
        if (!data.bridge) {
            return false;
        }
        // 只在空闲时介入，避免和挖矿/移动抢状态
        if (MemoryUtil.getCurrent(maid) != CurrentWork.IDLE) {
            resetStall(maid);
            return false;
        }
        // 停滞检测
        if (isSamePosition(maid)) {
            stallCounter++;
        } else {
            resetStall(maid);
            return false;
        }
        if (stallCounter < STALL_TICKS) {
            return false;
        }
        // 正下方是否临空（需要填坑）
        if (!needsBridge(level, maid)) {
            return false;
        }
        // 有没有材料
        return hasBridgeMaterial(maid);
    }

    @Override
    protected void start(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        BlockPos below = maid.blockPosition().below();
        // 换材料到主手（自实现，与 1.21.1 版保持一致，不依赖 maid_useful_task 的 swapToHand）
        swapToHand(maid, OreMatcher::isBridgeMaterial);
        if (!OreMatcher.isBridgeMaterial(maid.getMainHandItem())) {
            resetStall(maid);
            return;
        }
        MemoryUtil.setLookAt(maid, below);
        // 尝试放置；无论成败都重置停滞计时，避免连续触发
        MaidUtils.placeBlock(maid, below);
        resetStall(maid);
    }

    @Override
    protected boolean canStillUse(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        return false;
    }

    private boolean needsBridge(ServerLevel level, EntityMaid maid) {
        BlockPos below = maid.blockPosition().below();
        BlockState belowState = level.getBlockState(below);
        // 正下方为空气或流体视为坑
        return belowState.isAir() || !belowState.getFluidState().isEmpty();
    }

    private boolean hasBridgeMaterial(EntityMaid maid) {
        if (OreMatcher.isBridgeMaterial(maid.getMainHandItem())) {
            return true;
        }
        var inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            if (OreMatcher.isBridgeMaterial(inv.getStackInSlot(i))) {
                return true;
            }
        }
        return false;
    }

    private boolean isSamePosition(EntityMaid maid) {
        boolean same = Math.abs(maid.getX() - lastX) < 0.05
                && Math.abs(maid.getY() - lastY) < 0.05
                && Math.abs(maid.getZ() - lastZ) < 0.05;
        lastX = maid.getX();
        lastY = maid.getY();
        lastZ = maid.getZ();
        return same;
    }

    private void resetStall(EntityMaid maid) {
        stallCounter = 0;
        lastX = maid.getX();
        lastY = maid.getY();
        lastZ = maid.getZ();
    }

    /**
     * 把背包里第一个满足条件的物品换到主手（原主手物品放回该槽）。
     * 自实现，与 1.21.1 版保持一致，直接用 TLM 的 getAvailableBackpackInv()
     * 与 setItemInHand，不调用 maid_useful_task 的 MaidUtils.swapToHand。
     */
    private void swapToHand(EntityMaid maid, Predicate<ItemStack> predicate) {
        if (predicate.test(maid.getMainHandItem())) {
            return;
        }
        CombinedInvWrapper inv = maid.getAvailableBackpackInv();
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (predicate.test(stack)) {
                ItemStack target = inv.getStackInSlot(i);
                inv.setStackInSlot(i, maid.getMainHandItem());
                maid.setItemInHand(InteractionHand.MAIN_HAND, target);
                return;
            }
        }
    }

    @Override
    protected boolean timedOut(long gameTime) {
        return false;
    }
}
