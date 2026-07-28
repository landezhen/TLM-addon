package com.github.maidmining.task;

import com.github.maidmining.MaidMining;
import com.github.maidmining.behavior.MaidBridgeBehavior;
import com.github.maidmining.behavior.MaidChainMiningBehavior;
import com.github.maidmining.behavior.MaidDropJunkBehavior;
import com.github.maidmining.behavior.MaidSonarMoveBehavior;
import com.github.maidmining.behavior.MaidAnchorMoveBehavior;
import com.github.maidmining.behavior.MaidAnchorTimeoutBehavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.config.MiningConfig;
import com.github.maidmining.menu.MaidMiningConfigGui;
import com.github.maidmining.network.VisualVersionTracker;
import com.github.maidmining.util.ChainMiningManager;
import com.github.maidmining.util.OreMatcher;

import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitSounds;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.items.wrapper.CombinedInvWrapper;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import studio.fantasyit.maid_useful_task.behavior.common.DestoryBlockBehavior;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;
import studio.fantasyit.maid_useful_task.util.WrappedMaidFakePlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * 女仆挖矿任务。
 *
 * 复用 maid_useful_task 的方块破坏框架（IMaidBlockDestroyTask）：
 * 搜索 -> 寻路移动 -> 破坏 -> 掉落收集 -> 连锁矿脉，全部由框架处理。
 *
 * 本类负责定义"什么是矿"（per-maid 白名单）、"用什么工具"、掉落判定、
 * 连锁开关，并提供任务配置 GUI。
 */
public class MaidMiningTask implements IMaidTask, IMaidBlockDestroyTask {

    public static final ResourceLocation UID = new ResourceLocation(MaidMining.MODID, "mining");

    @Override
    public ResourceLocation getUid() {
        return UID;
    }

    @Override
    public ItemStack getIcon() {
        return Items.IRON_PICKAXE.getDefaultInstance();
    }

    @Nullable
    @Override
    public SoundEvent getAmbientSound(EntityMaid maid) {
        // 挖矿任务无专属语音，复用空闲语音，避免切换到本任务时静默。
        return InitSounds.MAID_IDLE.get();
    }

    @Override
    public boolean enableLookAndRandomWalk(EntityMaid maid) {
        return true;
    }

    /**
     * 任务模式选择界面的「额外条件」说明（鼠标悬浮任务图标时显示，
     * 与「弹幕攻击需装备御币」同款机制）。
     *
     * <p>返回的 key 会拼成翻译键 {@code task.maid_mining.mining.condition.<key>}，
     * Predicate 决定该条件当前是否满足（满足显示为绿色勾、未满足红色叉）。</p>
     *
     * <p>挖矿任务的额外条件：女仆需能拿出任意镐子（主手或背包里有镐），否则无法开采。</p>
     */
    @Override
    public List<Pair<String, java.util.function.Predicate<EntityMaid>>> getConditionDescription(EntityMaid maid) {
        return java.util.Collections.singletonList(Pair.of("has_pickaxe", this::hasAnyPickaxe));
    }

    /**
     * 射线穿透预算——<b>定值 22</b>，不再跟随 {@code passRadius}。
     *
     * <p><b>为什么是定值</b>：{@code passRadius} 的语义已收敛为纯粹的「范围」——
     * 女仆可见并可执行任务的空间边界（锚点模式即锚点框，其他模式即声波/探测半径）。
     * 穿透预算不是玩家该关心的旋钮，它只是一张「保证范围内不出意外」的安全网。</p>
     *
     * <p><b>为什么是 22</b>：{@code passRadius} 在 GUI（{@code Math.min(8, ...)}）与
     * 服务端收包（{@code Math.min(8, r)}）两处硬 clamp，永远 ≤ 8。范围上限 8 时最坏
     * 情况是「站在框心、目标在框角、全程纯实心」：切比雪夫 8、欧几里得 13.86，DDA
     * 射线步进经过的格子数约 14~22。取上界 22，则范围内任意一格在最坏地形下都打得通，
     * 玩家永远碰不到这条线。范围既然锁死 8，预算就不可能被滥用成「穿 22 格挖一块煤」。</p>
     */
    private static final int MAX_BREAK_BUDGET = 22;

