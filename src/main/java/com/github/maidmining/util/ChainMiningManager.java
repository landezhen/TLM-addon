package com.github.maidmining.util;

import com.github.maidmining.network.VisualVersionTracker;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import studio.fantasyit.maid_useful_task.util.MaidUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连锁挖矿状态管理（引线式逐个破碎）。
 *
 * 设计要点（对齐需求）：
 * - 触发点在"起点矿被正常挖掉之后"（见 MaidMiningTask.tryDestroyBlock 的种子注入），
 *   起点本身仍走原版寻路/够得着/穿墙检测；连锁扩散阶段不做够得着检测。
 * - "同种"用精确方块注册名判定（iron_ore 与 deepslate_iron_ore 视为不同种）。
 * - 只沿六面紧贴的同种矿扩散（Direction 六向，不含对角）。
 * - 每 tick 破碎一个（引线效果），破碎经 MaidUtils.destroyBlock：
 *   掉落自动进女仆背包，主手镐子自动扣 1 点耐久 → 天然实现"按连锁数量扣耐久"。
 * - 单次连锁受 chainLimit 上限约束，防止巨型矿脉卡死 / 一次崩镐。
 *
 * 状态按女仆实体 ID 索引；一只女仆同时只跑一条连锁，未完成前不接新种子。
 */
public final class ChainMiningManager {

    private ChainMiningManager() {
    }

    /** 每破碎一块的间隔 tick 数（引线节奏，2 = 每 0.1 秒一块）。 */
    private static final int BREAK_INTERVAL = 2;

    private static final Map<Integer, ChainTask> TASKS = new HashMap<>();

    private static final class ChainTask {
        final Deque<BlockPos> queue = new ArrayDeque<>();
        final Set<BlockPos> visited = new HashSet<>();
        String oreId;
        int remaining;
        int cooldown;
    }

    /**
     * 起点矿被成功挖掉后调用：扫起点六面，把同种矿纳入连锁队列。
     * 若该女仆已有未完成的连锁，则忽略本次（等当前连锁结束）。
     */
    public static void seed(EntityMaid maid, BlockPos start, String oreId, int limit) {
        if (oreId == null || oreId.isEmpty() || limit <= 0) {
            return;
        }
        ChainTask existing = TASKS.get(maid.getId());
        if (existing != null && !existing.queue.isEmpty() && existing.remaining > 0) {
            return;
        }
        ServerLevel level = (ServerLevel) maid.level();
        ChainTask t = new ChainTask();
        t.oreId = oreId;
        t.remaining = limit;
        t.cooldown = BREAK_INTERVAL;
        BlockPos startImm = start.immutable();
        t.visited.add(startImm);
        enqueueNeighbors(level, t, startImm);
        if (t.queue.isEmpty()) {
            return;
        }
        TASKS.put(maid.getId(), t);
    }

    /** 该女仆是否有待处理的连锁。 */
    public static boolean hasPending(EntityMaid maid) {
        ChainTask t = TASKS.get(maid.getId());
        return t != null && !t.queue.isEmpty() && t.remaining > 0;
    }

    /**
     * 每 tick 推进一步：到达节奏间隔时<b>把当前整层一次性全部破碎</b>，并收集下一层。
     *
     * <p><b>为什么是整层而不是一块</b>：一条引线烧到分叉口时，三条支脉是同时被点燃的，
     * 视觉上应当一起往外烧，而不是「A 烧一格 → B 烧一格 → C 烧一格」轮转。旧实现每个
     * 间隔只 {@code poll()} 一个，三条支脉共享一条队列，只能轮着来。现在每个间隔把
     * <b>队列里当前深度的全部方块</b>取空同时破碎，再统一收集它们的邻居作为下一层。
     * 效果就是矿脉从起点向四周同速膨胀，分叉处齐头并进。</p>
     *
     * <p><b>为什么没有脚下保护了</b>：旧实现在这里判「队首恰是女仆脚下那格 → return」，
     * 一次 return 白吃掉一个完整间隔（肉眼可见的<b>引线突然停顿一下再继续</b>），而且
     * {@code visited} 已标记该格，它背后连着的整条支脉从此永久斩断（<b>最后一块不碎</b>）。
     * 更糟的是矿脉围住脚下时，一圈全碎只剩脚下独柱，女仆一动就掉。现在保护移到点火之前
     * ——{@link com.github.maidmining.behavior.MaidAnchorMoveBehavior} 锁定目标时若发现
     * 脚下会被连锁吃掉，先把她挪到「岸上」（脚下不是矿的落点）站稳再点火。既然点火时
     * 脚下已不在矿脉里，引线阶段就无需任何跳过，节奏一路烧到底。</p>
     *
     * <p>总破碎量仍由 {@code chainLimit}（{@code remaining}）封顶，一层内逐个递减，
     * 减到 0 立即停手，不会因为「整层同碎」而超额。</p>
     */
    public static void tick(EntityMaid maid) {
        ChainTask t = TASKS.get(maid.getId());
        if (t == null) {
            return;
        }
        if (t.queue.isEmpty() || t.remaining <= 0) {
            TASKS.remove(maid.getId());
            return;
        }
        if (t.cooldown-- > 0) {
            return;
        }
        t.cooldown = BREAK_INTERVAL;

        ServerLevel level = (ServerLevel) maid.level();

        // 取出当前整层（先固定层大小，本轮新入队的邻居属于下一层，不会被本轮吃掉）
        int layerSize = t.queue.size();
        List<BlockPos> nextSeeds = new ArrayList<>(layerSize);
        for (int i = 0; i < layerSize && t.remaining > 0; i++) {
            BlockPos pos = t.queue.poll();
            if (pos == null) {
                break;
            }
            BlockState state = level.getBlockState(pos);
            // 仍是同种矿才破碎（防止扩散过程中环境变化）。跳过也继续处理同层剩余，不空转。
            if (!t.oreId.equals(OreMatcher.blockId(state))) {
                continue;
            }
            // 破碎：掉落进背包 + 主手镐子扣 1 点耐久（框架内置）
            MaidUtils.destroyBlock(maid, pos);
            t.remaining--;
            nextSeeds.add(pos);
        }

        // 收集下一层：本层所有已碎方块的六面同种矿
        for (BlockPos p : nextSeeds) {
            enqueueNeighbors(level, t, p);
        }

        // 本层确实碎了东西 → 通知客户端可视化重扫。
        // 按层 bump 而非按块：整层是同 tick 一起消失的，客户端一次重扫就能全部反映，
        // 逐块递增只会让同一 tick 内的版本号连跳几次，多出来的变化客户端也看不到。
        if (!nextSeeds.isEmpty()) {
            VisualVersionTracker.bump(maid.getId());
        }
    }

    /** 扫描 pos 的六面（不含对角），把未访问的同种矿加入队列。 */
    private static void enqueueNeighbors(ServerLevel level, ChainTask t, BlockPos pos) {
        for (Direction dir : Direction.values()) {
            BlockPos np = pos.relative(dir).immutable();
            if (t.visited.contains(np)) {
                continue;
            }
            t.visited.add(np);
            if (t.oreId.equals(OreMatcher.blockId(level.getBlockState(np)))) {
                t.queue.add(np);
            }
        }
    }

    /** 女仆卸载 / 任务切换时清理，避免状态残留。 */
    public static void clear(EntityMaid maid) {
        TASKS.remove(maid.getId());
    }
}