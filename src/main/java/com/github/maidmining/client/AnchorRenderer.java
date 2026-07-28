package com.github.maidmining.client;

import com.github.maidmining.MaidMining;
import com.github.maidmining.config.MiningClientConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Map;

/**
 * 锚点可视化渲染器（客户端，仅 CLIENT dist）。世界渲染的半透明阶段后绘制：
 * <ul>
 *   <li><b>范围框</b>：锚点为中心、边长 (2*radius+1) 的立方体棱框，灰色半透明，
 *       即时模式 DEBUG_LINES + 关深度，穿透可见（玩家在框外也能看见）。</li>
 *   <li><b>矿物描棱</b>：范围内每块勾选矿物，画完整 12 条棱，按矿种上色，穿墙可见。
 *       顶点数据由 {@link OreScanCache} 预先算好并缓存（与相机无关），本类每帧只做拷贝。</li>
 *   <li><b>锚点图标</b>：锚点上方的公告板贴图，始终朝向相机，一格见方。</li>
 * </ul>
 *
 * <p>数据来自 {@link AnchorClientCache}（服务端同步，带 TTL）与 {@link OreScanCache}（本地扫描）。
 * 服务端仅在「一键连锁开 且 持续检测关」的锚定模式下推送；女仆切模式/收魂符/死亡/超距即停推，
 * 客户端缓存随之过期清除，渲染自动消失。</p>
 */
@Mod.EventBusSubscriber(modid = MaidMining.MODID, value = Dist.CLIENT)
public final class AnchorRenderer {

    private AnchorRenderer() {
    }

    private static final ResourceLocation ANCHOR_TEX =
            new ResourceLocation(MaidMining.MODID, "textures/render/anchor.png");

    // 范围框灰色半透明
    private static final float BOX_R = 0.75f, BOX_G = 0.75f, BOX_B = 0.78f, BOX_A = 0.5f;
    private static final float ORE_A = 0.95f;

    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        if (!MiningClientConfig.ENABLE.get()) {
            return;
        }
        Map<Integer, AnchorClientCache.Entry> data = AnchorClientCache.snapshot();
        if (data.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            return;
        }
        Camera cam = event.getCamera();
        Vec3 camPos = cam.getPosition();
        double maxDist = MiningClientConfig.RENDER_DISTANCE.get();
        double maxDistSqr = maxDist * maxDist;

        PoseStack pose = event.getPoseStack();

        // 先画所有线框（范围框 + 矿物棱），一次即时模式提交
        drawLines(pose, camPos, data, maxDistSqr);