    /**
     * 框架可达/搜索半径。返回 14。
     *
     * <p><b>为什么必须远大于 passRadius</b>：框架 {@code DestoryBlockMoveBehavior.shouldMoveTo}
     * 与射线可达判定都以此为上限闸，循环条件是 {@code dx < reachDistance()}（严格小于）。
     * 锚点模式的够得着范围已改为<b>与锚点框同形的矩形</b>（三轴各 ±passRadius），框角的
     * 欧几里得距离达 {@code 8√3 ≈ 13.86}——若此处仍返回 9，框角目标会被框架层先毙掉，
     * 表现为「框里画着矿但女仆纹丝不动」。返回 14 覆盖 13.86，把框架闸让开，
     * 真正的形状判定交给 {@link #toDestroyFromStanding} 的范围闸。</p>
     *
     * <p>放大此值不会让女仆挖得更远：范围闸仍按 {@code passRadius} 硬卡，
     * reachDistance 只负责「不要提前拦」。</p>
     */
    @Override
    public int reachDistance() {
        return 14;
    }

    /* ===================== 配置 GUI ===================== */

    @Override
    public MenuProvider getTaskConfigGuiProvider(EntityMaid maid) {
        return new MenuProvider() {
            @Override
            public Component getDisplayName() {
                return Component.literal("");
            }

            @Override
            public AbstractContainerMenu createMenu(int index, Inventory playerInventory, Player player) {
                return new MaidMiningConfigGui.Container(index, playerInventory, maid.getId());
            }
        };
    }

    /* ===================== 矿物 / 工具判定 ===================== */

