package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidGatherConfigData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.common.collect.ImmutableMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import studio.fantasyit.maid_useful_task.memory.CurrentWork;
import studio.fantasyit.maid_useful_task.util.MemoryUtil;

/**
 * 采集交付行为。
 *
 * 触发条件：本轮已采够（gatheredCount >= gatherAmount > 0）且女仆处于 IDLE（挖掘行为已结束）。
 * 执行流程：
 *   1. 用官方 brain 移动方式（WALK_TARGET memory）走向主人；
 *   2. 到达主人 2 格内后，把背包物品丢向主人；
 *   3. resetRound：gatherAmount 与 gatheredCount 一并归零（重置数量栏，防止死循环重采）。
 *
 * 关键点：女仆的移动由 brain 系统驱动，必须用 BehaviorUtils.setWalkAndLookTargetMemories
 * 写入 WALK_TARGET memory，直接调 getNavigation().moveTo 不会被女仆 brain 执行。
 * 因此构造时声明 WALK_TARGET = REGISTERED，brain 才会把本行为纳入调度。
 */
public class MaidGatherDeliverBehavior extends Behavior<EntityMaid> {

    private static final float SPEED = 0.7f;
    private static final int STOP_DISTANCE = 2;
    private static final int TELEPORT_TIMEOUT = 160; // tick，寻路超时后传送兜底
    private static final int IDLE_WAIT_LIMIT = 60;   // tick，采够后等挖掘复位 IDLE 的上限，超时强制接管

    private int moveTicks = 0;
    private boolean delivered = false;
    private int idleWaitTicks = 0; // 采够后等待 CurrentWork 回到 IDLE 的计时，超时则强制接管

    public MaidGatherDeliverBehavior() {
        // 声明会用到 WALK_TARGET / LOOK_TARGET，brain 才会调度本行为并允许我们写入移动目标
        super(ImmutableMap.of(
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED
        ), 1200);
    }

    @Override
    protected boolean checkExtraStartConditions(ServerLevel level, EntityMaid maid) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);
        if (d.gatherAmount <= 0 || d.gatheredCount < d.gatherAmount) {
            idleWaitTicks = 0;
            return false;
        }
        // 优先在挖掘空闲时介入；若挖掘状态长时间没复位（残留 DESTROY 等），
        // 超过 IDLE_WAIT_LIMIT 后强制接管，避免和挖掘互锁导致女仆全局停摆。
        CurrentWork work = MemoryUtil.getCurrent(maid);
        if (work == CurrentWork.IDLE) {
            idleWaitTicks = 0;
            return true;
        }
        idleWaitTicks++;
        return idleWaitTicks >= IDLE_WAIT_LIMIT;
    }

    @Override
    protected void start(ServerLevel level, EntityMaid maid, long gameTime) {
        moveTicks = 0;
        delivered = false;
        idleWaitTicks = 0;
        // 接管时强制清理挖掘残留状态，确保不会卡在 DESTROY 等非 IDLE 值
        if (MemoryUtil.getCurrent(maid) != CurrentWork.IDLE) {
            MemoryUtil.setCurrent(maid, CurrentWork.IDLE);
        }
        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            // 没有主人：直接重置，结束任务，避免卡死
            resetRound(maid);
            delivered = true;
            return;
        }
        BehaviorUtils.setWalkAndLookTargetMemories(maid, owner, SPEED, STOP_DISTANCE);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, EntityMaid maid, long gameTime) {
        return !delivered;
    }

    @Override
    protected void tick(ServerLevel level, EntityMaid maid, long gameTime) {
        if (delivered) return;

        LivingEntity owner = maid.getOwner();
        if (owner == null) {
            resetRound(maid);
            delivered = true;
            return;
        }

        double distSqr = maid.distanceToSqr(owner);
        if (distSqr <= (STOP_DISTANCE + 1) * (STOP_DISTANCE + 1)) {
            // 到达主人身边：先清空数量栏（确保万无一失），再交付
            resetRound(maid);
            deliverToOwner(maid, owner);
            delivered = true;
            return;
        }

        moveTicks++;
        if (moveTicks >= TELEPORT_TIMEOUT) {
            // 寻路超时（被困/路径中断）：传送到主人身边兜底
            maid.moveTo(owner.getX(), owner.getY(), owner.getZ(), maid.getYRot(), maid.getXRot());
            resetRound(maid);
            deliverToOwner(maid, owner);
            delivered = true;
            return;
        }

        // 持续刷新移动目标，保证不丢失
        BehaviorUtils.setWalkAndLookTargetMemories(maid, owner, SPEED, STOP_DISTANCE);
    }

    @Override
    protected void stop(ServerLevel level, EntityMaid maid, long gameTime) {
        maid.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        // 结束时确保挖掘状态复位为 IDLE，避免残留非 IDLE 值污染后续任务（伐木/挖矿等）
        MemoryUtil.setCurrent(maid, CurrentWork.IDLE);
        moveTicks = 0;
        idleWaitTicks = 0;
    }

    @Override
    protected boolean timedOut(long gameTime) {
        // 不因默认时长被打断，由 delivered / checkExtraStartConditions 控制生命周期
        return false;
    }

    /**
     * 把女仆背包内的目标方块物品朝主人方向抛射交付。
     * - 只丢任务指定的目标方块，工具和无关物品留在背包；
     * - 物品从女仆位置生成，带朝主人方向的初速度；
     * - 设 pickUpDelay=60（3秒），足够飞到主人脚下并被主人拾取；
     * - 不操作女仆拾取开关，避免异常中断导致永久不拾取；
     * - 交付是 fire-and-forget：物品丢出即完成，无需侦测主人是否拾取。
     */
    private void deliverToOwner(EntityMaid maid, LivingEntity owner) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);
        CombinedInvWrapper inv = maid.getAvailableBackpackInv();

        // 计算从女仆到主人的方向向量（归一化后乘以抛射速度）
        double dx = owner.getX() - maid.getX();
        double dz = owner.getZ() - maid.getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);
        double throwSpeed = 0.3;
        double vx = dist > 0.1 ? (dx / dist) * throwSpeed : 0;
        double vz = dist > 0.1 ? (dz / dist) * throwSpeed : 0;
        double vy = 0.2; // 轻微上抛弧度

        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            // 只交付任务目标方块物品
            if (!com.github.maidmining.task.MaidGatherTask.isGatherItem(stack, d)) continue;

            ItemStack toThrow = stack.copy();
            inv.setStackInSlot(i, ItemStack.EMPTY);

            net.minecraft.world.entity.item.ItemEntity item = new net.minecraft.world.entity.item.ItemEntity(
                    maid.level(), maid.getX(), maid.getEyeY() - 0.3, maid.getZ(), toThrow);
            item.setDeltaMovement(vx, vy, vz);
            item.setPickUpDelay(60); // 3秒内谁都捡不到
            item.setThrower(maid.getUUID());
            maid.level().addFreshEntity(item);
        }
    }

    /** 一轮交付完成：目标数量与进度一并归零，写回并同步到客户端，重置 GUI 数量栏。 */
    private void resetRound(EntityMaid maid) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);
        d.gatherAmount = 0;
        d.gatheredCount = 0;
        MaidGatherConfigData.setAndSync(maid, d);
    }
}
