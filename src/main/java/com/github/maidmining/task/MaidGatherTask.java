package com.github.maidmining.task;

import com.github.maidmining.MaidMining;
import com.github.maidmining.behavior.MaidGatherDeliverBehavior;
import com.github.maidmining.config.MaidGatherConfigData;
import com.github.maidmining.menu.MaidGatherConfigGui;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluids;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import studio.fantasyit.maid_useful_task.behavior.common.DestoryBlockBehavior;
import studio.fantasyit.maid_useful_task.behavior.common.DestoryBlockMoveBehavior;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;

import java.util.ArrayList;
import java.util.List;

/**
 * 独立采集任务。在任务栏中与挖矿并列。
 * 20格范围、最多3目标方块、数量0-128、自动切换工具、水平优先、采够交付并重置。
 *
 * 计数策略：使用持久化的 gatheredCount（每挖掉一个目标方块 +1），不依赖背包物品数。
 * 这样即便挖出的掉落物与方块不同名（石头→圆石）也能精确计数，且交付清空背包后不会误判为「没采够」重新开挖。
 */
public class MaidGatherTask implements IMaidTask, IMaidBlockDestroyTask {
    public static final ResourceLocation UID = new ResourceLocation(MaidMining.MODID, "gather");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.IRON_AXE.getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        // 采集任务无专属语音，复用空闲语音，避免切换到本任务时静默。
        return InitSounds.MAID_IDLE.get();
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return true;
    }

    @Override
    public int reachDistance() {
        return 8;
    }

    @Override
    public MenuProvider getTaskConfigGuiProvider(EntityMaid maid) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal("");
            }
            @Override
            public AbstractContainerMenu createMenu(int index, Inventory playerInventory, Player player) {
                return new MaidGatherConfigGui.Container(index, playerInventory, maid.getId());
            }
        };
    }

    /* ===================== 目标判定 ===================== */

    @Override
    public boolean shouldDestroyBlock(EntityMaid maid, BlockPos pos) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);

        // 任务停止：数量为0，或本轮已采够 → 不再挖掘（等待交付行为接管）
        if (d.gatherAmount <= 0 || d.gatheredCount >= d.gatherAmount) {
            return false;
        }

        // 基本可挖判定（目标/主人范围/高度/安全）
        if (!isMineableTarget(maid, pos, d)) {
            return false;
        }

        BlockPos maidPos = maid.blockPosition();
        int dx = Math.abs(pos.getX() - maidPos.getX());
        int dz = Math.abs(pos.getZ() - maidPos.getZ());
        int dy = pos.getY() - maidPos.getY();

        // 水平优先：目标在女仆脚下/头顶（水平距离≤1的垂直方块）时，
        // 若水平方向（同Y层、半径2）还有目标，先挖水平的，拒绝当前垂直目标（避免挖脚下掉坑）。
        boolean isVertical = (dx <= 1 && dz <= 1 && dy != 0);
        if (isVertical && hasHorizontalTarget(maid, maidPos, d)) {
            return false;
        }

        // 最近优先：附近若存在离女仆更近的可挖目标，先挖近的、拒绝当前较远目标。
        // 修复去掉视线检测后，女仆「穿墙」先挖远处方块（面前石头没挖先挖后面）的问题。
        if (existsNearerTarget(maid, pos, d)) {
            return false;
        }

        return true;
    }

    /**
     * 基本可挖判定：是采集目标方块、在主人 20 格范围内、与女仆高度差 ≤3、挖了不会摔死/落岩浆。
     * 不含水平优先与最近优先，供 existsNearerTarget 扫描复用，避免递归。
     */
    private boolean isMineableTarget(EntityMaid maid, BlockPos pos, MaidGatherConfigData.Data d) {
        BlockState state = maid.level().getBlockState(pos);
        if (!isGatherTarget(state, d)) return false;
        LivingEntity owner = maid.getOwner();
        if (owner != null && pos.distSqr(owner.blockPosition()) > 20 * 20) {
            return false;
        }
        BlockPos maidPos = maid.blockPosition();
        if (Math.abs(pos.getY() - maidPos.getY()) > 3) {
            return false;
        }
        return isSafeToMine(maid, pos);
    }

    /**
     * 最近优先扫描：以女仆为中心、半径 r（≤reachDistance）的立方体内，
     * 若存在离女仆比 pos 更近的可挖目标方块，返回 true（应先挖那块，拒绝当前 pos）。
     * 先用便宜的距离剪枝，再做 isMineableTarget，控制开销。
     */
    private boolean existsNearerTarget(EntityMaid maid, BlockPos pos, MaidGatherConfigData.Data d) {
        BlockPos maidPos = maid.blockPosition();
        double targetDist = pos.distSqr(maidPos);
        int r = Math.min(reachDistance(), 4);
        for (int ox = -r; ox <= r; ox++) {
            for (int oy = -r; oy <= r; oy++) {
                for (int oz = -r; oz <= r; oz++) {
                    BlockPos check = maidPos.offset(ox, oy, oz);
                    if (check.equals(pos)) continue;
                    if (check.distSqr(maidPos) >= targetDist) continue; // 只看更近的
                    if (isMineableTarget(maid, check, d)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public boolean mayDestroy(EntityMaid maid, BlockPos pos) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);
        BlockState state = maid.level().getBlockState(pos);
        return isGatherTarget(state, d);
    }

    /**
     * 破坏方块回调：成功挖掉目标方块后累计 gatheredCount。
     * 达到 gatherAmount 后由 MaidGatherDeliverBehavior 接管交付并重置。
     */
    @Override
    public boolean tryDestroyBlock(EntityMaid maid, BlockPos pos) {
        MaidGatherConfigData.Data d = MaidGatherConfigData.get(maid);
        BlockState before = maid.level().getBlockState(pos);
        boolean wasTarget = isGatherTarget(before, d);

        boolean ok = IMaidBlockDestroyTask.super.tryDestroyBlock(maid, pos);

        if (ok && wasTarget) {
            d.gatheredCount = Math.min(d.gatherAmount, d.gatheredCount + 1);
            MaidGatherConfigData.set(maid, d);
        }
        return ok;
    }

    /** 不连锁：一个一个采，精确计数。 */
    @Override
    public List<BlockPos> getTryDestroyBlockListBesidesStart(BlockPos startPos, BlockPos standPos, EntityMaid maid) {
        return new ArrayList<>();
    }

    /**
     * 覆写站位可挖判定。
     * <p>
     * 框架默认实现从女仆眼睛向目标中心做射线穿透（traverseBlocks），路径上只要遇到
     * 非目标方块（mayDestroy=false）就判定「这个站位挖不到目标」。结果是采集时女仆必须
     * 找到与目标等高、能水平直视的站位才肯挖：目标高一格就往上爬，附近没有等高站位就
     * 干脆不挖。
     * <p>
     * 这里改成纯距离判定——只要目标在够得着范围（reachDistance）内且确实是采集目标，就
     * 返回可挖列表，不做视线遮挡检查。这样女仆原地就能挖斜上/斜下的目标方块。
     */
    @Override
    public List<BlockPos> toDestroyFromStanding(EntityMaid maid, BlockPos targetPos, BlockPos standPos) {
        // distSqr 用 standPos（女仆将要站立的格子）到目标的距离，和框架内置约束一致
        double reach = reachDistance();
        if (standPos.distSqr(targetPos) > reach * reach) {
            return null;
        }
        BlockState state = maid.level().getBlockState(targetPos);
        if (state.isAir()) {
            return null;
        }
        if (!mayDestroy(maid, targetPos)) {
            return null;
        }
        List<BlockPos> result = new ArrayList<>();
        result.add(targetPos.immutable());
        return result;
    }

    /* ===================== 工具切换 ===================== */

    @Override
    public void tryTakeOutToolForTarget(EntityMaid maid, BlockPos pos) {
        BlockState state = maid.level().getBlockState(pos);
        swapToBestToolFor(maid, state);
    }

    @Override
    public void tryTakeOutTool(EntityMaid maid) {
        // 不预切换，等 target 确定后再切
    }

    private void swapToBestToolFor(EntityMaid maid, BlockState state) {
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        int bestSlot = -1;
        float bestSpeed = 1.0f;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.isEmpty()) continue;
            float speed = stack.getDestroySpeed(state);
            if (speed > bestSpeed) {
                bestSpeed = speed;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) {
            ItemStack tmp = inv.getStackInSlot(bestSlot);
            inv.setStackInSlot(bestSlot, maid.getMainHandItem());
            maid.setItemInHand(InteractionHand.MAIN_HAND, tmp);
        }
    }

    /* ===================== 辅助方法 ===================== */

    public static boolean isGatherTarget(BlockState state, MaidGatherConfigData.Data d) {
        ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (blockId == null) return false;
        String id = blockId.toString();
        String localName = state.getBlock().getName().getString();
        if (!d.gatherTarget1.isEmpty() && matchTarget(d.gatherTarget1, id, localName)) return true;
        if (!d.gatherTarget2.isEmpty() && matchTarget(d.gatherTarget2, id, localName)) return true;
        if (!d.gatherTarget3.isEmpty() && matchTarget(d.gatherTarget3, id, localName)) return true;
        return false;
    }

    /**
     * 判断某个物品是否属于任务目标方块（用于交付时只丢目标物，不丢工具/其它）。
     * 物品需对应一个方块，且该方块匹配目标配置；同时也匹配方块掉落物的常见情况
     * （如石头掉圆石）：直接按物品对应方块名 + 物品本地名做匹配。
     */
    public static boolean isGatherItem(ItemStack stack, MaidGatherConfigData.Data d) {
        if (stack.isEmpty()) return false;
        ResourceLocation itemId = ForgeRegistries.ITEMS.getKey(stack.getItem());
        if (itemId == null) return false;
        String id = itemId.toString();
        String localName = stack.getHoverName().getString();
        if (!d.gatherTarget1.isEmpty() && matchTarget(d.gatherTarget1, id, localName)) return true;
        if (!d.gatherTarget2.isEmpty() && matchTarget(d.gatherTarget2, id, localName)) return true;
        if (!d.gatherTarget3.isEmpty() && matchTarget(d.gatherTarget3, id, localName)) return true;
        // 方块→物品同名情形：把物品当方块名再比一次
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem bi) {
            ResourceLocation blockId = ForgeRegistries.BLOCKS.getKey(bi.getBlock());
            if (blockId != null) {
                String bid = blockId.toString();
                String bname = bi.getBlock().getName().getString();
                if (!d.gatherTarget1.isEmpty() && matchTarget(d.gatherTarget1, bid, bname)) return true;
                if (!d.gatherTarget2.isEmpty() && matchTarget(d.gatherTarget2, bid, bname)) return true;
                if (!d.gatherTarget3.isEmpty() && matchTarget(d.gatherTarget3, bid, bname)) return true;
            }
        }
        return false;
    }

    private static boolean matchTarget(String target, String registryId, String localName) {
        return target.equals(registryId) || target.equals(localName)
                || registryId.endsWith(":" + target);
    }

    /**
     * 水平优先检测：以女仆为中心、同Y层、由近到远扫描水平半径2格，
     * 只要存在匹配目标就返回 true（说明应优先处理水平方块，而非往脚下/头顶挖）。
     * 扫描顺序按曼哈顿距离从近到远（先相邻1格，再2格）。
     */
    private boolean hasHorizontalTarget(EntityMaid maid, BlockPos maidPos, MaidGatherConfigData.Data d) {
        for (int ring = 1; ring <= 2; ring++) {
            for (int x = -ring; x <= ring; x++) {
                for (int z = -ring; z <= ring; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != ring) continue; // 只扫当前环
                    BlockPos check = maidPos.offset(x, 0, z);
                    BlockState st = maid.level().getBlockState(check);
                    if (isGatherTarget(st, d)) return true;
                }
            }
        }
        return false;
    }

    /**
     * 安全检查：挖掉 pos 后女仆是否安全。
     * - 若 pos 是女仆脚下那一格，或挖掉后女仆需要踩的支撑格，检查其下方：
     *   - 岩浆 → 不安全
     *   - 连续空气落差超过 8 格无固体着陆点 → 不安全（致命摔伤）
     *   - 8 格以内有固体着陆 → 安全（8格以下落差均可接受，避免「4格不跳5格跳」的卡顿）
     */
    private boolean isSafeToMine(EntityMaid maid, BlockPos pos) {
        BlockPos maidPos = maid.blockPosition();
        BlockPos belowMaid = maidPos.below();

        if (pos.equals(belowMaid)) {
            return checkFallSafety(maid, pos.below());
        }
        if (pos.getY() == maidPos.getY() - 1) {
            return checkFallSafety(maid, pos.below());
        }
        return true;
    }

    /**
     * 从 startBelow 往下检查落差安全性。
     * - 遇到岩浆 → false
     * - 8 格以内遇到固体方块（着陆点）→ true
     * - 连续空气超过 8 格无着陆 → false（致命）
     */
    private boolean checkFallSafety(EntityMaid maid, BlockPos startBelow) {
        for (int drop = 0; drop <= 8; drop++) {
            BlockPos check = startBelow.below(drop);
            BlockState st = maid.level().getBlockState(check);
            if (st.getFluidState().is(Fluids.LAVA) || st.getFluidState().is(Fluids.FLOWING_LAVA)) {
                return false;
            }
            if (!st.isAir() && !st.getFluidState().is(Fluids.WATER) && !st.getFluidState().is(Fluids.FLOWING_WATER)) {
                return true;
            }
        }
        return false;
    }

    /* ===================== 行为注册 ===================== */

    @NotNull
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(@NotNull EntityMaid maid) {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        // 交付优先级最高：采够后先回主人身边交付并重置
        list.add(Pair.of(3, new MaidGatherDeliverBehavior()));
        list.add(Pair.of(5, new DestoryBlockBehavior()));
        list.add(Pair.of(4, new DestoryBlockMoveBehavior()));
        return list;
    }
}