    /**
     * 女仆是否"想要"破坏这个方块。
     * 采集模式：检查方块是否在目标列表中、是否在主人 20 格范围内、背包存量是否达标。
     * 挖矿模式：被配置选中的矿物，且女仆能拿出能正确开采它的镐子。
     */
    @Override
    public boolean shouldDestroyBlock(EntityMaid maid, BlockPos pos) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);

        // 连锁进行中：不再把任何方块列为新的挖掘目标，避免女仆去锁定队列里
        // 尚未破碎、但仍是矿方块的坐标（表现为放着眼前不挖、跑去追远处即将连锁的方块）。
        // 引线烧完 hasPending 变 false 后自动恢复搜索。
        if (ChainMiningManager.hasPending(maid)) {
            return false;
        }

        BlockState state = maid.level().getBlockState(pos);
        if (!OreMatcher.isEnabledOre(maid, state)) {
            return false;
        }
        if (d.correctTool) {
            return hasCorrectPickaxeFor(maid, state);
        }
        return hasAnyPickaxe(maid);
    }

    /**
     * 女仆"可以"破坏这个方块：用于光线投射路径判定。
     * 采集模式：只允许目标方块本身（无穿透）。
     * 挖矿模式：矿物或可穿透废石（仅判断类型，不做距离限制）。
     *
     * 注意：距离约束由框架 toDestroyFromStanding 内置的 distSqr(standPos) 处理，
     * 此处不再用 maid.blockPosition() 做距离检查——规划阶段女仆尚未移动到 standPos，
     * 用当前位置判断会错误拒绝隧道内的有效站位。
     */
    @Override
    public boolean mayDestroy(EntityMaid maid, BlockPos pos) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        BlockState state = maid.level().getBlockState(pos);

        if (OreMatcher.isEnabledOre(maid, state)) {
            return true;
        }
        if (!d.passthrough) {
            return false;
        }
        return OreMatcher.isPassThroughStone(state);
    }

    /**
     * 逐块破坏前的可达判定（覆写框架默认实现）。
     *
     * <p><b>为什么必须覆写</b>：框架默认实现用的是 {@code maid.distanceToSqr(pos.getCenter())
     * > (reachDistance()+1)²} 这个<b>欧几里得球</b>。锚点模式下我们已经把
     * {@link #toDestroyFromStanding} 的范围闸改成了与锚点框同形的矩形，两者形状不一致：
     * 框角方向的目标能通过搜索阶段的矩形闸、渲染也画得出来，却在
     * {@code DestoryBlockBehavior.tick} 里被这个球闸逐块拒绝——表现就是
     * <b>「穿透洞打通了、目标高亮着，但最后那一块死活不碎」</b>，以及
     * <b>「-5y 往下的目标一律不挖」</b>（球心在女仆胸口，向下的有效半径被身高吃掉一截）。</p>
     *
     * <p>锚点模式改用<b>矩形</b>，三轴各自 {@code ≤ passRadius + 1}。+1 是把女仆浮点坐标
     * 与方块整数坐标的错位吃掉：她站在格子中心时到边界格的轴向差恰好是 passRadius，
     * 稍有偏移就会临界抖动。其余模式沿用框架球闸不动。</p>
     */
    @Override
    public boolean canDestroyBlock(EntityMaid maid, BlockPos pos) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        if (d.vein && !d.continuousScan) {
            BlockPos cur = maid.blockPosition();
            int limit = d.passRadius + 1;
            return Math.abs(pos.getX() - cur.getX()) <= limit
                    && Math.abs(pos.getY() - cur.getY()) <= limit
                    && Math.abs(pos.getZ() - cur.getZ()) <= limit;
        }
        return IMaidBlockDestroyTask.super.canDestroyBlock(maid, pos);
    }

    /**
     * 站位 -> 目标矿的射线穿透判定（覆写框架默认实现），两条轨道通用。
     *
     * 两道闸门（AND 关系，都过才接受该站位/目标）：
     *
     * <ol>
     *   <li><b>范围闸门</b>：站位（standPos）到目标的距离必须落在 {@code passRadius}
     *       划定的范围内。形状按模式分流：
     *       <ul>
     *         <li><b>锚点模式</b>（vein=true 且 continuousScan=false）：<b>矩形</b>，
     *             三轴各自 {@code ≤ passRadius}。这与 {@link MaidAnchorMoveBehavior}
     *             的锚点框（切比雪夫 ±passRadius）<b>完全同形</b>——女仆够得着的范围
     *             就是锚点框本身，框内任意一格都够得着，框外一格都碰不到。
     *             「所见即所得」：可视化画出来的框 = 实际会被挖的区域。</li>
     *         <li><b>其他模式</b>（漂移 / 关一键连锁）：沿用<b>欧几里得球</b>
     *             ≤ {@code passRadius + 1}，+1 是方块角坐标带来的高差容差。</li>
     *       </ul>
     *       此闸治「16 格外、中间 8 石头 8 空气」这种超远穿透——超范围直接毙掉，
     *       不管中途是不是空气。</li>
     *   <li><b>穿透闸门</b>：沿 standPos→targetPos 射线统计挡路的实心非矿方块，
     *       数量 ≤ {@link #MAX_BREAK_BUDGET}。空气与目标矿不计入代价。
     *       隧道路径代价=0 优先被采纳。</li>
     * </ol>
     *
     * 关闭 passthrough 时穿透闸退化为「射线必须全空气或只有矿」，不允许挖废石；
     * 范围闸始终生效。
     */
    @Override
    public List<BlockPos> toDestroyFromStanding(EntityMaid maid, BlockPos targetPos, BlockPos standPos) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);

        // 闸门1：范围闸——基准是女仆将要站的位置，即"穿墙跨度"；不能用女仆当前位置，
        // 否则搜索阶段女仆尚未移动，远处有效目标会被误毙（表现为面前的矿不挖）。
        int adx = Math.abs(targetPos.getX() - standPos.getX());
        int ady = Math.abs(targetPos.getY() - standPos.getY());
        int adz = Math.abs(targetPos.getZ() - standPos.getZ());
        if (d.vein && !d.continuousScan) {
            // 锚点模式：矩形闸，与锚点框同形。女仆钉在锚点上不动，
            // 框内每一格都在够得着范围内，不存在"框里有矿但碰不到"。
            if (adx > d.passRadius || ady > d.passRadius || adz > d.passRadius) {
                return null;
            }
        } else {
            // 其他模式：欧几里得球。+1 容差——standPos/targetPos 是方块角坐标，正对
            // passRadius 格的矿常带 1~2 格高差（矿在墙上、女仆站地面），纯 passRadius
            // 会因高差把合法目标卡在临界外，表现为对着 8 格矿完全不动、或临界抖动。
            double maxDist = d.passRadius + 1.0D;
            if ((double) adx * adx + (double) ady * ady + (double) adz * adz > maxDist * maxDist) {
                return null;
            }
        }

        // 调用框架默认射线实现拿到需要破坏的方块列表
        List<BlockPos> result = IMaidBlockDestroyTask.super.toDestroyFromStanding(maid, targetPos, standPos);
        if (result == null) {
            return null;
        }

        if (!d.passthrough) {
            // 关闭穿透：只接受路径上没有废石的情况（只有矿本身）
            for (BlockPos p : result) {
                BlockState st = maid.level().getBlockState(p);
                if (!OreMatcher.isEnabledOre(maid, st) && !st.isAir()) {
                    return null;
                }
            }
            return result;
        }

        // 闸门2：穿透闸——沿途需要破坏的挡路实心非矿方块数 ≤ MAX_BREAK_BUDGET（定值 22）
        int maxBreak = MAX_BREAK_BUDGET;
        int breakCount = 0;
        for (BlockPos p : result) {
            BlockState st = maid.level().getBlockState(p);
            if (st.isAir()) continue;
            if (OreMatcher.isEnabledOre(maid, st)) continue; // 矿本身不算代价
            breakCount++;
            if (breakCount > maxBreak) {
                return null; // 代价太高，拒绝该站位
            }
        }
        return result;
    }

    /**
     * 双轨道隔离的核心开关点。{@code vein} 现语义为「一键连锁」总开关：
     *
     * <ul>
     *   <li><b>vein=false（原轨道 / 关一键连锁）</b>：恢复框架原生 BFS 矿脉扩散并按
     *       {@link MiningConfig#VEIN_MAX_BLOCKS} 截断。女仆逐个移动、逐个挖，把整条
     *       矿脉的目标一个接一个挖完 —— 即「加连锁功能之前的原机制连续挖矿」，
     *       该行为锁死常开、无需配置。</li>
     *   <li><b>vein=true（新轨道 / 开一键连锁）</b>：返回空，起点破碎后交给
     *       {@link ChainMiningManager} 引线式扩散（只沿六面紧贴同种矿、精确注册名
     *       判定），女仆不逐个挖，单次任务完成整条矿脉。</li>
     * </ul>
     *
     * 两条轨道用同一 {@code vein} 开关互斥，互不干扰。
     */
    @Override
    public List<BlockPos> getTryDestroyBlockListBesidesStart(BlockPos startPos, BlockPos standPos, EntityMaid maid) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);

        // 新轨道（一键连锁）：不走框架 BFS，整条矿脉由引线接管
        if (d.vein) {
            return new ArrayList<>();
        }

        // 原轨道（连续挖矿）：走框架原生 BFS 扩散，再按上限截断
        List<BlockPos> list = IMaidBlockDestroyTask.super.getTryDestroyBlockListBesidesStart(startPos, standPos, maid);
        if (list == null) {
            return new ArrayList<>();
        }
        int max = MiningConfig.VEIN_MAX_BLOCKS.get();
        if (list.size() > max) {
            return new ArrayList<>(list.subList(0, max));
        }
        return list;
    }

    /**
     * 起点破碎入口：走框架默认破碎（掉落进背包、主手镐子扣耐久），
     * 若挖掉的是被启用的矿且开启了连锁开关，就以该方块为种子注入连锁队列。
     * 连锁方块随后由 MaidChainMiningBehavior 每 tick 引线式破碎。
     */
    @Override
    public boolean tryDestroyBlock(EntityMaid maid, BlockPos pos) {
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        BlockState before = maid.level().getBlockState(pos);
        boolean wasEnabledOre = OreMatcher.isEnabledOre(maid, before);
        String oreId = wasEnabledOre ? OreMatcher.blockId(before) : "";

        boolean result = IMaidBlockDestroyTask.super.tryDestroyBlock(maid, pos);

        if (result) {
            // 框内方块构成变了，通知客户端可视化重扫（见 VisualVersionTracker）。
            // 放在 vein 判断之外：穿透开路挖掉的废石也会改变框内内容，同样要计入。
            VisualVersionTracker.bump(maid.getId());
        }
        if (result && d.vein && !oreId.isEmpty()) {
            ChainMiningManager.seed(maid, pos, oreId, d.chainLimit);
        }
        return result;
    }

    /**
     * 掉落判定：开启"仅正确工具"时，必须主手工具能让方块正确掉落才采集。
     * 这实现了"铁镐及以上才挖钻石"的需求。
     */
    @Override
    public boolean availableToGetDrop(EntityMaid maid, WrappedMaidFakePlayer fakePlayer,
                                      BlockPos pos, BlockState targetBlockState) {
        if (MaidMiningConfigData.get(maid).correctTool) {
            return fakePlayer.hasCorrectToolForDrops(targetBlockState);
        }
        return true;
    }

    /* ===================== 工具切换 ===================== */

    @Override
    public void tryTakeOutTool(EntityMaid maid) {
        swapToBestPickaxe(maid, null);
    }

    @Override
    public void tryTakeOutToolForTarget(EntityMaid maid, BlockPos pos) {
        BlockState state = maid.level().getBlockState(pos);
        swapToBestPickaxe(maid, state);
    }

    private boolean isPickaxe(ItemStack stack) {
        return stack.getItem() instanceof PickaxeItem || stack.is(ItemTags.PICKAXES);
    }

    /**
     * 在女仆可用背包里寻找镐子换到主手。
     * 给定 targetState 时优先选能正确开采该矿的镐子，否则选材质等级最高的。
     */
    private void swapToBestPickaxe(EntityMaid maid, @Nullable BlockState targetState) {
        ItemStack mainHand = maid.getMainHandItem();
        if (isPickaxe(mainHand)) {
            if (targetState == null || canHarvest(mainHand, targetState)) {
                return;
            }
        }
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        int bestSlot = -1;
        int bestScore = -1;
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (!(stack.getItem() instanceof PickaxeItem)) {
                continue;
            }
            if (targetState != null && !canHarvest(stack, targetState)) {
                continue;
            }
            int score = toolTier(stack);
            if (score > bestScore) {
                bestScore = score;
                bestSlot = i;
            }
        }
        if (bestSlot != -1) {
            ItemStack tmp = inv.getStackInSlot(bestSlot);
            inv.setStackInSlot(bestSlot, maid.getMainHandItem());
            maid.setItemInHand(InteractionHand.MAIN_HAND, tmp);
        }
    }

    private boolean hasAnyPickaxe(EntityMaid maid) {
        if (maid.getMainHandItem().getItem() instanceof PickaxeItem) {
            return true;
        }
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            if (inv.getStackInSlot(i).getItem() instanceof PickaxeItem) {
                return true;
            }
        }
        return false;
    }

    private boolean hasCorrectPickaxeFor(EntityMaid maid, BlockState state) {
        ItemStack mainHand = maid.getMainHandItem();
        if (mainHand.getItem() instanceof PickaxeItem && canHarvest(mainHand, state)) {
            return true;
        }
        CombinedInvWrapper inv = maid.getAvailableInv(true);
        for (int i = 0; i < inv.getSlots(); i++) {
            ItemStack stack = inv.getStackInSlot(i);
            if (stack.getItem() instanceof PickaxeItem && canHarvest(stack, state)) {
                return true;
            }
        }
        return false;
    }

    private boolean canHarvest(ItemStack tool, BlockState state) {
        if (!state.requiresCorrectToolForDrops()) {
            return true;
        }
        return tool.isCorrectToolForDrops(state);
    }

    private int toolTier(ItemStack stack) {
        if (stack.getItem() instanceof PickaxeItem pick) {
            return pick.getTier().getLevel();
        }
        return -1;
    }

    /* ===================== 行为注册 ===================== */

    @NotNull
    @Override
    public List<Pair<Integer, BehaviorControl<? super EntityMaid>>> createBrainTasks(@NotNull EntityMaid maid) {
        List<Pair<Integer, BehaviorControl<? super EntityMaid>>> list = new ArrayList<>();
        list.add(Pair.of(0, new MaidBridgeBehavior()));
        // 连锁挖矿：引线式逐个破碎相邻同种矿。
        list.add(Pair.of(0, new MaidChainMiningBehavior()));
        // 丢废石：空闲时清理背包内多余废石。
        list.add(Pair.of(60, new MaidDropJunkBehavior()));
        list.add(Pair.of(5, new DestoryBlockBehavior()));
        // 声波探测选矿（替换框架原生 Move）：仅 vein=false 生效，维持框架原机制（跟随女仆）。
        list.add(Pair.of(4, new MaidSonarMoveBehavior()));
        // 锚定挖矿选矿：仅 vein=true 生效，以固定锚点为探测中心，根治「越挖越远」漂移。
        // 与声波行为按 vein 开关互斥，二者永不同时启动。
        list.add(Pair.of(4, new MaidAnchorMoveBehavior()));
        // 锚定任务超时巡检：目标锁定 10 秒未挖动即解锁并脚下重埋锚点，破「够不着导致永久死锁」。
        // 必须独立注册：锁定目标后 MaidAnchorMoveBehavior 因记忆占用不再运行，正是死锁时段。
        list.add(Pair.of(0, new MaidAnchorTimeoutBehavior()));
        return list;
    }
}