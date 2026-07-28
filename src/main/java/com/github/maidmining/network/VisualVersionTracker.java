package com.github.maidmining.network;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 可视化数据版本计数器（服务端）。按女仆实体 ID 记一个只增不减的计数，
 * <b>女仆每破坏一个方块就递增一次</b>，随 {@link AnchorSyncPacket} 推给客户端。
 *
 * <p><b>解决什么问题</b>：客户端的 X 光扫描原本每 0.5 秒无条件重扫整个锚点框，
 * 哪怕这半秒里一块方块都没变。而「框里的矿少了一块」这件事服务端最清楚——
 * 女仆的破坏行为本身就是服务端逻辑。于是让服务端把「我动过手了」这个事实告诉客户端，
 * 客户端只在计数变化时才重扫，静止时开销归零。</p>
 *
 * <p><b>为什么不传具体坐标</b>：传坐标就得维护增删列表、处理丢包与乱序，
 * 而客户端本地重扫在有了子区块调色板预筛之后已经很便宜。一个整数够用，
 * 它只回答「要不要重算」，不回答「哪里变了」。</p>
 *
 * <p><b>覆盖不到的情况</b>：玩家自己在框里挖、爆炸、活塞、其他模组改方块——
 * 这些不经过女仆，计数不会变。由客户端的时间兜底（见 {@code OreScanCache} 的
 * 强制重扫间隔）覆盖，最坏情况下延迟几秒自愈，不会永久残留错误轮廓。</p>
 */
public final class VisualVersionTracker {

    private VisualVersionTracker() {
    }

    private static final Map<Integer, Integer> VERSIONS = new ConcurrentHashMap<>();

    /**
     * 记一次方块破坏。女仆挖掉起点矿、连锁引线烧掉每一块、搭路方块被放置——
     * 任何会改变框内方块构成的服务端动作都该调这里。
     *
     * <p>整数溢出无害：客户端只比较「与上次是否相同」，不比较大小，
     * 绕回到负数照样能正确触发一次重扫。</p>
     */
    public static void bump(int maidId) {
        VERSIONS.merge(maidId, 1, Integer::sum);
    }

    /** 取当前计数，未记录过返回 0。 */
    public static int get(int maidId) {
        Integer v = VERSIONS.get(maidId);
        return v == null ? 0 : v;
    }

    /** 女仆卸载 / 死亡 / 收魂符时清理，避免 Map 无限增长。 */
    public static void clear(int maidId) {
        VERSIONS.remove(maidId);
    }
}