        // 再画锚点图标（贴图 billboard）
        for (Map.Entry<Integer, AnchorClientCache.Entry> me : data.entrySet()) {
            BlockPos c = me.getValue().center;
            double cx = c.getX() + 0.5, cy = c.getY() + 0.5, cz = c.getZ() + 0.5;
            if (camPos.distanceToSqr(cx, cy, cz) > maxDistSqr) {
                continue;
            }
            drawAnchorIcon(pose, cam, camPos, c);
        }
    }

    // ===================== 线框（范围框 + 矿物棱） =====================
    private static void drawLines(PoseStack pose, Vec3 camPos,
                                  Map<Integer, AnchorClientCache.Entry> data, double maxDistSqr) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest(); // 穿透：无视遮挡
        RenderSystem.disableCull();

        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();

        Matrix4f mat = pose.last().pose(); // 未平移：Pass1 坐标已相对相机

        // ===== Pass 1：范围框（面向相机的四边形粗线，真加粗）=====
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (Map.Entry<Integer, AnchorClientCache.Entry> me : data.entrySet()) {
            AnchorClientCache.Entry e = me.getValue();
            BlockPos center = e.center;
            double cx = center.getX() + 0.5, cy = center.getY() + 0.5, cz = center.getZ() + 0.5;
            if (camPos.distanceToSqr(cx, cy, cz) > maxDistSqr) {
                continue;
            }
            int radius = e.radius;
            double minX = center.getX() - radius - camPos.x;
            double minY = center.getY() - radius - camPos.y;
            double minZ = center.getZ() - radius - camPos.z;
            double side = 2 * radius + 1;
            drawBoxThick(buf, mat, minX, minY, minZ, side, BOX_R, BOX_G, BOX_B, BOX_A);
        }
        tess.end();

        // ===== Pass 2：矿物描棱（DEBUG_LINES，完整 12 棱）=====
        // 用「已平移」矩阵，配合缓存里的绝对世界坐标
        pose.pushPose();
        pose.translate(-camPos.x, -camPos.y, -camPos.z);
        Matrix4f matT = pose.last().pose();
        RenderSystem.lineWidth(2.0f);
        buf.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (Map.Entry<Integer, AnchorClientCache.Entry> me : data.entrySet()) {
            AnchorClientCache.Entry e = me.getValue();
            BlockPos center = e.center;
            double cx = center.getX() + 0.5, cy = center.getY() + 0.5, cz = center.getZ() + 0.5;
            if (camPos.distanceToSqr(cx, cy, cz) > maxDistSqr) {
                continue;
            }
            // 顶点已在扫描时算好并缓存（与相机无关），这里只做数组到缓冲的拷贝，
            // 不再逐块做面可见性判定。数据是否需要重算由 OreScanCache 按
            // 服务端版本号 / 参数变化 / 兜底超时三个条件决定。
            OreScanCache.ScanResult cached = OreScanCache.getVerts(
                    me.getKey(), center, e.radius, e.oreMask, e.version);
            float[] v = cached.lineVerts;
            float[] c = cached.lineColors;
            int n = cached.vertCount;
            for (int i = 0, o = 0; i < n; i++, o += 3) {
                buf.vertex(matT, v[o], v[o + 1], v[o + 2])
                        .color(c[o], c[o + 1], c[o + 2], ORE_A)
                        .endVertex();
            }
        }
        tess.end();
        pose.popPose();

        RenderSystem.lineWidth(1.0f);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    /** 范围框线厚（世界单位，近大远小视觉自然）。 */
    private static final float BOX_THICK = 0.08f;

    /**
     * 画立方体 12 条粗棱（坐标已相对相机，即相机位于原点）。每条棱用面向相机的四边形条带。
     */
    private static void drawBoxThick(BufferBuilder buf, Matrix4f mat,
                                     double x, double y, double z, double s,
                                     float r, float g, float b, float a) {
        double x1 = x + s, y1 = y + s, z1 = z + s;
        // 底面
        thick(buf, mat, x, y, z, x1, y, z, r, g, b, a);
        thick(buf, mat, x1, y, z, x1, y, z1, r, g, b, a);
        thick(buf, mat, x1, y, z1, x, y, z1, r, g, b, a);
        thick(buf, mat, x, y, z1, x, y, z, r, g, b, a);
        // 顶面
        thick(buf, mat, x, y1, z, x1, y1, z, r, g, b, a);
        thick(buf, mat, x1, y1, z, x1, y1, z1, r, g, b, a);
        thick(buf, mat, x1, y1, z1, x, y1, z1, r, g, b, a);
        thick(buf, mat, x, y1, z1, x, y1, z, r, g, b, a);
        // 竖直
        thick(buf, mat, x, y, z, x, y1, z, r, g, b, a);
        thick(buf, mat, x1, y, z, x1, y1, z, r, g, b, a);
        thick(buf, mat, x1, y, z1, x1, y1, z1, r, g, b, a);
        thick(buf, mat, x, y, z1, x, y1, z1, r, g, b, a);
    }

    /**
     * 一条面向相机的粗线段（QUADS）。坐标相对相机（相机在原点），
     * 屏幕垂直方向 = normalize(cross(线方向, 相机→线中点))，向两侧偏移半厚生成四边形。
     */
    private static void thick(BufferBuilder buf, Matrix4f mat,
                              double x0, double y0, double z0,
                              double x1, double y1, double z1,
                              float r, float g, float b, float a) {
        double dx = x1 - x0, dy = y1 - y0, dz = z1 - z0;
        double mx = (x0 + x1) * 0.5, my = (y0 + y1) * 0.5, mz = (z0 + z1) * 0.5; // 相机→中点
        // cross(dir, view)
        double ox = dy * mz - dz * my;
        double oy = dz * mx - dx * mz;
        double oz = dx * my - dy * mx;
        double len = Math.sqrt(ox * ox + oy * oy + oz * oz);
        if (len < 1.0e-6) {
            return;
        }
        double h = BOX_THICK * 0.5 / len;
        ox *= h; oy *= h; oz *= h;
        buf.vertex(mat, (float) (x0 + ox), (float) (y0 + oy), (float) (z0 + oz)).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float) (x1 + ox), (float) (y1 + oy), (float) (z1 + oz)).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float) (x1 - ox), (float) (y1 - oy), (float) (z1 - oz)).color(r, g, b, a).endVertex();
        buf.vertex(mat, (float) (x0 - ox), (float) (y0 - oy), (float) (z0 - oz)).color(r, g, b, a).endVertex();
    }


    // ===================== 锚点图标（billboard 贴图） =====================

    private static void drawAnchorIcon(PoseStack pose, Camera cam, Vec3 camPos, BlockPos center) {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderTexture(0, ANCHOR_TEX);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.enableDepthTest();
        RenderSystem.disableCull();

        // 图标立于锚点上方 1.4 格，一格见方
        double ix = center.getX() + 0.5 - camPos.x;
        double iy = center.getY() + 1.4 - camPos.y;
        double iz = center.getZ() + 0.5 - camPos.z;

        pose.pushPose();
        pose.translate(ix, iy, iz);
        pose.mulPose(cam.rotation()); // 朝向相机（billboard）
        Matrix4f mat = pose.last().pose();

        float half = 0.5f; // 一格见方
        Tesselator tess = Tesselator.getInstance();
        BufferBuilder buf = tess.getBuilder();
        buf.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
        // 四角（贴图 v 上下：MC 屏幕空间与贴图 y 反向，用 0/1 常规映射）
        buf.vertex(mat, -half, -half, 0).uv(0.0f, 1.0f).endVertex();
        buf.vertex(mat,  half, -half, 0).uv(1.0f, 1.0f).endVertex();
        buf.vertex(mat,  half,  half, 0).uv(1.0f, 0.0f).endVertex();
        buf.vertex(mat, -half,  half, 0).uv(0.0f, 0.0f).endVertex();
        tess.end();

        pose.popPose();

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }
}