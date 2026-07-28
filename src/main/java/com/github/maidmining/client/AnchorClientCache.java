package com.github.maidmining.client;

import net.minecraft.core.BlockPos;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 客户端锚点缓存：存服务端同步来的每只挖矿女仆的可视化数据。
 * 渲染循环从这里读，网络包处理线程往这里写，用并发容器。
 */
public final class AnchorClientCache {

    /** 单条锚点数据。 */
    public static final class Entry {
        public final BlockPos center;
        public final int radius;
        public final int oreMask;
        /**
         * 服务端的可视化数据版本号（女仆每破坏方块递增）。
         * {@link OreScanCache} 用它判断是否需要重扫，值没变就复用上次的扫描结果与顶点数据。
         */
        public final int version;
        /** 最后更新的客户端时间（毫秒），用于过期清理。 */
        public volatile long lastUpdate;

        Entry(BlockPos center, int radius, int oreMask, int version, long now) {
            this.center = center;
            this.radius = radius;
            this.oreMask = oreMask;
            this.version = version;
            this.lastUpdate = now;
        }
    }

    /** 超过此时长（毫秒）未收到更新即视为过期，自动清除（防止女仆离开视野后残留）。 */
    private static final long TTL_MS = 3000L;

    private static final Map<Integer, Entry> DATA = new ConcurrentHashMap<>();

    private AnchorClientCache() {
    }

    public static void put(int maidId, BlockPos center, int radius, int oreMask, int version) {
        DATA.put(maidId, new Entry(center, radius, oreMask, version, System.currentTimeMillis()));
    }

    public static void remove(int maidId) {
        DATA.remove(maidId);
    }

    public static void clear() {
        DATA.clear();
    }

    /** 取全部未过期条目，同时顺手清掉过期项。 */
    public static Map<Integer, Entry> snapshot() {
        long now = System.currentTimeMillis();
        DATA.entrySet().removeIf(e -> now - e.getValue().lastUpdate > TTL_MS);
        return DATA;
    }
}