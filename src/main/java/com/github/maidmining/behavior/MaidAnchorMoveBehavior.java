package com.github.maidmining.behavior;

import com.github.maidmining.config.MaidMiningConfigData;
import com.github.maidmining.util.ChainMiningManager;
import com.github.maidmining.util.OreMatcher;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.init.InitEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BehaviorUtils;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Path;
import org.jetbrains.annotations.NotNull;
import studio.fantasyit.maid_useful_task.memory.CurrentWork;
import studio.fantasyit.maid_useful_task.task.IMaidBlockDestroyTask;
import studio.fantasyit.maid_useful_task.util.Conditions;
import studio.fantasyit.maid_useful_task.util.MemoryUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 锚定挖矿移动/选矿行为（仅一键连锁 vein=true 生效）。
 *
 * <p><b>为什么必须完全自写而不能继承框架 Move：</b>「越挖越远」漂移的根源在框架
 * {@code MaidCenterMoveToBlockTask}——它的探测中心 {@code getWorkSearchPos()} 是 private，
 * 写死调用 {@code MaidUtils.getMaidRestrictCenter()}，非 home 模式下直接返回女仆<b>当前位置</b>；
 * 而执行搜索的 {@code searchForDestination()} 是 {@code final}。两者都无法通过继承改写，
 * 于是探测中心永远跟着女仆跑，挖一块位置变一次，中心随之平移，逐轮偏离初始目标（愈推愈下）。
 * 唯一根治办法是完整复刻框架的「搜索矿 → 找站位 → 写记忆」链路，但把探测中心从
 * 女仆位置替换为一个<b>锁死的锚点</b>。</p>
 *
 * <p><b>锚定模型（TNT 比喻）：</b>女仆开始一轮时在脚下埋一个锚点 P，本轮所有探测都以 P
 * 为切比雪夫方形壳层的中心，覆盖 ±{@code passRadius}（水平与垂直<b>对称</b>，修正了 1.1.0
 * 垂直上多下少的问题）。女仆在半径内跑来跑去挖矿，中心 P 始终不动——等于「脚下埋颗八格
 * TNT，波及范围内由近到远挖光」。</p>
 *
 * <p><b>层内近锚点优先：</b>沿用框架壳层遍历顺序（半径 i 由 0 递增），离锚点近的壳层先挖，
 * 与实测满意的「近的先挖、不舍近取远」一致，只是基准从女仆改成锚点。</p>
 *
 * <p><b>三条重置触发（重埋锚点、开启新一轮）：</b></p>
 * <ul>
 *   <li>①锚点范围内已无可达目标：本轮搜索失败 → 清除锚点，下一 tick 以女仆当前位置重埋。</li>
 *   <li>②女仆超出锚点范围：正常挖矿不会走出，只有脱离主人过远/传送才发生 →
 *       {@link #resolveAnchor} 检测到越界即重埋。</li>
 *   <li>③收放魂符：女仆卸载/重生后实体 ID 变化，{@link #ANCHORS} 查不到旧锚点 → 自动重埋。</li>
 * </ul>
 *
 * <p><b>圆中方（锁定 ⊇ 穿透）仍成立：</b>探测用切比雪夫方形壳层（本行为），穿透用欧几里得
 * 直线距离（{@code MaidMiningTask.toDestroyFromStanding} 的距离闸）。方形外接于圆，任何能过
 * 穿透闸的目标都一定落在探测壳层内。</p>
 */
public class MaidAnchorMoveBehavior extends Behavior<EntityMaid> {

    /** per-maid 锚点表（key = 实体 ID）。收放魂符后 ID 变化，旧条目自然失效并被重埋覆盖。 */
    private static final Map<Integer, BlockPos> ANCHORS = new HashMap<>();

    /**
     * per-maid「当前锁定目标 + 锁定时刻」表（key = 实体 ID）。
     *
     * <p>由 {@link MaidAnchorTimeoutBehavior} 每 tick 巡检：目标方块若已不是启用矿
     * （被挖掉/被连锁带走）说明任务正常完成，清除记录；若持续满 {@link #TARGET_TIMEOUT_TICKS}
     * （10 秒）仍在原地没被挖动，判定为「够不着的死锁目标」，强制解锁并在脚下重埋锚点。</p>
     */
    private static final Map<Integer, TargetLock> TARGET_LOCK = new HashMap<>();

    /** 同一目标锁定满此 tick 数（5 秒 = 100 tick）未被挖动 → 判定该目标够不着，弃置脱锁。 */
    private static final long TARGET_TIMEOUT_TICKS = 100L;

    /**
     * per-maid「本轮放弃目标集」（key = 实体 ID）。
     *
     * <p><b>治什么</b>：超时的根因通常是物理上够不着（要翻两格以上高差、寻路必须穿岩浆）。
     * 这类目标往往同时是<b>离女仆最近</b>的候选，若超时后不做标记，下一 tick 会立刻被重新锁定，
     * 形成「锁定 → 10 秒超时 → 重锁」的空转，锚点内其他能挖的矿永远轮不到。故超时目标登记于此，
     * 本轮探测直接跳过，女仆先去挖框内别的矿。</p>
     *
     * <p><b>生命周期绑定当前锚点</b>：锚点重定位（范围挖空 / 首次定位 / 魂符重生 / 女仆被拉出框）时
     * 一并清空。届时女仆位置已改变，原先够不着的目标作为新一轮的普通候选重新参与竞争——
     * 能挖就挖，仍够不着就再走一次超时放弃，不会永久拉黑。</p>
     */
    private static final Map<Integer, Set<BlockPos>> ABANDONED = new HashMap<>();

    /**
     * per-maid「首次检测到女仆位于框外的游戏时刻」（key = 实体 ID）。
     *
     * <p><b>治什么</b>：女仆本身不为挖矿移动（站位恒为当前位置，见 {@link #tryTarget}），
     * 所以正常开采期间她不会出框。真正会出框的只有三种情况：脚下保护触发的
     * {@link #stepAside} 挪位、玩家把她拉走（跟随主人、传送、拴绳）、以及被地形推挤。
     * 前者位移仅 1~2 格通常仍在框内，后两者是异常，需要重埋锚点换到新位置继续干活。</p>
     *
     * <p>不能一出框就重埋——玩家带她赶路时会逐格脱框，秒重埋等于沿途疯狂重算
     * {@code (2r+1)³} 格。故改为计时：连续出框满 {@link #OUT_TIMEOUT_TICKS}（10 秒）才重埋，
     * 赶路途中每一帧都在刷新计时，走到目的地停稳后才触发一次。</p>
     *
     * <p><b>与迁移态互斥</b>：换框迁移（{@link #MIGRATING}）期间「出框」正是目的，
     * 由 {@link #tickTimeout} 优先处理并直接 return，不会走到本计时。</p>
     */
    private static final Map<Integer, Long> OUT_SINCE = new HashMap<>();

    /** 女仆连续位于框外满此 tick 数（10 秒 = 200 tick）→ 判定被拉走/卡死，重置锚点。 */
    private static final long OUT_TIMEOUT_TICKS = 200L;

    /**
     * per-maid「换框迁移状态」（key = 实体 ID）。非 null 表示女仆正在走出已挖空的旧框、
     * 准备在新地点重埋锚点。
     *
     * <p><b>治什么（零位移带来的死锁）</b>：站位搜索砍掉后女仆钉在锚点上不动，框内挖空时
     * 旧逻辑 {@code relocateAnchor(maid.blockPosition())} 会把锚点重埋在<b>同一个位置</b>，
     * 新框与旧框完全重合 → 依然搜不到目标 → 每 tick 重埋一次同一点，女仆永久停摆。</p>
     *
     * <p><b>怎么治</b>：挖空后不立即重埋，而是进入迁移态——朝一个方向走，直到<b>走出旧框</b>
     * （切比雪夫超过 radius）才重埋。新框与旧框只在边界处少量重叠，重叠区已挖空，搜索时
     * 白名单里没东西直接跳过，代价仅一次遍历。</p>
     *
     * <p><b>走不动怎么办</b>：宽范围远大于当前可通行空地时（贴着基岩、墙角、封闭洞穴），
     * 女仆走不出框。故记录位置与停滞计时——连续 {@link #STUCK_TICKS} 没有位移变化就地重埋，
     * 「随便找个走不动的尽头重放」。不预判地形，交给原版寻路自己撞。</p>
     */
    private static final Map<Integer, Migration> MIGRATING = new HashMap<>();

    /** 迁移中连续此 tick 数（1.5 秒 = 30 tick）位置无变化 → 判定走不动，就地重埋。 */
    private static final long STUCK_TICKS = 30L;

    /**
     * per-maid「持锁让位状态」（key = 实体 ID）。非 null 表示女仆<b>已经选定了一个正下方的
     * 目标</b>，正在走向井沿落脚点，走到之后才对该目标动手。
     *
     * <p><b>治什么（无记忆让位导致的原地振荡）</b>：漂移模式的探测中心恒为女仆当前位置，
     * 每 tick 从头按距离排序。旧实现里「目标在正下方」只是下发一次侧移就返回 false，
     * 不留任何记忆，于是：她为目标 P 挪一格 → 位置变了，重新排序发现另一个方向的矿更近
     * → P 被抛掉 → 挪回去 → 又发现 P 最近 → 再挪。表现就是在目标上方来回一小步、
     * 像原地转圈。根子在于<b>判定基准每 tick 都在动，而动作没有记忆</b>。</p>
     *
     * <p><b>怎么治</b>：让位期间把「目标 + 落脚点」记下来，{@link #start} 最前面就接管，
     * <b>不重新搜索、不重新排序</b>——走到落脚点才执行对该目标的挖掘。这样「为避开正下方
     * 而移动」不再算作新一轮选择，优先级不会中途被别的方向抢走。</p>
     *
     * <p><b>走不到怎么办</b>：满 {@link #YIELD_TIMEOUT_TICKS} 还没到位（路径被封、
     * 落脚点其实过不去）就把该目标记入 {@link #ABANDONED} 并清状态，转头挖别的，
     * 避免永久挂着。</p>
     */
    private static final Map<Integer, Yield> YIELDING = new HashMap<>();

    /** 持锁让位的最长时限（3 秒 = 60 tick），超时判定走不到，弃置该目标。 */
    private static final long YIELD_TIMEOUT_TICKS = 60L;

    /** 到落脚点的判定阈值（格）：水平距离在此之内且已不在目标正上方，即视为站稳井沿。 */
    private static final double YIELD_ARRIVE_DIST = 0.8;

    /**
     * 让位/复工判定扫描的高度偏移顺序：同层 → 上提一格 → 下沉一格。
     *
     * <p><b>为什么只放开一格</b>：上提一格女仆走得上去，下沉一格也走得回来，两者都不会
     * 让井口变成回不去的绝路。下沉两格就不行——她跳不回来，走过去之后井口这个位置
     * 等于永久失去，任务自锁。</p>
     *
     * <p>顺序影响的只是同距离候选之间的取舍（同层优先），距离比较仍由 {@code distSqrTo} 决定。</p>
     */
    private static final int[] YIELD_DY_ORDER = {0, 1, -1};

    /**
     * per-maid「停摆状态」（key = 实体 ID，值 = 停摆时所在位置）。非 null 表示女仆<b>确认
     * 无事可做</b>，主动进入悠闲，不再每 tick 重搜。
     *
     * <p><b>治什么（死点抽搐）</b>：探测范围内只剩正下方那一列的矿、而八格井沿又全站不住
     * （2×2×2 封闭坑、贴墙深井）。此时每个候选都会被弃置，搜索必然全空，而下一 tick
     * 弃置集若已清空又会把同一批目标重新拿出来判一遍——表现为原地抽搐。</p>
     *
     * <p><b>为什么不救</b>：救的办法是「随机方向打两格废石，钻进去再挖下方」，
     * 但这要额外一套开凿机制，而触发场景极罕见，性能与收益不成正比。所以不理会：
     * 直接躺平，确保她不会因为那个够不着的目标抽搐。</p>
     *
     * <p><b>怎么复工</b>：只看<b>女仆位置有没有变</b>，不扫地形。玩家帮忙打通一条
     * 两格高的通道（错开两格的不算，她本来就走不过去）→ 她能走了 → 位置一变即自动解除；
     * 收进魂符重放走 {@link #clearAnchor}，状态全清同样复工。代价是一次
     * {@code BlockPos.equals}，比每 tick 全量重搜便宜得多。</p>
     */
    private static final Map<Integer, BlockPos> IDLED = new HashMap<>();

    /**
     * per-maid「上次跑游离复工探针的时刻」（key = 实体 ID），配合 {@link #IDLE_PROBE_INTERVAL} 节流。
     *
     * <p>探针 {@link #hasShoreNow} 内含寻路可达性验证（A*），不能每 tick 跑。
     * 玩家挖开一条通路后最多等 1 秒她就会动，感知上察觉不出延迟。</p>
     */
    private static final Map<Integer, Long> IDLE_PROBE = new HashMap<>();

    /** 游离复工探针的最小间隔（tick）。 */
    private static final long IDLE_PROBE_INTERVAL = 20L;

    /** 搜索阶段：只收女仆脚平面及以上的候选（四面八方 + 头顶）。 */
    private static final int PHASE_UPPER = 0;
    /** 搜索阶段：只收女仆脚平面以下的候选。 */
    private static final int PHASE_LOWER = 1;
    /** 搜索阶段：不按层筛选，纯按距离由近到远（漂移模式）。 */
    private static final int PHASE_ALL = 2;

    /** 两次重埋锚点的最小间隔（1 秒 = 20 tick），防止边界抖动导致高频重埋。 */
    private static final long RELOCATE_COOLDOWN_TICKS = 20L;

    /** per-maid「上次重埋锚点的游戏时刻」（key = 实体 ID），配合 {@link #RELOCATE_COOLDOWN_TICKS}。 */
    private static final Map<Integer, Long> LAST_RELOCATE = new HashMap<>();

    /** 迁移记录：出发时的旧锚点、迁移目标点（主人会移动，故可变）、上次位置与停滞起始时刻。 */
    private static final class Migration {
        final BlockPos fromAnchor;
        BlockPos walkTo;
        BlockPos lastPos;
        long lastMoveTick;

        Migration(BlockPos fromAnchor, BlockPos walkTo, BlockPos lastPos, long tick) {
            this.fromAnchor = fromAnchor;
            this.walkTo = walkTo;
            this.lastPos = lastPos;
            this.lastMoveTick = tick;
        }
    }

    /** 锁定记录：目标方块 + 锁定起始游戏时刻。 */
    private static final class TargetLock {
        final BlockPos target;
        final long sinceTick;

        TargetLock(BlockPos target, long sinceTick) {
            this.target = target;
            this.sinceTick = sinceTick;
        }
    }

    /**
     * 让位类型：决定落点怎么找、到达怎么判。
     *
     * <ul>
     *   <li>{@link #ASHORE} 上岸——脚踩的方块与目标<b>同种</b>（会被同一条连锁引线烧到），
     *       走到脚踩非同种矿的位置。到达判定看脚踩。</li>
     *   <li>{@link #WELLSIDE} 井边——目标在女仆那一列的下方，走到身边八格里离开该列的位置。
     *       到达判定看是否已离开目标那一列。</li>
     * </ul>
     */
    private enum YieldKind {
        ASHORE,
        WELLSIDE
    }

    /** 持锁让位记录：被锁住不放的目标、要走去的落脚点、让位类型、进入让位的时刻。 */
    private static final class Yield {
        final BlockPos target;
        final BlockPos standAt;
        final YieldKind kind;
        final long sinceTick;

        Yield(BlockPos target, BlockPos standAt, YieldKind kind, long sinceTick) {
            this.target = target;
            this.standAt = standAt;
            this.kind = kind;
            this.sinceTick = sinceTick;
        }
    }

    /**
     * 只读访问当前锚点（供服务端可视化同步用）。无锚点返回 null。
     * 漂移模式（continuousScan=true）下不维护锚点表，故此处也返回 null，
     * 由同步层回退到女仆当前位置作为中心。
     */
    public static BlockPos getAnchor(int maidId) {
        return ANCHORS.get(maidId);
    }

    /** 供收放魂符/死亡等外部清理：一并清掉锁定与放弃集，避免残留。 */
    public static void clearAnchor(int maidId) {
        ANCHORS.remove(maidId);
        TARGET_LOCK.remove(maidId);
        ABANDONED.remove(maidId);
        OUT_SINCE.remove(maidId);
        MIGRATING.remove(maidId);
        LAST_RELOCATE.remove(maidId);
        YIELDING.remove(maidId);
        IDLED.remove(maidId);
        IDLE_PROBE.remove(maidId);
    }

    /** 重埋锚点：换了新一片区域，旧的放弃集、目标锁与迁移态一律作废。 */
    private static void relocateAnchor(int maidId, BlockPos pos) {
        ANCHORS.put(maidId, pos);
        ABANDONED.remove(maidId);
        TARGET_LOCK.remove(maidId);
        OUT_SINCE.remove(maidId);
        MIGRATING.remove(maidId);
        YIELDING.remove(maidId);
    }

    /**
     * 带冷却的重埋：距上次重埋不足 {@link #RELOCATE_COOLDOWN_TICKS} 则跳过本次。
     *
     * <p>治「边界抖动高频重埋」——女仆恰好停在新旧框交界处时，可能出现「重埋 → 新框
     * 大半是已挖空的旧区 → 搜不到 → 又重埋」的短周期循环。每次重埋都要遍历
     * {@code (2r+1)³} 格（r=8 时 4913 格），高频触发是实打实的卡顿源。上一次重埋后
     * 至少给女仆 1 秒（走完几格、位置有实质变化）再允许下一次。</p>
     *
     * @return 真正执行了重埋返回 true；被冷却拦下返回 false。
     */
    private static boolean relocateAnchorThrottled(int maidId, BlockPos pos, long gameTime) {
        Long last = LAST_RELOCATE.get(maidId);
        if (last != null && gameTime - last < RELOCATE_COOLDOWN_TICKS) {
            return false;
        }
        relocateAnchor(maidId, pos);
        LAST_RELOCATE.put(maidId, gameTime);
        return true;
    }

    /** 移动速度，与框架 {@code DestoryBlockMoveBehavior}（super(0.5f, ...)）保持一致。 */
    private static final float SPEED = 0.5f;

    public MaidAnchorMoveBehavior() {
        super(buildMemories(), 60);
    }

    /**
     * 复刻框架 Move 的记忆前置条件：WALK_TARGET 与 TARGET_POS 都必须为空才启动
     * （即女仆当前没有在走向某目标、也没有锁定的破坏目标）。
     * 用显式 HashMap 而非 ImmutableMap.of，规避 MemoryModuleType 混合泛型的编译歧义。
     */
    private static Map<MemoryModuleType<?>, MemoryStatus> buildMemories() {
        Map<MemoryModuleType<?>, MemoryStatus> m = new HashMap<>();
        m.put(MemoryModuleType.WALK_TARGET, MemoryStatus.VALUE_ABSENT);
        m.put(InitEntities.TARGET_POS.get(), MemoryStatus.VALUE_ABSENT);
        return m;
    }

    @Override
    protected boolean checkExtraStartConditions(@NotNull ServerLevel level, @NotNull EntityMaid maid) {
        if (!(maid.getTask() instanceof IMaidBlockDestroyTask)) {
            return false;
        }
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        // 仅一键连锁生效；关闭连锁时让位给 MaidSonarMoveBehavior（原机制）
        if (!d.vein) {
            return false;
        }
        return Conditions.isCurrent(maid, CurrentWork.IDLE)
                || Conditions.isCurrent(maid, CurrentWork.BLOCKUP_DESTROY);
    }

    @Override
    protected void start(@NotNull ServerLevel level, @NotNull EntityMaid maid, long gameTime) {
        IMaidBlockDestroyTask task = (IMaidBlockDestroyTask) maid.getTask();
        MaidMiningConfigData.Data d = MaidMiningConfigData.get(maid);
        int radius = d.passRadius;

        task.tryTakeOutTool(maid);

        // 迁移中（旧框挖空、正在走向新地点）：本行为让位，由 tickTimeout 驱动迁移推进
        if (!d.continuousScan && MIGRATING.containsKey(maid.getId())) {
            return;
        }

        // 游离中（弃置了目标、确认没活可干）：不搜索、不重判，避免原地抽搐。
        // 两个复工条件，任一满足即解除：
        //   1) 女仆位置变了 —— 她自己走了 / 被玩家拉走 / 收进魂符重放；
        //   2) 之前找不到可达的岸，现在找得到了 —— 玩家挖出了可站空间或阶梯。
        //      复工判据与寻岸是同一个函数（含寻路可达性），不存在口径差：
        //      判定不到就不复工，判定到了必然走得通。
        //      带 A* 所以按 IDLE_PROBE_INTERVAL 节流，不每 tick 跑。
        BlockPos idledAt = IDLED.get(maid.getId());
        if (idledAt != null) {
            if (idledAt.equals(maid.blockPosition())) {
                Long last = IDLE_PROBE.get(maid.getId());
                if (last != null && gameTime - last < IDLE_PROBE_INTERVAL) {
                    return; // 探测冷却中，继续躺平
                }
                IDLE_PROBE.put(maid.getId(), gameTime);
                if (!hasShoreNow(level, maid, maid.blockPosition())) {
                    return;
                }
            }
            IDLED.remove(maid.getId());
            IDLE_PROBE.remove(maid.getId());
            ABANDONED.remove(maid.getId());
        }

        // 持锁让位中（已选定正下方的目标，正走向井沿）：接管本轮，不重新搜索、不重新排序。
        // 这一步必须在搜索之前——「为避开正下方而移动」不算新一轮选择，否则中途会被
        // 别的方向抢走优先级，退化成原地来回挪。
        if (tickYield(level, maid, task, gameTime)) {
            return;
        }

        BlockPos anchor = resolveAnchor(maid, d, radius, gameTime);

        // 漂移模式：中心恒为女仆当前位置，纯按距离由近到远，不分层。
        // 正下方目标不降级，照常参与竞争——只是锁定时会先走去井沿再动手（见 tryTarget）。
        if (d.continuousScan) {
            if (searchFromAnchor(level, maid, task, anchor, radius, PHASE_ALL)) {
                return;
            }
            // 搜不到目标，且本轮确实有目标被弃置（寻岸/寻井边失效）→ 进入游离（无任务状态）。
            // 不救，直接躺平，免得每 tick 重搜重弃置地抽搐。两个复工出口：位置变化、
            // 或周围岸数变化（玩家挖出可站空间/阶梯）。
            Set<BlockPos> ab = ABANDONED.get(maid.getId());
            if (ab != null && !ab.isEmpty()) {
                IDLED.put(maid.getId(), maid.blockPosition());
                MemoryUtil.setCurrent(maid, CurrentWork.IDLE);
            }
            return;
        }

        // ===== 锚点模式三阶段 =====
        // ①先把脚平面及以上（四面八方 + 头顶）挖净——这一段完全不需要位移，锚点框覆盖最完整
        if (searchFromAnchor(level, maid, task, anchor, radius, PHASE_UPPER)) {
            return;
        }
        // ②上半部分已空，才判定脚下：踩着白名单矿则先上岸站稳，再动下方
        //   注意顺序不能反——先判上岸会导致「封闭空间里四周无岸」时她原地发呆，
        //   而实际上框内上层还有活可干。上岸只是开挖下方之前的准备动作。
        if (stepAshoreIfNeeded(level, maid)) {
            return;
        }
        // ③处理脚平面以下的目标
        boolean found = searchFromAnchor(level, maid, task, anchor, radius, PHASE_LOWER);
        if (!found) {
            // 连锁引线仍在烧（hasPending=true）时，shouldDestroyBlock 会对所有候选返回 false，
            // 导致本轮"搜不到目标"——这不是范围真的挖空了，而是连锁尚未结束、暂不锁新目标。
            // 此时【绝不能重定位锚点】，否则会出现"采两三块就换锚点、原范围没挖完就跑"。
            if (ChainMiningManager.hasPending(maid)) {
                return; // 连锁进行中，保持原锚点，等引线烧完下一 tick 再搜
            }
            // 框内目标已全部清空 → 进入迁移态：朝一个方向走出旧框再重埋。
            // 不能原地重埋——女仆已不移动，同位置重埋出的新框与旧框重合，会每 tick 死循环。
            beginMigration(maid, anchor, radius, gameTime);
        }
    }

    /**
     * 进入换框迁移态：<b>朝主人走</b>，走出旧框就地重埋锚点。
     *
     * <p><b>为什么是朝主人而不是定向推进</b>：旧实现按「女仆相对锚点的偏移符号」选方向，
     * 结果是她一头扎进随机方向的深处，离主人越来越远。正常人下矿是跟着女仆一起走的，
     * 所以「去主人身边」既符合直觉，也天然让开采面跟着玩家推进。</p>
     *
     * <p><b>两种终止</b>：主人在旧框外时，走到脱框那一刻立即重埋并停下，<b>不再继续往
     * 主人那边走</b>——她只需要一片新地，不需要真的走到主人脚边。主人在旧框内（或没有
     * 主人/主人不在同世界）时，脱框条件永远不成立，改为走到主人身边就地重埋，
     * 由 {@link #STUCK_TICKS} 停滞判定兜底。</p>
     */
    private void beginMigration(EntityMaid maid, BlockPos anchor, int radius, long gameTime) {
        BlockPos cur = maid.blockPosition();
        BlockPos walkTo = ownerPos(maid);
        if (walkTo == null) {
            // 没有主人可循（未驯服 / 主人不在同世界）：退回定向推进，保证仍能换框
            int sx = Integer.signum(cur.getX() - anchor.getX());
            int sz = Integer.signum(cur.getZ() - anchor.getZ());
            if (sx == 0 && sz == 0) {
                sx = 1;
            }
            int step = 2 * radius + 2;
            walkTo = cur.offset(sx * step, 0, sz * step);
        }
        MIGRATING.put(maid.getId(), new Migration(anchor, walkTo, cur, gameTime));
        BehaviorUtils.setWalkAndLookTargetMemories(maid, walkTo, SPEED, 0);
    }

    /** 主人当前所在方块；未驯服、主人离线或不在同一世界时返回 null。 */
    private static BlockPos ownerPos(EntityMaid maid) {
        LivingEntity owner = maid.getOwner();
        if (owner == null || owner.level() != maid.level()) {
            return null;
        }
        return owner.blockPosition();
    }

    /**
     * 迁移态推进（每 tick 由 {@link #tickTimeout} 调用）。四个出口：
     *
     * <ol>
     *   <li><b>已走出旧框</b>（切比雪夫超过 radius）→ 就地重埋锚点、<b>清掉走位记忆</b>，
     *       迁移结束，女仆当场停下开始新框作业。</li>
     *   <li><b>已到主人身边</b>（≤ 2 格）→ 就地重埋。用于主人本来就在旧框内、
     *       脱框条件永远不成立的情况。</li>
     *   <li><b>连续 {@link #STUCK_TICKS} 位置没变</b>（走不动：墙角、基岩、封闭洞穴，
     *       或可通行空地远小于框宽）→ 就地重埋，「随便找个走不动的尽头重放」。</li>
     *   <li><b>仍在推进</b> → 刷新走位记忆（原版寻路可能因中途受阻清空 WALK_TARGET），
     *       更新停滞计时基准。主人会动，所以目标点每 tick 跟着刷新。</li>
     * </ol>
     *
     * <p><b>为什么必须清走位记忆</b>：{@code start} 的前置条件要求 {@code WALK_TARGET}
     * 和 {@code TARGET_POS} 都为空。重埋锚点却留着旧的 WALK_TARGET，女仆会继续走向旧目标，
     * 新锚点的作业任务永远起不来——一路走到撞墙、寻路自然终止才恢复，中途每次脱框满 10 秒
     * 又重埋一次。这就是实测里「锚点一直放、女仆一直走，撞墙才开工」的成因。</p>
     *
     * <p>位移安全完全交给原版 {@code PathNavigation}：它自带岩浆/掉落惩罚，会绕开危险路径。
     * 我们不做任何落点预判——这正是移除 {@code isSafePos} 之后的分工。</p>
     */
    private static void tickMigration(EntityMaid maid, Migration mig, int radius, long gameTime) {
        int id = maid.getId();
        BlockPos cur = maid.blockPosition();

        // 出口1：走出旧框 → 就地重埋、停下
        if (!isInRange(cur, mig.fromAnchor, radius)) {
            finishMigration(maid, id, cur, gameTime);
            return;
        }

        // 出口2：主人在旧框内时永远脱不了框 → 走到主人身边（≤2 格）就地重埋
        BlockPos owner = ownerPos(maid);
        if (owner != null && cur.distSqr(owner) <= 4.0D) {
            finishMigration(maid, id, cur, gameTime);
            return;
        }

        // 出口3：停滞判定
        if (!cur.equals(mig.lastPos)) {
            mig.lastPos = cur;
            mig.lastMoveTick = gameTime;
        } else if (gameTime - mig.lastMoveTick >= STUCK_TICKS) {
            finishMigration(maid, id, cur, gameTime);
            return;
        }

        // 出口4：继续推进。主人会移动，所以每 tick 用最新坐标刷新目标点
        if (owner != null) {
            mig.walkTo = owner;
        }
        BehaviorUtils.setWalkAndLookTargetMemories(maid, mig.walkTo, SPEED, 0);
    }

    /**
     * 结束迁移：重埋锚点并<b>清掉走位/目标记忆</b>，让女仆当场停下、下一 tick 就能起新任务。
     *
     * <p>重埋被冷却拦下时也要清走位记忆——否则她继续走向旧目标，等于白等一个冷却周期。</p>
     */
    private static void finishMigration(EntityMaid maid, int id, BlockPos cur, long gameTime) {
        relocateAnchorThrottled(id, cur, gameTime);
        MIGRATING.remove(id);
        maid.getNavigation().stop();
        MemoryUtil.clearTarget(maid);
    }

    /**
     * 取得本轮探测中心（锚点）。
     *
     * <p><b>锚点三条判定：</b></p>
     * <ul>
     *   <li>首次定位：切任务模式 + 一键连锁开 + 持续检测关，且当前无锚点 → 以女仆脚下定位首个锚点。</li>
     *   <li>魂符放出：女仆卸载重生后实体 ID 变化，ANCHORS 查不到旧锚点 → 等同首次定位，脚下重定位。</li>
     *   <li>范围清空：本轮搜索失败（见 {@link #start}）→ 脚下立刻重定位（换到新一片区域）。</li>
     * </ul>
     *
     * <p><b>为什么没有「脱离范围」判定</b>：已由「目标锁定 10 秒未挖动」超时统一取代
     * （见 {@link #tickTimeout}）。被主人拉出框时，锁定的旧目标同样 10 秒挖不动 → 超时触发脚下重埋，
     * 一条规则覆盖两种失败模式，也顺带治了「目标物理够不着导致永久死锁」（框内目标同样会超时）。</p>
     *
     * <p>持续检测开启（漂移模式）：不维护锚点，中心恒为女仆当前位置。</p>
     */
    private BlockPos resolveAnchor(EntityMaid maid, MaidMiningConfigData.Data d, int radius, long gameTime) {
        BlockPos cur = maid.blockPosition();
        int id = maid.getId();
        if (d.continuousScan) {
            // 漂移模式：不维护锚点，清掉锚点相关的残留状态，直接以女仆当前位置为中心。
            //
            // 注意这里【绝不能清 ABANDONED】。弃置集的生命周期是「弃置 → 挖完一块后作废」
            // （清空点在 tickTimeout），而本方法每 tick 都会被 start 调用一次，
            // 在这里清等于弃置集永远只活一个 tick，后果是一个死目标被反复重选：
            //   让位选落点 A → 走到 A → 打不通 → 弃置 → 下一 tick 弃置集被清 →
            //   同一目标又是最近的 → 又让位，这次她在 A 上，八格里最近的落点是原来的 B →
            //   走回 B → 打不通 → 弃置 → 清 → ……表现就是在两格之间左右摇摆，
            //   人既不在目标正上方（beginYield 排除了那一列），也不在连锁范围上方。
            // 同理 TARGET_LOCK 也不能清：清了 tickTimeout 就拿不到锁，5 秒超时弃置
            // 和「挖完一块清弃置集」两条规则在漂移模式下会一起失效。
            ANCHORS.remove(id);
            MIGRATING.remove(id);
            LAST_RELOCATE.remove(id);
            return cur;
        }
        BlockPos anchor = ANCHORS.get(id);
        // 首次定位 / 魂符放出（查无旧锚点）：脚下定位
        if (anchor == null) {
            relocateAnchor(id, cur);
            return cur;
        }
        return anchor;
    }

    /** 切比雪夫在范围判定：三轴都不超过 radius 即视为在锚点范围框内。 */
    private static boolean isInRange(BlockPos a, BlockPos b, int radius) {
        return Math.abs(a.getX() - b.getX()) <= radius
                && Math.abs(a.getY() - b.getY()) <= radius
                && Math.abs(a.getZ() - b.getZ()) <= radius;
    }

    /**
     * 锚点范围内、<b>以女仆为中心由近到远</b>的目标搜索（不超出锚点范围框）。
     *
     * <p><b>寻路优先级（对应用户定义）：</b>枚举以锚点为中心 ±radius 的整个方形范围内所有格，
     * 但排序基准是<b>女仆当前位置</b>——按到女仆的欧几里得距离从近到远尝试，先挖离女仆最近的可行目标。
     * 这与「锚点固定框住一片区域，女仆在框内就近开采」一致：框（范围）锁死不动，选择（就近）跟着女仆走。</p>
     *
     * <p><b>{@code phase} 分层</b>：锚点模式按 {@link #start} 的三阶段调用两次，
     * 阶段之间夹一次上岸判定，故本方法只负责「按层筛选 + 按距离排序」：</p>
     * <ul>
     *   <li>{@link #PHASE_UPPER}：只收女仆脚平面<b>及以上</b>（{@code y >= maidY}）的候选——四面八方和头顶。</li>
     *   <li>{@link #PHASE_LOWER}：只收<b>脚平面以下</b>（{@code y < maidY}）的候选。</li>
     *   <li>{@link #PHASE_ALL}：不筛选，纯按距离由近到远（漂移模式用）。</li>
     * </ul>
     *
     * <p><b>为什么要分层</b>：脚下目标往往就是「最近」的那一批，一旦被优先选中就要为避让而位移，
     * 女仆一离开锚点，锚点框对她来说就不再是「站着够得到的全部」，框的覆盖出现缺口。
     * 先把上半部分挖净，位移的副作用就无关紧要了。</p>
     *
     * <p><b>脚下那格本身：</b>候选若正好是女仆当前脚踩的那一格（{@code blockPosition().below()}），
     * 本轮跳过。一次 equals，不做坠落模拟。</p>
     *
     * <p><b>放弃集跳过：</b>{@link #ABANDONED} 里的目标（本轮超时判定为够不着）直接跳过，
     * 让女仆把框内能挖的矿先挖完；等范围清空重定位锚点时放弃集一并清空，那些目标重新参与竞争。</p>
     *
     * @return 成功占位一个目标返回 true；该阶段内无任何可达目标返回 false。
     */
    private boolean searchFromAnchor(ServerLevel level, EntityMaid maid, IMaidBlockDestroyTask task,
                                     BlockPos anchor, int radius, int phase) {
        BlockPos maidPos = maid.blockPosition();
        BlockPos footBlock = maidPos.below(); // 女仆脚踩的那一格（y-1）
        Set<BlockPos> abandoned = ABANDONED.get(maid.getId());
        final int maidY = maidPos.getY();

        // 收集锚点范围框内、属于本阶段的候选格
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos p = anchor.offset(dx, dy, dz);
                    if (phase == PHASE_UPPER && p.getY() < maidY) {
                        continue;
                    }
                    if (phase == PHASE_LOWER && p.getY() >= maidY) {
                        continue;
                    }
                    candidates.add(p);
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(p -> distSqrTo(p, maidPos)));

        for (BlockPos p : candidates) {
            // 本轮已放弃（够不着 / 无井沿可站）的目标：跳过，先挖别的
            if (abandoned != null && abandoned.contains(p)) {
                continue;
            }
            cursor.set(p);
            if (tryTarget(level, maid, task, cursor, anchor, radius, footBlock)) {
                return true;
            }
        }
        return false;
    }

    /** 方块到某点的距离平方（用方块中心，供由近到远排序）。 */
    private static double distSqrTo(BlockPos p, BlockPos origin) {
        double dx = (p.getX() + 0.5) - (origin.getX() + 0.5);
        double dy = (p.getY() + 0.5) - (origin.getY() + 0.5);
        double dz = (p.getZ() + 0.5) - (origin.getZ() + 0.5);
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * 原地判定：女仆<b>就站在当前位置</b>，直接对候选矿投一次射线，能挖就挖。
     *
     * <p><b>为什么不再搜索站位</b>：旧实现复刻框架 shouldMoveTo，围着目标矿螺旋试最多
     * {@code reach³} 个站位，每个站位投一次射线。三个致命问题：<b>①性能</b>——候选矿数 ×
     * 站位数 × 射线长度，三重相乘；<b>②乱走</b>——女仆为了「更好的站位」在框内到处挪，
     * 违背锚点模式「钉在一片区域安静开采」的设计；<b>③自掘竖井</b>——螺旋第一候选恒为
     * {@code targetPos.offset(0,0,0)}（矿块自身），目标在脚下时该站位被判合法，女仆
     * 站在矿正上方向下挖，挖一格掉一格，把自己埋进井里出不来。</p>
     *
     * <p>现在 {@code stand} <b>恒等于女仆当前位置</b>，每个候选只投一次射线。这靠两件事
     * 才成立：{@link MaidMiningTask#toDestroyFromStanding} 的范围闸在锚点模式下改成了
     * <b>与锚点框同形的矩形</b>（框内每格都够得着），穿透预算给到
     * {@link MaidMiningTask#MAX_BREAK_BUDGET}（最坏地形也打得通）。于是「够不着」这个
     * 失败原因被彻底消除，女仆不需要为了够到目标而移动。</p>
     *
     * <p><b>剩下的拒绝理由：脚下安全。</b>两种情况会让女仆把自己站的地板挖穿：</p>
     * <ol>
     *   <li><b>目标在正下方或就是脚下那格</b>（{@code ddx == ddz == 0 且 ddy ≤ -1}）：
     *       射线必穿脚下地板。注意只有<b>同 x/z 那一列</b>算，斜下方的目标不受限制，
     *       站着斜射打斜井完全可行。</li>
     *   <li><b>破坏集合含脚下那格</b>（{@code footBlock}）：穿透路径直接吃掉地板。</li>
     * </ol>
     * <p>两种情况统一由 {@link #beginYield} 处理：<b>目标不降级、不放弃</b>，只是先走到
     * 井沿——离开目标那一列的落脚点——站稳之后再对同一个目标动手（见 {@link #tickYield}），
     * 于是脚底的矿照样能挖、能打井。八格井沿全站不住才弃置，转头挖别的，
     * 等挖完一块自动放回竞争。</p>
     *
     * <p><b>不再做安全落点判定</b>：{@code PosUtils.isSafePos}、{@code canPathReach} 全部移除。
     * 女仆不移动就不可能踩空或掉岩浆，脚下保护已覆盖唯一的自伤路径；位移场景（挪位、
     * 换框）走原版寻路，{@code PathNavigation} 自带危险地形惩罚，不需要我们重复判定。</p>
     *
     * @return 成功锁定并占位返回 true；被脚下保护或闸门拒绝返回 false（换下一个候选）。
     */
    private boolean tryTarget(ServerLevel level, EntityMaid maid,
                              IMaidBlockDestroyTask task, BlockPos.MutableBlockPos targetCursor,
                              BlockPos anchor, int radius, BlockPos footBlock) {
        BlockPos targetPos = targetCursor.immutable();
        if (!task.shouldDestroyBlock(maid, targetPos)) {
            return false;
        }
        // 站位恒为女仆当前位置——不搜索、不位移、不产生竖井
        BlockPos stand = maid.blockPosition();

        if (!Conditions.isGlobalValidTarget(maid, stand, targetPos)) {
            return false;
        }

        // ===== 闸零｜池塘检查，优先于所有几何判定 =====
        // 脚踩的方块与目标是【同一种】白名单矿 → 同一条连锁引线一定会烧到脚下，
        // 无论目标在哪个方位（水平、斜上、正下）都先上岸。漂移模式没有方位优先级，
        // 允许为了锁定的目标位移，所以这里不降级、不放弃，直接进上岸让位。
        if (isSamePondAsTarget(level, maid, stand.below(), targetPos)) {
            if (!beginYield(level, maid, targetPos, YieldKind.ASHORE, level.getGameTime())) {
                ABANDONED.computeIfAbsent(maid.getId(), k -> new HashSet<>()).add(targetPos);
            }
            return false;
        }

        // 几何规避：目标在女仆正下方（同 x/z 且 dy ≤ -1）时不能原地开挖，射线必穿脚下地板。
        // 不降级、不放弃——进入持锁让位，先走到井边站稳，走到之后再对这个目标动手。
        // 八格井边全站不住才弃置（记入 ABANDONED，转头挖别的，等挖完一块再放回竞争）。
        int ddx = targetPos.getX() - stand.getX();
        int ddz = targetPos.getZ() - stand.getZ();
        int ddy = targetPos.getY() - stand.getY();
        if ((ddx == 0 && ddz == 0 && ddy <= -1) || targetPos.equals(footBlock)) {
            if (!beginYield(level, maid, targetPos, YieldKind.WELLSIDE, level.getGameTime())) {
                ABANDONED.computeIfAbsent(maid.getId(), k -> new HashSet<>()).add(targetPos);
            }
            return false;
        }

        List<BlockPos> destroyList = task.toDestroyFromStanding(maid, targetPos, stand);
        if (destroyList == null) {
            return false;
        }
        // 脚下保护：这轮穿透路径会挖掉女仆站的地板 → 同样走井边让位，站稳了再打
        if (destroyList.contains(footBlock)) {
            if (!beginYield(level, maid, targetPos, YieldKind.WELLSIDE, level.getGameTime())) {
                ABANDONED.computeIfAbsent(maid.getId(), k -> new HashSet<>()).add(targetPos);
            }
            return false;
        }
        acceptTarget(maid, task, targetPos, stand, destroyList, level.getGameTime());
        return true;
    }

    /**
     * 池塘判定：{@code floorPos} 上的方块是否与目标<b>同种</b>的启用矿。
     *
     * <p><b>为什么比种类而不是「任意白名单」</b>：连锁引线只沿同种方块扩散，脚踩铁矿、
     * 目标是煤矿时铁矿不会被烧掉，那格就是合法的岸。若要求「脚踩非任意白名单」，
     * 混合矿脉里几乎找不到落点，女仆会直接弃置进游离——明明安全得很。</p>
     *
     * <p>开销恒定：两次 {@code getBlockState} 加一次矿匹配，不跑矿脉 BFS。</p>
     */
    private boolean isSamePondAsTarget(ServerLevel level, EntityMaid maid, BlockPos floorPos, BlockPos target) {
        BlockState floor = level.getBlockState(floorPos);
        if (!OreMatcher.isEnabledOre(maid, floor)) {
            return false;
        }
        return floor.getBlock() == level.getBlockState(target).getBlock();
    }

    /**
     * 上岸检查（锚点模式）：女仆脚下踩的是白名单矿时，走到最近的「岸上」位置，本轮不作业。
     *
     * <p><b>为什么不做任何预演</b>：早先的版本试图算清「这次连锁会不会烧到脚下」，
     * 要为每个候选目标跑一遍矿脉 BFS，代价高还只覆盖一部分情形。规则收敛成一句话之后
     * 什么都不用算：<b>脚下是矿就上岸，与目标无关</b>。一次 {@code getBlockState}
     * 加最多一圈邻位扫描，恒定开销。</p>
     *
     * <p><b>怎么算岸</b>：脚下方块不是白名单矿、且能站人（见 {@link #isShorePos}）。
     * 先扫水平八邻位，取最近的合格点；八邻位都不合格（整片是矿）就把搜索圈扩到 ±2。</p>
     *
     * <p><b>找不到岸时返回 false</b>：这一点关键——封闭空间里（比如 2×2×2 全是矿的坑）
     * 四周根本没有岸，若在此空转等待，女仆就永久发呆。返回 false 让调用方继续往下走，
     * 该挖的照挖，脚下安全由 {@link #tryTarget} 里的穿透路径检查兜底。
     * 另外在锚点模式下本方法只在<b>上层已挖净之后</b>才被调用（见 {@link #start}），
     * 所以「没岸可上」时她此前一定已经把周围和头顶清完了，不存在有活不干的情况。</p>
     *
     * @return 需要上岸并已下发走位指令返回 true（调用方应立即结束本轮）；
     *         无需上岸、或需要但四周无岸可上时返回 false。
     */
    private boolean stepAshoreIfNeeded(ServerLevel level, EntityMaid maid) {
        BlockPos cur = maid.blockPosition();
        BlockState floor = level.getBlockState(cur.below());
        if (!OreMatcher.isEnabledOre(maid, floor)) {
            return false; // 已在岸上
        }
        BlockPos shore = findAshoreSpot(level, maid, cur, floor.getBlock());
        if (shore == null) {
            return false; // 四周全是矿/封闭空间，没岸可上：照常作业，别发呆
        }
        BehaviorUtils.setWalkAndLookTargetMemories(maid, shore, SPEED, 0);
        return true;
    }

    /**
     * 进入持锁让位：为锁定的目标选一个落脚点并记账，本轮不动手。
     *
     * <p><b>两种落点，由 {@code kind} 决定：</b></p>
     * <ul>
     *   <li>{@link YieldKind#ASHORE 上岸}——复用锚点模式的 {@link #findShore}：先扫 ±1 圈，
     *       没有再扩 ±2，仅此两圈。岸的条件是脚踩非启用矿且站得住。</li>
     *   <li>{@link YieldKind#WELLSIDE 井边}——以<b>女仆当前位置</b>为中心的水平八格。
     *       以女仆而不是目标为中心，是因为目标可能在深处（dy 很负），那一圈往往整片在岩石里，
     *       一格都站不住；而她只需要让身体离开目标所在的那一列，之后斜射打井就完全可行。</li>
     * </ul>
     *
     * <p><b>井边落点的高度范围是 dy ∈ {+1, 0, -1}</b>：上提一格走得上去，下沉一格也走得回来，
     * 都不会把井口变成绝路。下沉两格就不行了——她跳不回来，走过去之后井口这个位置
     * 等于永远回不去，等于自锁。故只放开一格。</p>
     *
     * @return 找到落点并已记账返回 true（调用方视为「本轮已处理，勿再选此目标」）；
     *         无落点可站返回 false（调用方应弃置该目标，进入游离）。
     */
    private boolean beginYield(ServerLevel level, EntityMaid maid, BlockPos target,
                               YieldKind kind, long gameTime) {
        BlockPos cur = maid.blockPosition();
        BlockPos best;
        if (kind == YieldKind.ASHORE) {
            // 池塘类型 = 女仆当前脚踩的那种矿。岸就是「脚踩非此种」的可站格。
            Block pond = level.getBlockState(cur.below()).getBlock();
            best = findAshoreSpot(level, maid, cur, pond);
        } else {
            best = findWellSide(level, cur, target);
        }
        if (best == null) {
            return false; // 无落点可站 → 交给调用方弃置
        }
        YIELDING.put(maid.getId(), new Yield(target, best, kind, gameTime));
        BehaviorUtils.setWalkAndLookTargetMemories(maid, best, SPEED, 0);
        return true;
    }

    /**
     * 上岸落点：从 ±1 圈开始逐圈往外扩，直到找到岸或超出穿透范围上限。
     *
     * <p><b>扩圈方式与声波探测同一套</b>：ring = 1、2、3 …… 每圈只扫该壳层，命中即返回，
     * 所以结果天然是「由近到远第一个岸」。上限取 {@code passRadius}（女仆的穿透范围），
     * 超了还没岸就返回 null，调用方弃置目标进游离——不再是死板的两圈。</p>
     *
     * <p><b>扩圈途中不会重判目标</b>：本方法只找落点，目标由调用方持有并保持不变。</p>
     *
     * @param pondBlock 池塘类型（女仆脚踩那种矿）；岸的条件是脚踩非此种方块。
     */
    private BlockPos findAshoreSpot(ServerLevel level, EntityMaid maid, BlockPos cur, Block pondBlock) {
        int limit = MaidMiningConfigData.get(maid).passRadius;
        for (int ring = 1; ring <= limit; ring++) {
            BlockPos shore = findShoreRing(level, maid, cur, ring, pondBlock);
            if (shore != null) {
                return shore;
            }
        }
        return null;
    }

    /** 井边落点：身边八格，高度 dy ∈ {+1, 0, -1}，且自身不落在目标那一列上。 */
    private BlockPos findWellSide(ServerLevel level, BlockPos cur, BlockPos target) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                // 同层优先，其次上提一格、下沉一格：井边常常是台阶地形。
                // 只扫同层会把这类落点全判成「踩空」，结果八格全废、直接弃置 ——
                // 明明肉眼可见旁边能站。
                for (int dy : YIELD_DY_ORDER) {
                    BlockPos cand = cur.offset(dx, dy, dz);
                    // 落点自身不能又落在目标那一列上，否则挪过去等于没挪
                    if (cand.getX() == target.getX() && cand.getZ() == target.getZ()) {
                        continue;
                    }
                    if (!isStandablePos(level, cand)) {
                        continue;
                    }
                    double dist = distSqrTo(cand, cur);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = cand;
                    }
                }
            }
        }
        return best;
    }

    /**
     * 推进持锁让位：走到井沿就对记账里的目标动手，超时就弃置它。
     *
     * <p>让位期间<b>完全屏蔽搜索</b>，这是治「原地转圈」的关键——见 {@link #YIELDING}。</p>
     *
     * @return 让位状态存在（本轮已被接管，调用方应立即结束）返回 true；无让位状态返回 false。
     */
    private boolean tickYield(ServerLevel level, EntityMaid maid,
                              IMaidBlockDestroyTask task, long gameTime) {
        Yield y = YIELDING.get(maid.getId());
        if (y == null) {
            return false;
        }
        BlockPos cur = maid.blockPosition();

        // 目标已经不存在了（被连锁带走、被玩家挖了）：清状态，本轮让搜索正常进行
        if (!task.shouldDestroyBlock(maid, y.target)) {
            YIELDING.remove(maid.getId());
            return false;
        }

        // 到达判定：**只看有没有站到落点上**，不再要求「脚踩已非同种矿」。
        //
        // 这里曾经有个隐蔽的死锁：上岸让位要求 cleared（脚踩非同种矿）才兑现，可她是从
        // 一整片矿池中间往外走的，路上每一格脚下都还是同种矿，cleared 恒 false。于是
        // 「到了却不算到」，每 tick 重新下发走位指令，卡在离落点最近的角落贴墙磨，
        // 3 秒后超时弃置进游离。表现就是「明明旁边一格高的台阶上就是岸，她走到墙角不动了」。
        //
        // 正确的口径是：落点是 findShore/findWellSide 选出来的，它的合格性在选的时候
        // 就验过了（上岸型脚踩非矿、井边型离开目标那一列）。站上去即达成，不必再验一遍。
        boolean atSpot = cur.getX() == y.standAt.getX() && cur.getZ() == y.standAt.getZ();
        double dx = (cur.getX() + 0.5) - (y.standAt.getX() + 0.5);
        double dz = (cur.getZ() + 0.5) - (y.standAt.getZ() + 0.5);
        boolean arrived = atSpot || Math.sqrt(dx * dx + dz * dz) <= YIELD_ARRIVE_DIST;

        // 站上落点 → 兑现这次锁定，【不重跑判定链】。
        // 目标从头到尾是同一个：站到井边就不可能打脚底，站到岸上就不可能被连锁带走，
        // 落点选的时候已经保证过这两件事，到达后再判一遍纯属浪费性能。
        if (arrived) {
            YIELDING.remove(maid.getId());
            List<BlockPos> destroyList = task.toDestroyFromStanding(maid, y.target, cur);
            // 唯一保留的一道网：穿透路径不能吃掉脚下踩着的那格。允许射线经过脚下、
            // 允许斜着打洞，只是不能把地板本身挖了 —— 让位换来的安全不能在最后一步丢掉。
            if (destroyList != null && !destroyList.contains(cur.below())) {
                acceptTarget(maid, task, y.target, cur, destroyList, gameTime);
                return true;
            }
            // 够不着（地形变了 / 走过头出了穿透范围）：弃置，下一轮挖别的
            ABANDONED.computeIfAbsent(maid.getId(), k -> new HashSet<>()).add(y.target);
            return true;
        }

        // 走不到（路径被封、落脚点其实过不去）：弃置该目标，转头挖别的，别永久挂着
        if (gameTime - y.sinceTick >= YIELD_TIMEOUT_TICKS) {
            YIELDING.remove(maid.getId());
            ABANDONED.computeIfAbsent(maid.getId(), k -> new HashSet<>()).add(y.target);
            return true;
        }

        // 仍在路上：持续驱动走位，本轮不选目标
        BehaviorUtils.setWalkAndLookTargetMemories(maid, y.standAt, SPEED, 0);
        return true;
    }

    /**
     * 在半径恰为 {@code ring} 的方形壳层上找最近的<b>可达</b>岸（高度 dy ∈ {0, +1, -1}）。
     *
     * <p><b>只扫壳层不扫实心方块</b>：由 {@link #findAshoreSpot} 从 ring=1 逐圈往外调用，
     * 内圈已经扫过，重复扫是白花开销。壳层判据是 {@code max(|dx|, |dz|) == ring}。</p>
     *
     * <p><b>为什么必须验寻路可达</b>：几何上合格的岸未必走得到——隔一道墙、隔一个越不过的
     * 台阶都算。选中这种落点的后果是死锁：她朝着走却永远到不了，3 秒超时弃置进游离，
     * 而游离的复工判据（{@link #hasShoreNow}）用的是同一套判据，于是「一直报有岸、
     * 却永远走不到」，卡死在墙角。玩家在她近处新挖一格才会好，因为那个落点恰好走得通。
     * 加上 {@code createPath} 之后，「是岸」与「能到」合并成一个条件，死锁不成立。</p>
     */
    private BlockPos findShoreRing(ServerLevel level, EntityMaid maid, BlockPos cur,
                                   int ring, Block pondBlock) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -ring; dx <= ring; dx++) {
            for (int dz = -ring; dz <= ring; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                    continue; // 内圈已扫过
                }
                for (int dy : YIELD_DY_ORDER) {
                    BlockPos cand = cur.offset(dx, dy, dz);
                    if (!isShorePos(level, maid, cand, pondBlock)) {
                        continue;
                    }
                    double dist = distSqrTo(cand, cur);
                    if (dist >= bestDist) {
                        continue; // 已有更近的候选，不必为它跑寻路
                    }
                    if (!canReach(maid, cand)) {
                        continue; // 几何合格但走不到：不是有效的岸
                    }
                    bestDist = dist;
                    best = cand;
                }
            }
        }
        return best;
    }

    /**
     * 女仆当前位置能否靠寻路走到 {@code target}。
     *
     * <p>用原版 {@code PathNavigation.createPath} 试算一条路，要求算得出来<b>且终点就是
     * 目标格</b>。只判 {@code path != null} 是不够的：原版在目标不可达时会返回一条
     * 「尽可能靠近」的残路，那正是女仆贴着墙角磨的来源。</p>
     *
     * <p>开销是一次 A*，只在寻岸挑中「当前最近候选」时才跑（见 {@link #findShoreRing}
     * 里的距离剪枝），不是每格都跑。</p>
     */
    private boolean canReach(EntityMaid maid, BlockPos target) {
        Path path = maid.getNavigation().createPath(target, 0);
        if (path == null || !path.canReach()) {
            return false;
        }
        BlockPos end = path.getTarget();
        return end.getX() == target.getX() && end.getZ() == target.getZ();
    }

    /**
     * 游离复工探针：现在还能不能找到岸？
     *
     * <p><b>为什么直接跑寻岸而不是数格子</b>：复工的唯一意义是「地形变了，之前找不到的岸
     * 现在找得到了」。数格子是个代理指标，两次都错过：范围与寻岸不一致会漏掉远处新挖的岸，
     * 而「能站的格」在整片矿池里会误报（挖开一格露出的地面还是同种矿，寻岸依然失败）。
     * 直接调 {@link #findAshoreSpot} 就没有口径差：<b>判定不到岸就不复工</b>，
     * 判定到了必然能走。</p>
     *
     * <p>只在游离状态下每 tick 跑一次，命中即返回，绝大多数情况在 ring=1 就结束。</p>
     */
    private boolean hasShoreNow(ServerLevel level, EntityMaid maid, BlockPos cur) {
        Block pond = level.getBlockState(cur.below()).getBlock();
        return findAshoreSpot(level, maid, cur, pond) != null;
    }


    /**
     * 判断某格能否站人：本体与头顶都是空气，脚下实心不踩空。<b>不关心脚下是不是矿</b>。
     *
     * <p>与 {@link #isShorePos} 的区别就在最后那一条：找「岸」要求脚下非矿（锚点模式，
     * 目的是彻底脱离矿脉再动手），而漂移模式只需要「站得住、不在洞正上方」，
     * 脚下是矿也接受——否则在整片矿区里永远找不到落点。</p>
     */
    private boolean isStandablePos(ServerLevel level, BlockPos pos) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        return !floor.isAir() && floor.isSolidRender(level, below);
    }

    /**
     * 判断某格能否当「岸」：本体与头顶可站人，脚下实心，且脚下<b>不是池塘类型</b>那种矿。
     *
     * <p><b>池塘类型</b>由调用方传入（{@code pondBlock}，即女仆当前脚踩的那种白名单矿，
     * 通常等于目标的种类）。判定收紧到「非同种」而不是「非任意白名单」：连锁引线只沿
     * 同种方块扩散，脚踩铁矿而目标是金矿时铁矿不会被带走，那格就是合格的岸。
     * 要求「非任意白名单」会让混合矿区几乎找不到岸，女仆白白进游离。</p>
     *
     * <p><b>判定够准，落点就不用复检</b>：站上去之后脚下那格与目标不同种，连锁烧不到，
     * 直接开挖即可（见 {@link #tickYield}）。</p>
     *
     * @param pondBlock 池塘的方块类型；传 null 表示退化为「脚下非任意启用矿」。
     */
    private boolean isShorePos(ServerLevel level, EntityMaid maid, BlockPos pos, Block pondBlock) {
        if (!level.getBlockState(pos).isAir() || !level.getBlockState(pos.above()).isAir()) {
            return false;
        }
        BlockPos below = pos.below();
        BlockState floor = level.getBlockState(below);
        if (floor.isAir() || !floor.isSolidRender(level, below)) {
            return false;
        }
        if (pondBlock == null) {
            return !OreMatcher.isEnabledOre(maid, floor);
        }
        return floor.getBlock() != pondBlock;
    }

    /**
     * 占位：设走路/朝向/目标记忆，追加连锁待挖列表，并切换到 DESTROY 状态。
     * 与框架 start 后半段等价（walk 与 TARGET_POS 都指向站位 stand，破坏列表以矿 targetPos 为基准）。
     *
     * <p>同时登记 {@link #TARGET_LOCK}：由 {@link #tickTimeout} 巡检 10 秒未挖动的死锁目标。</p>
     */
    private void acceptTarget(EntityMaid maid, IMaidBlockDestroyTask task,
                              BlockPos targetPos, BlockPos stand, List<BlockPos> destroyList, long gameTime) {
        BehaviorUtils.setWalkAndLookTargetMemories(maid, stand, SPEED, 0);
        MemoryUtil.setTarget(maid, stand, SPEED);
        // vein=true 时 getTryDestroyBlockListBesidesStart 返回空表（起点交给 ChainMiningManager 引线扩散）
        List<BlockPos> extra = task.getTryDestroyBlockListBesidesStart(targetPos, stand, maid);
        if (extra != null) {
            destroyList.addAll(extra);
        }
        MemoryUtil.setDestroyTargetMemory(maid, destroyList);
        if (Conditions.isCurrent(maid, CurrentWork.IDLE)) {
            MemoryUtil.setCurrent(maid, CurrentWork.DESTROY);
        }
        TARGET_LOCK.put(maid.getId(), new TargetLock(targetPos, gameTime));
    }

    /**
     * 超时巡检——分两条独立计时：框内单目标 5 秒弃置，整体出框 10 秒重置。
     *
     * <p><b>① 框内目标锁定 5 秒未挖动 → 弃置脱锁</b>：女仆锁定了一个寻路机制报废的目标
     * （四面岩浆、悬崖、无合法落脚点），框架不会自己放弃，表现为无限站桩/来回蹭。持续满
     * {@link #TARGET_TIMEOUT_TICKS}（5 秒）没挖动 → 记入 {@link #ABANDONED} 放弃集、<b>锚点保持
     * 原位</b>，女仆转头去挖框内别的矿；等框内目标全挖完（{@link #start} 搜索失败）重定位时，
     * 放弃集一并清空，那个目标作为新一轮普通候选重新参与竞争。</p>
     *
     * <p><b>② 女仆连续位于框外 10 秒 → 重置锚点</b>：女仆不为挖矿移动，所以正常开采时不会
     * 出框；出框意味着被玩家拉走/传送、或脚下保护挪位挪过了头。不能一出框就重置——玩家带她
     * 赶路时会逐格脱框，秒重埋等于沿途反复重算整框。故要求<b>连续</b>出框满
     * {@link #OUT_TIMEOUT_TICKS}（10 秒），赶路途中不断刷新计时，停稳后才触发一次重埋。
     * 回到框内即清零。换框迁移期间由 ⓪ 优先接管，不走本条。</p>
     *
     * <p>由 {@link MaidAnchorTimeoutBehavior} 驱动（无记忆前置条件，DESTROY 状态下照样跑，
     * 这点很关键：锁定目标后 {@link #start} 因 WALK_TARGET/TARGET_POS 已占用而不再执行，
     * 超时检测必须挂在一个持续运行的钩子上）。</p>
     */
    public static void tickTimeout(EntityMaid maid, long gameTime) {
        int id = maid.getId();
        BlockPos anchor = ANCHORS.get(id);
        int radius = MaidMiningConfigData.get(maid).passRadius;

        // ⓪ 迁移优先：旧框挖空、女仆正走向新地点。此状态下「出框」是迁移的目的而非异常，
        // 必须先于出框计时处理，否则 10 秒超时会抢先重埋、打断迁移。
        Migration mig = MIGRATING.get(id);
        if (mig != null) {
            tickMigration(maid, mig, radius, gameTime);
            return;
        }

        boolean outOfFrame = anchor != null && !isInRange(maid.blockPosition(), anchor, radius);

        // ② 出框 10 秒计时：在框内清零；持续出框满 10 秒才重置锚点。
        if (outOfFrame) {
            Long since = OUT_SINCE.get(id);
            if (since == null) {
                OUT_SINCE.put(id, gameTime);
            } else if (gameTime - since >= OUT_TIMEOUT_TICKS) {
                MemoryUtil.clearTarget(maid);
                MemoryUtil.clearDestroyTargetMemory(maid);
                MemoryUtil.setCurrent(maid, CurrentWork.IDLE);
                relocateAnchor(id, maid.blockPosition()); // 内部会清 OUT_SINCE
                return;
            }
        } else {
            OUT_SINCE.remove(id);
        }

        TargetLock lock = TARGET_LOCK.get(id);
        if (lock == null) {
            return;
        }
        IMaidBlockDestroyTask task = (IMaidBlockDestroyTask) maid.getTask();
        // 目标已被挖掉（或已不是启用矿）→ 任务正常推进，撤销计时
        if (!task.shouldDestroyBlock(maid, lock.target)) {
            // 连锁引线烧着时 shouldDestroyBlock 恒 false，此时不能当"挖完了"处理，
            // 但引线本身在推进，也不该判超时——直接顺延计时起点。
            if (ChainMiningManager.hasPending(maid)) {
                TARGET_LOCK.put(id, new TargetLock(lock.target, gameTime));
                return;
            }
            // 一块挖完了 → 清空弃置集。弃置的理由纯粹是「当时的几何位置不适合动手」，
            // 挖完一块之后女仆的位置和周围地形都变了，那些判定全部失效，
            // 留着只会误伤——让它们作为新一轮普通候选重新参与竞争。
            TARGET_LOCK.remove(id);
            ABANDONED.remove(id);
            return;
        }
        if (gameTime - lock.sinceTick < TARGET_TIMEOUT_TICKS) {
            return;
        }

        // ① 5 秒未挖动：解除记忆占用，让下一 tick 的 start 能重新搜目标
        TARGET_LOCK.remove(id);
        MemoryUtil.clearTarget(maid);
        MemoryUtil.clearDestroyTargetMemory(maid);
        MemoryUtil.setCurrent(maid, CurrentWork.IDLE);

        if (anchor == null) {
            // 漂移模式恒无锚点：不重埋（重埋会清弃置集，正是摇摆的来源之一），
            // 只把这个目标记入弃置集，下一轮换别的挖。
            if (MaidMiningConfigData.get(maid).continuousScan) {
                ABANDONED.computeIfAbsent(id, k -> new HashSet<>()).add(lock.target);
                return;
            }
            relocateAnchor(id, maid.blockPosition());
            return;
        }
        // 女仆仍有锚点：只放弃这一个目标，锚点不动，下一 tick 去挖框内别的矿
        ABANDONED.computeIfAbsent(id, k -> new HashSet<>()).add(lock.target);
    }
}
