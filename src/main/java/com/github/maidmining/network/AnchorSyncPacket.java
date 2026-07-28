package com.github.maidmining.network;

import com.github.maidmining.client.ClientPacketHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * S2C：把一只挖矿女仆的锚点可视化数据推给追踪它的客户端。
 *
 * <p>只带渲染必需的最小信息：女仆实体 ID、锚点中心、切比雪夫半径、矿物勾选掩码，
 * 外加一个<b>数据版本号</b>。矿物具体坐标不传——客户端拿到中心+半径后<b>本地扫描</b>
 * 方块，省网络流量，也保证与真实世界方块一致。valid=false 表示该女仆已停止挖矿/
 * 无锚点，客户端据此清除缓存。</p>
 *
 * <p><b>版本号的作用</b>：客户端原本每 0.5 秒无条件重扫一次锚点框，哪怕这半秒里
 * 什么都没变。但「框里的矿少了一块」这件事服务端最清楚——女仆的破坏是服务端逻辑。
 * 于是服务端每完成一次破坏就递增计数，随包带给客户端；客户端只在版本号变化时才重扫。
 * 静止时开销归零，挖矿时即时响应。</p>
 *
 * <p><b>为什么不用客户端方块变化事件</b>：Forge 客户端侧没有这类事件（{@code BlockEvent}
 * 系列只在服务端触发），要挂只能靠 Mixin 拦 {@code ClientLevel.setBlock}。本模组
 * 全程零 Mixin，移动端 Forge 环境下引入字节码变换的风险不值得，故走服务端计数这条路。
 * 服务端不知道的方块变化（玩家自己挖、爆炸、活塞）由客户端的时间兜底覆盖。</p>
 */
public class AnchorSyncPacket {

    private final int maidId;
    private final boolean valid;
    private final BlockPos center;
    private final int radius;
    /** 矿物勾选掩码：OreType.ordinal() 对应的位为 1 表示该类矿被选中采集。 */
    private final int oreMask;
    /**
     * 数据版本号：女仆每破坏一个方块递增。客户端据此判断是否需要重扫，
     * 与上次收到的值相同就直接复用缓存（含已算好的顶点数据）。
     */
    private final int version;

    public AnchorSyncPacket(int maidId, boolean valid, BlockPos center, int radius,
                            int oreMask, int version) {
        this.maidId = maidId;
        this.valid = valid;
        this.center = center == null ? BlockPos.ZERO : center;
        this.radius = radius;
        this.oreMask = oreMask;
        this.version = version;
    }

    public static void encode(AnchorSyncPacket msg, FriendlyByteBuf buf) {
        buf.writeVarInt(msg.maidId);
        buf.writeBoolean(msg.valid);
        buf.writeBlockPos(msg.center);
        buf.writeVarInt(msg.radius);
        buf.writeVarInt(msg.oreMask);
        buf.writeVarInt(msg.version);
    }

    public static AnchorSyncPacket decode(FriendlyByteBuf buf) {
        int id = buf.readVarInt();
        boolean valid = buf.readBoolean();
        BlockPos center = buf.readBlockPos();
        int radius = buf.readVarInt();
        int mask = buf.readVarInt();
        int version = buf.readVarInt();
        return new AnchorSyncPacket(id, valid, center, radius, mask, version);
    }

    public static void handle(AnchorSyncPacket msg, Supplier<NetworkEvent.Context> ctx) {
        NetworkEvent.Context c = ctx.get();
        c.enqueueWork(() ->
                // 客户端专属处理用 DistExecutor 隔离，避免专用服务器加载客户端类
                DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> ClientPacketHandler.handleAnchorSync(msg))
        );
        c.setPacketHandled(true);
    }

    // ==== 供客户端处理器读取的访问器 ====
    public int maidId() { return maidId; }
    public boolean valid() { return valid; }
    public BlockPos center() { return center; }
    public int radius() { return radius; }
    public int oreMask() { return oreMask; }
    public int version() { return version; }
}