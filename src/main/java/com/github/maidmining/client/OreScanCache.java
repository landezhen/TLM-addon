package com.github.maidmining.client;

import com.github.maidmining.util.OreMatcher;
import com.github.maidmining.util.OreMatcher.OreType;
import com.github.maidmining.util.OreColors;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端矿物扫描缓存。对每只需渲染的女仆，以锚点为中心、切比雪夫 ±radius 遍历方块，
 * 找出被该女仆勾选（oreMask 命中）的矿物坐标，缓存供描棱渲染。
 *
 * <p>逐帧扫方块开销大，故按女仆做节流：每 {@link #RESCAN_MS} 毫秒才重扫一次，
 * 中心/半径/掩码变化时立即失效重扫。扫描只读客户端已加载区块，不触发区块加载。</p>
 *
 * <p><b>子区块调色板预筛</b>：扫描不再逐格 {@code getBlockState}，而是先按 16×16×16
 * 子区块分组，用 {@link LevelChunkSection#getStates()} 的 {@code maybeHas} 问一次
 * 「这个子区块的调色板里有没有被勾选的矿」——调色板是子区块自带的方块种类清单，
 * 查询是常数开销。地下绝大多数子区块是纯石头/深板岩，一次判定即可整块跳过 4096 格。
 * 只有调色板命中的子区块才真正逐格遍历。</p>
 */
public final class OreScanCache {

    /**
     * 兜底强制重扫间隔（毫秒）。有了版本号驱动之后，这个不再是主力触发条件，
     * 只用来覆盖服务端不知道的方块变化——玩家自己在框里挖、爆炸、活塞、其他模组改方块。
     * 从原先的 0.5 秒放宽到 2 秒：静止时每秒重扫次数从 2 降到 0.5，
     * 而真有变化时版本号会立刻触发，不依赖这个间隔。
     */
    private static final long RESCAN_MS = 2000L;
    /** 单次扫描的方块数硬上限，防止半径异常时卡顿（(2*16+1)^3 量级封顶）。 */
    private static final int MAX_BLOCKS = 40000;

    /** 一个矿物点：坐标 + 类型（决定颜色）。 */
    public static final class Ore {
        public final BlockPos pos;
        public final OreType type;
        Ore(BlockPos pos, OreType type) {
            this.pos = pos;
            this.type = type;
        }
    }

    /**
     * 一只女仆的扫描结果，连带渲染要用的顶点数据。
     *
     * <p><b>顶点数据为什么能缓存</b>：矿物线框画的是完整 12 条棱，顶点位置只取决于
     * 方块坐标，<b>与相机位置无关</b>。所以扫描一次算出来的顶点数组，在下次扫描之前
     * 一直有效——玩家怎么走、怎么转视角都不用重算。渲染每帧只需把这份数组拷进
     * 顶点缓冲，不再做任何几何计算。</p>
     *
     * <p>坐标存的是<b>绝对世界坐标</b>，相机相对偏移交给渲染时的矩阵平移处理，
     * 这样相机移动同样不会让缓存失效。</p>
     */
    public static final class ScanResult {
        List<Ore> ores = new ArrayList<>();
        /** 顶点位置，每 3 个 float 一个点，每 2 个点一条线段。 */
        public float[] lineVerts = new float[0];
        /** 与 lineVerts 对应的颜色，每 3 个 float（r/g/b）一个点。 */
        public float[] lineColors = new float[0];
        /** lineVerts 里的有效顶点数（= 有效 float 数 / 3）。 */
        public int vertCount = 0;

        long lastScan = 0L;
        BlockPos center;
        int radius;
        int oreMask;
        /** 上次扫描时对应的服务端版本号，用于判断数据是否已变。 */
        int version = Integer.MIN_VALUE;
    }

    private static final Map<Integer, ScanResult> CACHE = new ConcurrentHashMap<>();

    private OreScanCache() {
    }

    /**
     * 取渲染用的顶点数据，必要时重扫。
     *
     * <p>返回缓存对象本身而非包装视图——包装视图会让渲染每帧为每只女仆 new 一个对象，
     * 那正是本次优化要消除的东西。调用方<b>只读</b>返回值的字段，不得修改数组内容。</p>
     *
     * <p>三个重扫触发条件：</p>
     * <ol>
     *   <li><b>版本号变了</b>——服务端告知女仆动过手，框内方块构成已变。这是主力条件，
     *       响应即时且精确。</li>
     *   <li><b>中心/半径/掩码变了</b>——锚点重埋、玩家改了配置，缓存直接作废。</li>
     *   <li><b>超过 {@link #RESCAN_MS} 未扫</b>——兜底，覆盖服务端不知道的方块变化
     *       （玩家自己挖、爆炸、活塞、其他模组）。</li>
     * </ol>
     */
    public static ScanResult getVerts(int maidId, BlockPos center, int radius,
                                      int oreMask, int version) {
        return ensureFresh(maidId, center, radius, oreMask, version);
    }

    /** 按三个条件判断是否需要重扫，需要就扫一遍，返回最新的结果对象。 */
    private static ScanResult ensureFresh(int maidId, BlockPos center, int radius,
                                          int oreMask, int version) {
        ScanResult sr = CACHE.computeIfAbsent(maidId, k -> new ScanResult());
        long now = System.currentTimeMillis();
        boolean versionChanged = version != sr.version;
        boolean paramsChanged = !center.equals(sr.center) || radius != sr.radius || oreMask != sr.oreMask;
        boolean stale = now - sr.lastScan > RESCAN_MS;
        if (versionChanged || paramsChanged || stale) {
            rescan(sr, center, radius, oreMask);
            buildVerts(sr);
            sr.lastScan = now;
            sr.center = center;
            sr.radius = radius;
            sr.oreMask = oreMask;
            sr.version = version;
        }
        return sr;
    }

    public static void remove(int maidId) {
        CACHE.remove(maidId);
    }

    public static void clear() {
        CACHE.clear();
    }

    /* ===================== 顶点数据构建 ===================== */

    /** 立方体 8 个角的单位偏移。 */
    private static final float[][] CORNERS = {
            {0, 0, 0}, {1, 0, 0}, {1, 0, 1}, {0, 0, 1},
            {0, 1, 0}, {1, 1, 0}, {1, 1, 1}, {0, 1, 1}
    };

    /** 12 条棱，每条给出两个端点的角索引。 */
    private static final int[][] EDGES = {
            // 底面四条
            {0, 1}, {1, 2}, {2, 3}, {3, 0},
            // 顶面四条
            {4, 5}, {5, 6}, {6, 7}, {7, 4},
            // 竖直四条
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    /**
     * 把矿列表烤成顶点与颜色数组，供渲染每帧直接拷用。
     *
     * <p><b>画完整 12 棱，不做可见面剔除</b>。旧实现按「面法线与视线的点积」剔掉背对
     * 相机的面，一个立方体只描 9/7/4 条棱。看着省，实际不划算：剔除结果依赖相机位置，
     * <b>相机一动就得全部重算</b>，而玩家在挖矿时基本一直在动。于是每帧要为每块矿做
     * 6 次面判定、分配一个去重数组，200 块矿就是每帧 1200 次浮点判定加 200 个短命对象。</p>
     *
     * <p>改画 12 棱之后，顶点只跟方块坐标有关，与相机彻底解耦——算一次能用到下次扫描。
     * 代价是顶点数从平均 7 棱涨到 12 棱（多约七成），但在移动端这笔交换是赚的：
     * 多传几千个顶点对 GPU 是零头，而省下的浮点运算和 GC 压力是 CPU 侧的真实瓶颈。
     * 转译层（如 MG）对单次绘制的顶点数量也不敏感，对绘制调用次数才敏感。</p>
     *
     * <p>顺带好处：完整线框视觉上更清晰，X 光风格本就不追求真实遮挡感。</p>
     */
    private static void buildVerts(ScanResult sr) {
        int oreCount = sr.ores.size();
        int vertCount = oreCount * EDGES.length * 2; // 每棱两个端点
        int needFloats = vertCount * 3;

        // 数组按需增长，不缩容——矿数在挖掘过程中单向减少，复用已分配的空间避免反复 new
        if (sr.lineVerts.length < needFloats) {
            sr.lineVerts = new float[needFloats];
            sr.lineColors = new float[needFloats];
        }
        float[] v = sr.lineVerts;
        float[] c = sr.lineColors;

        int vi = 0;
        for (int i = 0; i < oreCount; i++) {
            Ore ore = sr.ores.get(i);
            OreColors.Rgb col = OreColors.of(ore.type);
            float bx = ore.pos.getX();
            float by = ore.pos.getY();
            float bz = ore.pos.getZ();
            for (int[] e : EDGES) {
                float[] p0 = CORNERS[e[0]];
                float[] p1 = CORNERS[e[1]];
                v[vi] = bx + p0[0];
                v[vi + 1] = by + p0[1];
                v[vi + 2] = bz + p0[2];
                c[vi] = col.r;
                c[vi + 1] = col.g;
                c[vi + 2] = col.b;
                vi += 3;
                v[vi] = bx + p1[0];
                v[vi + 1] = by + p1[1];
                v[vi + 2] = bz + p1[2];
                c[vi] = col.r;
                c[vi + 1] = col.g;
                c[vi + 2] = col.b;
                vi += 3;
            }
        }
        sr.vertCount = vertCount;
    }

    /**
     * 重扫一遍范围，结果写回 {@code sr.ores}。
     *
     * <p>按 16×16×16 子区块分组遍历。对每个子区块先做两道预筛，任一不通过就整块跳过，
     * 省下 4096 次 {@code getBlockState}：</p>
     * <ol>
     *   <li><b>纯空气</b>（{@code hasOnlyAir}）：洞穴、地表以上，直接跳。</li>
     *   <li><b>调色板不含勾选矿</b>（{@code maybeHas}）：子区块的调色板是它内部出现过的
     *       方块种类清单，遍历这张清单（通常一到几项）远比遍历 4096 格便宜。
     *       地下大多是纯石头/深板岩子区块，这一刀砍掉绝大部分遍历量。</li>
     * </ol>
     *
     * <p><b>为什么调色板命中不等于真有矿</b>：{@code maybeHas} 的语义是「调色板里存在
     * 这样的方块种类」，方法名的 maybe 就是这个意思——矿可能刚被挖掉、格子已经换成空气，
     * 但调色板条目还留着（调色板不会因为最后一个实例消失就立刻收缩）。所以命中之后仍要
     * 逐格确认，预筛只做排除、不做断定，不会漏画也不会错画。</p>
     */
    private static void rescan(ScanResult sr, BlockPos center, int radius, int oreMask) {
        List<Ore> found = new ArrayList<>();
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            sr.ores = found;
            return;
        }
        int side = 2 * radius + 1;
        if ((long) side * side * side > MAX_BLOCKS) {
            sr.ores = found; // 半径异常，放弃扫描
            return;
        }

        // 扫描范围的世界坐标闭区间
        final int minX = center.getX() - radius, maxX = center.getX() + radius;
        final int minY = center.getY() - radius, maxY = center.getY() + radius;
        final int minZ = center.getZ() - radius, maxZ = center.getZ() + radius;

        // 该掩码下「这个方块算不算要画的矿」——预筛与逐格确认共用同一判据，口径不会有偏差
        final java.util.function.Predicate<BlockState> wanted = st -> {
            OreType t = OreMatcher.classify(st);
            return t != OreType.NONE && (oreMask & (1 << t.ordinal())) != 0;
        };

        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        // 按区块列（x/z）→ 子区块（y）分组
        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                // 只读已加载区块，不触发区块加载（hasChunk 为假直接跳过整列）
                if (!level.hasChunk(cx, cz)) {
                    continue;
                }
                ChunkAccess chunk = level.getChunk(cx, cz);
                if (chunk == null) {
                    continue;
                }

                for (int cy = minY >> 4; cy <= (maxY >> 4); cy++) {
                    int secIdx = chunk.getSectionIndexFromSectionY(cy);
                    if (secIdx < 0 || secIdx >= chunk.getSections().length) {
                        continue; // 超出世界高度
                    }
                    LevelChunkSection sec = chunk.getSection(secIdx);
                    if (sec == null || sec.hasOnlyAir()) {
                        continue; // 预筛一：纯空气
                    }
                    if (!sec.getStates().maybeHas(wanted)) {
                        continue; // 预筛二：调色板里没有勾选的矿，整块 4096 格免遍历
                    }

                    // 命中：把扫描范围与本子区块求交，只遍历重叠部分
                    int secMinX = cx << 4, secMinY = cy << 4, secMinZ = cz << 4;
                    int x0 = Math.max(minX, secMinX), x1 = Math.min(maxX, secMinX + 15);
                    int y0 = Math.max(minY, secMinY), y1 = Math.min(maxY, secMinY + 15);
                    int z0 = Math.max(minZ, secMinZ), z1 = Math.min(maxZ, secMinZ + 15);

                    for (int y = y0; y <= y1; y++) {
                        for (int x = x0; x <= x1; x++) {
                            for (int z = z0; z <= z1; z++) {
                                // 直接问子区块要状态，绕开 Level 的区块查找开销
                                BlockState st = sec.getBlockState(x & 15, y & 15, z & 15);
                                OreType type = OreMatcher.classify(st);
                                if (type == OreType.NONE) {
                                    continue;
                                }
                                if ((oreMask & (1 << type.ordinal())) == 0) {
                                    continue; // 该类矿未被女仆勾选
                                }
                                cursor.set(x, y, z);
                                found.add(new Ore(cursor.immutable(), type));
                            }
                        }
                    }
                }
            }
        }
        sr.ores = found;
    }
}